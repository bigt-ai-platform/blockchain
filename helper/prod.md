Copyright 2018 Inasset GmbH.

# Layer0 Production Deployment (Proof-of-Stake)

## Architecture

```
PostgreSQL ← layer0-server (API / P2P sync) ← requester (peer node)
                   ↕ (same DB)
              layer0-mcmc (PoS consensus / MCMC / reward)
```

Two processes share the same database: `layer0-server` handles API requests and
block sync; `layer0-mcmc` runs the MCMC scheduler, the proof-of-stake slot
ticker, reward distribution and epoch finality. Consensus is **proof-of-stake**
— there is no proof-of-work mining.

## Consensus: Proof-of-Stake

- Time is divided into **slots** (default 12 s, `POS_SLOT_INTERVAL_MS`) and
  **epochs** (default 32 slots ≈ 6.4 min, `POS_SLOTS_PER_EPOCH`).
- At each slot, `SlotService.selectProposer(slot)` deterministically picks a
  validator from the **active** stake set:
  `randaoMix ^ slot  mod  |active validators|`.
- Only the node whose configured `POS_VALIDATOR_KEY` pubkey matches the selected
  proposer creates the `BLOCKTYPE_BEACON` block; the other nodes attest it. This
  keeps the beacon/reward chain single-headed even with many validators.
- A beacon block carries a `RewardInfo` (chainlength, prevRewardHash and the
  referenced DAG blocks). Confirming it on the reward chain confirms the
  referenced DAG UTXOs.
- At epoch boundaries: Casper `finalizeCheckpoint`, epoch-reward distribution
  from the per-chain fee pool, and stake withdrawals (`processWithdrawal`).

## Node topology (many nodes)

Each validator node is one independent triple: **1 PostgreSQL + 1 layer0-server
+ 1 layer0-mcmc**, each with its own ports and its own database. Nodes stay in
sync through three channels:

| Channel | Config | Purpose |
|---------|--------|---------|
| DAG sync | `REQUESTER` + seed DNS (`dnsSeeds` in params) | pull blocks from a peer / the network |
| P2P gossip | `peer.udpPort`, `peer.tcpPort`, `gossip.port` | block/broadcast gossip |
| PoS attestation mesh | `POS_GOSSIP_PEERS` (`host:port,host:port,…`) | broadcast attestations, slashing proofs, beacon hashes between validators |

Every node sees the same validator set because `stakeDeposit`/`activateValidator`
write STAKE blocks into the shared DAG.

## Single-Instance Guarantee via DB Lock

Each critical service acquires a row-level lock in the `lockobject` table before
doing work. This prevents duplicate processing even if multiple JVM processes
share the same database.

| Service | Lock ID |
|---------|---------|
| `MCMCService` | `net.bigtangle.mcmc.service.MCMCService` |
| `RewardService` | `net.bigtangle.mcmc.service.RewardService` |
| `SyncBlockService` | `net.bigtangle.server.service.SyncBlockService` |
| `BlockStoreService` | `net.bigtangle.store.BlockStoreService` |
| `AVGPriceService` | `net.bigtangle.l1.order.service.AVGPriceService` |

The protocol: `selectLockobject(LOCKID)` → if free, `insertLockobject` and
proceed; if stale (locktime expired), delete and re-acquire; if active, skip;
release with `deleteLockobject` after the work. You can safely run several JVMs
against one DB, but it is still recommended to run exactly one `layer0-server`
per database to avoid resource contention.

## Prerequisites

- Java 25, Maven 3.6+
- PostgreSQL 16
- Docker (optional, for containerized deployment)
- One PQ validator key per validator (see §3)

## 1. Database Setup

One database **per node**:

```sql
CREATE DATABASE layer0;
CREATE USER root WITH PASSWORD 'test1234';
GRANT ALL PRIVILEGES ON DATABASE layer0 TO root;
```

Or via Docker:

```bash
docker run -d --name l0-pg \
  -e POSTGRES_USER=root -e POSTGRES_PASSWORD=test1234 -e POSTGRES_DB=layer0 \
  -p 5432:5432 -v /data/l0-pg:/var/lib/postgresql/data postgres:16
```

## 2. Build

```bash
mvn package -DskipTests
```

Produces (version 0.6.0):
- `layer0-server/target/layer0-server-0.6.0-exec.jar`
- `layer0-mcmc/target/layer0-mcmc-0.6.0-exec.jar`

(Or build the Docker images with `helper/deploy.sh`.)

## 3. Generate validator keys

Each validator needs a PQ seed (ML-DSA-87). The seed is the private key
(`POS_VALIDATOR_KEY`); its pubkey (`VALIDATOR_PUBKEY`) is registered on-chain.

- ML-DSA-only seed: **32 bytes = 64 hex chars**.
- Dual seed (ML-DSA + SLH-DSA, for the post-activation phase): **64 bytes = 128 hex chars**.

```
POS_VALIDATOR_KEY=<64 or 128 hex>
VALIDATOR_PUBKEY=<hex of the prefixed key bundle>
```

Keep `POS_VALIDATOR_KEY` secret — put it in a gitignored `validator.env`:

