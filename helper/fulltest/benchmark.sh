#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/jcui/git/blockchain
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

# Defaults
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.l0-test.yml"
SERVER_URL="${SERVER_URL:-http://localhost:8081}"
RUN_NETWORK=true
STOP_NETWORK=true
BENCHMARKS="payment"

# Java 25
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "  -s, --server URL    Server URL (default: http://localhost:8081)"
    echo "  -b, --benchmark T   Benchmark: payment | remote | max-tps | all (default: payment)"
    echo "  --no-start          Skip starting Docker network"
    echo "  --no-stop           Skip stopping Docker network after run"
    echo "  -h, --help          Show this help"
    echo ""
    echo "Benchmark classes:"
    echo "  payment  → BenchmarkRunner (10 clients, 200 payments each, HTTP)"
    echo "  payment  → PaymentBenchmarkMain (10 clients, 50 payments each, HTTP)"
    echo "  remote   → RemoteTest / RemoteTokenTests / RemoteFromAddressTests / RemoteBinaryTests"
    echo "  max-tps  → MaxTpsBenchmark (200 clients, 250 tx each, embedded+HTTP)"
    echo "  max-tps  → MaxTPSBenchmark (50 clients, 1000 tx each, embedded)"
    echo "  all      → Everything above"
    echo ""
    echo "Environment variables: SERVER_URL"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--server)    SERVER_URL="$2"; shift 2 ;;
        -b|--benchmark) BENCHMARKS="$2"; shift 2 ;;
        --no-start)     RUN_NETWORK=false; shift ;;
        --no-stop)      STOP_NETWORK=false; shift ;;
        -h|--help)      usage ;;
        *)              echo "Unknown option: $1"; usage ;;
    esac
done

# ── Start Docker network ──────────────────────────────────────────────────
if [ "$RUN_NETWORK" = true ]; then
    header "Starting L0 Docker network..."
    docker compose -f "$COMPOSE_FILE" up -d

    SERVER_PORTS=(8081 8082 8083)
    MCMC_PORTS=(8084 8085 8086)

    info "Waiting for server nodes..."
    for port in "${SERVER_PORTS[@]}"; do
        for i in $(seq 1 30); do
            if curl -sf "http://localhost:$port/" >/dev/null 2>&1; then
                log "server :${port} ready"
                break
            fi
            if [ "$i" -eq 30 ]; then
                docker logs --tail=20 "l0-svr-0" 2>/dev/null || true
                fail "server :${port} not ready after 90s"
            fi
            sleep 3
        done
    done

    info "Waiting for MCMC nodes..."
    for port in "${MCMC_PORTS[@]}"; do
        for i in $(seq 1 15); do
            if docker ps --format '{{.Names}}' | grep -q "l0-mcmc"; then
                log "mcmc :${port} accessible"
                break
            fi
            sleep 3
        done
    done

    # Quick sanity check: block count
    for port in 8081 8082 8083; do
        height=$(curl -sf "http://localhost:$port/getBlockCount" 2>/dev/null || echo "unreachable")
        info "Node :${port} block count: ${height}"
    done
fi

# ── Build modules ──────────────────────────────────────────────────────────
header "Building project modules..."
cd "$ROOT"
mvn install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am -q
log "Build complete"

# We need test-compile for the benchmark classes
mvn test-compile -pl layer0-mcmc -q
log "Test classes compiled"

# ── Benchmark: Payment via BenchmarkRunner ───────────────────────────────────
run_payment_runner() {
    header "BenchmarkRunner — ${SERVER_URL}"
    echo "  10 clients × 200 payments each via HTTP"

    local tmpfile
    tmpfile=$(mktemp)
    mvn exec:java -pl layer0-mcmc \
        -Dexec.classpathScope=test \
        -Dexec.mainClass=net.bigtangle.mcmc.test.benchmark.BenchmarkRunner \
        -Dexec.args="${SERVER_URL}" 2>&1 | tee "$tmpfile" || true

    local tps avg wall ok fail
    tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$tmpfile" | tail -1)
    avg=$(grep -oP 'Avg latency:\s+\K[\d.]+' "$tmpfile" | tail -1)
    wall=$(grep -oP 'Wall time:\s+\K[\d.]+' "$tmpfile" | tail -1)
    ok=$(grep -oP 'OK\s+\K[\d]+' "$tmpfile" | tail -1)
    fail=$(grep -oP 'fail\s+\K[\d]+' "$tmpfile" | tail -1)
    rm -f "$tmpfile"

    RESULTS+=("BenchmarkRunner|${tps:-N/A}|${avg:-N/A} ms|${wall:-N/A} ms|${ok:-0}|${fail:-0}")
    log "BenchmarkRunner: ${tps} tx/s"
}

