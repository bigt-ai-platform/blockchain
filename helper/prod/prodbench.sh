#!/usr/bin/env bash
# prodbench.sh — real-time TPS benchmark against the DEPLOYED production PoS
# network (the 3-node WireGuard mesh: node-0/node-1 on s2001, node-2 on cui).
#
# Drives MaxTpsBenchmarkProd: a remote HTTP benchmark that funds wallets via the
# bootstrap faucet (/fundAddresses), submits payments over /submitTransactions
# and measures submit TPS + end-to-end confirmation TPS and latency percentiles.
#
# The benchmark client MUST run on a mesh host (the driver has no route into
# 10.8.0.x), so this script shells out to CHECK_HOST (default cui) and runs the
# maven test there. The benchmark test source is scp'd to the remote build dir
# first; run with --no-build to skip the dependency install step.
#
# Usage: ./prodbench.sh [options]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_SOURCE="$ROOT/bigtangle-servercore/src/test/java/net/bigtangle/server/benchmark/MaxTpsBenchmarkProd.java"
TEST_CLASS="net.bigtangle.server.benchmark.MaxTpsBenchmarkProd#testProdRealtime"

# ---- Config (override via env, same conventions as prodtest.sh) ------------
SSH_USER="${SSH_USER:-root}"
S2001_HOST="${S2001_HOST:-10.8.0.1}"
CUI_HOST="${CUI_HOST:-10.8.0.2}"
JUMP_HOST="${JUMP_HOST-}"
CHECK_HOST="${CHECK_HOST:-$CUI_HOST}"
BUILD_DIR="${BUILD_DIR:-/tmp/build3}"   # repo checkout on the mesh host
SSH_OPTS="${SSH_OPTS:--o BatchMode=yes -o ConnectTimeout=10 -i /config/.ssh/oraclevpc.key}"

# ---- Benchmark parameters (defaults match a modest prod burst) --------------
SEED="${SEED:-http://10.8.0.2:8083}"
TX="${TX:-2000}"
CLIENTS="${CLIENTS:-50}"
BATCH_SIZE="${BATCH_SIZE:-250}"
AMOUNT="${AMOUNT:-40000}"
PAY="${PAY:-25000}"
CONFIRM_TIMEOUT="${CONFIRM_TIMEOUT:-900}"
REQUIRE_CONFIRM=true
DO_BUILD=true

usage() {
    cat <<'EOF'
prodbench.sh — real-time TPS benchmark against the deployed prod PoS network.

  ./prodbench.sh [options]

Options:
  -s, --seed URL         target prod node (default: http://10.8.0.2:8083)
  -t, --tx N             total transactions (default: 2000)
  -c, --clients N        parallel submit clients (default: 50)
  -b, --batch-size N     tx per /submitTransactions call (default: 250)
  -a, --amount N         satoshis funded per wallet (default: 40000)
  -p, --pay N            satoshis paid per tx (default: 25000)
      --confirm-timeout S  seconds to wait for confirmation (default: 900)
      --no-confirm         do not require any tx to confirm (max-submit-TPS runs)
      --no-build           skip the maven dependency install on the mesh host
  -h, --help             this help

Env: SSH_USER, S2001_HOST, CUI_HOST, JUMP_HOST, CHECK_HOST, BUILD_DIR, SSH_OPTS,
     SEED, TX, CLIENTS, BATCH_SIZE, AMOUNT, PAY, CONFIRM_TIMEOUT
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--seed)             SEED="$2"; shift 2 ;;
        -t|--tx)               TX="$2"; shift 2 ;;
        -c|--clients)          CLIENTS="$2"; shift 2 ;;
        -b|--batch-size)       BATCH_SIZE="$2"; shift 2 ;;
        -a|--amount)           AMOUNT="$2"; shift 2 ;;
        -p|--pay)              PAY="$2"; shift 2 ;;
        --confirm-timeout)     CONFIRM_TIMEOUT="$2"; shift 2 ;;
        --no-confirm)          REQUIRE_CONFIRM=false; shift ;;
        --no-build)            DO_BUILD=false; shift ;;
        -h|--help)             usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

ssh_transport() { # $1=host
    local host="$1"
    if [ "$host" = "$S2001_HOST" ] && [ -n "${JUMP_HOST:-}" ]; then
        echo "ssh $SSH_OPTS -J ${JUMP_HOST} -o StrictHostKeyChecking=accept-new"
    else
        echo "ssh $SSH_OPTS"
    fi
}

scp_transport() { # $1=host -> scp-compatible options (no leading "ssh" token)
    local host="$1"
    if [ "$host" = "$S2001_HOST" ] && [ -n "${JUMP_HOST:-}" ]; then
        echo "-o StrictHostKeyChecking=accept-new -J ${JUMP_HOST} $SSH_OPTS"
    else
        echo "-o StrictHostKeyChecking=accept-new $SSH_OPTS"
    fi
}

