# Layer0 PoS Pre-Production Network

Multi-node Proof-of-Stake blockchain network with Docker, node discovery, and
automated validator key generation. Designed to **simulate production scale**
on a single machine.

## Architecture

```
                          ┌──────────────────────────────────────┐
                          │         Docker Bridge Network        │
                          │         pos-preprod-net              │
                          │                                      │
  Node 0 (bootstrap)      │   Node 1-15         Node N           │
  ┌─────────────────┐     │   ┌──────────────┐   ┌──────────────┐│
  │ postgres-l0-0    │     │   │ postgres-l0-N│   │ ...          ││
  └──────▲──────────┘     │   └──────▲───────┘   └──────────────┘│
         │                │          │                            │
  ┌──────┴──────────┐     │   ┌──────┴───────┐                   │
  │  l0-server-0    │◄────┼───│ l0-server-N  │                   │
  │  (bootstrap)    │     │   │ (→ n0)       │                   │
  └──────▲──────────┘     │   └──────▲───────┘                   │
         │                │          │                            │
  ┌──────┴──────────┐     │   ┌──────┴───────┐                   │
  │  l0-mcmc-0      │     │   │ l0-mcmc-N    │                   │
  │  (miner+pos)    │     │   │ (miner+pos)  │                   │
  └─────────────────┘     │   └──────────────┘                   │
                          └──────────────────────────────────────┘
```

Each **node** = 1 PostgreSQL + 1 layer0-server + 1 layer0-mcmc.

| Component | Role |
|-----------|------|
| `l0-server` | REST API, P2P block sync, graph management |
| `l0-mcmc` | Tip selection (MCMC), PoW mining, PoS validator duties |
| `postgres` | Blockchain state, UTXOs, PoS validator registry |

Node 0 is the **bootstrap**. All other servers set `REQUESTER=http://pos-svr-0:8081`.

## Quick Start

```bash
# 1. Build (Maven + Docker)
./run.sh build

# 2. Start a 16-node PoS network (default)
./run.sh up

# 3. Check status
./run.sh status

# 4. Tail logs
./run.sh logs svr-0
./run.sh logs mcmc-2

# 5. Stop
./run.sh down
```

## Scale Examples

```bash
# Minimal smoke test (4 nodes)
./run.sh up --nodes 4

# Prod simulation (16 nodes — default)
./run.sh up

# Large cluster (32 nodes, 512 MB heap each to fit in memory)
./run.sh up --nodes 32 --mem 512

# Extreme: 64 validators (requires 64 GB+ host)
./run.sh up --nodes 64 --mem 512
```

## Resource Planning

| Nodes | Containers | JVMs | Heap (1 GB) | Heap (512 MB) |
|-------|-----------|------|-------------|---------------|
| 4     | 12        | 8    | 8 GB        | 4 GB          |
| 16    | 48        | 32   | 32 GB       | 16 GB         |
| 32    | 96        | 64   | 64 GB       | 32 GB         |
| 64    | 192       | 128  | 128 GB      | 64 GB         |

Use `--mem` to cap heap per JVM. The script warns if total JVM heap exceeds host
memory.

## Command Reference

### `build`

```bash
./run.sh build [--no-rebuild] [--tag T] [--mem MB]
```

Runs `mvn -DskipTests clean install` then `docker build`. The `--mem` value is
baked into the generated compose files as `JAVA_OPTS=-Xmx`.

### `up`

```bash
./run.sh up [--nodes N] [--tag T] [--mem MB] [--miner ADDR] [--net NAME]
```

| Option | Default | Description |
|--------|---------|-------------|
| `--nodes` | 16 | Number of PoS nodes |
| `--mem` | 1024 | Max heap per JVM (MB) |
| `--tag` | preprod | Docker image tag |
| `--miner` | mjWvz... | Miner reward address |

For N ≤ 4, uses the static `docker-compose.pos.yml` with profiles.
For N > 4, auto-generates a compose file and starts all services.

### `generate`

```bash
./run.sh generate --nodes 16
```

Writes `docker-compose.pos-16.yml` with all nodes expanded. Use this to inspect
the generated config before starting.

### `down` / `clean` / `status` / `logs`

```bash
./run.sh down              # stop (preserves volumes)
./run.sh clean             # stop + remove volumes + generated files
./run.sh status            # docker compose ps
./run.sh logs              # tail all logs
./run.sh logs svr-3        # tail server-3 only
./run.sh logs mcmc-0       # tail mcmc-0 only
```

## Node Discovery

1. **Bootstrap requester** — `l0-server-{1..N}` → `http://pos-svr-0:8081`
   for block sync and peer discovery.
2. **MCMC requester** — `l0-mcmc-{i}` → its local `l0-server-{i}` for tx data.
3. **Docker DNS** — all containers resolve each other by name on `pos-preprod-net`.
4. **Kafka (optional)** — add `BOOT_STRAP_SERVERS=kafka:9092` for gossip.

## Port Mapping

| Node | Server Port | MCMC Port | PG Host Port |
|------|-------------|-----------|--------------|
| 0    | 8081        | 8085      | 5432         |
| 1    | 8082        | 8086      | —            |
| ...  | ...         | ...       | —            |
| N    | 8081+N      | 8085+N    | —            |

Only node 0 PG is exposed (for `psql`, DBeaver, etc.).

## PoS Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `POS_ENABLED` | true | Enable PoS beacon chain |
| `POS_SLOT_INTERVAL_MS` | 12000 | Slot duration (ms) |
| `POS_SLOTS_PER_EPOCH` | 32 | Slots per epoch |
| `POS_VALIDATOR_KEY_N` | auto | Per-node hex validator key |

Validator keys are deterministic: `SHA-256("preprod-validator-key-{N}")`.
Override with env vars for reproducible runs:

```bash
export POS_VALIDATOR_KEY_0=<your-hex-key>
export POS_VALIDATOR_KEY_1=<your-hex-key>
./run.sh up --nodes 2
```

## Verification

```bash
# Health
for port in $(seq 8081 $((8080 + NODE_COUNT))); do
  echo ":${port} $(curl -sf http://localhost:${port}/actuator/health | jq -r .status)"
done

# Block height per node
for port in $(seq 8081 $((8080 + NODE_COUNT))); do
  echo "Node :${port} height: $(curl -s http://localhost:${port}/getBlockCount)"
done

# Peers on bootstrap
curl http://localhost:8081/getPeers | jq .

# PoS chain
curl http://localhost:8081/pos/getEpoch
```

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Nodes stuck at "waiting" | OOM or port conflict | Lower `--mem` or check `lsof -i :8081-...` |
| Docker says "no space" | Image volume | `docker system prune -af` |
| PostgreSQL healthcheck fails | PG auth or port conflict | Ensure port 5432 is free |
| Validator never proposes | Wrong key or no stake | Check `POS_VALIDATOR_KEY_N` matches the staked key |

## Files

| File | Purpose |
|------|---------|
| `docker-compose.pos.yml` | Static 4-node reference (for N ≤ 4) |
| `run.sh` | Orchestration script |
| `readme.md` | This document |
| `.docker-compose.pos-N.generated.yml` | Auto-generated (for N > 4, created at runtime) |
| `docker-compose.pos-N.yml` | Generated by `./run.sh generate` |