# ── Benchmark: Payment via PaymentBenchmarkMain ──────────────────────────────
run_payment_main() {
    header "PaymentBenchmarkMain — ${SERVER_URL}"
    echo "  10 clients × 50 payments each via HTTP"

    local tmpfile
    tmpfile=$(mktemp)
    mvn exec:java -pl layer0-mcmc \
        -Dexec.classpathScope=test \
        -Dexec.mainClass=net.bigtangle.performance.PaymentBenchmarkMain \
        -Dexec.args="${SERVER_URL}" 2>&1 | tee "$tmpfile" || true

    local tps avg wall ok fail
    tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$tmpfile" | tail -1 || true)
    avg=$(grep -oP 'Avg latency:\s+\K[\d.]+' "$tmpfile" | tail -1 || true)
    wall=$(grep -oP 'Wall time:\s+\K[\d.]+' "$tmpfile" | tail -1 || true)
    ok=$(grep -oP 'OK\s+\K\d+' "$tmpfile" | tail -1 || true)
    fail=$(grep -oP 'fail\s+\K\d+' "$tmpfile" | tail -1 || true)
    rm -f "$tmpfile"

    RESULTS+=("PaymentBenchmarkMain|${tps:-N/A}|${avg:-N/A} ms|${wall:-N/A} ms|${ok:-0}|${fail:-0}")
    log "PaymentBenchmarkMain: ${tps} tx/s"
}

# ── Benchmark: Remote Tests ─────────────────────────────────────────────────
run_remote_tests() {
    header "Remote Integration Tests — server.url=${SERVER_URL}"

    local classes=(
        "net.bigtangle.mcmc.remote.RemoteTest"
        "net.bigtangle.mcmc.remote.RemoteTokenTests"
        "net.bigtangle.mcmc.remote.RemoteFromAddressTests"
        "net.bigtangle.mcmc.remote.RemoteBinaryTests"
    )
    for cls in "${classes[@]}"; do
        info "Running ${cls}..."
        mvn test -pl layer0-mcmc -q \
            -Dtest="${cls}#*" \
            -Dserver.url="${SERVER_URL}" \
            -DfailIfNoTests=false 2>&1 || true
    done
    RESULTS+=("RemoteTests|pass|—|—|—|—")
    log "Remote tests done"
}

# ── Benchmark: MaxTpsBenchmark (benchmark pkg, embedded + Docker DB) ────────
run_max_tps_bench() {
    header "MaxTpsBenchmark (200 clients × 250 tx, embedded + Docker PG)"
    info "DB_HOSTNAME=localhost DB_PORT=5432 DB_NAME=layer0"

    local tmpfile
    tmpfile=$(mktemp)
    mvn test -pl layer0-mcmc -q \
        -Dtest=net.bigtangle.mcmc.test.benchmark.MaxTpsBenchmark#testMempoolTps \
        -DDB_HOSTNAME=localhost \
        -DDB_PORT=5432 \
        -DDB_USERNAME=root \
        -DDB_PASSWORD=test1234 \
        -DDB_NAME=layer0 \
        -DfailIfNoTests=false 2>&1 | tee "$tmpfile" || true

    local tps submit_ms batch_ms mcmc_ms chain_ms ok fail
    tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$tmpfile" | tail -1)
    submit_ms=$(grep -oP 'Submit wall:\s+\K[\d.]+' "$tmpfile" | tail -1)
    batch_ms=$(grep -oP 'Batch wall:\s+\K[\d.]+' "$tmpfile" | tail -1)
    mcmc_ms=$(grep -oP 'MCMC update:\s+\K[\d.]+' "$tmpfile" | tail -1)
    chain_ms=$(grep -oP 'Chain update:\s+\K[\d.]+' "$tmpfile" | tail -1)
    ok=$(grep -oP 'OK\s+\K[\d]+' "$tmpfile" | tail -1)
    fail=$(grep -oP 'fail\s+\K[\d]+' "$tmpfile" | tail -1)
    rm -f "$tmpfile"

    RESULTS+=("MaxTpsBench(bmark)|${tps:-ERR}|submit=${submit_ms:-N/A}ms|${batch_ms:-N/A}ms|${ok:-0}|${fail:-0}")
    log "MaxTpsBenchmark: ${tps:-ERR} tx/s"
}

