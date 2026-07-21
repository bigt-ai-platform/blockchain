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
TX=50000
CLIENTS=200
BATCH_SIZE=250
MCMC_INTERVAL=10

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "  -t, --tx N            Total transactions (default: 50000)"
    echo "  -c, --clients N       Parallel clients (default: 200)"
    echo "  -b, --batch-size N    Tx per batch/submission (default: 250)"
    echo "  -m, --mcmc-interval N MCMC every N blocks (default: 10)"
    echo "  -h, --help            Show this help"
    echo ""
    echo "Example: $0 -t 50000 -c 200 -b 250 -m 10"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--tx)                TX="$2"; shift 2 ;;
        -c|--clients)           CLIENTS="$2"; shift 2 ;;
        -b|--batch-size)        BATCH_SIZE="$2"; shift 2 ;;
        -m|--mcmc-interval)     MCMC_INTERVAL="$2"; shift 2 ;;
        -h|--help)              usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

header "Real-Time Chain Benchmark :: ${TX} tx, ${CLIENTS} clients, batch ${BATCH_SIZE}, MCMC every ${MCMC_INTERVAL} blocks"

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
header "Running MaxTpsBenchmarkChain#testChainRealtime..."
mvn test -pl layer0-mcmc \
    -Dtest=net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmarkChain#testChainRealtime \
    -Dchain.tx="$TX" \
    -Dchain.clients="$CLIENTS" \
    -Dchain.batchSize="$BATCH_SIZE" \
    -Dchain.mcmcInterval="$MCMC_INTERVAL" \
    -DDB_HOSTNAME=localhost \
    -DDB_PORT=5432 \
    -DDB_USERNAME=root \
    -DDB_PASSWORD=test1234 \
    -DDB_NAME=layer0 \
    -DfailIfNoTests=false

# ── 4. Parse results from surefire report ──────────────────────────────
header "Results"
REPORT="$ROOT/layer0-mcmc/target/surefire-reports/net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmarkChain-output.txt"
totaltx=$(grep -oP 'Total tx:\s+\K[\d]+' "$REPORT" | tail -1)
blocks=$(grep -oP 'Blocks:\s+\K[\d]+' "$REPORT" | tail -1)
submit=$(grep -oP 'Submit wall:\s+\K[\d.]+' "$REPORT" | tail -1)
drain=$(grep -oP 'Drain wall:\s+\K[\d.]+' "$REPORT" | tail -1)
total=$(grep -oP 'Total wall:\s+\K[\d.]+' "$REPORT" | tail -1)
tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$REPORT" | tail -1)
batch=$(grep -oP 'Batch .cum.\s+\K[\d]+' "$REPORT" | tail -1)
mcmc=$(grep -oP 'MCMC .cum.\s+\K[\d]+' "$REPORT" | tail -1)
proto=$(grep -oP 'Prototype .cum.\s+\K[\d]+' "$REPORT" | tail -1)
chainupd=$(grep -oP 'Chain .cum.\s+\K[\d]+' "$REPORT" | tail -1)

if [ -z "$tps" ]; then
    echo -e "${RED}No TPS metric found — benchmark may have failed.${NC}"
    grep -i "FAILURE\|ERROR" "$REPORT" | tail -5
    exit 1
fi

echo ""
printf "  %-20s %s\n" "Configuration:" "${totaltx} tx, ${blocks} blocks, MCMC every ${MCMC_INTERVAL} blocks"
printf "  %-20s %s\n" "Throughput:" "${tps} tx/s"
printf "  %-20s %s\n" "Total wall:" "${total} ms"
echo ""
printf "  %-20s %12s\n" "Phase" "Time (ms)"
printf "  %-20s %12s\n" "────────────────────" "────────────"
printf "  %-20s %12s\n" "Submit" "${submit:-N/A}"
printf "  %-20s %12s\n" "Batch (cum)" "${batch:-N/A}"
printf "  %-20s %12s\n" "MCMC (cum)" "${mcmc:-N/A}"
printf "  %-20s %12s\n" "Prototype (cum)" "${proto:-N/A}"
printf "  %-20s %12s\n" "Chain (cum)" "${chainupd:-N/A}"
printf "  %-20s %12s\n" "Drain wall" "${drain:-N/A}"
printf "  %-20s %12s\n" "Total wall" "${total:-N/A}"
echo ""
log "Benchmark passed — ${tps} tx/s"
