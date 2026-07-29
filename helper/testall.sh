#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SPECIFIC_TEST="${1:-}"
if [ -n "$SPECIFIC_TEST" ]; then
    TEST_ARG="-Dtest=${SPECIFIC_TEST}"
    echo "=== Running only ${SPECIFIC_TEST} in layer0-mcmc ==="
else
    TEST_ARG=""
fi

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
for db in info_l0; do
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS $db;" 2>/dev/null || true
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "CREATE DATABASE $db;" 2>/dev/null || true
done

ARG_LINE="-Xmx512m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED"
JVM_ARGS=(-DargLine="${ARG_LINE}")
FORK_ARGS=(-Dsurefire.forkCount=1)
DB_ARGS="-DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234"

echo "=== Running core tests (no DB needed) ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}"
echo "=== Core tests passed ==="

echo "=== Building layer0 modules ==="
mvn install -DskipTests -q -f "$ROOT/pom.xml" -am -pl layer0-server,layer0-mcmc 2>&1 | tail -1
mvn test-compile -q -f "$ROOT/pom.xml" -am -pl layer0-mcmc 2>&1 | tail -1
echo "=== Build done ==="

echo "=== Running Layer 0 tests ==="
mvn test -pl layer0-mcmc -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $TEST_ARG $DB_ARGS -DDB_NAME=info_l0
