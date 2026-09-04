#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SPECIFIC_TEST="${1:-}"
if [ -n "$SPECIFIC_TEST" ]; then
    TEST_ARG="-Dtest=${SPECIFIC_TEST}"
    echo "=== Running only ${SPECIFIC_TEST} in bigtangle-servercore ==="
else
    TEST_ARG=""
fi

if [ -z "${JAVA_HOME:-}" ] && [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

# ML-DSA-87 is the default suite (PQKey.createNew is ML-DSA-only; genesis is
# ML-DSA-only). SLH-DSA-256s is only added after the dual-suite activation
# height. Set DUAL_H=<height> to run the suite in post-activation mode.
DUAL_ARG=""
if [ -n "${DUAL_H:-}" ]; then
    DUAL_ARG="-Dnet.bigtangle.pq.dualActivationHeight=${DUAL_H}"
fi
ARG_LINE="-Xmx512m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED ${DUAL_ARG}"
JVM_ARGS=(-DargLine="${ARG_LINE}")
FORK_ARGS=(-Dsurefire.forkCount=1)

# Local Kafka for the suite (same provisioning as testnodes.sh). The broker is
# always present so Kafka-capable tests have one; streams stay OFF by default
# (server default) because 100+ ITs sharing stream threads is flaky — set
# KAFKA_STREAMS=1 to opt the whole suite into streams-on.
# shellcheck disable=SC1091
source "${ROOT}/helper/kafka-local.sh"
export KAFKA_CONTAINER="${KAFKA_CONTAINER:-l0-unit-kafka}"
export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-9492}"
export KAFKA_CHAINS="${KAFKA_CHAINS:-L0}" KAFKA_FRESH_TOPICS=1
kafka_local_ensure || { echo "local kafka broker failed" >&2; exit 1; }
kafka_local_topics || { echo "local kafka topics failed" >&2; exit 1; }
export BOOT_STRAP_SERVERS="localhost:${KAFKA_HOST_PORT}"
KAFKA_SUITE_ARGS=()
if [ "${KAFKA_STREAMS:-0}" = "1" ]; then
    KAFKA_SUITE_ARGS=(-Dserver.runKafkaStream=true -Dkafka.bootstrapServers="$BOOT_STRAP_SERVERS")
    echo "=== Suite streams ON via $BOOT_STRAP_SERVERS ==="
fi

echo "=== Running core tests ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" "${KAFKA_SUITE_ARGS[@]}"
echo "=== Core tests passed ==="

echo "=== Building servercore module ==="
mvn install -DskipTests -q -f "$ROOT/pom.xml" -am -pl bigtangle-servercore 2>&1 | tail -1
echo "=== Build done ==="

echo "=== Running PoS consensus and mempool tests ==="
mvn test -pl bigtangle-servercore -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" "${KAFKA_SUITE_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $TEST_ARG
echo "=== All tests passed ==="
