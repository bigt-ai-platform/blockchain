#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

# Java 25
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

COMPOSE_FILE="$ROOT/helper/fulltest/docker-compose.l0-test.yml"
trap "docker compose -f '$COMPOSE_FILE' down -v 2>/dev/null" EXIT INT TERM

# Defaults
BLOCKS=100
TX_PER_BLOCK=10

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "  -n, --blocks N       Number of blocks in chain (default: 100)"
    echo "  -t, --tx-per-block N Transactions per block (default: 10)"
    echo "  -h, --help           Show this help"
    echo ""
    echo "Example: $0 -n 500 -t 20"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--blocks)        BLOCKS="$2"; shift 2 ;;
        -t|--tx-per-block)  TX_PER_BLOCK="$2"; shift 2 ;;
        -h|--help)          usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

TOTAL_TX=$(( BLOCKS * TX_PER_BLOCK ))

header "Chain MCMC Benchmark :: ${BLOCKS} blocks × ${TX_PER_BLOCK} tx = ${TOTAL_TX} total"

# ── 1. Start PostgreSQL ────────────────────────────────────────────────
info "Starting Docker PostgreSQL (single node)..."
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null
docker compose -f "$COMPOSE_FILE" up -d postgres-l0-0
for i in $(seq 1 30); do
    if docker compose -f "$COMPOSE_FILE" exec postgres-l0-0 pg_isready -U root -d layer0 >/dev/null 2>&1; then
        log "PostgreSQL ready"
        break
    fi
    if [ "$i" -eq 30 ]; then fail "PostgreSQL not ready after 90s"; fi
    sleep 3
done

# ── 2. Build ────────────────────────────────────────────────────────────
header "Building project..."
mvn install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am -q
mvn test-compile -pl layer0-mcmc -q
log "Build complete"

# ── 3. Run benchmark ────────────────────────────────────────────────────
header "Running MaxTpsBenchmarkChain..."
mvn test -pl layer0-mcmc \
    -Dtest=net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmarkChain#testChainMcmc \
    -Dchain.blocks="$BLOCKS" \
    -Dchain.txPerBlock="$TX_PER_BLOCK" \
    -DDB_HOSTNAME=localhost \
    -DDB_PORT=5432 \
    -DDB_USERNAME=root \
    -DDB_PASSWORD=test1234 \
    -DDB_NAME=layer0 \
    -DfailIfNoTests=false

# ── 4. Parse results from surefire report ──────────────────────────────
header "Results"
REPORT="$ROOT/layer0-mcmc/target/surefire-reports/net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmarkChain-output.txt"
blocks=$(grep -oP 'Blocks:\s+\K[\d]+' "$REPORT" | tail -1)
txpb=$(grep -oP 'Tx/block:\s+\K[\d]+' "$REPORT" | tail -1)
totaltx=$(grep -oP 'Total tx:\s+\K[\d]+' "$REPORT" | tail -1)
total=$(grep -oP 'Total wall:\s+\K[\d.]+' "$REPORT" | tail -1)
tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$REPORT" | tail -1)
submit=$(grep -oP 'Submit\s+\K[\d]+' "$REPORT" | head -1)
batch=$(grep -oP 'Batch\s+\K[\d]+' "$REPORT" | head -1)
mcmc=$(grep -oP 'MCMC update\s+\K[\d]+' "$REPORT" | head -1)
proto=$(grep -oP 'Prototype\s+\K[\d]+' "$REPORT" | head -1)
chainupd=$(grep -oP 'Chain update\s+\K[\d]+' "$REPORT" | head -1)

if [ -z "$mcmc" ]; then
    echo -e "${RED}No MCMC metric found — benchmark may have failed.${NC}"
    grep -i "FAILURE\|ERROR" "$REPORT" | tail -5
    exit 1
fi

echo ""
printf "  %-20s %s\n" "Configuration:" "${blocks} blocks × ${txpb} tx = ${totaltx} total"
printf "  %-20s %s\n" "Throughput:" "${tps:-N/A} tx/s"
printf "  %-20s %s\n" "Total wall:" "${total} ms"
echo ""
printf "  %-20s %12s  %s\n" "Phase" "Total (ms)" "% of wall"
printf "  %-20s %12s  %s\n" "────────────────────" "────────────" "────────"
printf "  %-20s %12s  %s\n" "Submit" "${submit:-N/A}" "$([ -n "$submit" ] && [ -n "$total" ] && [ "$total" -gt 0 ] && echo "$(( submit * 100 / total ))%" || echo "-")"
printf "  %-20s %12s  %s\n" "Batch" "${batch:-N/A}" "$([ -n "$batch" ] && [ -n "$total" ] && [ "$total" -gt 0 ] && echo "$(( batch * 100 / total ))%" || echo "-")"
printf "  %-20s %12s  %s\n" "MCMC update" "${mcmc:-N/A}" "$([ -n "$mcmc" ] && [ -n "$total" ] && [ "$total" -gt 0 ] && echo "$(( mcmc * 100 / total ))%" || echo "-")"
printf "  %-20s %12s  %s\n" "Prototype" "${proto:-N/A}" "$([ -n "$proto" ] && [ -n "$total" ] && [ "$total" -gt 0 ] && echo "$(( proto * 100 / total ))%" || echo "-")"
printf "  %-20s %12s  %s\n" "Chain update" "${chainupd:-N/A}" "$([ -n "$chainupd" ] && [ -n "$total" ] && [ "$total" -gt 0 ] && echo "$(( chainupd * 100 / total ))%" || echo "-")"
echo ""
log "Benchmark passed — ${blocks} blocks, ${tps} tx/s"
