#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$ROOT"

# --- Config (override any via env, e.g. L0_PORT=8089 PG_PORT=5432 ./remote.sh) ---
# Defaults below are tuned to avoid colliding with other infra running on this
# dev machine (dev servers on 808x/909x, test-bigtangle-postgres on 21532).
# On a clean machine use the standard ports (8089/8086/5432).
L0_PORT="${L0_PORT:-24089}"
L1_PORT="${L1_PORT:-24086}"
PG_PORT="${PG_PORT:-21532}"
# peer/gossip ports (must be unique per process on the same host)
L0_PEER_UDP="${L0_PEER_UDP:-46307}"
L0_PEER_TCP="${L0_PEER_TCP:-46308}"
L0_GOSSIP="${L0_GOSSIP:-25095}"
L1_PEER_UDP="${L1_PEER_UDP:-46311}"
L1_PEER_TCP="${L1_PEER_TCP:-46312}"
L1_GOSSIP="${L1_GOSSIP:-25099}"
DB_NAME="${DB_NAME:-info}"
L1_DB_NAME="${L1_DB_NAME:-info_order}"
SERVER_URL="http://127.0.0.1:$L0_PORT/"
L1_URL="http://127.0.0.1:$L1_PORT/"
# Bases without the trailing slash, for the script's own curl calls
# (a URL like //fundAddresses 404s).
SERVER_BASE="${SERVER_URL%/}"
L1_BASE="${L1_URL%/}"
DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=$PG_PORT -DDB_NAME=$DB_NAME"
L1_DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=$PG_PORT -DDB_NAME=$L1_DB_NAME"
SCHED_ARGS="-Dservice.schedule.microbatch=true -Dservice.schedule.blockbatch=true -Dservice.schedule.blockbatchrate=5000 -Dservice.schedule.initsync=true -Dservice.schedule.chainlength=true"
L0_ARGS="--server.net=Test --server.port=$L0_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4 --spring.main.allow-circular-references=true"

# PoS-era: the L0 HTTP server itself runs the validator duty (beacon proposal +
# attestation) on the settlement chain. It must be launched with its own
# ML-DSA-87 validator key (hex seed). Deterministic default seeds keep the
# harness reproducible; override per environment via L0_VALIDATOR_KEY /
# L1_VALIDATOR_KEY.
#
# Important: /stakeDeposit authorizes the deposit with the server's CONFIGURED
# validator key (Layer0DispatcherController returns 403 when the request pubkey
# does not match it), so the key the server holds MUST be the key whose pubkey
# we fund/stake/activate. See ValidatorKeyTool for generating a fresh pair.
#
# TLDR: L0_VALIDATOR_KEY must stay in sync with L0_VALIDATOR_PUBKEY, and
# L1_VALIDATOR_KEY with L1_VALIDATOR_PUBKEY.
L0_VALIDATOR_KEY="${L0_VALIDATOR_KEY:-0404040404040404040404040404040404040404040404040404040404040404}"
# The L1 order chain must not mint bc at genesis (layers.md §5), so its own
# validator cannot stake until the vault peg-in mints confirm — but those mints
# need a beacon chain, and the beacon chain needs a registered, active deposit.
# Bootstrap: the L1 server proposes beacons with the L0 validator key (seed 04,
# pubkey f38c...). The L0 STAKE block the L1 imports via the requester registers
# that exact key as an active L1 deposit (the "phantom"), so the warmup proposer
# (lowest registered pubkey = f38c, the only deposit) is a key the L1 server
# actually holds — the L1 beacon chain starts with no L1 genesis bc. The peg-in
# mints then confirm and the validator stakes vault-backed bc on top.
L1_VALIDATOR_KEY="${L1_VALIDATOR_KEY:-0404040404040404040404040404040404040404040404040404040404040404}"
# Derived via `ValidatorKeyTool pubkey <seed>` (see below for exact values).
L0_VALIDATOR_PUBKEY="${L0_VALIDATOR_PUBKEY:-}"
L1_VALIDATOR_PUBKEY="${L1_VALIDATOR_PUBKEY:-}"

# Bridge / vault keys (layers.md §5): the L1 order chain must NOT mint bc at
# genesis, so its validator + test wallets are funded by VAULT PEG-INS — L0
# locks bc to the vault, L1's PegInWatcherService mints the wrapped bc 1:1.
# The vault key lives on L0 (it locks/unlocks vault collateral); the issuance
# key is the L1 chain's dedicated wrapped-mint signer (never the vault key).
VAULT_SEED="${VAULT_SEED:-1111111111111111111111111111111111111111111111111111111111111111}"
ISSUANCE_SEED="${ISSUANCE_SEED:-2222222222222222222222222222222222222222222222222222222222222222}"
VAULT_PUBKEY="${VAULT_PUBKEY:-}"
ISSUANCE_PUBKEY="${ISSUANCE_PUBKEY:-}"

