#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
LAYER0_PORT=15432
LAYER1_PORT=15433

cleanup() {
    echo "=== Shutting down PostgreSQL instances ==="
    pg_ctl -D "$ROOT/tmp/pgdata-l0" stop -m immediate 2>/dev/null || true
    pg_ctl -D "$ROOT/tmp/pgdata-l1" stop -m immediate 2>/dev/null || true
    rm -rf "$ROOT/tmp/pgdata-l0" "$ROOT/tmp/pgdata-l1" 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

echo "=== Creating temporary PostgreSQL instances ==="
mkdir -p "$ROOT/tmp"
rm -rf "$ROOT/tmp/pgdata-l0" "$ROOT/tmp/pgdata-l1"

initdb -D "$ROOT/tmp/pgdata-l0" --no-locale --encoding=UTF8 -U root > /dev/null 2>&1
initdb -D "$ROOT/tmp/pgdata-l1" --no-locale --encoding=UTF8 -U root > /dev/null 2>&1

echo "local all all trust" >> "$ROOT/tmp/pgdata-l0/pg_hba.conf"
echo "host all all 127.0.0.1/32 trust" >> "$ROOT/tmp/pgdata-l0/pg_hba.conf"
echo "local all all trust" >> "$ROOT/tmp/pgdata-l1/pg_hba.conf"
echo "host all all 127.0.0.1/32 trust" >> "$ROOT/tmp/pgdata-l1/pg_hba.conf"

echo "=== Starting PostgreSQL on port $LAYER0_PORT ==="
pg_ctl -D "$ROOT/tmp/pgdata-l0" -o "-p $LAYER0_PORT -k /tmp" -l "$ROOT/tmp/pg-l0.log" start
sleep 1

echo "=== Starting PostgreSQL on port $LAYER1_PORT ==="
pg_ctl -D "$ROOT/tmp/pgdata-l1" -o "-p $LAYER1_PORT -k /tmp" -l "$ROOT/tmp/pg-l1.log" start
sleep 2

echo "=== Creating databases ==="
PGPASSWORD=test1234 createdb -h 127.0.0.1 -p "$LAYER0_PORT" -U root info 2>/dev/null || true
PGPASSWORD=test1234 createdb -h 127.0.0.1 -p "$LAYER1_PORT" -U root info 2>/dev/null || true

DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -Dtest.minio.reset=false"

echo "=== Running L0 tests (port $LAYER0_PORT) and L1 tests (port $LAYER1_PORT) in parallel ==="
mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" $DB_ARGS -DDB_PORT=$LAYER0_PORT -DDB_NAME=info &
L0_PID=$!

mvn test -pl layer1-mcmc -q -f "$ROOT/pom.xml" $DB_ARGS -DDB_PORT=$LAYER1_PORT -DDB_NAME=info &
L1_PID=$!

EXIT_CODE=0
wait $L0_PID || { echo "Layer 0 tests FAILED"; EXIT_CODE=1; }
wait $L1_PID || { echo "Layer 1 tests FAILED"; EXIT_CODE=1; }

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "=== All tests passed ==="
else
    echo "=== Some tests FAILED ==="
fi
exit $EXIT_CODE
