# Layer 0 Full Network Real Tests via Docker

Spin up a full Bigtangle Layer 0 blockchain network entirely in Docker for integration testing and protocol validation. This reproduces production-like topology (validator servers) on a single machine.

## Architecture

```
┌────────────────────────────────────────────────────────────┐
│  Docker Network: bigtangle-test-net                        │
│                                                            │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐              │
│  │ postgres- │  │ postgres- │  │ postgres- │              │
│  │   l0-0    │  │   l0-1    │  │   l0-2    │              │
│  └─────▲─────┘  └─────▲─────┘  └─────▲─────┘              │
│        │              │              │                     │
│  ┌─────┴─────┐  ┌─────┴─────┐  ┌─────┴─────┐              │
│  │ l0-svr-0  │  │ l0-svr-1  │  │ l0-svr-2  │  kafka (opt)│
│  │ (bootstrap)│ │           │  │           │              │
│  └───────────┘  └───────────┘  └───────────┘              │
└────────────────────────────────────────────────────────────┘
```

Each **server node** runs the `layer0-server` module (PoS: slot tick, GHOST tip
selection, validator duties, block batches). Nodes discover each other via the
`REQUESTER` bootstrap URL and gossip over Kafka (optional).

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
docker build -t ghcr.io/bigt-ai-platform/layer0-server -f helper/bigtangle/Dockerfile .

# 2. Start a 3-node L0 test network
cd /home/jcui/git/blockchain/helper/fulltest
docker compose -f docker-compose.l0-test.yml up -d

# 3. Check container status
docker compose -f docker-compose.l0-test.yml ps

# 4. Run integration tests
./run-tests.sh

# 5. Tear down
docker compose -f docker-compose.l0-test.yml down -v
```

`run-tests.sh` starts the network, waits for the three servers on 8081–8083,
installs `bigtangle-core,bigtangle-servercore,bigtangle-bridge`, then runs
`mvn test -pl layer0-server` against the Docker PostgreSQL.

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
      retries: 10
```

(one `postgres-l0-N` per node, DB `layer0`)

### Server Nodes (PoS validators)

```yaml
  l0-server-0:
    image: ghcr.io/bigt-ai-platform/layer0-server
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
      SERVICE_MICROBATCH: "true"
      SERVICE_BLOCKBATCH: "true"
      SERVICE_SYNC: "true"
      SERVICE_INITSYNC: "true"
      SYNCBLOCKS: 1000
      RUNKAFKASTREAM: "false"
      CREATETABLE: "true"
    depends_on:
      postgres-l0-0: { condition: service_healthy }

  l0-server-1:
    image: ghcr.io/bigt-ai-platform/layer0-server
    container_name: l0-svr-1
    networks: [l0-test-net]
    ports:
      - "8082:8082"
    environment:
      SERVER_PORT: 8082
      SERVER_NET: Test
      REQUESTER: http://l0-svr-0:8081
      DB_HOSTNAME: postgres-l0-1
      # ... same as server-0, CREATETABLE: "false"
    depends_on:
      postgres-l0-1: { condition: service_healthy }
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

  # Add to each server:
  #   BOOT_STRAP_SERVERS: kafka:9092
  #   TOPIC_OUT_NAME: bigtangle
```

## Full Configuration Reference

All configuration is via environment variables (defined in `layer0-server/src/main/resources/application.yml`):

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | 8081 | HTTP server port |
| `SERVER_NET` | Mainnet | Network: `Mainnet` or `Test` |
| `SERVER_MINERADDRESS` | mjWvz... | Genesis/fee reward address |
| `SSL` | false | Enable HTTPS |
| `PERMISSIONED` | false | Permissioned node mode |
| `SERVERMODE` | Fullnode | Node mode |
| `CHECKPOINT` | -1 | Checkpoint interval (-1 = disabled) |
| `SYNCBLOCKS` | 1000 | Blocks per sync batch |
| `DB_HOSTNAME` | localhost | Database host |
| `DB_PORT` | 5432 | Database port |
| `DB_NAME` | layer0 | Database name |
| `DB_USERNAME` | root | Database user |
| `DB_PASSWORD` | test1234 | Database password |
| `CREATETABLE` | true | Auto-create tables on startup |
| `SERVICE_MINING` | true | Enable mining (legacy; no-op in PoS) |
| `SERVICE_MICROBATCH` | false | Enable micro-batch service |
| `SERVICE_BLOCKBATCH` | false | Enable batch block service |
| `SERVICE_CHAINLENGTH` | false | Enable the chain-length update scheduler |
| `SERVICE_SYNC` | true | Enable block sync |
| `SERVICE_INITSYNC` | true | Initial sync on startup |
| `REQUESTER` | (empty) | Bootstrap node URL (e.g., `http://l0-svr-0:8081`) |
| `BOOT_STRAP_SERVERS` | (empty) | Kafka bootstrap servers |
| `TOPIC_OUT_NAME` | bigtangle | Kafka topic for block events |
| `MINIO_URL` | http://localhost:9000 | endpoint |
| `HIKARI_MAX_POOL` | 50 | HikariCP max pool size |
| `IPCHECK` | false | Enable IP whitelist/blacklist |
| `POS_ENABLED` | true | Enable PoS beacon chain (slot tick, validator duties) |
| `POS_SLOT_INTERVAL_MS` | 12000 | Slot duration in ms |
| `POS_SLOTS_PER_EPOCH` | 32 | Slots per epoch |
| `POS_VALIDATOR_KEY` | (empty) | Hex-encoded validator private key |
| `POS_DUTY_ENABLED` | true | This process proposes/attests (validator duties) |