```bash
POS_VALIDATOR_KEY=...
VALIDATOR_PUBKEY=...
```

## 4. Start the first node

### layer0-server (API + P2P sync) — creates the schema on first start

```bash
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar layer0-server/target/layer0-server-0.6.0-exec.jar \
  --server.port=8081 --server.net=Mainnet --server.chain=L0 \
  --db.hostname=localhost --db.port=5432 --db.dbName=layer0 \
  --db.username=root --db.password=test1234 --db.dbtype=postgresql \
  --server.createtable=true \
  --service.schedule.mcmc=true --service.schedule.blockbatch=true \
  --service.schedule.microbatch=true --service.schedule.initsync=true \
  --server.runKafkaStream=false \
  --peer.udpPort=30307 --peer.tcpPort=30308 --gossip.port=9095
```

(`--server.chain=L0` runs the shared Layer-0 chain; leave it unset for the
network default. `SERVER_NET=Mainnet` selects the mainnet `NetworkParameters`,
whose genesis is fixed at chain launch.)

### layer0-mcmc (PoS consensus) — no schema creation

```bash
java -Xmx2048m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar layer0-mcmc/target/layer0-mcmc-0.6.0-exec.jar \
  --server.port=8091 --server.net=Mainnet \
  --db.hostname=localhost --db.port=5432 --db.dbName=layer0 \
  --db.username=root --db.password=test1234 --db.dbtype=postgresql \
  --server.requester=http://127.0.0.1:8081 \
  --server.createtable=false \
  --server.runKafkaStream=false \
  --service.schedule.mcmc=true --service.schedule.blockbatch=true \
  --service.schedule.microbatch=true \
  --pos.validatorKey=<POS_VALIDATOR_KEY_0> \
  --peer.udpPort=30309 --peer.tcpPort=30310 --gossip.port=9097
```

## 5. Fund, stake and activate validators

For **every** validator `i` (submit to any reachable node; the STAKE blocks
propagate through the DAG):

```bash
# 1) Fund the validator's address (requires FUND_ENABLED=true, see below)
curl -X POST http://127.0.0.1:8081/fundAddresses -H 'Content-Type: application/json' \
  -d '{"addresses":[{"address":"validator","value":1000000000000,"pubkey":"<VALIDATOR_PUBKEY_i>"}]}'

# 2) Stake (amount must be >= 32,000,000 BIG); privateKey signs the STAKE block
curl -X POST http://127.0.0.1:8081/stakeDeposit -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","amount":"32000000","privateKey":"<POS_VALIDATOR_KEY_i>"}'

# 3) Activate (join the proposer set at the given epoch)
curl -X POST http://127.0.0.1:8081/activateValidator -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","epoch":0}'
```

After activation the node proposes when selected (`getValidators` shows the
active set). Other PoS endpoints: `processWithdrawal`, `submitSlashingProof`,
`getBaseFee`, `setValidatorKey`, `getValidatorKey`.

## 6. Add more nodes (N validators)

Repeat §4 for nodes 1..N-1 with, per node:

- its own database (different `DB_NAME` or PostgreSQL instance),
- unique ports (`server.port`, `peer.udpPort`, `peer.tcpPort`, `gossip.port`),
- its own `--pos.validatorKey`,
- `--server.requester` pointed at node 0 (or a seed node),
- `--pos.gossipPeers="<node0 host:port>,<node1 host:port>,…"` on every node so
  attestations/slashing proofs reach the validator set.

Example for node 1:

```bash
java ... -jar layer0-server-0.6.0-exec.jar \
  --server.port=8091 ... --db.dbName=layer0_1 \
  --server.requester=http://<node0>:8081 --server.createtable=true \
  --peer.udpPort=30313 --peer.tcpPort=30314 --gossip.port=9101 \
  --pos.gossipPeers="<node0 host>:8081,<node1 host>:8091"

java ... -jar layer0-mcmc-0.6.0-exec.jar \
  --server.port=8101 ... --db.dbName=layer0_1 \
  --server.requester=http://<node0>:8081 \
  --pos.validatorKey=<POS_VALIDATOR_KEY_1> \
  --pos.gossipPeers="<node0 host>:8081,<node1 host>:8091" \
  --peer.udpPort=30315 --peer.tcpPort=30316 --gossip.port=9103
```

The network converges: every node syncs the same DAG, sees the same activated
validator set, and only the slot-selected proposer mints each beacon block.

## 7. Docker deployment

Build images (`helper/deploy.sh`) then, per node, run a postgres + server +
mcmc container on the same network. Example for node 0:

