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

header "TPS Benchmark :: MaxTpsBenchmark (200 clients × 250 tx = 50k total)"

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
header "Running MaxTpsBenchmark..."
mvn test -pl layer0-mcmc \
    -Dtest=net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmark#testMempoolTps \
    -DDB_HOSTNAME=localhost \
    -DDB_PORT=5432 \
    -DDB_USERNAME=root \
    -DDB_PASSWORD=test1234 \
    -DDB_NAME=layer0 \
    -DfailIfNoTests=false

# ── 4. Parse results from surefire report ──────────────────────────────
header "Results"
REPORT="$ROOT/layer0-mcmc/target/surefire-reports/net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmark-output.txt"
tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$REPORT" | tail -1)
submit=$(grep -oP 'Submit wall:\s+\K[\d.]+' "$REPORT" | tail -1)
batch=$(grep -oP 'Batch wall:\s+\K[\d.]+' "$REPORT" | tail -1)
mcmc=$(grep -oP 'MCMC update:\s+\K[\d.]+' "$REPORT" | tail -1)
proto=$(grep -oP 'Prototype:\s+\K[\d.]+' "$REPORT" | tail -1)
chain=$(grep -oP 'Chain update:\s+\K[\d.]+' "$REPORT" | tail -1)
total=$(grep -oP 'Total wall:\s+\K[\d.]+' "$REPORT" | tail -1)
ok=$(grep -oP 'OK \K[\d]+' "$REPORT" | tail -1)
failc=$(grep -oP 'fail \K[\d]+' "$REPORT" | tail -1)

if [ -z "$tps" ]; then
    echo -e "${RED}No TPS metric found in output — benchmark may have failed.${NC}"
    grep -i "FAILURE\|ERROR" "$REPORT" | tail -5
    exit 1
fi

echo ""
printf "  %-20s %s\n" "Throughput:" "${tps} tx/s"
printf "  %-20s %s\n" "Total tx:" "${ok} OK, ${failc} fail"
printf "  %-20s %s\n" "Total wall:" "${total} ms"
echo ""
printf "  %-20s %12s\n" "Phase" "Time (ms)"
printf "  %-20s %12s\n" "────────────────────" "────────────"
printf "  %-20s %12s\n" "Submit" "${submit:-N/A}"
printf "  %-20s %12s\n" "Batch" "${batch:-N/A}"
printf "  %-20s %12s\n" "MCMC update" "${mcmc:-N/A}"
printf "  %-20s %12s\n" "Prototype" "${proto:-N/A}"
printf "  %-20s %12s\n" "Chain update" "${chain:-N/A}"
echo ""

if [ "$ok" -gt 0 ]; then
    log "Benchmark passed — ${tps} tx/s"
else
    fail "No successful transactions"
fi
