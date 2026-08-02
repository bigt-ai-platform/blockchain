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

# ML-DSA-87 is the default suite (PQKey.createNew is ML-DSA-only; genesis is
# ML-DSA-only). SLH-DSA-256s is only added after the dual-suite activation
# height. Set DUAL_H=<height> to run the suite in post-activation mode.
DUAL_ARG=""
if [ -n "${DUAL_H:-}" ]; then
    DUAL_ARG="-Dnet.bigtangle.pq.dualActivationHeight=${DUAL_H}"
fi
# Attack-test workload (DoubleSpentAttackTest). Default 200; use 1000 for a
# full stress run: ATTACK_COUNT=1000 bash helper/testall.sh
ATTACK_ARG=""
if [ -n "${ATTACK_COUNT:-}" ]; then
    ATTACK_ARG="-Dnet.bigtangle.attackCount=${ATTACK_COUNT}"
fi
ARG_LINE="-Xmx512m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED ${DUAL_ARG}"
JVM_ARGS=(-DargLine="${ARG_LINE}")
# layer0-mcmc/pom.xml defines bigtangle.mcmc.argLine (default -Xmx2g); override
# it so the flags reach the surefire fork (the pom argLine otherwise wins over
# -DargLine).
MCMC_ARG_LINE="-Xmx2g --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -Dspring.main.allow-bean-definition-overriding=true ${DUAL_ARG} ${ATTACK_ARG}"
MCMC_JVM_ARGS=(-Dbigtangle.mcmc.argLine="${MCMC_ARG_LINE}")
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
mvn test -pl layer0-mcmc -f "$ROOT/pom.xml" "${MCMC_JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $TEST_ARG $DB_ARGS -DDB_NAME=info_l0
