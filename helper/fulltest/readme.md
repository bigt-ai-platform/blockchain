# Layer 0 Full Network Real Tests via Docker

Spin up a full Bigtangle Layer 0 blockchain network entirely in Docker for integration testing, performance benchmarking, and protocol validation. This reproduces production-like topology (server + MCMC nodes ) on a single machine.

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│  Docker Network: bigtangle-test-net                                │
│                                                                    │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────────────┐    │
│  │ postgres-│  │ postgres-│  │ postgres-│                                │
│  │   l0-0   │  │   l0-1   │  │   l0-2   │  │      │    │
│  └────▲─────┘  └────▲─────┘  └────▲─────┘  └────────▲─────────┘    │
│       │              │              │               │              │
│  ┌────┴─────┐  ┌────┴─────┐  ┌────┴─────┐         │              │
│  │  l0-svr-0 │  │  l0-svr-1 │  │  l0-svr-2 │        │              │
│  │ (bootstrap)│  │           │  │           │        │              │
│  └────▲─────┘  └────▲─────┘  └────▲─────┘         │              │
│       │              │              │               │              │
│  ┌────┴─────┐  ┌────┴─────┐  ┌────┴─────┐         │              │
│  │ l0-mcmc-0│  │ l0-mcmc-1│  │ l0-mcmc-2│         │              │
│  │ (miner)  │  │ (miner)  │  │ (miner)  │         │              │
│  └──────────┘  └──────────┘  └──────────┘         │              │
│                                                    │              │
│  ┌─────────────────────────────────────────────────┴──────────┐  │
│  │                    kafka (optional)                         │  │
│  └─────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

Each **server node** runs `layer0-server` (Spring Boot, REST API on `SERVER_PORT`).

Each **MCMC node** runs `layer0-mcmc` (tip selection + mining, REST API on a different port).

Nodes discover each other via the `REQUESTER` bootstrap URL and gossip over Kafka (optional).

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 24+ | Container runtime |
| Docker Compose | 2.20+ | Orchestration |
| Java | 25 (Temurin) | Build only (or use pre-built image) |
| Maven | 3.9+ | Build only |
| Git | 2.30+ | Clone repos |

## Quick Start

```bash
# 1. Build the Docker image
cd /home/jcui/git/blockchain
docker build -t bigtangle:test -f helper/bigtangle/Dockerfile .

# 2. Start a 3-node L0 test network
cd /home/jcui/git/blockchain/helper/fulltest
docker compose -f docker-compose.l0-test.yml up -d

# 3. Check container status
docker compose -f docker-compose.l0-test.yml ps

# 4. Run integration tests
./run-tests.sh

# 5. Run benchmarks
./benchmark.sh -b all

# 6. Tear down
docker compose -f docker-compose.l0-test.yml down -v
```

## Docker Compose Configuration

### Base network & services

```yaml
# docker-compose.l0-test.yml
networks:
  l0-test-net:
    driver: bridge

services:

  postgres-l0-0:
    image: postgres:16
    container_name: l0-pg-0
    networks: [l0-test-net]
    environment:
      POSTGRES_USER: root
      POSTGRES_PASSWORD: test1234
      POSTGRES_DB: layer0
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U root -d layer0"]
      interval: 5s
      timeout: 3s
      retries: 5

  postgres-l0-1:
    image: postgres:16
    container_name: l0-pg-1
    networks: [l0-test-net]
    environment:
      POSTGRES_USER: root
      POSTGRES_PASSWORD: test1234
      POSTGRES_DB: layer0
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U root -d layer0"]
      interval: 5s
      timeout: 3s
      retries: 5

  postgres-l0-2:
    image: postgres:16
    container_name: l0-pg-2
    networks: [l0-test-net]
    environment:
      POSTGRES_USER: root
      POSTGRES_PASSWORD: test1234
      POSTGRES_DB: layer0
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U root -d layer0"]
      interval: 5s
      timeout: 3s
      retries: 5
```

