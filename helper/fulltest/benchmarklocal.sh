#!/usr/bin/env bash
# benchmarklocal.sh — single-server ConfirmedPaymentBenchmark on THIS host.
#
# Boots/steps a local PostgreSQL, builds reactor deps, then runs the full
# pipeline benchmark (HTTP submit → mempool → micro-batch blocks → beacon →
# on-chain CONFIRMED) as one JUnit test inside layer0-server.
#
# Usage: ./benchmarklocal.sh [options]
#   -t, --tx N            total payments          (default 10000)
#   -c, --clients N       submit concurrency      (default 20)
#   -b, --batch N         tx per submit call      (default 250)
#   -m, --min-tx N        mempool drain threshold (default 3000)
#   -a, --max-age MS      force-drain age         (default 1500)
#   -S, --slot-ms MS      pos.slotIntervalMs      (default 12000)
#       --db-port P       postgres port           (default 5432)
#       --db-name NAME    database name           (default layer0)
#       --heap SIZE       benchmark JVM heap      (default 9g; scale with
#                         load: the client holds every signed tx plus node
#                         caches while submitting)
#       --no-build        skip mvn dependency install
#       --no-db-start     assume postgres already running
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
log()    { echo -e "${GREEN}[OK]${NC} $1"; }
fail()   { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()   { echo -e "${YELLOW}[INFO]${NC} $1"; }
header() { echo -e "\n${CYAN}════════════════════════════════════════════${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}════════════════════════════════════════════${NC}"; }

TX="${TX:-10000}"
CLIENTS="${CLIENTS:-20}"
BATCH="${BATCH:-250}"
MIN_TX="${MIN_TX:-3000}"
MAX_AGE="${MAX_AGE:-1500}"
SLOT_MS="${SLOT_MS:-12000}"
HEAP="${HEAP:-9g}"
DB_CONTAINER="${DB_CONTAINER:-}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-layer0}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-test1234}"
DO_BUILD=true
DO_DB_START=true
OUT="${OUT:-/tmp/localbench.out}"

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--tx)          TX="$2"; shift 2 ;;
        -c|--clients)     CLIENTS="$2"; shift 2 ;;
        -b|--batch)       BATCH="$2"; shift 2 ;;
        -m|--min-tx)      MIN_TX="$2"; shift 2 ;;
        -a|--max-age)     MAX_AGE="$2"; shift 2 ;;
        -S|--slot-ms)     SLOT_MS="$2"; shift 2 ;;
        --heap)           HEAP="$2"; shift 2 ;;
        --db-port)        DB_PORT="$2"; shift 2 ;;
        --db-name)        DB_NAME="$2"; shift 2 ;;
        --no-build)       DO_BUILD=false; shift ;;
        --no-db-start)    DO_DB_START=false; shift ;;
        -h|--help)        usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

# ── JDK 25 ────────────────────────────────────────────────────────────────────
for jh in "${JAVA_HOME:-}" /opt/jdk25 /usr/lib/jvm/java-25-openjdk-amd64 \
         /usr/lib/jvm/java-25-openjdk-arm64 /home/jcui/.local/java-25; do
    if [ -n "$jh" ] && [ -x "$jh/bin/java" ]; then
        export JAVA_HOME="$jh"; export PATH="$JAVA_HOME/bin:$PATH"; break
    fi
done
command -v java >/dev/null || fail "no JDK 25 found (set JAVA_HOME)"
java -version 2>&1 | head -1 | grep -q '"25\.' || fail "JDK 25 required, got: $(java -version 2>&1 | head -1)"

header "Local single-server benchmark :: ${TX} tx, ${CLIENTS} clients, batch ${BATCH}, slot ${SLOT_MS}ms"
info "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ── Database ──────────────────────────────────────────────────────────────────
# Works against a host-native postgres (service/su) OR a dockerized one:
# DB_CONTAINER=<name> targets a container; when local client tools are
# missing, the container publishing ${DB_PORT} is auto-detected.
db_psql() { # db_psql <sql> [database]
    local sql="$1" db="${2:-postgres}"
    if [ -n "$DB_CONTAINER" ]; then
        docker exec "$DB_CONTAINER" psql -U "${DB_USER}" -d "$db" -tAc "$sql"
    else
        su postgres -c "psql -d '$db' -tAc \"$sql\""
    fi
}

if [ -z "$DB_CONTAINER" ] && ! command -v pg_isready >/dev/null 2>&1; then
    DB_CONTAINER=$(docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null \
        | grep "0.0.0.0:${DB_PORT}->" | awk '{print $1}' | head -1 || true)
    [ -n "$DB_CONTAINER" ] && info "using dockerized postgres container: $DB_CONTAINER"
fi

port_open() {
    (echo > "/dev/tcp/127.0.0.1/${DB_PORT}") >/dev/null 2>&1
}

if [ "$DO_DB_START" = true ]; then
    if ! port_open; then
        info "starting postgresql..."
        service postgresql start >/dev/null 2>&1 || pg_ctlcluster 15 main start >/dev/null 2>&1 \
            || fail "cannot start postgresql"
        sleep 2
    fi
fi
port_open || fail "postgres not ready on :${DB_PORT}"
log "postgresql ready on :${DB_PORT}"

if [ -n "$DB_CONTAINER" ]; then
    db_psql "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" | grep -q 1 \
        || docker exec "$DB_CONTAINER" psql -U "${DB_USER:-root}" -d postgres \
            -c "CREATE USER ${DB_USER} WITH SUPERUSER PASSWORD '${DB_PASS}';" >/dev/null \
        || fail "cannot create db user ${DB_USER}"
