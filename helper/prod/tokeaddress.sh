#!/usr/bin/env bash
# tokeaddress.sh — run the RemoteTokenAddres test, which lists every address
# holding the "bc" token on the prod BIG node with the summed balance per
# address (mirrors WalletService.searchTotalNoSave via the outputsOfTokenid API).
#
# Usage:
#   ./tokeaddress.sh
# Env overrides:
#   PROD_URL    default https://p.bigtangle.org:8088/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$ROOT"

PROD_URL="${PROD_URL:-https://p.bigtangle.org:8088/}"
PROD_BASE="${PROD_URL%/}"

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

echo "=== Pre-check: $PROD_BASE/getTip ==="
if curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$PROD_BASE/getTip" -H 'Content-Type: application/json' -d '{}' | grep -q 200; then
    echo "prod node is up at $PROD_BASE"
else
    echo "WARNING: prod node not reachable at $PROD_BASE" >&2
fi

echo ""
echo "=== Run RemoteTokenAddres against $PROD_URL ==="
mvn test -pl layer0-server \
    -Dtest=RemoteTokenAddres \
    -Dserver.url="$PROD_URL" \
    -Dnet.params="${NET_PARAMS:-main}" \
    -Dsurefire.failIfNoSpecifiedTests=false