### Server Nodes

```yaml
  l0-server-0:
    image: bigtangle:test
    container_name: l0-svr-0
    networks: [l0-test-net]
    ports:
      - "8081:8081"
    environment:
      SERVER_PORT: 8081
      SERVER_NET: Test
      SERVER_MINERADDRESS: mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG
      DB_HOSTNAME: postgres-l0-0
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "false"
      SERVICE_MCMC: "true"
      SERVICE_MCMC_RATE: 1000
      SERVICE_SYNC: "true"
      SERVICE_INITSYNC: "true"
      CREATETABLE: "true"
    depends_on:
      postgres-l0-0: { condition: service_healthy }

  l0-server-1:
    image: bigtangle:test
    container_name: l0-svr-1
    networks: [l0-test-net]
    ports:
      - "8082:8082"
    environment:
      SERVER_PORT: 8082
      SERVER_NET: Test
      REQUESTER: http://l0-svr-0:8081
      DB_HOSTNAME: postgres-l0-1
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "false"
      SERVICE_MCMC: "true"
      SERVICE_SYNC: "true"
      SERVICE_INITSYNC: "true"
      CREATETABLE: "true"
    depends_on:
      postgres-l0-1: { condition: service_healthy }

  l0-server-2:
    image: bigtangle:test
    container_name: l0-svr-2
    networks: [l0-test-net]
    ports:
      - "8083:8083"
    environment:
      SERVER_PORT: 8083
      SERVER_NET: Test
      REQUESTER: http://l0-svr-0:8081
      DB_HOSTNAME: postgres-l0-2
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "false"
      SERVICE_MCMC: "true"
      SERVICE_SYNC: "true"
      SERVICE_INITSYNC: "true"
      CREATETABLE: "true"
    depends_on:
      postgres-l0-2: { condition: service_healthy }
```

### MCMC (Miner) Nodes

```yaml
  l0-mcmc-0:
    image: bigtangle:test
    container_name: l0-mcmc-0
    networks: [l0-test-net]
    ports:
      - "8084:8084"
    environment:
      SERVER_PORT: 8084
      SERVER_NET: Test
      REQUESTER: http://l0-svr-0:8081
      DB_HOSTNAME: postgres-l0-0       # shares DB with server-0
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "true"
      SERVICE_MINING_RATE: 50000
      SERVICE_MCMC: "true"
      SERVICE_INITSYNC: "true"
      SERVICE_SYNC: "true"
      CREATETABLE: "false"             # tables created by server node
    depends_on:
      - l0-server-0

  l0-mcmc-1:
    image: bigtangle:test
    container_name: l0-mcmc-1
    networks: [l0-test-net]
    ports:
      - "8085:8085"
    environment:
      SERVER_PORT: 8085
      SERVER_NET: Test
      REQUESTER: http://l0-svr-1:8082
      DB_HOSTNAME: postgres-l0-1
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "true"
      SERVICE_MINING_RATE: 50000
      SERVICE_MCMC: "true"
      SERVICE_INITSYNC: "true"
      SERVICE_SYNC: "true"
      CREATETABLE: "false"
    depends_on:
      - l0-server-1

  l0-mcmc-2:
    image: bigtangle:test
    container_name: l0-mcmc-2
    networks: [l0-test-net]
    ports:
      - "8086:8086"
    environment:
      SERVER_PORT: 8086
      SERVER_NET: Test
      REQUESTER: http://l0-svr-2:8083
      DB_HOSTNAME: postgres-l0-2
      DB_PORT: 5432
      DB_NAME: layer0
      DB_USERNAME: root
      DB_PASSWORD: test1234
      DBTYPE: postgresql
      SERVICE_MINING: "true"
      SERVICE_MINING_RATE: 50000
      SERVICE_MCMC: "true"
      SERVICE_INITSYNC: "true"
      SERVICE_SYNC: "true"
      CREATETABLE: "false"
    depends_on:
      - l0-server-2
```

