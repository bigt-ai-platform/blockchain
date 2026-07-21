Copyright 2018 Inasset GmbH.

# Layer0 Production Deployment

## Architecture

```
PostgreSQL ← layer0-server (API / P2P) ← requester (peer node)
                   ↕ (same DB)
              layer0-mcmc (mining & consensus)
```

Two processes share the same database: `layer0-server` handles API requests and
P2P sync; `layer0-mcmc` does proof-of-work mining and MCMC consensus.

## Single-Instance Guarantee via DB Lock

Each critical service acquires a row-level lock in the `lockobject` table before
doing work. This prevents duplicate processing even if multiple JVM processes
share the same database.

| Service | Lock ID | What it protects |
|---------|---------|-----------------|
| `MCMCService` | `net.bigtangle.mcmc.service.MCMCService` | MCMC consensus cycle |
| `RewardService` | `net.bigtangle.mcmc.service.RewardService` | Reward distribution |
| `SyncBlockService` | `net.bigtangle.server.service.SyncBlockService` | Block sync & orphan resolution |
| `BlockStoreService` | `net.bigtangle.store.BlockStoreService` | Block graph updates |
| `AVGPriceService` | `net.bigtangle.l1.order.service.AVGPriceService` | Price feed updates |

The lock protocol:

1. `store.selectLockobject(LOCKID)` — check if another instance holds the lock.
2. If **no lock** → `store.insertLockobject(...)` to acquire it and proceed.
3. If **stale lock** (locktime expired) → delete and re-insert to take over.
4. If **active lock** → skip (another instance is already processing).
5. After work → `store.deleteLockobject(LOCKID)` to release.

This means you can safely run multiple JVM instances; the DB lock ensures only
one processes each critical section at a time. In production, it is still
recommended to run exactly one `layer0-server` per database to avoid
unnecessary resource contention.

## Prerequisites

- Java 25, Maven 3.6+
- PostgreSQL 16
- Docker (optional, for containerized deployment)

## 1. Database Setup

```sql
CREATE DATABASE layer0;
CREATE USER root WITH PASSWORD 'test1234';
GRANT ALL PRIVILEGES ON DATABASE layer0 TO root;
```

Or via Docker:

```bash
docker run -d --name l0-pg \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD=test1234 \
  -e POSTGRES_DB=layer0 \
  -p 5432:5432 \
  -v /data/l0-pg:/var/lib/postgresql/data \
  postgres:16
```

## 2. Build

```bash
mvn -DskipTests clean install
```

Produces:
- `layer0-server/target/layer0-server-0.5.0-exec.jar`
- `layer0-mcmc/target/layer0-mcmc-0.5.0-exec.jar`

## 3. Start Services

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP listen port | `8081` |
| `SERVER_NET` | Network name (`Mainnet` / `Test`) | `Mainnet` |
| `SERVER_MINERADDRESS` | Miner reward address | `1CWxNA...` |
| `DB_HOSTNAME` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `layer0` |
| `DB_USERNAME` | DB user | `root` |
| `DB_PASSWORD` | DB password | `test1234` |
| `DBTYPE` | Database type | `postgresql` |
| `REQUESTER` | Peer node URL for P2P sync | `https://peer.bigtangle.org:8088` |
| `SERVICE_MINING` | Enable mining | `false` (server) / `true` (mcmc) |
| `SERVICE_MINING_RATE` | Mining interval (ms) | `50000` |
| `SERVICE_MCMC` | Enable MCMC consensus | `true` |
| `SERVICE_MCMC_RATE` | MCMC interval (ms) | `1000` |
| `SERVICE_INITSYNC` | Sync on startup | `true` |
| `CREATETABLE` | Auto-create tables | `true` (server only) |
| `SSL` | Enable HTTPS | `true` |
| `KEYSTORE` | PKCS12 keystore path | `/app/ca.pkcs12` |

### Start layer0-server (API + P2P)

