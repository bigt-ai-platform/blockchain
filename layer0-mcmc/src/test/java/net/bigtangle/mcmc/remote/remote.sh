#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../../../../../.." && pwd)"
cd "$ROOT"

# --- Config (override any via env, e.g. L0_PORT=8089 PG_PORT=5432 ./remote.sh) ---
# Defaults below are tuned to avoid colliding with other infra running on this
# dev machine (dev servers on 808x/909x, test-bigtangle-postgres on 21532).
# On a clean machine use the standard ports (8089/8086/8091/5432).
L0_PORT="${L0_PORT:-24089}"
L1_PORT="${L1_PORT:-24086}"
MCMC_PORT="${MCMC_PORT:-24091}"
PG_PORT="${PG_PORT:-21532}"
# peer/gossip ports (must be unique per process on the same host)
L0_PEER_UDP="${L0_PEER_UDP:-46307}"
L0_PEER_TCP="${L0_PEER_TCP:-46308}"
L0_GOSSIP="${L0_GOSSIP:-25095}"
MCMC_PEER_UDP="${MCMC_PEER_UDP:-46309}"
MCMC_PEER_TCP="${MCMC_PEER_TCP:-46310}"
MCMC_GOSSIP="${MCMC_GOSSIP:-25097}"
L1_PEER_UDP="${L1_PEER_UDP:-46311}"
L1_PEER_TCP="${L1_PEER_TCP:-46312}"
L1_GOSSIP="${L1_GOSSIP:-25099}"
DB_NAME="${DB_NAME:-info}"
L1_DB_NAME="${L1_DB_NAME:-info_order}"
SERVER_URL="http://127.0.0.1:$L0_PORT/"
L1_URL="http://127.0.0.1:$L1_PORT/"
COMPOSE_FILE="$ROOT/helper/docker-compose-base.yml"
DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=$PG_PORT -DDB_NAME=$DB_NAME"
L1_DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=$PG_PORT -DDB_NAME=$L1_DB_NAME"
SCHED_ARGS="-Dservice.schedule.mcmc=true -Dservice.schedule.microbatch=true -Dservice.schedule.blockbatch=true -Dservice.schedule.blockbatchrate=5000 -Dservice.schedule.initsync=true"
L0_ARGS="--server.net=Test --server.port=$L0_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"
# L1-order runs its own ordermatch chain, so it needs its own validator
# (ML-DSA-87 seed 0x05). Using the L0 validator key here would double-vote
# on L0 and get it slashed.
L1_VALIDATOR_KEY="${L1_VALIDATOR_KEY:-0505050505050505050505050505050505050505050505050505050505050505}"
L1_VALIDATOR_PUBKEY="${L1_VALIDATOR_PUBKEY:-$(cat /home/jcui/git/blockchain/.l1validatorpub 2>/dev/null || true)}"
L1_POS_ARGS="-Dpos.validatorKey=$L1_VALIDATOR_KEY -Dpos.slotIntervalMs=2000"

# PoS validator configuration (if the env file is present). The L0 HTTP server
# must hold the same validator key as the MCMC: /stakeDeposit authorizes the
# deposit with the server's CONFIGURED validator key (DispatcherController
# returns 403 when it is null), so without it the validator can never be
# registered and no beacon is ever produced.
POS_ARGS=""
if [ -f "$ROOT/validator.env" ]; then
    set -a; . "$ROOT/validator.env"; set +a
    # PoS-only: the slot proposer is the only beacon producer (single-headed
    # chain, no forks). A short slot interval makes blocks confirm quickly so
    # the remote tests' polling windows fit.
    POS_ARGS="-Dpos.validatorKey=$POS_VALIDATOR_KEY -Dpos.slotIntervalMs=2000"
    echo "PoS enabled: validator key configured (${#POS_VALIDATOR_KEY} hex)"
fi

# Use Java 25 if available
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

LOG_DIR=/tmp
L0_LOG="$LOG_DIR/l0-server.log"
MCMC_LOG="$LOG_DIR/l0-mcmc.log"
L1_LOG="$LOG_DIR/l1-order-server.log"