## Test Scripts

### `run-tests.sh`

Starts the network and runs the standard Maven integration tests:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT=/home/jcui/git/blockchain

# Start + wait for the L0 server nodes (8081, 8082, 8083)
docker compose -f helper/fulltest/docker-compose.l0-test.yml up -d
for port in 8081 8082 8083; do
  until curl -sf "http://localhost:$port/" >/dev/null 2>&1; do
    echo "  Waiting for :$port..."
    sleep 3
  done
  echo "  :$port ready"
done

# Install core modules, then run the layer0-server integration tests
cd "$ROOT"
mvn install -DskipTests -pl bigtangle-core,bigtangle-servercore,bigtangle-bridge -am -q
mvn test -pl layer0-server -q \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432 -DDB_USERNAME=root \
  -DDB_PASSWORD=test1234 -DDB_NAME=layer0

echo "All tests passed."
```

### `benchmark.sh` — payment throughput

Starts the network and runs `PaymentBenchmark` (`bigtangle-servercore`) over HTTP:

```bash
bash helper/fulltest/benchmark.sh                    # 30 clients × 2000 payments
bash helper/fulltest/benchmark.sh --clients 50 --payments 5000
bash helper/fulltest/benchmark.sh --no-start         # reuse a running network
```

The benchmark funds each client at genesis (the `/fundAddresses` faucet was
removed — clients must be funded via a genesis distribution CSV), then submits
batched multi-recipient payments through the mempool and prints TPS, latency,
wall time and OK/fail counts. Tune with `SERVER_URL`, `CLIENTS`, `PAYMENTS`.

### Running the PoS/mempool unit suite (no Docker)

```bash
bash helper/testall.sh
```

Runs `bigtangle-core` then `bigtangle-servercore` (incl. `PosConsensusHardeningTest`,
`MempoolServiceTest`, `StoreDomainTest`, `CoreStoreSchemaTest`).

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
curl -X POST http://localhost:8081/getBalances \
  -H "Content-Type: application/json" \
  -d '["mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG"]'
```

### 4. Multi-Signature Token

Create and issue a multi-signature token requiring 2-of-3 signers.

### 5. PoS Beacon Finality

With registered validators (`stakeDeposit` ≥ 32,000,000 BIG + `activateValidator`),
beacon blocks advance the confirmed chain:

```bash
curl -X POST http://localhost:8081/getValidators -H 'Content-Type: application/json' -d '{}'
curl -X POST http://localhost:8081/getChainNumber -H 'Content-Type: application/json' -d '{}'
```

## Topology Variations

### Single-node (minimum)

For basic testing, run one server with a shared PostgreSQL:

```bash
docker compose -f docker-compose.l0-single.yml up -d   # 1 postgres + 1 server
```

### Multi-region (cross-machine)

For geographic distribution tests, use separate compose files per host and point `REQUESTER` at the bootstrap node's public IP.

### With Kafka

Add Kafka to test block propagation via event streaming. Set `BOOT_STRAP_SERVERS` and `TOPIC_OUT_NAME` on each node.

## Health Checks

```bash
# Spring Boot actuator
for port in 8081 8082 8083; do
  echo "Node :$port: $(curl -sf http://localhost:$port/actuator/health | jq .status)"
done

# Docker
docker ps --filter "network=l0-test-net" --format "table {{.Names}}\t{{.Status}}"

# Logs
docker logs l0-svr-0 | tail -20
```

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Node won't start, DB connection error | PostgreSQL not ready | Increase `depends_on` healthcheck retries |
| `REQUESTER` connection refused | Bootstrap node not ready | Start bootstrap first, then peers |
| Blocks not propagating | Kafka not connected | Check `BOOT_STRAP_SERVERS` config |
| PoS slot tick not firing | Validators not registered | Register validators via `stakeDeposit` + `activateValidator` |
| Validator never selected as proposer | Insufficient stake or wrong `POS_VALIDATOR_KEY` | Ensure ≥32 BIG stake and correct key in `ValidatorDutyService` |
| Table creation errors on a follower node | `CREATETABLE=false` but tables don't exist | Let the first server create tables, or set `CREATETABLE=true` |
| High memory usage | Java heap insufficient | Reduce `HIKARI_MAX_POOL` or add `-Xmx` JVM args |

## Files In This Directory

| File | Purpose |
|------|---------|
| `readme.md` | This document |
| `docker-compose.l0-test.yml` | Full 3-node L0 test network |
| `docker-compose.l0-single.yml` | Single-node L0 test |
| `docker-compose.l0-kafka.yml` | Kafka integration variant |
| `run-tests.sh` | Automated test runner |
| `benchmark.sh` | Payment throughput benchmark (clients funded via genesis CSV) |

## References

- [blockchain.md](../../blockchain.md) — PoS consensus architecture (GHOST + Casper FFG), comparison vs Solana, Ethereum PoS, Visa
- `layer0-server/src/main/resources/application.yml` — all config options
- `bigtangle-servercore/src/test/java/net/bigtangle/server/service/PosConsensusHardeningTest.java` — PoS consensus/hardening suite
- `/home/jcui/git/blockchain/helper/testall.sh` — PoS-era test runner (core + servercore, no Docker)
- `/home/jcui/git/blockchain/helper/docker-compose-base.yml` — base compose (PG)