### Kafka (Optional — for cross-node gossip)

```yaml
  kafka:
    image: bitnami/kafka:3.7
    container_name: l0-kafka
    networks: [l0-test-net]
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      ALLOW_PLAINTEXT_LISTENER: "yes"

  # Add to each server/mcmc:
  #   BOOT_STRAP_SERVERS: kafka:9092
  #   TOPIC_OUT_NAME: bigtangle
```

## Full Configuration Reference

All configuration is via environment variables (defined in `layer0-server/src/main/resources/application.yml`):

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8081 | HTTP server port |
| `SERVER_NET` | Mainnet | Network: `Mainnet` or `Test` |
| `SERVER_MINERADDRESS` | mjWvz... | Mining reward address |
| `SSL` | false | Enable HTTPS |
| `PERMISSIONED` | false | Permissioned node mode |
| `SERVERMODE` | Fullnode | Node mode |
| `CHECKPOINT` | -1 | Checkpoint interval (-1 = disabled) |
| `SYNCBLOCKS` | 1000 | Blocks per sync batch |
| `DBTYPE` | postgresql | Database type (`postgresql` or `mysql`) |
| `DB_HOSTNAME` | localhost | Database host |
| `DB_PORT` | 5432 | Database port |
| `DB_NAME` | layer0 | Database name |
| `DB_USERNAME` | root | Database user |
| `DB_PASSWORD` | test1234 | Database password |
| `CREATETABLE` | true | Auto-create tables on startup |
| `SERVICE_MINING` | true | Enable mining |
| `SERVICE_MINING_RATE` | 50000 | Mining interval (ms) |
| `SERVICE_MCMC` | false | Enable MCMC (tip selection) |
| `SERVICE_MCMC_RATE` | 500 | MCMC interval (ms) |
| `SERVICE_SYNC` | true | Enable block sync |
| `SERVICE_INITSYNC` | true | Initial sync on startup |
| `REQUESTER` | (empty) | Bootstrap node URL (e.g., `http://l0-svr-0:8081`) |
| `BOOT_STRAP_SERVERS` | (empty) | Kafka bootstrap servers |
| `TOPIC_OUT_NAME` | bigtangle | Kafka topic for block events |
| `MINIO_URL` | http://localhost:9000 |  endpoint |
| `MINIO_ACCESS_KEY` | admin |  access key |
| `MINIO_SECRET_KEY` | adminpassword |  secret key |
| `HIKARI_MAX_POOL` | 50 | HikariCP max pool size |
| `IPCHECK` | false | Enable IP whitelist/blacklist |
| `POS_ENABLED` | false | Enable PoS beacon chain (slot tick, validator duties) |
| `POS_SLOT_INTERVAL_MS` | 12000 | Slot duration in ms |
| `POS_SLOTS_PER_EPOCH` | 32 | Slots per epoch |
| `POS_VALIDATOR_KEY` | (empty) | Hex-encoded validator private key |

## Test Scripts

### `run-tests.sh`

Starts the network and runs the standard Maven integration tests:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/jcui/git/blockchain

# Wait for all nodes to be ready
echo "Waiting for L0 nodes..."
for port in 8081 8082 8083 8084 8085 8086; do
  until curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; do
    echo "  Waiting for :$port..."
    sleep 3
  done
  echo "  :$port ready"
done

# Run the existing integration test suite
cd "$ROOT"
mvn test -pl layer0-mcmc -q \
  -Dtest..reset=true \
  -DDB_HOSTNAME=localhost \
  -DDB_PORT=5432 \
  -DDB_USERNAME=root \
  -DDB_PASSWORD=test1234 \
  -DDB_NAME=layer0

echo "All tests passed."
```

### Running specific test classes

```bash
# Payment flow
mvn test -pl layer0-mcmc -Dtest=PaymentServiceTest

# Token creation
mvn test -pl layer0-mcmc -Dtest=TokenTest

