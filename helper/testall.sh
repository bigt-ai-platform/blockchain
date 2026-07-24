#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

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
# Remove any stale container from a prior run, then start fresh
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
docker compose -f "$COMPOSE_FILE" up -d || {
    echo "WARNING: docker compose up failed, trying docker start"
    docker start test-bigtangle-postgres 2>/dev/null || true
}

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

echo "=== Recreating databases ==="
for db in info_l0 info_pai info_nft info_payment info_order; do
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS $db;" 2>/dev/null || true
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "CREATE DATABASE $db;" 2>/dev/null || true
done

JVM_ARGS=(-DargLine="-Xmx512m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED")
FORK_ARGS=(-Dsurefire.forkCount=1)
DB_ARGS="-DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234"

echo "=== Running core tests (no DB needed) ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}"
echo "=== Core tests passed ==="

echo "=== Building all modules (serial) ==="
# Build all server modules + core dependencies first (without tests)
mvn install -DskipTests -q -f "$ROOT/pom.xml" -am \
  -pl layer0-server,layer0-mcmc,l1-pai-server,l1-nft-server,l1-payment-server,l1-order-server 2>&1 | tail -1
# Also compile test classes to avoid stale test JARs referencing removed classes
mvn test-compile -q -f "$ROOT/pom.xml" -am \
  -pl layer0-mcmc,l1-pai-mcmc,l1-pai-server,l1-nft-mcmc,l1-nft-server,l1-payment-server,l1-payment-mcmc,l1-order-server,l1-order-mcmc 2>&1 | tail -1
echo "=== All modules built ==="

echo "=== Running L0 tests ==="
mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_l0 &
L0_PID=$!

echo "=== Running PAI tests (sequential, retry) ==="
PAI_OK=false
for attempt in 1 2 3; do
    mvn test -pl l1-pai-mcmc -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_pai && { PAI_OK=true; break; }
    echo "PAI tests attempt $attempt failed, retrying..."
done

ORDER_OK=false
for attempt in 1 2 3; do
    mvn test -pl l1-order-mcmc -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $DB_ARGS -DDB_NAME=info_order 2>&1 | tail -1 && { ORDER_OK=true; break; }
    echo "Order tests attempt $attempt failed, retrying..."
done

EXIT_CODE=0
wait $L0_PID      || { echo "Layer 0 tests FAILED";      EXIT_CODE=1; }
if [ "$PAI_OK" != "true" ]; then
    echo "PAI tests FAILED after 3 attempts"
    EXIT_CODE=1
fi
if [ "$ORDER_OK" != "true" ]; then
    echo "Order tests FAILED after 3 attempts"
    EXIT_CODE=1
fi

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "=== All tests passed ==="
else
    echo "=== Some tests FAILED ==="
fi
exit $EXIT_CODE
