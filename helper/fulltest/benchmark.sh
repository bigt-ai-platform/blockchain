#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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
BENCHMARK="payment"
CLIENTS="${CLIENTS:-30}"
PAYMENTS="${PAYMENTS:-2000}"

# Java 25
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "  -s, --server URL    Server URL (default: http://localhost:8081)"
    echo "      --clients N     Concurrent benchmark clients (default: 30)"
    echo "      --payments N    Payments (recipients) per client (default: 2000)"
    echo "  --no-start          Skip starting Docker network"
    echo "  --no-stop           Skip stopping Docker network after run"
    echo "  -h, --help          Show this help"
    echo ""
    echo "Benchmark:"
    echo "  payment  → PaymentBenchmark (N clients, N payments each, HTTP)"
    echo ""
    echo "Environment variables: SERVER_URL CLIENTS PAYMENTS"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--server)    SERVER_URL="$2"; shift 2 ;;
        --clients)      CLIENTS="$2"; shift 2 ;;
        --payments)     PAYMENTS="$2"; shift 2 ;;
        --no-start)     RUN_NETWORK=false; shift ;;
        --no-stop)      STOP_NETWORK=false; shift ;;
        -h|--help)      usage ;;
        *)              echo "Unknown option: $1"; usage ;;
    esac
done

# ── Start Docker network ──────────────────────────────────────────────────
if [ "$RUN_NETWORK" = true ]; then
    header "Starting L0 Docker network..."
    # Kafka streams for the bench net (KAFKA_STREAMS=0 keeps streams-off).
    # Same local-docker provisioning as testnodes.sh; servers reach the broker
    # as <container>:9094 on the compose network.
    KAFKA_STREAMS="${KAFKA_STREAMS:-1}"
    if [ "$KAFKA_STREAMS" = "1" ]; then
        export RUNKAFKASTREAM=true
        export KAFKA_CONTAINER="${KAFKA_CONTAINER:-l0-test-kafka}"
        export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-9192}"
        export BOOT_STRAP_SERVERS="${KAFKA_CONTAINER}:9094"
    fi
    docker compose -f "$COMPOSE_FILE" up -d

    if [ "$KAFKA_STREAMS" = "1" ]; then
        info "Provisioning local Kafka for the bench net..."
        # shellcheck disable=SC1091
        source "${ROOT}/helper/kafka-local.sh"
        export KAFKA_NETWORK
        KAFKA_NETWORK="$(docker network ls --format '{{.Name}}' | grep -E '(^|_)l0-test-net$' | head -1)"
        [ -n "$KAFKA_NETWORK" ] || fail "l0-test-net not found"
        export KAFKA_CHAINS="${KAFKA_CHAINS:-L0}" KAFKA_FRESH_TOPICS=1
        kafka_local_ensure || fail "local kafka broker failed"
        kafka_local_topics || fail "local kafka topics failed"
        log "bench net streams via ${BOOT_STRAP_SERVERS}"
    fi

    info "Waiting for server nodes..."
    for port in 8081 8082 8083; do
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

# ── Benchmark: PaymentBenchmark ─────────────────────────────────────────────
run_payment() {
    header "PaymentBenchmark — ${SERVER_URL}"
    echo "  ${CLIENTS} clients × ${PAYMENTS} payments each via HTTP"

    local tmpfile
    tmpfile=$(mktemp)
    mvn exec:java -pl bigtangle-servercore \
        -Dexec.classpathScope=test \
        -Dexec.mainClass=net.bigtangle.server.benchmark.PaymentBenchmark \
        -Dbenchmark.clients="${CLIENTS}" \
        -Dbenchmark.payments="${PAYMENTS}" \
        -Dexec.args="${SERVER_URL}" 2>&1 | tee "$tmpfile" || true

    local tps avg wall ok fail
    tps=$(grep -oP 'Throughput:\s+\K[\d.]+' "$tmpfile" | tail -1)
    avg=$(grep -oP 'Avg latency:\s+\K[\d.]+' "$tmpfile" | tail -1)
    wall=$(grep -oP 'Wall time:\s+\K[\d.]+' "$tmpfile" | tail -1)
    ok=$(grep -oP 'OK\s+\K[\d]+' "$tmpfile" | tail -1)
    fail=$(grep -oP 'fail\s+\K[\d]+' "$tmpfile" | tail -1)
    rm -f "$tmpfile"

    RESULTS+=("PaymentBenchmark|${tps:-N/A}|${avg:-N/A} ms|${wall:-N/A} ms|${ok:-0}|${fail:-0}")
    log "PaymentBenchmark: ${tps:-N/A} tx/s"
}

# ── Run selected benchmark ────────────────────────────────────────────────
RESULTS=()

case "$BENCHMARK" in
    payment)
        run_payment
        ;;
    *)
        echo "Unknown benchmark: $BENCHMARK"
        echo "Valid: payment"
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