```bash
java -Xmx5028m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -jar layer0-server/target/layer0-server-0.5.0-exec.jar \
  --SERVER_PORT=8081 \
  --SERVER_NET=Mainnet \
  --SERVER_MINERADDRESS=1CWxNAAAmTVRqodSSXTatxSopKEAD9EJw8 \
  --DB_HOSTNAME=localhost \
  --DB_PORT=5432 \
  --DB_NAME=layer0 \
  --DB_USERNAME=root \
  --DB_PASSWORD=test1234 \
  --DBTYPE=postgresql \
  --SERVICE_MINING=false \
  --SERVICE_MCMC=true \
  --SERVICE_MCMC_RATE=1000 \
  --SERVICE_INITSYNC=true \
  --CREATETABLE=true
```

### Start layer0-mcmc (Mining + Consensus)

```bash
java -Xmx2048m --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -Dfile.encoding=UTF-8 \
  -jar layer0-mcmc/target/layer0-mcmc-0.5.0-exec.jar \
  --SERVER_PORT=8084 \
  --SERVER_NET=Mainnet \
  --SERVER_MINERADDRESS=1CWxNAAAmTVRqodSSXTatxSopKEAD9EJw8 \
  --DB_HOSTNAME=localhost \
  --DB_PORT=5432 \
  --DB_NAME=layer0 \
  --DB_USERNAME=root \
  --DB_PASSWORD=test1234 \
  --DBTYPE=postgresql \
  --REQUESTER=http://localhost:8081 \
  --SERVICE_MINING=true \
  --SERVICE_MINING_RATE=50000 \
  --SERVICE_MCMC=true \
  --SERVICE_MCMC_RATE=1000 \
  --SERVICE_INITSYNC=true \
  --CREATETABLE=false
```

## 4. Docker Deployment

### Build Docker Image

```bash
docker build -t bigtangle -f helper/bigtangle/Dockerfile .
```

### Run with Docker Compose

```bash
docker compose -f helper/fulltest/docker-compose.l0-single.yml up -d
```

### Run Manually

```bash
docker network create cc-bridged-network

docker run -d --net=cc-bridged-network --name l0-pg \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD=test1234 \
  -e POSTGRES_DB=layer0 \
  postgres:16

docker run -d --net=cc-bridged-network --name l0-svr -p 8081:8081 \
  -e SERVER_PORT=8081 \
  -e SERVER_NET=Mainnet \
  -e DB_HOSTNAME=l0-pg \
  -e DB_NAME=layer0 \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=test1234 \
  -e DBTYPE=postgresql \
  -e SERVICE_MINING=false \
  -e SERVICE_MCMC=true \
  -e CREATETABLE=true \
  bigtangle

docker run -d --net=cc-bridged-network --name l0-mcmc -p 8084:8084 \
  -e APP_MODULE=layer0-mcmc \
  -e SERVER_PORT=8084 \
  -e SERVER_NET=Mainnet \
  -e DB_HOSTNAME=l0-pg \
  -e DB_NAME=layer0 \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=test1234 \
  -e DBTYPE=postgresql \
  -e REQUESTER=http://l0-svr:8081 \
  -e SERVICE_MINING=true \
  -e SERVICE_MINING_RATE=50000 \
  -e SERVICE_MCMC=true \
  -e CREATETABLE=false \
  bigtangle
```

### With SSL

Mount a PKCS12 keystore and set `SSL=true`, `KEYSTORE=/app/ca.pkcs12`:

```bash
docker run -d --net=cc-bridged-network -p 8088:8088 \
  -v /host/path/ca.pkcs12:/app/ca.pkcs12 \
  -e SSL=true \
  -e KEYSTORE=/app/ca.pkcs12 \
  -e SERVER_PORT=8088 \
  ...
  bigtangle
```

## 5. Verify

```bash
# Check server is running
curl http://localhost:8081/getChainHeight

# Check MCMC is mining
docker logs -f l0-mcmc
```

## Notes

- `layer0-mcmc` must point `REQUESTER` to `layer0-server`'s URL.
- Set `CREATETABLE=true` only on the first start (server), `false` on mcmc.
- For Mainnet, set `SERVER_NET=Mainnet` and provide a valid `SERVER_MINERADDRESS`.
- The seed discovery service lives in a separate repo: [bigt-ai-platform/seeds](https://github.com/bigt-ai-platform/seeds).