# The remote tests sign as the L0 genesis wallet (ML-DSA-87 seed 0x01). On a
# fresh PoS chain the genesis coin is paid out to the configured miner address,
# so the genesis wallet is funded explicitly below (Step 7). The L1-order chain
# has a fresh genesis and needs its wallet funded too (Step 7c).
#
# Preferred: fund the init wallets (genesis / yuan / validator / miner) directly
# in the L0 GENESIS BLOCK via a GenesisOutput CSV (TestGenesisOutput.csv), so
# the test wallets start with REAL confirmed chain state and no faucet
# (chicken-and-egg) injection is needed. If the CSV is absent, remote.sh falls
# back to the fundAddresses faucet in Step 7.
L0_GENESIS_PUBKEY="${L0_GENESIS_PUBKEY:-}"
L0_YUAN_PUBKEY="${L0_YUAN_PUBKEY:-}"
# ML-DSA-87 seed of the L0 genesis wallet (also the L1 genesis wallet). It is
# funded on L0 by the genesis CSV and is the funding wallet for the L1
# peg-ins below.
L0_GENESIS_KEY="${L0_GENESIS_KEY:-$(printf '01%.0s' {1..32})}"
TEST_GENESIS_CSV="${TEST_GENESIS_CSV:-$ROOT/helper/test/TestGenesisOutput.csv}"
L1_GENESIS_PUBKEY="${L1_GENESIS_PUBKEY:-}"

POS_ARGS="-Dpos.slotIntervalMs=${SLOT_INTERVAL_MS:-6000}"

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

LOG_DIR=/tmp
L0_LOG="$LOG_DIR/l0-server.log"
L1_LOG="$LOG_DIR/l1-order-server.log"

# Infra-only mode: start L0/L1 but skip the remote tests and keep everything
# running until Ctrl+C. Activate with: ./remote.sh infra
INFRA_ONLY=false
STOP_ONLY=false
case "${1:-}" in
    infra|--infra|infra-only) INFRA_ONLY=true ;;
    stop|down) STOP_ONLY=true ;;
esac

# Skip the maven build. Default: skip in infra-only mode (assumes binaries are
# already built), build for test runs. Force with SKIP_BUILD=0/1.
if [ -n "${SKIP_BUILD:-}" ]; then
    if [ "$SKIP_BUILD" = "1" ]; then SKIP_BUILD_BOOL=true; else SKIP_BUILD_BOOL=false; fi
else
    SKIP_BUILD_BOOL="$INFRA_ONLY"
fi

# Figure out how to talk to PostgreSQL. Prefer an already-created database; the
# harness needs a real (Docker or local) PostgreSQL instance with role
# root/test1234. Location can be a running container (PG_CONTAINER) or a local
# service (PG_PSQL=psql). Default: try Docker containers used by the fulltest
# compose files first, else fall back to the local postgres service.
export PGPASSWORD="${PG_PASSWORD:-test1234}"
PG_CONTAINER="${PG_CONTAINER:-}"
PG_WRAPPER=""
if [ -z "$PG_CONTAINER" ] && command -v docker >/dev/null 2>&1; then
    for cand in l0-pg-0 test-bigtangle-postgres; do
        if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${cand}$"; then
            PG_CONTAINER="$cand"
            break
        fi
    done
fi
if [ -n "$PG_CONTAINER" ]; then
    pg_exec() { docker exec "$PG_CONTAINER" "$@"; }
    # Postgres inside the container may listen on its own port (5432 for the
    # standard image) or on the host-mapped port (e.g. `postgres -p 21532`);
    # $PG_PORT is the HOST-side mapping used by the JVM server DB args. Probe
    # the container for the actual internal listener so in-container psql
    # always reaches it.
    PG_INTERNAL_PORT="${PG_INTERNAL_PORT:-}"
    if [ -z "$PG_INTERNAL_PORT" ]; then
        for cand in "$PG_PORT" 5432; do
            if pg_exec pg_isready -U root -d postgres -p "$cand" >/dev/null 2>&1; then
                PG_INTERNAL_PORT="$cand"
                break
            fi
        done
    fi
    [ -n "$PG_INTERNAL_PORT" ] || PG_INTERNAL_PORT=5432
else
    pg_exec() { "$@"; }
    PG_INTERNAL_PORT="$PG_PORT"
fi
pgisready() {
    if [ -n "$PG_CONTAINER" ]; then
        pg_exec pg_isready -U root -d postgres -p "$PG_INTERNAL_PORT" >/dev/null 2>&1
    else
        pg_exec pg_isready -h 127.0.0.1 -U root -d postgres -p "$PG_INTERNAL_PORT" >/dev/null 2>&1
    fi
}

