Copyright 2018 Inasset GmbH.

# Layer0 Production Deployment (Proof-of-Stake)

## Architecture

```
PostgreSQL ← layer0-server (API / P2P sync / PoS beacon duties) ← requester (peer node)
```

One process per node: `layer0-server` handles API requests, block sync, the
proof-of-stake slot ticker, beacon proposals/attestations, reward distribution
and epoch finality. Consensus is **proof-of-stake** — there is no proof-of-work
mining.

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
+ 1 stake generator**, each with its own ports and its own database. Nodes stay in
sync through three channels:

| Channel | Config | Purpose |
|---------|--------|---------|
| DAG sync | `server.requester` + seed DNS (`dnsSeeds` in params) | pull blocks from a peer / the network |
| P2P gossip | `peer.udpPort`, `peer.tcpPort`, `gossip.port` | block/broadcast gossip |
| PoS attestation mesh | `pos.gossipPeers` (`host:port,host:port,…`) | broadcast attestations, slashing proofs, beacon hashes between validators |

Every node sees the same validator set because `stakeDeposit`/`activateValidator`
write STAKE blocks into the shared DAG.

## Single-Instance Guarantee via DB Lock

Each critical service acquires a row-level lock in the `lockobject` table before
doing work. This prevents duplicate processing even if multiple JVM processes
share the same database.

| Service | Lock ID |
|---------|---------|
| `SyncBlockService` | `net.bigtangle.server.service.SyncBlockService` |
| `BlockStoreService` | `net.bigtangle.store.BlockStoreService` |
| `AVGPriceService` | `net.bigtangle.layer1.service.AVGPriceService` |

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

### layer0-server (API + P2P sync + PoS beacon duties) — creates the schema on first start

```bash
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -jar layer0-server/target/layer0-server-0.6.0-exec.jar \
  --server.port=8081 --server.net=Mainnet --server.chain=L0 \
  --db.hostname=localhost --db.port=5432 --db.dbName=layer0 \
  --db.username=root --db.password=test1234 --db.dbtype=postgresql \
  --server.createtable=true \
  --service.schedule.chainlength=true --service.schedule.blockbatch=true \
  --service.schedule.microbatch=true --service.schedule.initsync=true \
  --server.runKafkaStream=false \
  --pos.validatorKey=<POS_VALIDATOR_KEY_0> --pos.dutyEnabled=true \
  --peer.udpPort=30307 --peer.tcpPort=30308 --gossip.port=9095
```

(`--server.chain=L0` runs the shared Layer-0 chain; leave it unset for the
network default. `--server.net=Mainnet` selects the mainnet `NetworkParameters`,
whose genesis is fixed at chain launch. The server must set
`--pos.validatorKey` so `stakeDeposit` can sign, and `--pos.dutyEnabled=true`
so it proposes/attests — validator duties run on the `layer0-server` itself
in PoS mode.)

## 5. Fund, stake and activate validators

For **every** validator `i` (funding/activation can target any reachable node;
the STAKE blocks propagate through the DAG). The `stakeDeposit` call must be
submitted to validator `i`'s **own** node, because it signs with that node's
configured `--pos.validatorKey` and the request `pubkey` must match:

```bash
# 1) Fund the validator's address (requires FUND_ENABLED=true, see below)
curl -X POST http://127.0.0.1:8081/fundAddresses -H 'Content-Type: application/json' \
  -d '{"addresses":[{"address":"validator","value":1000000000000,"pubkey":"<VALIDATOR_PUBKEY_i>"}]}'

# 2) Stake (amount must be >= 32,000,000 satoshis = 32 BIG). No private key is
#    sent over HTTP: the STAKE block is signed with the server's configured
#    --pos.validatorKey, which must match <VALIDATOR_PUBKEY_i>.
curl -X POST http://127.0.0.1:8081/stakeDeposit -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","amount":"32000000"}'

# 3) Activate (join the proposer set at the given epoch)
curl -X POST http://127.0.0.1:8081/activateValidator -H 'Content-Type: application/json' \
  -d '{"pubkey":"<VALIDATOR_PUBKEY_i>","epoch":0}'
```

After activation the node proposes when selected (`getValidators` shows the
active set). Other PoS endpoints: `processWithdrawal`, `submitSlashingProof`,
`getBaseFee`, `setValidatorKey`, `getValidatorKey`.

## 6. Add more nodes (N validators)

Repeat §4 for nodes 1..N-1 with, per node:

- its own database (different `db.dbName` or PostgreSQL instance),
- unique ports (`server.port`, `peer.udpPort`, `peer.tcpPort`, `gossip.port`),
- its own `--pos.validatorKey`,
- `--server.requester` pointed at node 0 (or a seed node),
- `--pos.gossipPeers="<node0 host:port>,<node1 host:port>,…"` on every node so
  attestations/slashing proofs reach the validator set.

Example for node 1 (ports derive from node 0: server 8081+1, peer/gossip
30307/30308/9095 + 2·1, etc. — see `helper/prod/validators/`):

```bash
java ... -jar layer0-server-0.6.0-exec.jar \
  --server.port=8082 ... --db.dbName=layer0_1 \
  --server.requester=http://<node0>:8081 --server.createtable=true \
  --pos.validatorKey=<POS_VALIDATOR_KEY_1> --pos.dutyEnabled=true \
  --peer.udpPort=30309 --peer.tcpPort=30310 --gossip.port=9097 \
  --pos.gossipPeers="<node0 host>:8081,<node1 host>:8082"
```

The network converges: every node syncs the same DAG, sees the same activated
validator set, and only the slot-selected proposer mints each beacon block.

## 7. Docker deployment