# Multi-signature token
mvn test -pl layer0-mcmc -Dtest=FromAddressTests

# Cross-chain anchors (L0 → L1)
mvn test -pl layer0-mcmc -Dtest=AnchorRoundTripTest

# Full DAG validation
mvn test -pl layer0-mcmc -Dtest=FullPrunedBlockGraphTest

# Gossip / block propagation
mvn test -pl layer0-mcmc -Dtest=GossipServiceTest

# PoS consensus (15 unit tests)
mvn test -pl layer0-mcmc -Dtest=PoSTest
```

## Test Scenarios

### 1. Network Bootstrap & Discovery

Verifies that nodes discover each other and sync blocks:

```bash
# Check peer info on bootstrap node
curl http://localhost:8081/getPeers

# Check blockchain height on each node
for port in 8081 8082 8083; do
  echo "Node :$port height:"
  curl -s http://localhost:$port/getBlockCount
done
```

### 2. Token Creation

Create a test token on the network and verify it propagates:

```bash
# Create token (uses test private key)
curl -X POST http://localhost:8081/createToken \
  -H "Content-Type: application/json" \
  -d '{
    "name": "TestToken",
    "symbol": "TST",
    "amount": 1000000,
    "decimals": 0
  }'

# Verify token exists on all nodes
for port in 8081 8082 8083; do
  echo "Node :$port:"
  curl -s http://localhost:$port/getTokens | grep TestToken
done
```

### 3. Payment / Transfer

Send BIG tokens between addresses:

```bash
# Check balance
curl -X POST http://localhost:8081/getBalances \
  -H "Content-Type: application/json" \
  -d '["mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG"]'

# Submit transaction (from test key)
curl -X POST http://localhost:8081/sendTransaction \
  -H "Content-Type: application/json" \
  -d '{...}'
```

### 4. Multi-Signature Token

Create and issue a multi-signature token requiring 2-of-3 signers.

### 5. Cross-Chain Anchoring (L0 ↔ L1)

Submit an anchor from L0 to the L1 chain (see `AnchorRoundTripTest`):

```bash
curl -X POST http://localhost:8081/submitAnchor \
  -H "Content-Type: application/json" \
  -d '{"targetLayer": "L1", "blockHash": "0x..."}'
```

### 6. Performance Benchmarks

Three throughput benchmarks measure Layer 0 TPS (see [PERFORMANCE.md](../../PERFORMANCE.md) for full results):

```bash
# Non-PoS max throughput (zero-HTTP, direct mempool) — 3,769 tx/s
mvn test -pl layer0-mcmc -Dtest=MaxTPSBenchmark#testMaxTPS \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432 -DDB_NAME=layer0

# Non-PoS max throughput (HTTP batch submit) — 4,465 tx/s
mvn test -pl layer0-mcmc -Dtest=MaxTpsBenchmark#testMempoolTps \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432 -DDB_NAME=layer0

