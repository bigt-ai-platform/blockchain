#!/usr/bin/env bash
# Run the Remote*IT tests against a DEPLOYED (already running) prod L0 server.
#
# Unlike helper/fulltest/remote.sh this does NOT start/stop databases or
# servers -- it assumes the prod validator node is already up (fundAddresses
# bootstrap mode enabled). It only:
#   1. funds the genesis (seed 0x01) and yuan (seed 0x03) wallets used by the
#      remote tests with CONFIRMED BIG via /fundAddresses,
#   2. waits for the PoS beacon chain to be producing, and
#   3. runs `mvn test` on layer0-server against the prod URL.
#
# Usage:
#   ./prodtest.sh [TestClassPattern]      # default: net.bigtangle.server.remote.Remote*IT
# Env overrides:
#   PROD_URL       default http://10.8.0.1:8081/
#   GENESIS_FUND_UTXOS  default 64
#   YUAN_FUND_UTXOS     default 8
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$ROOT"

PROD_URL="${PROD_URL:-http://10.8.0.1:8081/}"
PROD_BASE="${PROD_URL%/}"
TEST_CLASS="${1:-net.bigtangle.server.remote.Remote*IT}"

# Use Java 25 if available
if [ -x /tmp/opencode/jdk25/bin/java ]; then
    export JAVA_HOME=/tmp/opencode/jdk25
    export PATH=$JAVA_HOME/bin:$PATH
elif [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi
# Use a known local Maven if it is not already on PATH
if ! command -v mvn >/dev/null 2>&1; then
    for cand in /tmp/opencode/maven/bin/mvn /opt/maven/bin/mvn /usr/local/maven/bin/mvn /home/jcui/.local/maven/bin/mvn; do
        if [ -x "$cand" ]; then
            export PATH="$(dirname "$cand"):$PATH"
            break
        fi
    done
fi

M2_REPO="$HOME/.m2/repository"
if [ ! -d "$M2_REPO" ] && [ -d /root/.m2/repository ]; then
    M2_REPO="/root/.m2/repository"
fi
req_pubkey() {
    local seed="$1"
    # pick NEWEST jar of each lib: head -1 can grab ancient versions
    # (guava-10 lacks com.google.common.io.BaseEncoding)
    newest() { find "$M2_REPO" -name "$1-*.jar" ! -name '*sources*' ! -name '*javadoc*' ! -name '*android*' | sort -V | tail -1; }
    "$JAVA_HOME/bin/java" -cp \
        "$ROOT/bigtangle-core/target/classes:$(newest slf4j-api):$(newest guava):$(newest bcprov-jdk18on)" \
        net.bigtangle.tools.ValidatorKeyTool pubkey "$seed" 2>/dev/null | grep '^VALIDATOR_PUBKEY=' | cut -d= -f2
}

GENESIS_PUBKEY="${GENESIS_PUBKEY:-$(req_pubkey "$(printf '01%.0s' {1..32})" || true)}"
YUAN_PUBKEY="${YUAN_PUBKEY:-$(req_pubkey "$(printf '03%.0s' {1..32})" || true)}"
if [ -z "$GENESIS_PUBKEY" ] || [ -z "$YUAN_PUBKEY" ]; then
    echo "ERROR: could not derive genesis/yuan pubkey" >&2
    exit 1
fi

echo "Targeting prod L0: $PROD_URL"
echo "genesis wallet pubkey: ${GENESIS_PUBKEY:0:24}..."
echo "yuan wallet pubkey:    ${YUAN_PUBKEY:0:24}..."

post_ok() {
    local url="$1"
    local data="$2"
    local tmp
    tmp=$(mktemp)
    printf '%s' "$data" > "$tmp"
    local resp
    resp=$(curl -s -m 10 -X POST "$url" -H 'Content-Type: application/json' --data-binary @"$tmp" 2>/dev/null || true)
    rm -f "$tmp"
    if [ -n "$resp" ] && printf '%s' "$resp" | grep -q '"errorcode"[ ]*:[ ]*0\|"errorcode"[ ]*:[ ]*100'; then
        return 0
    fi
    return 1
}

echo ""
echo "=== Pre-check: $PROD_BASE/getTip ==="
if curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$PROD_BASE/getTip" -H 'Content-Type: application/json' -d '{}' | grep -q 200; then
    echo "prod L0 is up"
else
    echo "ERROR: prod L0 not reachable at $PROD_BASE" >&2
    exit 1
fi

echo ""
echo "=== Fund genesis wallet (seed 0x01) ==="
GENESIS_FUND_UTXOS="${GENESIS_FUND_UTXOS:-64}"
GENESIS_ENTRIES=""
for i in $(seq 1 "$GENESIS_FUND_UTXOS"); do
    GENESIS_ENTRIES="${GENESIS_ENTRIES}{\"address\":\"genesis\",\"value\":100000000000000,\"pubkey\":\"$GENESIS_PUBKEY\"},"
done
GENESIS_ENTRIES="[${GENESIS_ENTRIES%,}]"
if post_ok "$PROD_BASE/fundAddresses" "{\"addresses\":$GENESIS_ENTRIES}"; then
    echo "genesis wallet funded ($GENESIS_FUND_UTXOS UTXOs)"
else
    echo "WARNING: genesis funding did not confirm via fundAddresses" >&2
fi

echo ""
echo "=== Fund yuan wallet (seed 0x03) ==="
YUAN_FUND_UTXOS="${YUAN_FUND_UTXOS:-8}"
YUAN_ENTRIES=""
for i in $(seq 1 "$YUAN_FUND_UTXOS"); do
    YUAN_ENTRIES="${YUAN_ENTRIES}{\"address\":\"yuan\",\"value\":100000000000000,\"pubkey\":\"$YUAN_PUBKEY\"},"
done
YUAN_ENTRIES="[${YUAN_ENTRIES%,}]"
if post_ok "$PROD_BASE/fundAddresses" "{\"addresses\":$YUAN_ENTRIES}"; then
    echo "yuan wallet funded ($YUAN_FUND_UTXOS UTXOs)"
else
    echo "WARNING: yuan funding did not confirm via fundAddresses" >&2
fi

echo ""
echo "=== Run remote tests against $PROD_URL ==="
mvn test -pl layer0-server \
    -Dtest="$TEST_CLASS" \
    -Dserver.url="$PROD_URL" \
    -Dl1.url="$PROD_URL" \
    -Dnet.params="${NET_PARAMS:-main}" \
    -Dsurefire.failIfNoSpecifiedTests=false