```bash
docker network create cc-bridged-network

docker run -d --net=cc-bridged-network --name l0-pg \
  -e POSTGRES_USER=root -e POSTGRES_PASSWORD=test1234 -e POSTGRES_DB=layer0 postgres:16

docker run -d --net=cc-bridged-network --name l0-svr -p 8081:8081 \
  -e SERVER_PORT=8081 -e SERVER_NET=Mainnet \
  -e DB_HOSTNAME=l0-pg -e DB_NAME=layer0 -e DB_USERNAME=root -e DB_PASSWORD=test1234 -e DBTYPE=postgresql \
  -e SERVICE_MCMC=true -e SERVICE_BLOCKBATCH=true -e SERVICE_SCHEDULE_MICROBATCH=true -e CREATETABLE=true \
  -e RUNKAFKASTREAM=false \
  ghcr.io/bigt-ai-platform/layer0-server

docker run -d --net=cc-bridged-network --name l0-mcmc -p 8091:8091 \
  -e SERVER_PORT=8091 -e SERVER_NET=Mainnet \
  -e DB_HOSTNAME=l0-pg -e DB_NAME=layer0 -e DB_USERNAME=root -e DB_PASSWORD=test1234 -e DBTYPE=postgresql \
  -e REQUESTER=http://l0-svr:8081 -e CREATETABLE=false \
  -e SERVICE_MCMC=true -e SERVICE_BLOCKBATCH=true -e SERVICE_SCHEDULE_MICROBATCH=true \
  -e RUNKAFKASTREAM=false \
  -e POS_VALIDATOR_KEY=<POS_VALIDATOR_KEY_0> \
  ghcr.io/bigt-ai-platform/layer0-mcmc
```

Repeat for the remaining nodes with unique ports/DBs and `POS_GOSSIP_PEERS`.

## 8. Config reference

| Variable | Description | Example |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP listen port | `8081` |
| `SERVER_NET` | `Mainnet` / `Test` (selects NetworkParameters) | `Mainnet` |
| `SERVER_CHAIN` | Chain id; `L0` for the shared Layer-0 chain | `L0` |
| `REQUESTER` | Peer node URL for DAG sync | `https://peer.bigtangle.org:8088` |
| `CREATETABLE` | Auto-create schema (`true` server only, first start) | `false` |
| `DB_HOSTNAME` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` / `DBTYPE` | PostgreSQL connection (`db.*`) | `localhost / 5432 / layer0 / root / … / postgresql` |
| `SERVICE_MCMC` / `SERVICE_MCMC_RATE` | MCMC consensus scheduler (`service.schedule.mcmc`) | `true / 1000` |
| `SERVICE_BLOCKBATCH` / `SERVICE_BLOCKBATCH_RATE` | Batch block service | `true / 50000` |
| `SERVICE_SYNC` / `SERVICE_SYNC_RATE` | Block sync service | `true / 50000` |
| `SERVICE_INITSYNC` | Sync on startup | `true` |
| `service.schedule.microbatch` | Micro-batch service (system property, e.g. `--service.schedule.microbatch=true`; env `SERVICE_SCHEDULE_MICROBATCH`) | `true` |
| `RUNKAFKASTREAM` | Kafka stream processing (unused in this deployment; leave off) | `false` |
| `FUND_ENABLED` | Enable the coin-minting `fundAddresses` endpoint (**test/bootstrap only**; must stay `false` on Mainnet) | `false` |
| `POS_VALIDATOR_KEY` | Validator private seed (64 or 128 hex) | `…` |
| `POS_SLOT_INTERVAL_MS` | Slot duration | `12000` |
| `POS_SLOTS_PER_EPOCH` | Slots per epoch | `32` |
| `POS_GOSSIP_PEERS` | Comma-separated `host:port` attestation mesh | `10.0.0.1:8081,10.0.0.2:8081` |
| `PEER_UDPPORT` / `PEER_TCPPORT` / `GOSSIP_PORT` | P2P gossip ports (unique per node) | `30307 / 30308 / 9095` |
| `SSL` / `KEYSTORE` / `KEYSTOREPW` / `KEYSTORETYPE` | TLS (PKCS12) | `true / /app/ca.pkcs12 / changeit / PKCS12` |

## 9. Verify

```bash
# Server is up (returns chain height)
curl http://localhost:8081/getChainHeight

# Active validator set
curl -X POST http://localhost:8081/getValidators -H 'Content-Type: application/json' -d '{}'

# PoS beacon progression
docker logs -f l0-mcmc
```

## Security notes

- `POS_VALIDATOR_KEY` is a private seed. Keep it in a gitignored `validator.env`,
  never log it, and never commit it.
- **`fundAddresses` mints confirmed coins over an unauthenticated endpoint.** It
  is disabled by default (`FUND_ENABLED=false`) and must remain disabled on any
  public or production node. Only enable it for test/bootstrap networks.
- `stakeDeposit` accepts the validator private key over HTTP — in production
  enable TLS (`SSL=true` + `KEYSTORE`) and/or restrict the endpoint to trusted
  operators.
- Use one DB per node and one `layer0-server` per DB.
- Genesis and the domain-permission root are fixed at chain launch (defined in
  the `NetworkParameters` for `SERVER_NET`).

## Notes

- The seed discovery service lives in a separate repo:
  [bigt-ai-platform/seeds](https://github.com/bigt-ai-platform/seeds).
- A single-validator testnet bootstrap is scripted in
  `layer0-mcmc/src/test/java/net/bigtangle/mcmc/remote/remote.sh`
  (PostgreSQL + `fundAddresses` → `stakeDeposit` → `activateValidator`).