# Full PoS throughput (32 validators, slot tick, attestations) — 4,873 tx/s
mvn test -pl layer0-mcmc -Dtest=PosThroughputBenchmark#testPosThroughput \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432 -DDB_NAME=layer0
```

Or run all with the benchmark script:
```bash
./benchmark.sh -b all
```

#### Cumulative Optimizations (3,018 → 4,873 tx/s)

| Optimization | Batch wall Δ | TPS Δ |
|-------------|-------------|-------|
| `BATCH_TX_PER_BLOCK` 5000→50000 (single-block fast path) | −38% | +39% |
| Skip cache eviction in batch mode | −3% | +3% |
| `reWriteBatchedInserts=true` (PG JDBC multi-row rewrite) | −36% | +21% |
| Skip gzip for batch blocks | −7% | +5% |
| PG COPY for UTXO bulk load (replaces batch INSERT) | **−48% total** | **+62% total** |

#### Scale Projection (see [PERFORMANCE.md](../../PERFORMANCE.md) for details)

| Hardware | TPS | Limit |
|----------|-----|-------|
| 4C i5 + SATA PG (current) | **4,873** | CPU + PG I/O |
| 128C EPYC + NVMe PG | **~31,000** | Single-thread block creation |
| + pipelining | **~40,000** | Pipeline latency |
| Architectural ceiling | **~80,000** | Block creation + MCMC consensus |

## Topology Variations

### Single-node (minimum)

For basic testing, run one server + one MCMC with shared PostgreSQL:

```bash
docker compose -f docker-compose.l0-single.yml up -d
# 1 postgres + 1 server + 1 mcmc node
```

### Multi-region (cross-machine)

For geographic distribution tests, use separate compose files per host and point `REQUESTER` at the bootstrap node's public IP.

### With Kafka

Add Kafka to test block propagation via event streaming. Set `BOOT_STRAP_SERVERS` and `TOPIC_OUT_NAME` on each node.

### L0 + L1-pai combined

For full stack testing, add L1-pai nodes:

```yaml
# See l1-pai-server/ and l1-pai-mcmc/ for equivalent config
```

## Health Checks

```bash
# Spring Boot actuator
for port in 8081 8082 8083 8084 8085 8086; do
  echo "Node :$port: $(curl -sf http://localhost:$port/actuator/health | jq .status)"
done

# Docker
docker ps --filter "network=l0-test-net" --format "table {{.Names}}\t{{.Status}}"

# Logs
docker logs l0-svr-0 | tail -20
docker logs l0-mcmc-0 | tail -20
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Node won't start, DB connection error | PostgreSQL not ready | Increase `depends_on` healthcheck retries |
| `REQUESTER` connection refused | Bootstrap node not ready | Start bootstrap first, then peers |
| Blocks not propagating | Kafka not connected | Check `BOOT_STRAP_SERVERS` config |
| Mining not producing blocks | `SERVICE_MINING=false` or no miner address | Set `SERVICE_MINING=true` and valid `SERVER_MINERADDRESS` |
| MCMC service not running | `SERVICE_MCMC=false` or rate too low | Set `SERVICE_MCMC=true` and `SERVICE_MCMC_RATE=500` or lower |
| Table creation errors on MCMC node | `CREATETABLE=false` but tables don't exist | Let server node create tables first, or set `CREATETABLE=true` |
| High memory usage | Java heap insufficient | Reduce `HIKARI_MAX_POOL` or add `-Xmx` JVM args |
| PoS slot tick not firing | `POS_ENABLED` not set or validators not registered | Set `POS_ENABLED=true` and register validators |
| Validator never selected as proposer | Insufficient stake or wrong `POS_VALIDATOR_KEY` | Ensure ≥32 BIG stake and correct key in `ValidatorDutyService` |

## Files In This Directory

| File | Purpose |
|------|---------|
| `readme.md` | This document |
| `docker-compose.l0-test.yml` | Full 3-node L0 test network |
| `docker-compose.l0-single.yml` | Single-node L0 test |
| `docker-compose.l0-kafka.yml` | Kafka integration variant |
| `run-tests.sh` | Automated test runner |
| `benchmark.sh` | TPS benchmark runner (payment, max-tps, PoS throughput) |

## References

- [DESIGN.md](../../DESIGN.md) — two-layer consensus architecture (MCMC + PoS beacon chain)
- [PERFORMANCE.md](../../PERFORMANCE.md) — benchmark results, optimizations, scale projections
- [COMPARE.md](../../COMPARE.md) — comparison vs Solana, Ethereum PoS, Visa
- `layer0-server/src/main/resources/application.yml` — all config options
- `layer0-mcmc/src/test/java/net/bigtangle/mcmc/test/` — integration tests
- `/home/jcui/git/blockchain/testall.sh` — existing test runner (local PG, no Docker)
- `/home/jcui/git/blockchain/helper/docker-compose-base.yml` — base compose (PG + )
