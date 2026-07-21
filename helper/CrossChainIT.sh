#!/bin/bash
set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
L0_PORT=8089
L1_PORT=8090

echo "============================================="
echo "  Cross-Chain Integration Test"
echo "============================================="

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up..."
    kill $(jobs -p) 2>/dev/null || true
    wait 2>/dev/null || true
    echo "Cleanup done."
}
trap cleanup EXIT INT TERM

# ---- Step 1: Setup databases ----
echo ""
echo "=== Step 1: Setup databases ==="

# Create fresh databases via Docker
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
    -c "DROP DATABASE IF EXISTS info;" 2>/dev/null || true
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
    -c "CREATE DATABASE info;"

docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
    -c "DROP DATABASE IF EXISTS layer1;" 2>/dev/null || true
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
    -c "CREATE DATABASE layer1;"

echo "Databases ready: info (L0), layer1 (L1)"

# ---- Step 2: Start L0 servers ----
echo ""
echo "=== Step 2: Start L0 HTTP server (port $L0_PORT) + MCMC ==="
DB_ARGS="-DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=5432"
L0_ARGS="--server.net=Test --server.port=$L0_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"

# L0 HTTP server (table creation + REST API)
nohup mvn spring-boot:run -pl layer0-server \
    -Dspring-boot.run.jvmArguments="$DB_ARGS -DDB_NAME=info -Dbridge.active=true -Danchor.active=true" \
    -Dspring-boot.run.arguments="$L0_ARGS" \
    > /tmp/l0-server.log 2>&1 &
L0_PID=$!
echo "L0 HTTP PID: $L0_PID"

# Wait for L0 HTTP to be ready
echo "Waiting for L0 HTTP..."
for i in $(seq 1 20); do
    sleep 2
    if curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$L0_PORT/" 2>/dev/null; then
        echo "L0 HTTP ready after ${i}s"
        break
    fi
    if [ $i -eq 20 ]; then
        echo "L0 HTTP failed to start"
        tail -30 /tmp/l0-server.log
        exit 1
    fi
done

# L0 MCMC process (tip selection, populates tipsqueue)
nohup mvn spring-boot:run -pl layer0-mcmc \
    -Dspring-boot.run.jvmArguments="$DB_ARGS -DDB_NAME=info -Dserver.port=8091" \
    -Dspring-boot.run.arguments="--server.net=Test --server.port=8091 --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4" \
    > /tmp/l0-mcmc.log 2>&1 &
L0_MCMC_PID=$!
echo "L0 MCMC PID: $L0_MCMC_PID"

# Wait for tip to appear
echo "Waiting for L0 tip..."
for i in $(seq 1 30); do
    sleep 2
    if curl -s "http://127.0.0.1:$L0_PORT/getTip" > /dev/null 2>&1; then
        echo "L0 tip ready after ${i}s"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "L0 tip not ready"
        tail -30 /tmp/l0-mcmc.log
        exit 1
    fi
done

# ---- Step 3: Start L1 server ----
echo ""
echo "=== Step 3: Start L1 server (port $L1_PORT) ==="

nohup mvn spring-boot:run -pl l1-order-mcmc \
    -Dspring-boot.run.jvmArguments="$DB_ARGS -DDB_NAME=layer1" \
    -Dspring-boot.run.arguments="--server.net=Test --server.port=$L1_PORT --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4" \
    > /tmp/l1-server.log 2>&1 &
L1_PID=$!
echo "L1 PID: $L1_PID"

# Wait for L1 to be ready
echo "Waiting for L1..."
for i in $(seq 1 30); do
    sleep 2
    if curl -s "http://127.0.0.1:$L1_PORT/getTip" > /dev/null 2>&1; then
        echo "L1 ready after ${i}s"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "L1 failed to start"
        tail -30 /tmp/l1-server.log
        exit 1
    fi
done

# ---- Step 4: Run the cross-chain test ----
echo ""
echo "=== Step 4: Run CrossChainIT ==="
mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test \
    -Dexec.mainClass=net.bigtangle.mcmc.test.CrossChainIT \
    -Dexec.args="http://127.0.0.1:$L0_PORT/ http://127.0.0.1:$L1_PORT/" \
    -DDB_HOSTNAME=127.0.0.1 -DDB_USERNAME=root -DDB_PASSWORD=test1234 -DDB_PORT=5432 -DDB_NAME=info \
    2>&1

EXIT_CODE=$?

# ---- Step 5: Report ----
echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo "============================================="
    echo "  CROSS-CHAIN TEST: SUCCESS"
    echo "============================================="
else
    echo "============================================="
    echo "  CROSS-CHAIN TEST: FAILED (exit $EXIT_CODE)"
    echo "============================================="
    tail -50 /tmp/l0-server.log
fi

exit $EXIT_CODE
