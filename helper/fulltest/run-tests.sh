#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/jcui/git/blockchain
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

log()   { echo -e "${GREEN}[OK]${NC} $1"; }
fail()  { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info()  { echo -e "${YELLOW}[INFO]${NC} $1"; }

# Use Java 25 if available
if [ -x /home/jcui/.local/java-25/bin/java ]; then
    export JAVA_HOME=/home/jcui/.local/java-25
    export PATH=$JAVA_HOME/bin:$PATH
fi

info "Starting L0 Docker network..."
docker compose -f "$SCRIPT_DIR/docker-compose.l0-test.yml" up -d

info "Waiting for L0 server nodes to be healthy..."
SERVER_PORTS=(8081 8082 8083)
for port in "${SERVER_PORTS[@]}"; do
  for i in $(seq 1 20); do
    if curl -sf "http://localhost:$port/" >/dev/null 2>&1; then
      log "server :${port} ready"
      break
    fi
    if [ "$i" -eq 20 ]; then
      docker logs --tail=30 "l0-svr-0" 2>/dev/null || true
      fail "server :${port} not ready after 60s"
    fi
    sleep 3
  done
done

info "Waiting for MCMC nodes to be running..."
MCMC_PORTS=(8084 8085 8086)
for port in "${MCMC_PORTS[@]}"; do
  for i in $(seq 1 10); do
    if docker ps --format '{{.Names}}' | grep -q "l0-mcmc"; then
      log "mcmc nodes running (server :${port} accessible)"
      break
    fi
    sleep 3
  done
done

info "Using Java: $(java -version 2>&1 | head -1)"
info "Running integration tests..."

cd "$ROOT"

# Install core modules locally first, then run tests
# (avoids stale .m2 cache issues with compiled MinIO references)
mvn install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am -q

# Run the standard integration test suite against the Docker network
mvn test -pl layer0-mcmc -q \
  -DDB_HOSTNAME=localhost \
  -DDB_PORT=5432 \
  -DDB_USERNAME=root \
  -DDB_PASSWORD=test1234 \
  -DDB_NAME=layer0 \
  "$@"

log "All tests passed."

info "Tearing down L0 Docker network..."
docker compose -f "$SCRIPT_DIR/docker-compose.l0-test.yml" down -v
log "Done."
