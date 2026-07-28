#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SPECIFIC_TEST="${1:-}"
if [ -n "$SPECIFIC_TEST" ]; then
    TEST_ARG="-Dtest=${SPECIFIC_TEST}"
    echo "=== Running only ${SPECIFIC_TEST} ==="
else
    TEST_ARG=""
fi

TEST_TIMEOUT="${TEST_TIMEOUT:-1800}"
PG_WAIT_COUNT="${PG_WAIT_COUNT:-60}"

if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

PG_PORT=5432
COMPOSE_FILE="$ROOT/helper/docker-compose-base.yml"

cleanup() {
    echo "=== Cleanup ==="
    docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "=== Stopping conflicting PG containers ==="
for c in l0-pg-0 l0-pg-1 l0-pg-2 test-bigtangle-postgres; do
    docker stop "$c" 2>/dev/null && echo "  stopped $c" || true
    docker rm "$c" 2>/dev/null && echo "  removed $c" || true
done

echo "=== Starting Docker PostgreSQL ==="
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
docker compose -f "$COMPOSE_FILE" up -d --force-recreate 2>&1 || {
    echo "FATAL: docker compose up failed"
    docker compose -f "$COMPOSE_FILE" logs; exit 1
}

echo "=== Waiting for PostgreSQL ==="
for i in $(seq 1 "$PG_WAIT_COUNT"); do
    if docker exec test-bigtangle-postgres pg_isready -U root -d info >/dev/null 2>&1; then
        echo "PostgreSQL ready"; break
    fi
    if [ "$i" -eq "$PG_WAIT_COUNT" ]; then
        echo "PostgreSQL not ready after $((PG_WAIT_COUNT * 3))s"
        docker compose -f "$COMPOSE_FILE" logs; exit 1
    fi
    sleep 3
done

echo "=== Recreating databases ==="
for db in info info_l0 info_l0b info_l0c info_pai info_nft info_payment info_order info_contract; do
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS $db;" 2>/dev/null || true
    docker exec test-bigtangle-postgres psql -U root -d postgres -c "CREATE DATABASE $db;" 2>/dev/null || true
done
echo "Databases ready"

# Reduce heap per fork when running 3 in parallel
PARALLEL_JVM_ARGS=(-DargLine="-Xmx1g --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -Dspring.main.allow-bean-definition-overriding=true -DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234")
SINGLE_JVM_ARGS=(-DargLine="-Xmx2g --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED -Dspring.main.allow-bean-definition-overriding=true -DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234")
FORK_ARGS=(-Dsurefire.forkCount=1 -DforkedProcessTimeoutInSeconds=7200)
DB_ARGS="-DDB_HOSTNAME=localhost -DDB_PORT=$PG_PORT -DDB_USERNAME=root -DDB_PASSWORD=test1234"

echo "=== Running core tests (no DB needed) ==="
timeout "$TEST_TIMEOUT" mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml" "${SINGLE_JVM_ARGS[@]}" "${FORK_ARGS[@]}"
echo "=== Core tests passed ==="

echo "=== Building all modules ==="
timeout "$TEST_TIMEOUT" mvn clean -q -f "$ROOT/pom.xml" -pl layer0-mcmc
timeout "$TEST_TIMEOUT" mvn install test-compile -DskipTests -q -f "$ROOT/pom.xml" -T 2C -am \
  -pl layer0-mcmc 2>&1 | tail -3
echo "=== Build done ==="

if [ -n "$SPECIFIC_TEST" ]; then
    echo "=== Running ${SPECIFIC_TEST} ==="
    MODULE=""; DB=""
    case "$SPECIFIC_TEST" in *Pai*) MODULE=l1-pai-mcmc; DB=info_pai ;;
        *Order*) MODULE=l1-order-mcmc; DB=info_order ;;
        *Contra*) MODULE=l1-contract-mcmc; DB=info_contract ;;
        *Nft*) MODULE=l1-nft-mcmc; DB=info_nft ;;
        *Payment*) MODULE=l1-payment-mcmc; DB=info_payment ;;
        *) MODULE=layer0-mcmc; DB=info_l0 ;;
    esac
    timeout "$TEST_TIMEOUT" mvn test -pl "$MODULE" -f "$ROOT/pom.xml" "${SINGLE_JVM_ARGS[@]}" "${FORK_ARGS[@]}" $TEST_ARG $DB_ARGS -DDB_NAME="$DB"
    exit $?
fi

echo "=== Running L0 tests in 3 parallel forks ==="

# Fork 1: slowest tests
FORK1_TEST="ValidatorServiceTest,ValidatorService2Test,PoSTest"
timeout "$TEST_TIMEOUT" mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" "${PARALLEL_JVM_ARGS[@]}" \
  "${FORK_ARGS[@]}" -Dtest="$FORK1_TEST" $DB_ARGS -DDB_NAME=info_l0 &
F1_PID=$!
echo "  Fork 1 (slow): $FORK1_TEST -> info_l0 [PID $F1_PID]"

# Fork 2: medium tests
FORK2_TEST="TokenTest,FullPrunedBlockGraphTest,RewardServiceTest,RewardService2Test,FeePoolRewardTest"
timeout "$TEST_TIMEOUT" mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" "${PARALLEL_JVM_ARGS[@]}" \
  "${FORK_ARGS[@]}" -Dtest="$FORK2_TEST" $DB_ARGS -DDB_NAME=info_l0b &
F2_PID=$!
echo "  Fork 2 (medium): $FORK2_TEST -> info_l0b [PID $F2_PID]"

# Fork 3: everything else (fast tests)
FORK3_TEST="AnchorRoundTripTest,BridgeServiceTest,CrossChainFlowTest,DirectExchangeTest"
FORK3_TEST="${FORK3_TEST},EpochRewardTest,GenesisBlockTipsTest,GossipServiceTest"
FORK3_TEST="${FORK3_TEST},Layer0BlockTypeScopingTest,MCMCServiceTest,PaymentServiceTest"
FORK3_TEST="${FORK3_TEST},PqSerializationIT,SlotTickServiceTest,TipsServiceTest"
FORK3_TEST="${FORK3_TEST},UserdataTest,UtilsTest,ValidatorDutyTest"
timeout "$TEST_TIMEOUT" mvn test -pl layer0-mcmc -q -f "$ROOT/pom.xml" "${PARALLEL_JVM_ARGS[@]}" \
  "${FORK_ARGS[@]}" -Dtest="$FORK3_TEST" $DB_ARGS -DDB_NAME=info_l0c &
F3_PID=$!
echo "  Fork 3 (fast): remaining -> info_l0c [PID $F3_PID]"

EXIT_CODE=0
for pid in $F1_PID $F2_PID $F3_PID; do
    wait $pid || EXIT_CODE=1
done

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "=== All tests passed ==="
else
    echo "=== Some tests FAILED ==="
fi
exit $EXIT_CODE