else
    su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'\"" 2>/dev/null | grep -q 1 \
        || su postgres -c "psql -c \"CREATE USER ${DB_USER} WITH SUPERUSER PASSWORD '${DB_PASS}';\"" \
        || fail "cannot create db user ${DB_USER}"
fi
db_psql "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \
    || db_psql "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};" >/dev/null 2>&1 \
    || fail "cannot create database ${DB_NAME}"
log "database ${DB_NAME} present"

# ── Build ─────────────────────────────────────────────────────────────────────
if [ "$DO_BUILD" = true ]; then
    info "installing reactor deps..."
    (cd "$ROOT" && mvn -q install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am) \
        || fail "dependency build failed"
    log "reactor deps installed"
fi

# ── Work around upstream-broken RemoteOrderIT (undefined waitForConfirmedBc) ──
BROKEN_IT="$ROOT/layer0-server/src/test/java/net/bigtangle/server/remote/RemoteOrderIT.java"
IT_BACKUP=""
if [ -f "$BROKEN_IT" ] && ! javac -version >/dev/null 2>&1; then true; fi
if [ -f "$BROKEN_IT" ]; then
    IT_BACKUP="$(mktemp /tmp/RemoteOrderIT.java.XXXX)"
    cp "$BROKEN_IT" "$IT_BACKUP"
    rm "$BROKEN_IT"
    info "temporarily moved broken RemoteOrderIT aside"
fi
restore_it() {
    if [ -n "$IT_BACKUP" ] && [ -f "$IT_BACKUP" ]; then
        mkdir -p "$(dirname "$BROKEN_IT")"
        mv "$IT_BACKUP" "$BROKEN_IT"
    fi
}
trap restore_it EXIT

# ── Local Kafka (same provisioning as testnodes.sh) ─────────────────────────
# Single-server benchmark still exercises the produce path when streams are on
# (KAFKA_STREAMS=0 keeps the old streams-off behavior).
KAFKA_STREAMS="${KAFKA_STREAMS:-1}"
KAFKA_SYS=()
if [ "$KAFKA_STREAMS" = "1" ]; then
    # shellcheck disable=SC1091
    source "${ROOT}/helper/kafka-local.sh"
    export KAFKA_CONTAINER="${KAFKA_CONTAINER:-l0-bench-kafka}"
    export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-9392}"
    export KAFKA_CHAINS="${KAFKA_CHAINS:-L0}" KAFKA_FRESH_TOPICS=1
    kafka_local_ensure || fail "local kafka broker failed"
    kafka_local_topics || fail "local kafka topics failed"
    KAFKA_SYS=(-Dserver.runKafkaStream=true -Dkafka.bootstrapServers="localhost:${KAFKA_HOST_PORT}")
    log "bench streams via localhost:${KAFKA_HOST_PORT}"
fi

# ── Run benchmark ─────────────────────────────────────────────────────────────
MVN_ARGS=(-Dtest=ConfirmedPaymentBenchmark -Dbench.tx="${TX}" -Dbench.clients="${CLIENTS}" \
-Dbench.batch="${BATCH}" -Dbatch.minTx="${MIN_TX}" -Dbatch.maxBatchAgeMs="${MAX_AGE}" \
-Dpos.slotIntervalMs="${SLOT_MS}" -Ddb.dbName="${DB_NAME}" -Ddb.port="${DB_PORT}" \
"${KAFKA_SYS[@]}" \
"-DargLine=-Xmx${HEAP} --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED" \
-DforkedProcessTimeoutInSeconds=0 -DfailIfNoTests=false)

header "Running ConfirmedPaymentBenchmark (logging to ${OUT})"
set +e
(cd "$ROOT" && mvn test -pl layer0-server "${MVN_ARGS[@]}" 2>&1) | tee "$OUT"
MVN_EXIT=${PIPESTATUS[0]}
set -e
restore_it
trap - EXIT

# ── Results ───────────────────────────────────────────────────────────────────
strip() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }
total=$(strip "$OUT" | grep -oP 'Total tx:\s+\K\d+ \(' | tail -1 | tr -d '(' || true)
submitted=$(strip "$OUT" | grep -oP 'submitted \K\d+' | tail -1 || true)
confirmed=$(strip "$OUT" | grep -oP '\(final \K\d+' | tail -1 || true)
submit_tps=$(strip "$OUT" | grep -oP 'Submit TPS:\s+\K[\d.]+' | tail -1 || true)
confirm_tps=$(strip "$OUT" | grep -oP 'CONFIRMED TPS:\s+\K[\d.]+' | tail -1 || true)

header "Results"
printf "  %-18s %s\n" "Benchmark:"     "ConfirmedPaymentBenchmark (single server)"
printf "  %-18s %s / %s\n"   "Confirmed:"     "${confirmed:-?}" "${total:-$TX}"
printf "  %-18s %s tx/s\n"   "Submit TPS:"    "${submit_tps:-N/A}"
printf "  %-18s %s tx/s\n"   "CONFIRMED TPS:" "${confirm_tps:-N/A}"

if [ "$MVN_EXIT" -ne 0 ]; then
    fail "maven exited ${MVN_EXIT} — see ${OUT}"
fi
if [ -z "${confirm_tps:-}" ] || [ "${confirmed:-0}" -eq 0 ]; then
    fail "no confirmed transactions — see ${OUT}"
fi
log "benchmark passed — ${confirmed}/${total:-$TX} confirmed, submit ${submit_tps:-?} tx/s, confirmed ${confirm_tps} tx/s"