# Infra-only mode: start L0/L1/MCMC but skip the remote tests and keep
# everything running until Ctrl+C. Activate with: ./remote.sh infra
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

cleanup() {
    echo ""
    echo "=== Cleaning up ==="
    kill $(jobs -p) 2>/dev/null || true
    # Also kill any JVMs this infra started, even if the launching shell was
    # detached (setsid/nohup) and the job table is empty. Match by the exact
    # module class names, or maven spring-boot:run for the three infra modules,
    # so we never kill unrelated JVMs (e.g. dev servers on other ports).
    for pat in "Layer0ServerStart" "Layer0MCMCStart" "OrderMatchL1ServerStart"; do
        pkill -9 -f "$pat" 2>/dev/null || true
    done
    for mod in "layer0-server" "layer0-mcmc" "l1-order-server"; do
        pkill -9 -f "spring-boot:run.*-pl $mod" 2>/dev/null || true
        pkill -9 -f "-pl $mod .*spring-boot:run" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

# --- Stop mode: kill the running infra (started detached by `infra`) ---
if [ "$STOP_ONLY" = "true" ]; then
    echo "=== Stopping infra (ports $L0_PORT/$MCMC_PORT/$L1_PORT) ==="
    for port in "$L0_PORT" "$MCMC_PORT" "$L1_PORT"; do
        pid=$(ss -tlnp 2>/dev/null | grep -E ":$port " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
        if [ -n "${pid:-}" ]; then
            echo "Killing pid $pid (port $port)"
            kill "$pid" 2>/dev/null || true
        fi
    done
    # Also kill any leftover infra JVMs (detached launch leaves orphans)
    for pat in "Layer0ServerStart" "Layer0MCMCStart" "OrderMatchL1ServerStart"; do
        pkill -9 -f "$pat" 2>/dev/null || true
    done
    for mod in "layer0-server" "layer0-mcmc" "l1-order-server"; do
        pkill -9 -f "spring-boot:run.*-pl $mod" 2>/dev/null || true
        pkill -9 -f "-pl $mod .*spring-boot:run" 2>/dev/null || true
    done
    sleep 3
    if ss -tln 2>/dev/null | grep -qE ":(24089|24091|24086) "; then
        echo "Ports still in use, forcing..."
        pkill -9 -f "spring-boot:run" 2>/dev/null || true
        sleep 2
    fi
    echo "=== Infra stopped ==="
    exit 0
fi

echo "============================================="
echo "  RemoteTest Infrastructure Setup"
echo "============================================="

# --- Step 1: Use existing PostgreSQL (l0-pg-0) ---
echo ""
echo "=== Step 1: Use existing PostgreSQL ==="
PG_CONTAINER="l0-pg-0"
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${PG_CONTAINER}$"; then
    PG_CONTAINER="test-bigtangle-postgres"
    if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${PG_CONTAINER}$"; then
        echo "ERROR: PostgreSQL container not found. Start one first."
        exit 1
    fi
fi

echo "Waiting for PostgreSQL..."
for i in $(seq 1 15); do
    if docker exec "$PG_CONTAINER" pg_isready -U root -d postgres >/dev/null 2>&1; then
        echo "PostgreSQL ready"
        break
    fi
    if [ "$i" -eq 15 ]; then
        echo "PostgreSQL not ready"
        exit 1
    fi
    sleep 2
done

# --- Step 2: Drop & recreate databases (L0 and L1-order are fully separated) ---
echo ""
echo "=== Step 2: Drop & recreate databases '$DB_NAME' and '$L1_DB_NAME' ==="
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;" 2>/dev/null || true
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "CREATE DATABASE $DB_NAME;"
echo "Database '$DB_NAME' ready"
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "DROP DATABASE IF EXISTS $L1_DB_NAME;" 2>/dev/null || true
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "CREATE DATABASE $L1_DB_NAME;"
echo "Database '$L1_DB_NAME' ready"

# --- Step 3: Build modules ---
echo ""
if [ "$SKIP_BUILD_BOOL" = "true" ]; then
    echo "=== Step 3: Build modules (SKIPPED) ==="
else
    echo "=== Step 3: Build modules ==="
    # Clean install so spring-boot:run picks up fresh JARs
    mvn clean install -DskipTests -q \
        -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge,layer0-server,layer0-mcmc,l1-order-server -am
fi

# --- Step 4: Start L0 HTTP server ---
echo ""
echo "=== Step 4: Start L0 HTTP server (port $L0_PORT) ==="
# Server peer + gossip ports
SERVER_PEER_ARGS="-Dpeer.udpPort=$L0_PEER_UDP -Dpeer.tcpPort=$L0_PEER_TCP -Dgossip.port=$L0_GOSSIP"
nohup mvn spring-boot:run -pl layer0-server \
    -Dspring-boot.run.jvmArguments="$DB_ARGS $SCHED_ARGS $SERVER_PEER_ARGS $POS_ARGS -Dserver.fundEnabled=true -Dbridge.active=false -Danchor.active=false" \
    -Dspring-boot.run.arguments="$L0_ARGS" \
    > "$L0_LOG" 2>&1 &
L0_PID=$!
echo "L0 HTTP PID: $L0_PID"

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

# --- Step 5: Wait for tip (insert genesis into TipsQueue if needed) ---
echo ""
echo "=== Step 5: Wait for genesis block ==="
for i in $(seq 1 10); do
    sleep 3
    HASH=$(docker exec "$PG_CONTAINER" psql -U root -d $DB_NAME -t -A -c "
      SELECT encode(hash, 'hex') FROM blocks WHERE blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1;
    " 2>/dev/null || echo "")
    if [ -n "$HASH" ]; then
        echo "Genesis block found: $HASH"
        break
    fi
    if [ $i -eq 10 ]; then
        echo "No genesis block found after 30s"
        exit 1
    fi
done

echo "=== Step 6: Start L0 MCMC (port $MCMC_PORT) ==="
# Use different ports from server to avoid conflicts on same machine
MCMC_PEER_ARGS="-Dpeer.udpPort=$MCMC_PEER_UDP -Dpeer.tcpPort=$MCMC_PEER_TCP -Dgossip.port=$MCMC_GOSSIP"
MCMC_ARGS="--server.net=Test --server.port=$MCMC_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"
nohup mvn spring-boot:run -pl layer0-mcmc \
  -Dspring-boot.run.jvmArguments="$DB_ARGS $SCHED_ARGS $MCMC_PEER_ARGS -Dserver.port=$MCMC_PORT -Dserver.requester=http://127.0.0.1:$L0_PORT $POS_ARGS" \
  -Dspring-boot.run.arguments="$MCMC_ARGS" \
  > "$MCMC_LOG" 2>&1 &
MCMC_PID=$!
echo "MCMC PID: $MCMC_PID"

echo "=== Step: Start L1 Order Server (port $L1_PORT) ==="
L1_PEER_ARGS="-Dpeer.udpPort=$L1_PEER_UDP -Dpeer.tcpPort=$L1_PEER_TCP -Dgossip.port=$L1_GOSSIP"
# L1-order runs on its OWN ordermatch chain with its OWN database, fully
# separated from Layer 0. It connects to L0 only to pull order blocks via
# the cross-layer transfer (blocksFromNonChainHeight), not by sharing L0's chain.
L1_ARGS="--server.net=Test --server.port=$L1_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4 --server.requester=http://127.0.0.1:$L0_PORT"
nohup mvn spring-boot:run -pl l1-order-server \
  -Dspring-boot.run.jvmArguments="$L1_DB_ARGS $SCHED_ARGS -Dservice.schedule.syncrate=10000 $L1_PEER_ARGS -Dserver.port=$L1_PORT $L1_POS_ARGS -Dserver.fundEnabled=true" \
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

# Recompile test classes with latest server changes
mvn test-compile -q -pl layer0-mcmc

# Insert genesis into TipsQueue for initial tip
echo "=== Step 7: Insert genesis into TipsQueue ==="
docker exec "$PG_CONTAINER" psql -U root -d $DB_NAME -c "
  INSERT INTO tipsqueue (hash, block, height, inserttime)
  SELECT b.hash, b.block, b.height, b.inserttime
  FROM blocks b WHERE b.blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1
  ON CONFLICT (hash) DO NOTHING;
" 2>/dev/null || true
# Insert a second copy with a different hash (timestamp-based to avoid pk conflict)
docker exec "$PG_CONTAINER" psql -U root -d $DB_NAME -c "
  INSERT INTO tipsqueue (hash, block, height, inserttime)
  SELECT decode(lpad(to_hex(nextval('hibernate_sequence')::bigint), 64, '0'), 'hex'),
         b.block, b.height, b.inserttime
  FROM blocks b WHERE b.blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1
  ON CONFLICT (hash) DO NOTHING;
" 2>/dev/null || true
sleep 3

echo "TipsQueue has $(docker exec "$PG_CONTAINER" psql -U root -d $DB_NAME -t -A -c "SELECT count(*) FROM tipsqueue;"), proceeding to tests"

# --- Step 7b: Register the PoS validator ---
if [ -f "$ROOT/validator.env" ] && [ -n "${VALIDATOR_PUBKEY:-}" ]; then
    echo ""
    echo "=== Step 7b: Register PoS validator ==="
    FUND_AMOUNT=1000000000000
    curl -sf -X POST "http://127.0.0.1:$L0_PORT/fundAddresses" \
        -H 'Content-Type: application/json' \
        -d "{\"addresses\":[{\"address\":\"validator\",\"value\":$FUND_AMOUNT,\"pubkey\":\"$VALIDATOR_PUBKEY\"}]}" \
        >/dev/null 2>&1 && echo "validator funded" || echo "validator funding failed"

    sleep 2
    curl -sf -X POST "http://127.0.0.1:$L0_PORT/stakeDeposit" \
        -H 'Content-Type: application/json' \
        -d "{\"pubkey\":\"$VALIDATOR_PUBKEY\",\"amount\":\"32000000\"}" \
        >/dev/null 2>&1 && echo "stake deposited" || echo "stake deposit failed"

    sleep 2
    curl -sf -X POST "http://127.0.0.1:$L0_PORT/activateValidator" \
        -H 'Content-Type: application/json' \
        -d "{\"pubkey\":\"$VALIDATOR_PUBKEY\",\"epoch\":0}" \
        >/dev/null 2>&1 && echo "validator activated" || echo "validator activation failed"

    # Wait for the mcmc's PoS beacon to be produced and confirmed
    echo "Waiting for PoS beacon production..."
    for i in $(seq 1 60); do
        sleep 3
        HEIGHT=$(docker exec "$PG_CONTAINER" psql -U root -d $DB_NAME -t -A -c \
            "SELECT max(height) FROM blocks WHERE blocktype <> 'BLOCKTYPE_INITIAL';" 2>/dev/null || echo "0")
        if [ -n "$HEIGHT" ] && [ "$HEIGHT" -gt 0 ]; then
            echo "PoS beacon produced, height=$HEIGHT"
            break
        fi
    done
    sleep 5
fi

# --- Step 7c: Bootstrap the L1-order chain's own validator ---
# L1-order runs a fully separated chain (own DB, own consensus). It must have
# its own staked validator so it can produce beacons, confirm transferred
# order blocks and run order matching.
echo ""
echo "=== Step 7c: Bootstrap L1-order validator ==="
L1_FUND_AMOUNT=1000000000000
curl -sf -X POST "http://127.0.0.1:$L1_PORT/fundAddresses" \
    -H 'Content-Type: application/json' \
    -d "{\"addresses\":[{\"address\":\"validator\",\"value\":$L1_FUND_AMOUNT,\"pubkey\":\"$L1_VALIDATOR_PUBKEY\"}]}" \
    >/dev/null 2>&1 && echo "L1 validator funded" || echo "L1 validator funding failed"

sleep 2
curl -sf -X POST "http://127.0.0.1:$L1_PORT/stakeDeposit" \
    -H 'Content-Type: application/json' \
    -d "{\"pubkey\":\"$L1_VALIDATOR_PUBKEY\",\"amount\":\"32000000\"}" \
    >/dev/null 2>&1 && echo "L1 stake deposited" || echo "L1 stake deposit failed"

sleep 2
curl -sf -X POST "http://127.0.0.1:$L1_PORT/activateValidator" \
    -H 'Content-Type: application/json' \
    -d "{\"pubkey\":\"$L1_VALIDATOR_PUBKEY\",\"epoch\":0}" \
    >/dev/null 2>&1 && echo "L1 validator activated" || echo "L1 validator activation failed"

# Fund the L1 genesis wallet (ML-DSA-87 seed 0x01) so the remote tests can
# pay fees / create tokens directly on the L1-order chain.
L1_GENESIS_PUBKEY="${L1_GENESIS_PUBKEY:-$(cat /home/jcui/git/blockchain/.l1genesispub 2>/dev/null || true)}"
if [ -n "$L1_GENESIS_PUBKEY" ]; then
    curl -sf -X POST "http://127.0.0.1:$L1_PORT/fundAddresses" \
        -H 'Content-Type: application/json' \
        -d "{\"addresses\":[{\"address\":\"genesis\",\"value\":100000000000000,\"pubkey\":\"$L1_GENESIS_PUBKEY\"}]}" \
        >/dev/null 2>&1 && echo "L1 genesis wallet funded" || echo "L1 genesis funding failed"
fi

# Wait for the L1-order chain to produce its own beacon
echo "Waiting for L1-order beacon production..."
for i in $(seq 1 60); do
    sleep 3
    L1_HEIGHT=$(docker exec "$PG_CONTAINER" psql -U root -d $L1_DB_NAME -t -A -c \
        "SELECT max(height) FROM blocks WHERE blocktype <> 'BLOCKTYPE_INITIAL';" 2>/dev/null || echo "0")
    if [ -n "$L1_HEIGHT" ] && [ "$L1_HEIGHT" -gt 0 ]; then
        echo "L1-order beacon produced, height=$L1_HEIGHT"
        break
    fi
done
sleep 5

# --- Step 8: Run remote tests (or hold if infra-only) ---
echo ""
if [ "$INFRA_ONLY" = "true" ]; then
    echo "============================================="
    echo "  INFRA RUNNING (Ctrl+C to stop)"
    echo "  L0   : http://127.0.0.1:$L0_PORT (log: $L0_LOG)"
    echo "  MCMC : http://127.0.0.1:$MCMC_PORT (log: $MCMC_LOG)"
    echo "  L1   : http://127.0.0.1:$L1_PORT (log: $L1_LOG)"
    echo "  DB   : postgres:$PG_PORT db=$DB_NAME/$L1_DB_NAME (container $PG_CONTAINER)"
    echo "============================================="
    while true; do sleep 3600; done
fi

echo "=== Step 6: Run remote tests ==="

# Default test class, override via first argument
TEST_CLASS="${1:-net.bigtangle.mcmc.remote.RemoteTokenTests}"

mvn test -pl layer0-mcmc \
    -Dtest="$TEST_CLASS" \
    -Dserver.url="$SERVER_URL" \
    -Dl1.url="$L1_URL" \
    $DB_ARGS \
    -Dsurefire.failIfNoSpecifiedTests=false

EXIT_CODE=$?

# --- Step 7: Report ---
echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "============================================="
    echo "  REMOTE TESTS: SUCCESS"
    echo "============================================="
else
    echo "============================================="
    echo "  REMOTE TESTS: FAILED (exit $EXIT_CODE)"
    echo "============================================="
    tail -50 "$L0_LOG" "$MCMC_LOG"
fi

exit $EXIT_CODE