remote() { # $1=host  rest=remote command string
    local host="$1"; shift
    # shellcheck disable=SC2086
    $(ssh_transport "$host") "${SSH_USER}@${host}" "$*"
}

if [ ! -f "$TEST_SOURCE" ]; then
    fail "test source not found: $TEST_SOURCE (run from the repo root?)"
fi

header "PROD Realtime Benchmark :: ${TX} tx, ${CLIENTS} clients, batch ${BATCH_SIZE}, target ${SEED}"

info "checking ${SSH_USER}@${CHECK_HOST}"
remote "$CHECK_HOST" "true" || fail "cannot reach ${SSH_USER}@${CHECK_HOST}"

info "syncing benchmark test -> ${SSH_USER}@${CHECK_HOST}:${BUILD_DIR}"
REMOTE_TEST="$BUILD_DIR/bigtangle-servercore/src/test/java/net/bigtangle/server/benchmark/MaxTpsBenchmarkProd.java"
remote "$CHECK_HOST" "mkdir -p '$(dirname "$REMOTE_TEST")'"
# shellcheck disable=SC2086
scp $(scp_transport "$CHECK_HOST") "$TEST_SOURCE" "${SSH_USER}@${CHECK_HOST}:${REMOTE_TEST}"
log "test source synced"

if [ "$DO_BUILD" = true ]; then
    info "installing reactor deps on ${CHECK_HOST} (JDK 25)"
    remote "$CHECK_HOST" "export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; export PATH=\$JAVA_HOME/bin:\$PATH; cd '${BUILD_DIR}' && mvn -q install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am" || fail "dependency install failed"
    log "reactor deps installed"
fi

info "running MaxTpsBenchmarkProd on ${CHECK_HOST}"
MVN_ARGS="-Dprod.seed=${SEED} -Dchain.tx=${TX} -Dchain.clients=${CLIENTS} -Dchain.batchSize=${BATCH_SIZE} -Dchain.amount=${AMOUNT} -Dchain.pay=${PAY} -Dchain.confirmTimeoutSec=${CONFIRM_TIMEOUT} -Dchain.requireConfirm=${REQUIRE_CONFIRM} -DfailIfNoTests=false"
set +e
remote "$CHECK_HOST" "export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64; export PATH=\$JAVA_HOME/bin:\$PATH; cd '${BUILD_DIR}' && mvn test -pl bigtangle-servercore -Dtest='${TEST_CLASS}' ${MVN_ARGS} 2>&1" | tee /tmp/prodbench.out
MVN_EXIT=${PIPESTATUS[0]}
set -e

# ---- Parse results from surefire report --------------------------------------
REPORT="$BUILD_DIR/bigtangle-servercore/target/surefire-reports/net.bigtangle.server.benchmark.MaxTpsBenchmarkProd-output.txt"
header "Results"
results=$(remote "$CHECK_HOST" "grep -E 'Total tx:|Submit wall:|Confirm wall:|Submit TPS:|Confirm TPS:|Confirm p50:|Confirm p95:|Confirm p99:' '$REPORT'" 2>/dev/null || true)
if [ -z "$results" ]; then
    echo -e "${RED}No benchmark metrics found in surefire output.${NC}"
    grep -iE "FAILURE|ERROR|BUILD FAILURE" /tmp/prodbench.out | tail -8 || true
    exit 1
fi
echo "$results"
confirmed=$(echo "$results" | grep -oP 'confirmed \K[0-9]+' | head -1)
submitted=$(echo "$results" | grep -oP 'submitted \K[0-9]+' | head -1)
submitTps=$(echo "$results" | grep -oP 'Submit TPS:\s+\K[0-9.]+' | head -1)
confirmTps=$(echo "$results" | grep -oP 'Confirm TPS:\s+\K[0-9.]+' | head -1)
if [ "${REQUIRE_CONFIRM}" = true ]; then
    if [ -n "$confirmed" ] && [ "$confirmed" -gt 0 ]; then
        log "Benchmark passed — ${confirmed}/${TX} confirmed, submit TPS ${submitTps:-0} tx/s, confirm TPS ${confirmTps:-0} tx/s"
    else
        fail "No confirmed transactions (submit TPS ${submitTps:-0} tx/s)"
    fi
else
    if [ -n "$submitted" ] && [ "$submitted" -gt 0 ]; then
        log "Benchmark done — ${submitted}/${TX} submitted, submit TPS ${submitTps:-0} tx/s, confirmed ${confirmed:-0}"
    else
        fail "No transactions submitted"
    fi
fi
