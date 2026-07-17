#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

# Use Java 25 if available (local install from Temurin)
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

LAYER0_PORT=15432
LAYER1_ORDER_PORT=15433
LAYER1_CONTRACT_PORT=15434

PGDATA_L0="$ROOT/tmp/pgdata-l0"
PGDATA_ORDER="$ROOT/tmp/pgdata-order"
PGDATA_CONTRACT="$ROOT/tmp/pgdata-contract"

cleanup() {
    echo "=== Shutting down PostgreSQL instances ==="
    pg_ctl -D "$PGDATA_L0" stop -m immediate 2>/dev/null || true
    pg_ctl -D "$PGDATA_ORDER" stop -m immediate 2>/dev/null || true
    pg_ctl -D "$PGDATA_CONTRACT" stop -m immediate 2>/dev/null || true
    rm -rf "$PGDATA_L0" "$PGDATA_ORDER" "$PGDATA_CONTRACT" 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

echo "=== Creating temporary PostgreSQL instances ==="
mkdir -p "$ROOT/tmp"
rm -rf "$PGDATA_L0" "$PGDATA_ORDER" "$PGDATA_CONTRACT"

for dir in "$PGDATA_L0" "$PGDATA_ORDER" "$PGDATA_CONTRACT"; do
    initdb -D "$dir" --no-locale --encoding=UTF8 -U root > /dev/null 2>&1
    echo "local all all trust" >> "$dir/pg_hba.conf"
    echo "host all all 127.0.0.1/32 trust" >> "$dir/pg_hba.conf"
done

echo "=== Starting PostgreSQL instances ==="
pg_ctl -D "$PGDATA_L0"     -o "-p $LAYER0_PORT -k /tmp" -l "$ROOT/tmp/pg-l0.log" start && sleep 1
pg_ctl -D "$PGDATA_ORDER"  -o "-p $LAYER1_ORDER_PORT -k /tmp" -l "$ROOT/tmp/pg-order.log" start && sleep 1
pg_ctl -D "$PGDATA_CONTRACT" -o "-p $LAYER1_CONTRACT_PORT -k /tmp" -l "$ROOT/tmp/pg-contract.log" start && sleep 1

echo "=== Creating databases ==="
for port in "$LAYER0_PORT" "$LAYER1_ORDER_PORT" "$LAYER1_CONTRACT_PORT"; do
    PGPASSWORD=test1234 createdb -h 127.0.0.1 -p "$port" -U root info 2>/dev/null || true
done

DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -Dtest.minio.reset=false"

echo "=== Running core tests (no DB needed) ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml"
echo "=== Core tests passed ==="

echo "=== Running L0 and L1 tests in parallel ==="
mvn test -pl layer0-mcmc        -q -f "$ROOT/pom.xml" $DB_ARGS -DDB_PORT=$LAYER0_PORT        -DDB_NAME=info &
L0_PID=$!
mvn test -pl l1-order-mcmc      -q -f "$ROOT/pom.xml" $DB_ARGS -DDB_PORT=$LAYER1_ORDER_PORT   -DDB_NAME=info &
ORDER_PID=$!
mvn test -pl l1-contract-mcmc   -q -f "$ROOT/pom.xml" $DB_ARGS -DDB_PORT=$LAYER1_CONTRACT_PORT -DDB_NAME=info &
CONTRACT_PID=$!

EXIT_CODE=0
wait $L0_PID      || { echo "Layer 0 tests FAILED";      EXIT_CODE=1; }
wait $ORDER_PID   || { echo "Order match tests FAILED";  EXIT_CODE=1; }
wait $CONTRACT_PID || { echo "Contract tests FAILED";     EXIT_CODE=1; }

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "=== All tests passed ==="
else
    echo "=== Some tests FAILED ==="
fi
exit $EXIT_CODE
