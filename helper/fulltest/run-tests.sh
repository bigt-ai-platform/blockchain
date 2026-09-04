#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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
# Kafka streams for the test net (KAFKA_STREAMS=0 keeps the old streams-off
# behavior). The broker is provisioned like testnodes.sh does — local docker,
# hermetic topics — and attached to the compose network so the servers reach
# it as <container>:9094 (host tools use localhost:<port>).
KAFKA_STREAMS="${KAFKA_STREAMS:-1}"
if [ "$KAFKA_STREAMS" = "1" ]; then
    export RUNKAFKASTREAM=true
    export KAFKA_CONTAINER="${KAFKA_CONTAINER:-l0-test-kafka}"
    export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-9192}"
    export BOOT_STRAP_SERVERS="${KAFKA_CONTAINER}:9094"
fi
docker compose -f "$SCRIPT_DIR/docker-compose.l0-test.yml" up -d

if [ "$KAFKA_STREAMS" = "1" ]; then
    info "Provisioning local Kafka for the test net..."
    # shellcheck disable=SC1091
    source "${ROOT}/helper/kafka-local.sh"
    export KAFKA_NETWORK
    KAFKA_NETWORK="$(docker network ls --format '{{.Name}}' | grep -E '(^|_)l0-test-net$' | head -1)"
    [ -n "$KAFKA_NETWORK" ] || { echo "l0-test-net not found" >&2; exit 1; }
    export KAFKA_CHAINS="${KAFKA_CHAINS:-L0}" KAFKA_FRESH_TOPICS=1
    kafka_local_ensure || fail "local kafka broker failed"
    kafka_local_topics || fail "local kafka topics failed"
    log "test net streams via ${BOOT_STRAP_SERVERS}"
fi

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

info "Using Java: $(java -version 2>&1 | head -1)"
info "Running integration tests..."

cd "$ROOT"

# Install core modules locally first, then run tests
# (avoids stale .m2 cache issues with compiled MinIO references)
mvn install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am -q

# Run the standard integration test suite against the Docker network
mvn test -pl layer0-server -q \
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
