#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()   { echo -e "${GREEN}[OK]${NC} $1"; }
fail()  { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()  { echo -e "${YELLOW}[INFO]${NC} $1"; }

# Default: 10 epochs x 16s = 160s
EPOCHS="${EPOCHS:-10}"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.prodsim.yml"

# Port offset: run the whole prodsim on its own ports so it never collides
# with other infra (default +20000 → servers 28081-28088, postgres 25432-25435).
PORT_OFFSET="${PORT_OFFSET:-20000}"
SERVER_PORTS=($((8081+PORT_OFFSET)) $((8082+PORT_OFFSET)) $((8083+PORT_OFFSET)) $((8084+PORT_OFFSET)))
PG_PORT=$((5432+PORT_OFFSET))
L0_URL="http://localhost:$((8081+PORT_OFFSET))/"

# Use Java 25 if available
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

cleanup() {
    info "Tearing down prodsim network..."
    docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    log "Cleanup done"
}
trap cleanup EXIT INT TERM

# ─── Build ──────────────────────────────────────────────────────────
info "Building Maven project (skipping tests)..."
cd "$ROOT"
mvn install -DskipTests -q \
    -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge,layer0-server,layer0-mcmc -am

info "Building Docker images (layer0-server, layer0-mcmc)..."
docker build -t ghcr.io/bigt-ai-platform/layer0-server:latest \
    -f "$ROOT/layer0-server/Dockerfile" "$ROOT/layer0-server"
docker build -t ghcr.io/bigt-ai-platform/layer0-mcmc:latest \
    -f "$ROOT/layer0-mcmc/Dockerfile" "$ROOT/layer0-mcmc"

# ─── Start network ──────────────────────────────────────────────────
info "Starting 4-node PoS prodsim network..."
docker compose -f "$COMPOSE_FILE" up -d

info "Waiting for server nodes to be healthy..."
for port in "${SERVER_PORTS[@]}"; do
  for i in $(seq 1 90); do
    if curl -sf "http://localhost:$port/" >/dev/null 2>&1; then
      log "server :${port} ready (${i}s)"
      break
    fi
    if [ "$i" -eq 90 ]; then
      docker logs --tail=20 "prodsim-svr-0" 2>/dev/null || true
      fail "server :${port} not ready after 270s"
    fi
    sleep 3
  done
done

info "Waiting for genesis block on node 0..."
for i in $(seq 1 15); do
  sleep 2
  HASH=$(docker exec prodsim-pg-0 psql -U root -d layer0 -t -A -c \
    "SELECT encode(hash, 'hex') FROM blocks WHERE blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1;" 2>/dev/null || echo "")
  if [ -n "$HASH" ]; then
    log "Genesis block: $HASH"
    break
  fi
  if [ "$i" -eq 15 ]; then
    fail "No genesis block found after 30s"
  fi
done

info "Waiting for MCMC nodes to be running..."
MCMC_NAMES=(prodsim-mcmc-0 prodsim-mcmc-1 prodsim-mcmc-2 prodsim-mcmc-3)
for name in "${MCMC_NAMES[@]}"; do
  for i in $(seq 1 15); do
    if docker ps --format '{{.Names}}' | grep -q "^${name}$"; then
      log "${name} running"
      break
    fi
    sleep 2
  done
done

# Insert genesis into TipsQueue on all 4 databases
info "Inserting genesis into TipsQueues..."
PG_NAMES=(prodsim-pg-0 prodsim-pg-1 prodsim-pg-2 prodsim-pg-3)
for pg in "${PG_NAMES[@]}"; do
  docker exec "$pg" psql -U root -d layer0 -c "
    INSERT INTO tipsqueue (hash, block, height, inserttime)
    SELECT b.hash, b.block, b.height, b.inserttime
    FROM blocks b WHERE b.blocktype = 'BLOCKTYPE_INITIAL' LIMIT 1
    ON CONFLICT (hash) DO NOTHING;" 2>/dev/null || true
done
sleep 3

# ─── Bootstrap validators ──────────────────────────────────────────
info "Bootstrapping validators (fund + stake)..."
cd "$ROOT"
mvn test-compile -q -pl layer0-mcmc

BOOTSTRAP_CLASS="net.bigtangle.mcmc.prodsim.ProdSimBootstrap"
mvn exec:java -pl layer0-mcmc -q \
  -Dexec.mainClass="$BOOTSTRAP_CLASS" \
  -Dexec.classpathScope=test \
  -DDB_HOSTNAME=localhost -DDB_PORT="$PG_PORT" \
  -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_NAME=layer0 \
  -Dserver.url="$L0_URL" -Dprodsim.portOffset="$PORT_OFFSET" 2>&1

log "Validators bootstrapped"

# ─── Simulation run ────────────────────────────────────────────────
SIM_DURATION=$((EPOCHS * 16 + 10))  # epochs x 16s + margin
info "Running simulation for $EPOCHS epochs (~${SIM_DURATION}s)..."
sleep "$SIM_DURATION"

# ─── Verification ──────────────────────────────────────────────────
info "Running verification..."
VERIFY_CLASS="net.bigtangle.mcmc.prodsim.ProdSimVerification"
mvn test -pl layer0-mcmc -q \
  -Dtest="$VERIFY_CLASS" \
  -Dserver.url="$L0_URL" \
  -Dprodsim.portOffset="$PORT_OFFSET" \
  -Dprodsim.epochs="$EPOCHS" \
  -Dsurefire.failIfNoSpecifiedTests=false

VERIFY_EXIT=$?

# ─── Attack-safety checks ─────────────────────────────────────────
info "Running attack-safety checks..."
ATTACK_CLASS="net.bigtangle.mcmc.prodsim.ProdSimAttackVerification"
mvn test -pl layer0-mcmc -q \
  -Dtest="$ATTACK_CLASS" \
  -Dserver.url="$L0_URL" \
  -Dprodsim.portOffset="$PORT_OFFSET" \
  -Dsurefire.failIfNoSpecifiedTests=false

ATTACK_EXIT=$?

EXIT_CODE=$((VERIFY_EXIT + ATTACK_EXIT))

echo ""
if [ $EXIT_CODE -eq 0 ]; then
  echo -e "${GREEN}=============================================${NC}"
  echo -e "${GREEN}  PRODSIM: SUCCESS ($EPOCHS epochs)${NC}"
  echo -e "${GREEN}  (happy-path + attack-safety checks)${NC}"
  echo -e "${GREEN}=============================================${NC}"
else
  echo -e "${RED}=============================================${NC}"
  echo -e "${RED}  PRODSIM: FAILED (verify=$VERIFY_EXIT attack=$ATTACK_EXIT)${NC}"
  echo -e "${RED}=============================================${NC}"
  for name in prodsim-svr-0 prodsim-mcmc-0; do
    echo "--- $name logs (last 20) ---"
    docker logs "$name" 2>/dev/null | tail -20 || true
  done
fi

exit $EXIT_CODE