Build images (`helper/deploy.sh`) then, per node, run a postgres + server
container. The `layer0-server` image runs `java -jar app.jar`, so pass the same
CLI flags as §4 (override the entrypoint to run `java`). Use `--network host`
so the DB on `localhost` stays reachable and the node's ports bind directly on
the host. Example for node 0:

```bash
docker run -d --name l0-pg \
  -e POSTGRES_USER=root -e POSTGRES_PASSWORD=test1234 -e POSTGRES_DB=layer0 \
  -p 5432:5432 -v /data/l0-pg:/var/lib/postgresql/data postgres:16

docker run -d --name l0-server --network host \
  --entrypoint java ghcr.io/bigt-ai-platform/layer0-server \
  -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports java.base/java.lang=ALL-UNNAMED -jar /app/app.jar \
  --server.port=8081 --server.net=Mainnet --server.chain=L0 \
  --db.hostname=localhost --db.port=5432 --db.dbName=layer0 \
  --db.username=root --db.password=test1234 --db.dbtype=postgresql \
  --server.createtable=true \
  --service.schedule.chainlength=true --service.schedule.blockbatch=true \
  --service.schedule.microbatch=true --service.schedule.initsync=true \
  --server.runKafkaStream=false \
  --pos.validatorKey=<POS_VALIDATOR_KEY_0> --pos.dutyEnabled=true \
  --peer.udpPort=30307 --peer.tcpPort=30308 --gossip.port=9095
```

Repeat for the remaining nodes with unique ports/DBs and `--pos.gossipPeers`.
The per-validator `helper/prod/validators/` scripts automate exactly this
(container names `node-<i>-server`, `--network host`, CLI flags).

The image entrypoint is `java ... -jar app.jar`, so configuration can also be
passed as environment variables via Spring relaxed binding (e.g. `SERVER_PORT`,
`SERVER_REQUESTER`, `DB_DBNAME`, `SERVICE_SCHEDULE_CHAINLENGTH`). The CLI flags
above mirror §4 and are the recommended form.

## 8. Config reference

| Property (CLI `--key=value`) | Description | Example |
|----------|-------------|---------|
| `server.port` | HTTP listen port | `8081` |
| `server.net` | `Mainnet` / `Test` (selects NetworkParameters) | `Mainnet` |
| `server.chain` | Chain id; `L0` for the shared Layer-0 chain | `L0` |
| `server.requester` | Peer node URL for DAG sync | `https://peer.bigtangle.org:8088` |
| `server.createtable` | Auto-create schema (`true` server only, first start) | `false` |
| `db.hostname` / `db.port` / `db.dbName` / `db.username` / `db.password` / `db.dbtype` | PostgreSQL connection | `localhost / 5432 / layer0 / root / … / postgresql` |
| `service.schedule.chainlength` / `service.schedule.syncrate` | Chain-length update scheduler + sync rate | `true / 50000` |
| `service.schedule.blockbatch` / `service.schedule.blockbatchrate` | Batch block service + rate | `true / 50000` |
| `service.schedule.syncrate` | Block sync rate | `50000` |
| `service.schedule.initsync` | Sync on startup | `true` |
| `service.schedule.microbatch` | Micro-batch service | `true` |
| `server.runKafkaStream` | Kafka stream processing (unused in this deployment; leave off) | `false` |
| `server.fundEnabled` | Enable the coin-minting `fundAddresses` endpoint (**test/bootstrap only**; must stay `false` on Mainnet) | `false` |
| `pos.validatorKey` | Validator private seed (64 or 128 hex) | `…` |
| `pos.dutyEnabled` | This process proposes/attests (validator duties run on `layer0-server`) | `true` |
| `pos.slotIntervalMs` | Slot duration | `12000` |
| `pos.gossipPeers` | Comma-separated `host:port` attestation mesh | `10.0.0.1:8081,10.0.0.2:8081` |
| `peer.udpPort` / `peer.tcpPort` / `gossip.port` | P2P gossip ports (unique per node) | `30307 / 30308 / 9095` |
| `SSL` / `KEYSTORE` / `KEYSTOREPW` / `KEYSTORETYPE` | TLS (PKCS12) | `true / /app/ca.pkcs12 / changeit / PKCS12` |

## 9. Verify

```bash
# Server is up (returns chain height)
curl -X POST http://localhost:8081/getChainNumber -H 'Content-Type: application/json' -d '{}'

# Active validator set
curl -X POST http://localhost:8081/getValidators -H 'Content-Type: application/json' -d '{}'

# PoS beacon progression
docker logs -f l0-server
```

## Security notes

- `POS_VALIDATOR_KEY` is a private seed. Keep it in a gitignored `validator.env`,
  never log it, and never commit it.
- **`fundAddresses` mints confirmed coins over an unauthenticated endpoint.** It
  is disabled by default (`server.fundEnabled=false`) and must remain disabled on any
  public or production node. Only enable it for test/bootstrap networks.
- `stakeDeposit` signs with the node's configured `pos.validatorKey` (it rejects
  a `privateKey` in the request). That key is supplied to the process as a
  command-line arg — in production enable TLS (`SSL=true` + `KEYSTORE`) and/or
  restrict the endpoint to trusted operators.
- Use one DB per node and one `layer0-server` per DB.
- Genesis and the domain-permission root are fixed at chain launch (defined in
  the `NetworkParameters` for `server.net`).

## Notes

- The seed discovery service lives in a separate repo:
  [bigt-ai-platform/seeds](https://github.com/bigt-ai-platform/seeds).
- A single-validator testnet bootstrap is scripted in
  `helper/prod/validators/` (`generate_keys.sh`, `node-<i>/setup.sh` phases
  server → stake → verify: PostgreSQL + `fundAddresses` → `stakeDeposit` →
  `activateValidator`).
