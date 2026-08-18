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

echo "=== Running core tests ==="
mvn test -pl bigtangle-core -q -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}"
echo "=== Core tests passed ==="

echo "=== Building servercore module ==="
mvn install -DskipTests -q -f "$ROOT/pom.xml" -am -pl bigtangle-servercore 2>&1 | tail -1
echo "=== Build done ==="

echo "=== Running PoS consensus and mempool tests ==="
mvn test -pl bigtangle-servercore -f "$ROOT/pom.xml" "${JVM_ARGS[@]}" "${FORK_ARGS[@]}" -Dsurefire.failIfNoSpecifiedTests=false $TEST_ARG
echo "=== All tests passed ==="