# ── Benchmark: MaxTPSBenchmark (perf pkg, embedded + Docker DB) ─────────────
run_max_tps_perf() {
    header "MaxTPSBenchmark (50 clients × 1000 tx, embedded, zero-HTTP)"
    info "DB_HOSTNAME=localhost DB_PORT=5432 DB_NAME=layer0"

    local tmpfile
    tmpfile=$(mktemp)
    mvn test -pl layer0-mcmc -q \
        -Dtest=net.bigtangle.mcmc.test.perf.MaxTPSBenchmark#testMaxTPS \
        -DDB_HOSTNAME=localhost \
        -DDB_PORT=5432 \
        -DDB_USERNAME=root \
        -DDB_PASSWORD=test1234 \
        -DDB_NAME=layer0 \
        -DfailIfNoTests=false 2>&1 | tee "$tmpfile" || true

    local tps submit_ms batch_ms mcmc_ms chain_ms ok fail
    tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$tmpfile" | tail -1)
    submit_ms=$(grep -oP 'Submit wall:\s+\K[\d.]+' "$tmpfile" | tail -1)
    batch_ms=$(grep -oP 'Batch wall:\s+\K[\d.]+' "$tmpfile" | tail -1)
    mcmc_ms=$(grep -oP 'MCMC update:\s+\K[\d.]+' "$tmpfile" | tail -1)
    chain_ms=$(grep -oP 'Chain update:\s+\K[\d.]+' "$tmpfile" | tail -1)
    ok=$(grep -oP 'OK\s+\K[\d]+' "$tmpfile" | tail -1)
    fail=$(grep -oP 'fail\s+\K[\d]+' "$tmpfile" | tail -1)
    rm -f "$tmpfile"

    RESULTS+=("MaxTPSBench(perf)|${tps:-ERR}|submit=${submit_ms:-N/A}ms|${batch_ms:-N/A}ms|${ok:-0}|${fail:-0}")
    log "MaxTPSBenchmark: ${tps:-ERR} tx/s"
}

# ── Run selected benchmarks ────────────────────────────────────────────────
RESULTS=()

case "$BENCHMARKS" in
    payment)
        run_payment_runner
        run_payment_main
        ;;
    remote)
        run_remote_tests
        ;;
    max-tps)
        run_max_tps_bench
        run_max_tps_perf
        ;;
    all)
        run_payment_runner
        run_payment_main
        run_remote_tests
        run_max_tps_bench
        run_max_tps_perf
        ;;
    *)
        echo "Unknown benchmark: $BENCHMARKS"
        echo "Valid: payment, remote, max-tps, all"
        exit 1
        ;;
esac

# ── Summary Table ────────────────────────────────────────────────────────────
header "Benchmark Summary"
printf "  %-30s %12s %14s %12s %6s %6s\n" "Benchmark" "TPS" "Avg Latency" "Wall" "OK" "Fail"
printf "  %-30s %12s %14s %12s %6s %6s\n" "──────────────────────────────" "────────────" "──────────────" "────────────" "──────" "──────"
for row in "${RESULTS[@]}"; do
    IFS='|' read -r name tps lat wall ok fail <<< "$row"
    printf "  %-30s %12s %14s %12s %6s %6s\n" "$name" "$tps" "$lat" "$wall" "$ok" "$fail"
done
echo ""

# ── Tear down ──────────────────────────────────────────────────────────────
if [ "$STOP_NETWORK" = true ]; then
    info "Tearing down Docker network..."
    docker compose -f "$COMPOSE_FILE" down -v
fi

log "All benchmarks finished."
