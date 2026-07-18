#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

# Use Java 25 if available (local install from Temurin)
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

PG_PORT=5432
COMPOSE_FILE="$ROOT/helper/docker-compose-base.yml"

cleanup() {
    echo "=== Shutting down Docker PostgreSQL ==="
    docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

echo "=== Starting Docker PostgreSQL ==="
docker compose -f "$COMPOSE_FILE" up -d

echo "=== Waiting for PostgreSQL to be healthy ==="
for i in $(seq 1 30); do
    if curl -sf http://localhost:$PG_PORT/ >/dev/null 2>&1 || \
       docker exec test-bigtangle-postgres pg_isready -U root -d info >/dev/null 2>&1; then
        echo "PostgreSQL ready"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "PostgreSQL not ready after 90s"
        docker compose -f "$COMPOSE_FILE" logs
        exit 1
    fi
    sleep 3
done

echo "=== Creating databases ==="
for db in info_l0 info_order info_contract info_pai; do
    docker exec test-bigtangle-postgres psql -U root -d info -c "CREATE DATABASE $db;" 2>/dev/null || true
done

DB_ARGS="-DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234 -Dtest.minio.reset=false"

echo "=== Running core tests (no DB needed) ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml"
echo "=== Core tests passed ==="

echo "=== Building all modules (serial) ==="
# Build all server modules + core dependencies first (without tests)
# This ensures L1 test modules get fresh JARs for MinioService etc.
mvn install -DskipTests -q -f "$ROOT/pom.xml" -am \
  -pl layer0-server,layer0-mcmc,l1-order-server,l1-contract-server,l1-pai-server 2>&1 | tail -1
echo "=== All modules built ==="

echo "=== Running L0 and L1 tests in parallel ==="
mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_l0 &
L0_PID=$!
mvn test -pl l1-order-mcmc -q -f "$ROOT/pom.xml" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_order &
ORDER_PID=$!
mvn test -pl l1-contract-mcmc -q -f "$ROOT/pom.xml" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_contract &
CONTRACT_PID=$!
mvn test -pl l1-pai-mcmc -q -f "$ROOT/pom.xml" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_pai &
PAI_PID=$!

EXIT_CODE=0
wait $L0_PID      || { echo "Layer 0 tests FAILED";      EXIT_CODE=1; }
wait $ORDER_PID   || { echo "Order match tests FAILED";  EXIT_CODE=1; }
wait $CONTRACT_PID || { echo "Contract tests FAILED";     EXIT_CODE=1; }
wait $PAI_PID     || { echo "PAI tests FAILED";          EXIT_CODE=1; }

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "=== All tests passed ==="
else
    echo "=== Some tests FAILED ==="
fi
exit $EXIT_CODE