cleanup() {
    echo ""
    echo "=== Cleaning up ==="
    kill $(jobs -p) 2>/dev/null || true
    # Also kill any JVMs this infra started, even if the launching shell was
    # detached (setsid/nohup) and the job table is empty. Match by the exact
    # module class names, or maven spring-boot:run for the two infra modules,
    # so we never kill unrelated JVMs (e.g. dev servers on other ports).
    for pat in "Layer0ServerStart" "OrderMatchL1ServerStart"; do
        pkill -9 -f "$pat" 2>/dev/null || true
    done
    for mod in "layer0-server" "l1-order-server"; do
        pkill -9 -f "spring-boot:run.*-pl $mod" 2>/dev/null || true
        pkill -9 -f "-pl $mod .*spring-boot:run" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

# --- Stop mode: kill the running infra (started detached by `infra`) ---
if [ "$STOP_ONLY" = "true" ]; then
    echo "=== Stopping infra (ports $L0_PORT/$L1_PORT) ==="
    for port in "$L0_PORT" "$L1_PORT"; do
        pid=$(ss -tlnp 2>/dev/null | grep -E ":$port " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
        if [ -n "${pid:-}" ]; then
            echo "Killing pid $pid (port $port)"
            kill "$pid" 2>/dev/null || true
        fi
    done
    # Also kill any leftover infra JVMs (detached launch leaves orphans)
    for pat in "Layer0ServerStart" "OrderMatchL1ServerStart"; do
        pkill -9 -f "$pat" 2>/dev/null || true
    done
    for mod in "layer0-server" "l1-order-server"; do
        pkill -9 -f "spring-boot:run.*-pl $mod" 2>/dev/null || true
        pkill -9 -f "-pl $mod .*spring-boot:run" 2>/dev/null || true
    done
    sleep 3
    if ss -tln 2>/dev/null | grep -qE ":(24089|24086) "; then
        echo "Ports still in use, forcing..."
        pkill -9 -f "spring-boot:run" 2>/dev/null || true
        sleep 2
    fi
    echo "=== Infra stopped ==="
    exit 0
fi

echo "============================================="
echo "  RemoteTest Infrastructure Setup (PoS era)"
echo "============================================="

# --- Step 1: PostgreSQL up ---
echo ""
echo "=== Step 1: Check PostgreSQL ==="
if [ -n "$PG_CONTAINER" ]; then
    echo "Using PostgreSQL container: $PG_CONTAINER"
else
    echo "Using local PostgreSQL on port $PG_PORT"
fi
if ! pgisready; then
    echo "WARNING: PostgreSQL not ready on port $PG_PORT. If nothing started it,"
    echo "         boot one first, e.g.:"
    echo "           docker compose -f helper/docker-compose-base.yml up -d"
    echo "           # or, for a local install: service postgresql start"
    if [ "$INFRA_ONLY" = "true" ]; then
        echo "Infra-only mode: continuing anyway (server will retry DB connect)."
    else
        exit 1
    fi
fi

# --- Step 2: Drop & recreate databases (L0 and L1-order are fully separated) ---
echo ""
echo "=== Step 2: Drop & recreate databases '$DB_NAME' and '$L1_DB_NAME' ==="
pgerr() { if [ -n "$PG_CONTAINER" ]; then echo "postgres:$PG_PORT (container $PG_CONTAINER)"; else echo "127.0.0.1:$PG_PORT"; fi; }
pg_exec psql -U root -d postgres -p "$PG_INTERNAL_PORT" -c "DROP DATABASE IF EXISTS $DB_NAME;" >/dev/null 2>&1 || true
if ! pg_exec psql -U root -d postgres -p "$PG_INTERNAL_PORT" -c "CREATE DATABASE $DB_NAME;" >/dev/null 2>&1; then
    echo "ERROR: could not create database '$DB_NAME' on $(pgerr). Is PostgreSQL up with role root/test1234?"
    exit 1
fi
echo "Database '$DB_NAME' ready"
pg_exec psql -U root -d postgres -p "$PG_INTERNAL_PORT" -c "DROP DATABASE IF EXISTS $L1_DB_NAME;" >/dev/null 2>&1 || true
pg_exec psql -U root -d postgres -p "$PG_INTERNAL_PORT" -c "CREATE DATABASE $L1_DB_NAME;" >/dev/null 2>&1
echo "Database '$L1_DB_NAME' ready"

# --- Step 3: Build modules ---
echo ""
if [ "$SKIP_BUILD_BOOL" = "true" ]; then
    echo "=== Step 3: Build modules (SKIPPED) ==="
else
    echo "=== Step 3: Build modules ==="
    # Clean install so spring-boot:run picks up fresh JARs
    mvn clean install -DskipTests -q \
        -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge,layer0-server,l1-order-server -am
fi

# Derive validator pubkeys from the configured seeds (authoritative) unless the
# caller provided explicit pubkeys.
M2_REPO="$HOME/.m2/repository"
if [ ! -d "$M2_REPO" ] && [ -d /root/.m2/repository ]; then
    M2_REPO="/root/.m2/repository"
fi
req_pubkey() {
    local seed="$1"
    # Pick the NEWEST matching jar: find's arbitrary order can yield ancient
    # versions (e.g. guava-10.0.1 lacks com.google.common.io.BaseEncoding).
    local slf4j guava bcprov
    slf4j="$(find "$M2_REPO" -name 'slf4j-api-*.jar' ! -name '*-sources*' ! -name '*-javadoc*' | sort -V | tail -1)"
    guava="$(find "$M2_REPO" -name 'guava-*.jar' ! -name '*-sources*' ! -name '*-javadoc*' | sort -V | tail -1)"
    bcprov="$(find "$M2_REPO" -name 'bcprov-jdk18on-*.jar' ! -name '*-sources*' ! -name '*-javadoc*' | sort -V | tail -1)"
    "$JAVA_HOME/bin/java" -cp \
        "$ROOT/bigtangle-core/target/classes:$slf4j:$guava:$bcprov" \
        net.bigtangle.tools.ValidatorKeyTool pubkey "$seed" 2>/dev/null | grep '^VALIDATOR_PUBKEY=' | cut -d= -f2
}
if [ -z "$L0_VALIDATOR_PUBKEY" ]; then
    L0_VALIDATOR_PUBKEY="$(req_pubkey "$L0_VALIDATOR_KEY" || true)"
fi
if [ -z "$L1_VALIDATOR_PUBKEY" ]; then
    L1_VALIDATOR_PUBKEY="$(req_pubkey "$L1_VALIDATOR_KEY" || true)"
fi
# The remote tests sign as the L0 genesis wallet (TestParams.genesisPub,
# ML-DSA-87 seed 0x01). On a fresh PoS chain the genesis coin lives in the
# miner address, not the genesis wallet, so fund that wallet to pay fees.
if [ -z "$L0_GENESIS_PUBKEY" ]; then
    L0_GENESIS_PUBKEY="$(req_pubkey "$(printf '01%.0s' {1..32})" || true)"
fi
# The yuan wallet in RemoteFromAddressIT is keyed on ML-DSA-87 seed 0x03 and
# must pay token creation fees + token-payment fees from confirmed BIG, so
# pre-fund it at genesis like the genesis wallet.
if [ -z "$L0_YUAN_PUBKEY" ]; then
    L0_YUAN_PUBKEY="$(req_pubkey "$(printf '03%.0s' {1..32})" || true)"
fi
# The L1-order chain's genesis wallet uses the SAME ML-DSA-87 seed 0x01 as the
# L0 genesis wallet. Without deriving it here the Step 7c funding block below
# is skipped (its [ -n "$L1_GENESIS_PUBKEY" ] guard), leaving the L1 genesis
# wallet unfunded and order tests failing with InsufficientMoneyException.
if [ -z "$L1_GENESIS_PUBKEY" ]; then
    L1_GENESIS_PUBKEY="$(req_pubkey "$(printf '01%.0s' {1..32})" || true)"
fi
# Bridge vault + L1 issuance keys (layers.md §5.2). Deterministic seeds keep
# the harness reproducible; override per environment via VAULT_PUBKEY /
# ISSUANCE_PUBKEY.
if [ -z "$VAULT_PUBKEY" ]; then
    VAULT_PUBKEY="$(req_pubkey "$VAULT_SEED" || true)"
fi
if [ -z "$ISSUANCE_PUBKEY" ]; then
    ISSUANCE_PUBKEY="$(req_pubkey "$ISSUANCE_SEED" || true)"
fi
echo "L0 validator pubkey: ${L0_VALIDATOR_PUBKEY:0:24}... (${#L0_VALIDATOR_PUBKEY} hex)"
echo "L1 validator pubkey: ${L1_VALIDATOR_PUBKEY:0:24}... (${#L1_VALIDATOR_PUBKEY} hex)"
echo "L0 genesis wallet pubkey: ${L0_GENESIS_PUBKEY:0:24}... (${#L0_GENESIS_PUBKEY} hex)"
echo "Bridge vault pubkey: ${VAULT_PUBKEY:0:24}... (${#VAULT_PUBKEY} hex)"
echo "L1 issuance pubkey: ${ISSUANCE_PUBKEY:0:24}... (${#ISSUANCE_PUBKEY} hex)"

L0_POS_ARGS="-Dpos.validatorKey=$L0_VALIDATOR_KEY $POS_ARGS -Dpos.dutyEnabled=true"
# When a test genesis CSV is present, mint the init wallets (genesis/yuan/
# validator/miner) inside the L0 genesis block instead of the fundAddresses
# faucet — the wallets then start with real, confirmed chain state.
L0_GENESIS_CSV_ARGS=""
if [ -f "$TEST_GENESIS_CSV" ]; then
    L0_GENESIS_CSV_ARGS="-Dbigtangle.genesis.csv=$TEST_GENESIS_CSV"
    echo "Using L0 genesis distribution: $TEST_GENESIS_CSV"
fi

# --- Step 4: Start L0 HTTP server (PoS validator duty on the settlement chain) ---
echo ""
echo "=== Step 4: Start L0 HTTP server (port $L0_PORT) ==="
SERVER_PEER_ARGS="-Dpeer.udpPort=$L0_PEER_UDP -Dpeer.tcpPort=$L0_PEER_TCP -Dgossip.port=$L0_GOSSIP"
# Bridge enabled with the vault key: the L1 chain is funded by vault peg-ins
# (layers.md §5.2), so L0 must accept processPegIn and record VaultRecords.
L0_BRIDGE_ARGS="-Dbridge.active=true -Dbridge.vaultPubKeyHex=$VAULT_PUBKEY -Dbridge.vaultPriKeyHex=$VAULT_SEED"
nohup mvn spring-boot:run -pl layer0-server \
    -Dspring-boot.run.jvmArguments="$DB_ARGS $SCHED_ARGS $SERVER_PEER_ARGS $L0_POS_ARGS $L0_GENESIS_CSV_ARGS $L0_BRIDGE_ARGS" \
    -Dspring-boot.run.arguments="$L0_ARGS" \
    > "$L0_LOG" 2>&1 &
L0_PID=$!
echo "L0 HTTP PID: $L0_PID"

# Wait until the HTTP server actually answers (an open TCP port can race
# Spring's context startup; the funding/staking curls below would then 404).
wait_http_ready() {
    local port="$1"
    local logf="$2"
    for i in $(seq 1 90); do
        if curl -sf "http://127.0.0.1:$port/" | grep -q "Bigtangle" 2>/dev/null; then
            echo "HTTP ready on :$port after ~${i}x2s"
            return 0
        fi
        if [ $i -eq 90 ]; then
            echo "HTTP server :$port failed to answer"
            tail -30 "$logf"
            return 1
        fi
        sleep 2
    done
}

# POST a JSON body to an endpoint and keep retrying until the controller really
# answers (success JSON with errorcode 0). The generic BaseDispatcherController
# catch-all answers HTTP 400/500 while the specific route (FundAddressesController,
# stakeDeposit, ...) is still being registered, so a plain curl -sf is not enough.
# Huge bodies (e.g. 64 funded UTXOs, each carrying a ~5KB ML-DSA pubkey) exceed
# the shell command-line ARG_MAX limit, so write the payload to a temp file and
# post it with --data-binary @file instead of an inline -d argument.
post_ok() {
    local url="$1"
    local data="$2"
    local tmp
    tmp=$(mktemp)
    printf '%s' "$data" > "$tmp"
    for i in $(seq 1 60); do
        local resp
        resp=$(curl -s -X POST "$url" -H 'Content-Type: application/json' --data-binary @"$tmp" 2>/dev/null || true)
        if printf '%s' "$resp" | grep -q '"errorcode" *: *0'; then
            rm -f "$tmp"
            return 0
        fi
        sleep 2
    done
    echo "  last response: ${resp:+$(printf '%s' "$resp" | head -c 200)}" >&2
    rm -f "$tmp"
    return 1
}

echo "Waiting for L0 HTTP..."
for i in $(seq 1 60); do
    sleep 2
    # Check if server is up by connecting to port
    if ss -tlnp 2>/dev/null | grep -q ":$L0_PORT "; then
        echo "L0 HTTP ready after ${i}s (port $L0_PORT open)"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "L0 HTTP failed to start"
        tail -30 "$L0_LOG"
        exit 1
    fi
done
wait_http_ready "$L0_PORT" "$L0_LOG" || exit 1

# --- Step 5: Wait for genesis block ---
echo ""
echo "=== Step 5: Wait for genesis block ==="
for i in $(seq 1 10); do
    sleep 3
    HASH=$(pg_exec psql -U root -d $DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c "
      SELECT encode(hash, 'hex') FROM blocks WHERE blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1;
    " 2>/dev/null || echo "")
    if [ -n "$HASH" ]; then
        echo "Genesis block found: ${HASH:0:24}..."
        break
    fi
    if [ $i -eq 10 ]; then
        echo "No genesis block found after 30s"
        tail -30 "$L0_LOG"
        exit 1
    fi
done

# Insert genesis into TipsQueue for initial tip
echo "=== Step 6: Insert genesis into TipsQueue ==="
pg_exec psql -U root -d $DB_NAME -p "$PG_INTERNAL_PORT" -c "
  INSERT INTO tipsqueue (hash, block, height, inserttime)
  SELECT b.hash, b.block, b.height, b.inserttime
  FROM blocks b WHERE b.blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1
  ON CONFLICT (hash) DO NOTHING;
" 2>/dev/null || true
sleep 3

echo "TipsQueue has $(pg_exec psql -U root -d $DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c "SELECT count(*) FROM tipsqueue;" 2>/dev/null || echo 0), proceeding to validators"

# --- Step 7: Register the L0 PoS validator (the key the L0 server holds) ---
echo ""
echo "=== Step 7: Register L0 PoS validator ==="

# When the genesis CSV is used, the validator / genesis / yuan wallets already
# hold confirmed coins from the genesis block, so the fundAddresses faucet calls
# below are skipped (no synthetic off-chain minting needed).
if [ -z "$L0_GENESIS_CSV_ARGS" ]; then
    FUND_AMOUNT=1000000000000
    if post_ok "$SERVER_BASE/fundAddresses" \
        "{\"addresses\":[{\"address\":\"validator\",\"value\":$FUND_AMOUNT,\"pubkey\":\"$L0_VALIDATOR_PUBKEY\"}]}"; then
        echo "L0 validator funded"
    else
        echo "L0 validator funding failed"
    fi
fi

sleep 2
if post_ok "$SERVER_BASE/stakeDeposit" \
    "{\"pubkey\":\"$L0_VALIDATOR_PUBKEY\",\"amount\":\"32000000\"}"; then
    echo "L0 stake deposited"
else
    echo "L0 stake deposit failed"
fi

sleep 2
if post_ok "$SERVER_BASE/activateValidator" \
    "{\"pubkey\":\"$L0_VALIDATOR_PUBKEY\",\"epoch\":0}"; then
    echo "L0 validator activated"
else
    echo "L0 validator activation failed"
fi

if [ -n "$L0_GENESIS_CSV_ARGS" ]; then
    echo "L0 init wallets funded via genesis CSV (no faucet)"
else
    # Fund the L0 genesis wallet (ML-DSA-87 seed 0x01) so the remote tests can
    # pay fees / create tokens on the settlement chain as the genesis wallet.
    # Many distinct confirmed UTXOs are minted (one per entry) so each test /
    # token creation can spend a fresh confirmed BIG source; a single UTXO would
    # leave only unconfirmed change after the first spend and later tests would
    # fail with InsufficientMoneyException.
    GENESIS_FUND_UTXOS="${GENESIS_FUND_UTXOS:-64}"
    L0_GENESIS_ENTRIES=""
    for i in $(seq 1 "$GENESIS_FUND_UTXOS"); do
        L0_GENESIS_ENTRIES="${L0_GENESIS_ENTRIES}{\"address\":\"genesis\",\"value\":100000000000000,\"pubkey\":\"$L0_GENESIS_PUBKEY\"},"
    done
    L0_GENESIS_ENTRIES="[${L0_GENESIS_ENTRIES%,}]"
    if post_ok "$SERVER_BASE/fundAddresses" "{\"addresses\":$L0_GENESIS_ENTRIES}"; then
        echo "L0 genesis wallet funded ($GENESIS_FUND_UTXOS UTXOs)"
    else
        echo "L0 genesis funding failed"
    fi

    # Fund the yuan key (ML-DSA-87 seed 0x03) that RemoteFromAddressIT uses as the
    # yuan token issuer + yuanWallet, so it has confirmed BIG to pay the token
    # creation fee and the token-payment fees. The plain mempool transfer path
    # (submitTransaction) never confirms on this PoS build, so the yuan wallet
    # must start with confirmed BIG instead of waiting for a transfer to confirm.
    YUAN_FUND_UTXOS="${YUAN_FUND_UTXOS:-8}"
    YUAN_FUND_ENTRIES=""
    for i in $(seq 1 "$YUAN_FUND_UTXOS"); do
        YUAN_FUND_ENTRIES="${YUAN_FUND_ENTRIES}{\"address\":\"yuan\",\"value\":100000000000000,\"pubkey\":\"$L0_YUAN_PUBKEY\"},"
    done
    YUAN_FUND_ENTRIES="[${YUAN_FUND_ENTRIES%,}]"
    if post_ok "$SERVER_BASE/fundAddresses" "{\"addresses\":$YUAN_FUND_ENTRIES}"; then
        echo "L0 yuan key funded ($YUAN_FUND_UTXOS UTXOs)"
    else
        echo "L0 yuan key funding failed"
    fi
fi

# Wait for the L0 (PoS) beacon chain to start producing
echo "Waiting for L0 PoS beacon production..."
for i in $(seq 1 60); do
    sleep 3
    HEIGHT=$(pg_exec psql -U root -d $DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c \
        "SELECT max(height) FROM blocks WHERE blocktype <> 'BLOCKTYPE_INITIAL';" 2>/dev/null || echo "0")
    if [ -n "$HEIGHT" ] && [ "$HEIGHT" -gt 0 ]; then
        echo "L0 PoS beacon produced, height=$HEIGHT"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "WARNING: L0 beacon not produced after 180s, tail:"
        tail -20 "$L0_LOG"
    fi
done

# --- Step 7b: Start L1 Order Server (port $L1_PORT) ---
echo ""
echo "=== Step 7b: Start L1 Order Server (port $L1_PORT) ==="
L1_PEER_ARGS="-Dpeer.udpPort=$L1_PEER_UDP -Dpeer.tcpPort=$L1_PEER_TCP -Dgossip.port=$L1_GOSSIP"
# L1-order runs on its OWN ordermatch chain with its OWN database + validator,
# fully separated from Layer 0. It connects to L0 only to pull order blocks via
# the cross-layer transfer (blocksFromNonChainHeight).
L1_ARGS="--server.net=Test --server.port=$L1_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4 --server.requester=http://127.0.0.1:$L0_PORT --spring.main.allow-circular-references=true"
L1_POS_ARGS="-Dpos.validatorKey=$L1_VALIDATOR_KEY $POS_ARGS -Dpos.dutyEnabled=true"
# L1 must NOT mint bc at genesis (genesisMintsBIG=false, layers.md §5). It is
# funded by vault peg-ins instead: the bridge watches L0 vault locks for this
# chain and mints wrapped bc (PegInWatcherService -> processPegInFromL0). The
# vault key matches L0; the issuance key signs the wrapped mint.
# requireFinality=false: the single-validator L0 does not reach Casper finality
# (its attestations gossip to a production seed), so the issuance/peg-out
# finality gate would stall the bootstrap. Production defaults the gate ON.
L1_BRIDGE_ARGS="-Dbridge.active=true -Dbridge.vaultPubKeyHex=$VAULT_PUBKEY -Dbridge.issuancePubKeyHex=$ISSUANCE_PUBKEY -Dbridge.issuancePriKeyHex=$ISSUANCE_SEED -Danchor.l0Url=$SERVER_BASE -Dbridge.requireFinality=false"
nohup mvn spring-boot:run -pl l1-order-server \
  -Dspring-boot.run.jvmArguments="$L1_DB_ARGS $SCHED_ARGS -Dservice.schedule.syncrate=10000 $L1_PEER_ARGS -Dserver.port=$L1_PORT $L1_POS_ARGS $L1_BRIDGE_ARGS" \
  -Dspring-boot.run.arguments="$L1_ARGS" \
  > "$L1_LOG" 2>&1 &
L1_PID=$!
echo "L1 PID: $L1_PID"

echo "Waiting for L1 Order Server..."
for i in $(seq 1 60); do
    sleep 2
    if ss -tlnp 2>/dev/null | grep -q ":$L1_PORT "; then
        echo "L1 Order Server ready after ${i}s (port $L1_PORT open)"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "L1 Order Server failed to start"
        tail -30 "$L1_LOG"
        exit 1
    fi
done
wait_http_ready "$L1_PORT" "$L1_LOG" || exit 1

# Recompile test classes with latest server changes
mvn test-compile -q -pl layer0-server

# --- Step 7c: Bootstrap the L1-order chain via vault peg-ins (layers.md §5.2) ---
echo ""
echo "=== Step 7c: Bootstrap L1-order via vault peg-in (no L1 bc genesis) ==="

# The L1 order chain does NOT mint bc at genesis (genesisMintsBIG=false).
# Its validator + test wallet are funded the design-consistent way: on L0 we
# lock bc to the vault (processPegIn) declaring chainId=ordermatch and the L1
# recipient; L1's PegInWatcherService observes the confirmed vault lock and
# mints the wrapped bc 1:1 on the L1 chain. Only then can the L1 validator
# stakeDeposit/activateValidator.
PEGIN_TOOL="$ROOT/helper/prod/validators/PegInTool.java"

# Unpack the layer0 exec jar once so the single-file Java tools can run
# (BOOT-INF classes + lib), mirroring testnodes.sh's sign_exit_for_cp.
CP_DIR="${PEGIN_CP_DIR:-$ROOT/target/pegin-cp}"
if [ ! -d "$CP_DIR/BOOT-INF/classes" ]; then
    rm -rf "$CP_DIR"; mkdir -p "$CP_DIR"
    EXEC_JAR="$(ls -t "$ROOT"/layer0-server/target/layer0-server-*-exec.jar 2>/dev/null | head -1)"
    if [ -z "$EXEC_JAR" ] || [ ! -f "$EXEC_JAR" ]; then
        echo "ERROR: no layer0-server exec jar at layer0-server/target/ (run mvn package first)"
        exit 1
    fi
    unzip -oq "$EXEC_JAR" 'BOOT-INF/*' -d "$CP_DIR"
fi
PEGIN_CP="$CP_DIR/BOOT-INF/classes:$CP_DIR/BOOT-INF/lib/*"

# Submit ONE peg-in from a funding wallet (L0 genesis wallet, seed 01) to the
# L1 beneficiary; echoes the beneficiary address for later polling.
run_pegin() {
    local seed="$1" benpub="$2" out
    out="$("$JAVA_HOME/bin/java" -cp "$PEGIN_CP" "$PEGIN_TOOL" \
        "$seed" "$benpub" "ordermatch" "$VAULT_PUBKEY" "$SERVER_BASE" 2>&1)"
    printf '%s\n' "$out"
    if [ "$(printf '%s\n' "$out" | grep '^PEGIN_OK=' | cut -d= -f2)" != "true" ]; then
        echo "  WARNING: peg-in to $benpub failed (see PEGIN_RESPONSE above)"
    fi
    printf '%s\n' "$out" | grep '^BENEFICIARY_ADDR=' | cut -d= -f2
}

# 0) Wait for the L1-order chain's beacon chain to START producing (the L1
#    server proposes as the imported L0 validator deposit, seed 04) BEFORE
#    submitting peg-ins — the wrapped mints are then collected and confirmed by
#    the live beacon chain within a few slots instead of sitting unconfirmed
#    for many minutes.
echo "Waiting for the L1-order beacon chain to start..."
for i in $(seq 1 60); do
    L1_CL=$(pg_exec psql -U root -d $L1_DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c \
        "SELECT max(chainlength) FROM blocks WHERE blocktype='BLOCKTYPE_BEACON';" 2>/dev/null || echo "0")
    if [ -n "$L1_CL" ] && [ "${L1_CL//[^0-9]/}" -ge 1 ]; then
        echo "L1-order beacon chain producing (chainlength=$L1_CL)"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "WARNING: L1 beacon chain not producing after 180s, tail:"
        tail -30 "$L1_LOG"
    fi
    sleep 3
done

# 1) Peg-in for the L1 validator (>= MIN_STAKE 32000000 + fee). A whole
#    genesis-CSV UTXO (100000000000000) is locked 1:1.
L1_VALIDATOR_ADDR="$(run_pegin "$L0_GENESIS_KEY" "$L1_VALIDATOR_PUBKEY")"
sleep 3

# 2) Peg-ins for the L1 genesis wallet (ML-DSA-87 seed 0x01, same key as the
#    L0 genesis wallet) so the remote order tests can pay fees / create tokens
#    directly on the L1-order chain. Multiple confirmed UTXOs (one per entry)
#    so consecutive order tests each spend a fresh confirmed BIG source — a
#    single UTXO would leave only unconfirmed change after the first spend.
L1_GENESIS_PEGIN_UTXOS="${L1_GENESIS_PEGIN_UTXOS:-8}"
L1_GENESIS_ADDR=""
for i in $(seq 1 "$L1_GENESIS_PEGIN_UTXOS"); do
    L1_GENESIS_ADDR="$(run_pegin "$L0_GENESIS_KEY" "$L0_GENESIS_PUBKEY")"
    sleep 2
done

# 3) Wait for the L1 chain to observe the confirmed L0 vault locks and mint the
#    wrapped bc (PegInWatcherService polls L0 every 15s), and for those
#    CROSSTANGLE mints to confirm on L1. The L1 beacon chain runs on the
#    imported L0 validator deposit, so the mints confirm.
echo "Waiting for L1 wrapped bc mints to confirm on L1..."
# Output confirmation is DERIVED from the containing block's confirmed state
# (OUTPUTS_CONFIRMED joins blocks.confirmed), NOT the outputs.confirmed column
# — poll the join so the count reflects real confirmation. The single-validator
# L1 beacon chain confirms slowly (production outpaces confirmation), so allow
# up to ~15 min.
EXPECTED_CONFIRMED=$((1 + L1_GENESIS_PEGIN_UTXOS))
for i in $(seq 1 300); do
    COUNT=$(pg_exec psql -U root -d $L1_DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c \
        "SELECT count(*) FROM outputs o JOIN blocks b ON b.hash=o.blockhash WHERE b.confirmed AND o.tokenid='bc' AND o.toaddress IN ('$L1_VALIDATOR_ADDR','$L1_GENESIS_ADDR');" 2>/dev/null || echo "0")
    if [ -n "$COUNT" ] && [ "${COUNT//[^0-9]/}" -ge "$EXPECTED_CONFIRMED" ]; then
        echo "L1 wrapped bc confirmed: $COUNT/$EXPECTED_CONFIRMED UTXOs"
        break
    fi
    if [ $i -eq 300 ]; then
        echo "WARNING: L1 wrapped bc mints not confirmed after 900s (count=$COUNT), tail:"
        tail -30 "$L1_LOG"
    fi
    sleep 3
done

sleep 2
if post_ok "$L1_BASE/stakeDeposit" \
    "{\"pubkey\":\"$L1_VALIDATOR_PUBKEY\",\"amount\":\"32000000\"}"; then
    echo "L1 stake deposited (funded via vault peg-in)"
else
    echo "L1 stake deposit failed"
fi

sleep 2
if post_ok "$L1_BASE/activateValidator" \
    "{\"pubkey\":\"$L1_VALIDATOR_PUBKEY\",\"epoch\":0}"; then
    echo "L1 validator activated"
else
    echo "L1 validator activation failed"
fi

# Wait for the L1-order chain to produce its own beacon
echo "Waiting for L1-order beacon production..."
for i in $(seq 1 60); do
    sleep 3
    L1_HEIGHT=$(pg_exec psql -U root -d $L1_DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c \
        "SELECT max(height) FROM blocks WHERE blocktype <> 'BLOCKTYPE_INITIAL';" 2>/dev/null || echo "0")
    if [ -n "$L1_HEIGHT" ] && [ "$L1_HEIGHT" -gt 0 ]; then
        echo "L1-order beacon produced, height=$L1_HEIGHT"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "WARNING: L1-order beacon not produced after 180s, tail:"
        tail -20 "$L1_LOG"
    fi
done

# Wait until the L0 beacon chain is STABLE before running token-creating tests.
# A token block built on an early beacon parent can be orphaned when the beacon
# chain still reorganises; once a few beacons have confirmed the chain is
# stable enough that freshly created token blocks confirm promptly.
STABLE_BEACONS="${STABLE_BEACONS:-6}"
echo "Waiting for a stable L0 beacon chain (>= $STABLE_BEACONS confirmed beacons)..."
for i in $(seq 1 60); do
    STABLE_COUNT=$(pg_exec psql -U root -d $DB_NAME -p "$PG_INTERNAL_PORT" -t -A -c \
        "SELECT count(*) FROM blocks WHERE confirmed AND chainlength > 0;" 2>/dev/null || echo "0")
    if [ -n "$STABLE_COUNT" ] && [ "${STABLE_COUNT//[^0-9]/}" -ge "$STABLE_BEACONS" ]; then
        echo "L0 beacon chain stable: $STABLE_COUNT confirmed beacons"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "WARNING: L0 beacon chain not stable after 180s (confirmed=$STABLE_COUNT), continuing anyway"
    fi
    sleep 3
done
sleep 3

# --- Step 8: Run remote tests (or hold if infra-only) ---
echo ""
if [ "$INFRA_ONLY" = "true" ]; then
    echo "============================================="
    echo "  INFRA RUNNING (Ctrl+C to stop)"
    echo "  L0   : http://127.0.0.1:$L0_PORT (log: $L0_LOG)"
    echo "  L1   : http://127.0.0.1:$L1_PORT (log: $L1_LOG)"
    echo "  DB   : postgres:$PG_PORT db=$DB_NAME/$L1_DB_NAME"
    echo "============================================="
    while true; do sleep 3600; done
fi

echo "=== Step 8: Run remote tests ==="

# Default: run ALL remote IT tests (wildcard matches every Remote*IT class in
# net.bigtangle.server.remote; RemoteTest is the abstract base and has no
# @Test). Override via first argument, e.g. ./remote.sh RemoteTokenIT or
# ./remote.sh 'Remote*IT'.
TEST_CLASS="${1:-net.bigtangle.server.remote.Remote*IT}"

mvn test -pl layer0-server \
    -Dtest="$TEST_CLASS" \
    -Dserver.url="$SERVER_URL" \
    -Dl1.url="$L1_URL" \
    $DB_ARGS \
    -Dsurefire.failIfNoSpecifiedTests=false

EXIT_CODE=$?

# --- Step 9: Report ---
echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "============================================="
    echo "  REMOTE TESTS: SUCCESS"
    echo "============================================="
else
    echo "============================================="
    echo "  REMOTE TESTS: FAILED (exit $EXIT_CODE)"
    echo "============================================="
    tail -50 "$L0_LOG" "$L1_LOG"
fi

exit $EXIT_CODE