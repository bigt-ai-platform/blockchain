#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../../../../../.." && pwd)"
cd "$ROOT"

# --- Config ---
L0_PORT=8089
L1_PORT=8086
MCMC_PORT=8091
SERVER_URL="http://127.0.0.1:$L0_PORT/"
L1_URL="http://127.0.0.1:$L1_PORT/"
PG_PORT=5432
DB_NAME=info
COMPOSE_FILE="$ROOT/helper/docker-compose-base.yml"
DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=$PG_PORT -DDB_NAME=$DB_NAME"
SCHED_ARGS="-Dservice.schedule.mcmc=true -Dservice.schedule.microbatch=true -Dservice.schedule.blockbatch=true -Dservice.schedule.blockbatchrate=5000 -Dservice.schedule.initsync=true"
L0_ARGS="--server.net=Test --server.port=$L0_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"

# Use Java 25 if available
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

LOG_DIR=/tmp
L0_LOG="$LOG_DIR/l0-server.log"
MCMC_LOG="$LOG_DIR/l0-mcmc.log"
L1_LOG="$LOG_DIR/l1-order-server.log"

cleanup() {
    echo ""
    echo "=== Cleaning up ==="
    kill $(jobs -p) 2>/dev/null || true
    wait 2>/dev/null || true
    echo "=== Cleanup done ==="
}
trap cleanup EXIT INT TERM

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

# --- Step 2: Drop & recreate database ---
echo ""
echo "=== Step 2: Drop & recreate database '$DB_NAME' ==="
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME;" 2>/dev/null || true
docker exec "$PG_CONTAINER" psql -U root -d postgres -c "CREATE DATABASE $DB_NAME;"
echo "Database '$DB_NAME' ready"

# --- Step 3: Build modules ---
echo ""
echo "=== Step 3: Build modules ==="
# Clean install so spring-boot:run picks up fresh JARs
mvn clean install -DskipTests -q \
    -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge,layer0-server,layer0-mcmc,l1-order-server -am

# --- Step 4: Start L0 HTTP server ---
echo ""
echo "=== Step 4: Start L0 HTTP server (port $L0_PORT) ==="
# Server peer + gossip ports
SERVER_PEER_ARGS="-Dpeer.udpPort=30307 -Dpeer.tcpPort=30308 -Dgossip.port=9095"
nohup mvn spring-boot:run -pl layer0-server \
    -Dspring-boot.run.jvmArguments="$DB_ARGS $SCHED_ARGS $SERVER_PEER_ARGS -Dbridge.active=false -Danchor.active=false" \
    -Dspring-boot.run.arguments="$L0_ARGS" \
    > "$L0_LOG" 2>&1 &
L0_PID=$!
echo "L0 HTTP PID: $L0_PID"

echo "Waiting for L0 HTTP..."
for i in $(seq 1 30); do
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
MCMC_PEER_ARGS="-Dpeer.udpPort=30309 -Dpeer.tcpPort=30310 -Dgossip.port=9097"
MCMC_ARGS="--server.net=Test --server.port=$MCMC_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"
# PoS validator configuration (if the env file is present)
POS_ARGS=""
if [ -f "$ROOT/validator.env" ]; then
    set -a; . "$ROOT/validator.env"; set +a
    # Reward service stays enabled (proven block-persistence path); the PoS
    # SlotService additionally proposes per-slot beacons via the validator key.
    POS_ARGS="-Dpos.validatorKey=$POS_VALIDATOR_KEY"
    echo "PoS enabled: validator key configured (${#POS_VALIDATOR_KEY} hex)"
fi
nohup mvn spring-boot:run -pl layer0-mcmc \
  -Dspring-boot.run.jvmArguments="$DB_ARGS $SCHED_ARGS $MCMC_PEER_ARGS -Dserver.port=$MCMC_PORT -Dserver.requester=http://127.0.0.1:$L0_PORT -Dservice.schedule.rewardonlywithreferenced=false $POS_ARGS" \
  -Dspring-boot.run.arguments="$MCMC_ARGS" \
  > "$MCMC_LOG" 2>&1 &
MCMC_PID=$!
echo "MCMC PID: $MCMC_PID"

echo "=== Step: Start L1 Order Server (port $L1_PORT) ==="
L1_PEER_ARGS="-Dpeer.udpPort=30311 -Dpeer.tcpPort=30312 -Dgossip.port=9099"
L1_ARGS="--server.net=Test --server.port=$L1_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4 --server.chain=L0"
nohup mvn spring-boot:run -pl l1-order-server \
  -Dspring-boot.run.jvmArguments="$DB_ARGS -Dservice.schedule.mcmc=true $L1_PEER_ARGS -Dserver.port=$L1_PORT -Dservice.schedule.rewardonlywithreferenced=false" \
  -Dspring-boot.run.arguments="$L1_ARGS" \
  > "$L1_LOG" 2>&1 &
L1_PID=$!
echo "L1 PID: $L1_PID"

echo "Waiting for L1 Order Server..."
for i in $(seq 1 30); do
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
        -d "{\"pubkey\":\"$VALIDATOR_PUBKEY\",\"amount\":\"32000000\",\"privateKey\":\"$POS_VALIDATOR_KEY\"}" \
        >/dev/null 2>&1 && echo "stake deposited" || echo "stake deposit failed"

    sleep 2
    curl -sf -X POST "http://127.0.0.1:$L0_PORT/activateValidator" \
        -H 'Content-Type: application/json' \
        -d "{\"pubkey\":\"$VALIDATOR_PUBKEY\",\"epoch\":0}" \
        >/dev/null 2>&1 && echo "validator activated" || echo "validator activation failed"

    # Wait for the mcmc's PoS beacon to be produced and confirmed
    echo "Waiting for PoS beacon production..."
    for i in $(seq 1 30); do
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

# --- Step 8: Run remote tests ---
echo ""
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
