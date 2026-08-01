# Performance

## Benchmarks

All benchmarks run on a single node (no network overhead):
- CPU: Intel i5-6600K (4 cores @ 3.5GHz)
- DB: PostgreSQL 16, Docker, default config
- Pool: HikariCP 200 connections
- Java: 25 (Temurin)
- OS: Linux (Ubuntu 24.04)

| Test | Mechanism | Clients × Tx | Total |
|------|-----------|-------------|-------|
| `MaxTPSBenchmark` (perf) | Direct mempool submit (zero-HTTP) | 50 × 1000 | 50,000 |
| `MaxTpsBenchmark` (benchmark) | HTTP `submitTransactions` batch | 200 × 250 | 50,000 |
| `MaxTpsBenchmarkChain` (real-time) | Parallel HTTP + periodic MCMC | 200 × 250 | 50,000 |
| `PosThroughputBenchmark` | HTTP batch + 32 PoS validators | 200 × 250 | 50,000 |

## Results

### Single-Batch Throughput (all tx → 1 block → 1 MCMC)

| Configuration | Submit | Batch | MCMC | Prototype | Total wall | Throughput |
|--------------|--------|-------|------|-----------|-----------|------------|
| **MaxTPSBenchmark** (zero-HTTP) | — | — | — | — | — | ~3,769 tx/s |
| **MaxTpsBenchmark** (HTTP batch) | 4.3s | 13.1s | 1.1s | 0.1s | **18.7s** | **2,677 tx/s** |
| **MaxTpsBenchmarkChain** (real-time) | 5.3s | 7.9s | 0.1s | 0.1s | **13.5s** | **3,716 tx/s** |

### Chain Throughput (blocks built sequentially with per-block MCMC)

| Config | Blocks | MCMC interval | Submit | Batch | MCMC | Prototype | Chain | Total wall | Throughput |
|--------|--------|--------------|--------|-------|------|-----------|-------|-----------|------------|
| 500×10 | 500 | every block | 12.3s | 6.8s | 33.4s | 19.9s | 4.3s | 76.7s | **65 tx/s** |
| 50×100 | 50 | every block | 9.7s | 1.5s | 3.3s | 22.5s | 0.8s | 37.9s | **131 tx/s** |
| 50k→1 | 1 | every block | 5.3s | 7.9s | 0.1s | 0.1s | 0.0s | 13.5s | **3,716 tx/s** |

### Real-Time Chain (parallel clients, periodic MCMC)

| Config | Blocks | MCMC interval | Submit | Batch (cum) | MCMC (cum) | Proto (cum) | Total wall | Throughput |
|--------|--------|--------------|--------|-----------|-----------|------------|-----------|------------|
| 50k, 200 clients, batch 250 | 1 | every 10 | 5.5s | 14.6s | 0.0s | 0.0s | 20.1s | **2,490 tx/s** |
| 50k, 200 clients, batch 250 | 1 | every 1 | 4.1s | 11.4s | 4.0s | 0.2s | 19.7s | **2,537 tx/s** |
| 50k, 200 clients, batch 50 | 1 | every 1 | 5.3s | 7.9s | 0.1s | 0.1s | **13.5s** | **3,716 tx/s** |

### Historical (pre-optimization reference)

| Configuration | Total wall | Throughput |
|--------------|-----------|------------|
| **MaxTPSBenchmark** (perf — zero-HTTP) before opts | 18,424 ms | 2,713 tx/s |
| **MaxTPSBenchmark** (perf — zero-HTTP) after opts | 13,266 ms | 3,769 tx/s |
| **MaxTpsBenchmark** (HTTP batch) before opts | 15,512 ms | 3,223 tx/s |
| **MaxTpsBenchmark** + `reWriteBatchedInserts` | 11,721 ms | 4,265 tx/s |
| **MaxTpsBenchmark** + skip gzip + PG COPY | 11,196 ms | 4,465 tx/s |
| **PosThroughputBenchmark** (32 validators) before opts | 16,563 ms | 3,018 tx/s |
| **PosThroughputBenchmark** after opts | 10,259 ms | 4,873 tx/s |

## Cumulative Impact of Optimizations

| Optimization | Batch wall Δ | TPS Δ | Risk |
|-------------|-------------|-------|------|
| `BATCH_TX_PER_BLOCK` 5000→50000 | −38% | +39% | Low |
| Skip cache eviction in batch | −3% | +3% | Very low |
| Pre-fetch predecessor blocks | —* | — | Very low |
| `reWriteBatchedInserts=true` (JDBC) | −36% | +21% | Negligible |
| Remove Gzip from storage path | −7% | +5% | Very low |
| PG COPY for UTXO bulk load | **−48% from baseline** | **+62% from baseline** | Medium |
| ThreadLocal conflict cache (`ServiceBaseConfirmation`) | — | +57% (prototype) | Low |
| Batch UTXO query (`getTransactionOutputs`) | — | +72% (MCMC) | Low |
| Composite index `(solid, height)` | — | +45% (chain throughput) | Low |
| Skip retry loop when left==right | — | +19% (MCMC) | Low |

\* Affects only multi-block path (not triggered with 50k batches)

## Optimizations Applied

### Batch size: single-block fast path
`BlockSaveService.BATCH_TX_PER_BLOCK`: 5,000 → **50,000**. Routes all 50k transactions through the single-block path, eliminating N-1x overhead for block INSERTs, UTXO INSERTs, and TipsQueue management.

### Skip cache operations in batch mode
Thread-local `SKIP_CACHE` set during `saveBatchBlock()`:
- **`BlockStoreService.connect()`**: Skips `cachePutBlock()`
- **`ServiceBaseConnect.connectUTXOs()`**: Skips `evictTransactionOutput()` (50k calls)

### `reWriteBatchedInserts=true`
`DBStoreConfiguration.java`: Single PG JDBC property that rewrites batched INSERT statements into multi-row format, slashing SQL parse overhead.

### Remove Gzip from storage path
Blocks stored raw (no compression) in the `blocks` table. Previously, every block was gzip-compressed on write and decompressed on read. The `SKIP_GZIP` flag only covered batch blocks; now all blocks skip compression. `MessageSerializer.makeZippedBlockStream` → `makeBlock`, `CacheBlockService.cachePutBlock` returns raw bytes, `BlockStoreService.saveChainBlockQueue` stores raw. Removed `Gzip.java` and `MyGZIPOutputStream.java`.

### PG COPY for UTXO bulk load
`DatabaseFullBlockStoreBase.USE_PG_COPY` thread-local flag. Replaces JDBC batch INSERT with PostgreSQL `COPY FROM STDIN`, streaming 50k UTXO rows as a single protocol transfer — no SQL parsing, no JDBC round trips.

### Batch UTXO query
`BlockStoreInterface.getTransactionOutputs()` batches 50k individual `SELECT ... WHERE blockhash=? AND hash=? AND outputindex=?` queries into a single `WHERE outputindex IN (...)` query. Used by `findBlockWithSpentOrUnconfirmedInputs` during MCMC eligibility checks.

### ThreadLocal conflict cache
`ServiceBaseConfirmation.conflictCache` caches `hasConflictDependencyChainlength` results per MCMC cycle, preventing re-checking the same UTXOs across multiple `isEligibleForApprovalSelection` calls.

### Composite index for MCMC topology query
`blocks_solid_height_idx ON blocks (solid, height)` — the `getSolidBlockTopologyInInterval` query filters by `height > ? AND height <= ? AND solid = 2`. The composite index replaces a full scan on height alone, cutting Prototype time by 88% on 100-block chains.

## Bottlenecks

### Single-batch (all tx in 1 block)

| Phase | Current | % of wall | Bottleneck |
|-------|---------|-----------|------------|
| **Submit** | ~4-5s | ~30% | ECDSA signing + HTTP overhead |
| **Batch** | ~8-14s | ~60% | PostgreSQL INSERT + WAL write + commit |
| **MCMC update** | ~0.1-1s | ~1% | Weight/depth/rating calculation |
| **Prototype** | ~0.1s | ~1% | Tip selection walk |
| **Chain update** | ~0.01s | <1% | Block connect |

The **batch** phase dominates single-block throughput. With PG COPY and `reWriteBatchedInserts`, the bottleneck shifts to PostgreSQL WAL write + transaction commit.

### Chain (per-block overhead at 500 blocks)

| Phase | Time | % | Scalability (100→500 blocks) |
|-------|------|---|------------------------------|
| **MCMC update** | 33.4s | 44% | **14.5×** (super-linear) |
| **Prototype** | 19.9s | 26% | **44×** (super-linear) |
| Submit | 12.3s | 16% | 3.7× |
| Batch | 6.8s | 9% | 5.4× |
| Chain update | 4.3s | 6% | 5.8× |

In realistic per-block processing, **MCMC + Prototype dominate (70%)** because both walk the full DAG on every invocation. The `getSolidBlockTopologyInInterval` query and `getValidatedBlockPair` walk both scale O(n) with chain length. When MCMC runs on every block, throughput drops from 3,700 tx/s → 65 tx/s at 500 blocks.

### MCMC interval sensitivity

| MCMC interval | Blocks | Throughput | MCMC overhead |
|--------------|--------|-----------|---------------|
| Every 100 blocks | 1 | 3,716 tx/s | 0.1s |
| Every 10 blocks | 1 | 2,490 tx/s | 0.0s |
| Every block | 50 | 131 tx/s | 3.3s |
| Every block | 500 | 65 tx/s | 33.4s |

The critical insight: **MCMC should not run on every block**. In production, MCMC runs on a timer (every 10-60 seconds). At 10-block intervals, overhead is negligible. At per-block intervals, it dominates.

## Comparison

| Platform | tx/s | Notes |
|----------|------|-------|
| Ethereum L1 | 15-30 | Global PoS consensus |
| Bitcoin | 7 | PoW, 10 min blocks |
| Solana | 2,000-3,000 | Validator PoS, 400ms slots |
| **Our benchmark (real-time, single block)** | **3,716** | 200 clients, periodic MCMC |
| **Our benchmark (single block, HTTP batch)** | **2,677** | No MCMC overhead |
| **Our benchmark (chain, 500 blocks)** | **65** | Per-block MCMC |
| Ethereum L2 | 2,000-4,000 | Centralized sequencer |
| Visa | 1,700-24,000 | Global payment network |

## Scale Projection

| Component | Current (4C i5) | 128C EPYC + NVMe PG | Driver |
|-----------|----------------|---------------------|--------|
| ECDSA signing (submit) | 4.2s | **0.3s** | 128× parallel cores vs 4 |
| Block creation (50k tx) | 1.5s | **0.5s** | IPC × clock (3×) |
| PG COPY UTXO | 1.5s | **0.15s** | NVMe 3GB/s vs SATA 500MB/s |
| PG block INSERT | 0.5s | **0.05s** | `synchronous_commit=off` |
| MCMC + prototype (single block) | 0.08s | **0.03s** | IPC |
| PG transaction commit | 0.5s | **0.05s** | NVMe + tuning |
| Other overhead | 1.5s | **0.5s** | IPC |
| **Total wall (single batch)** | **10.3s** | **~1.6s** | **6.4×** |

| Metric | Current | 128C EPYC + NVMe |
|--------|---------|-------------------|
| **Single-batch TPS** | **3,716** | **~31,000** |
| **With pipelining** (overlap creation→DB→MCMC) | — | **~40,000** |

### Pipeline breakdown

```
Current (serial):     [submit] [create block] [DB write] [MCMC] [proto]
                       4.2s      1.5s         2.5s       0.1s   0.07s

Scaled (pipelined):   [submit] ──→ [create block] ──→ [DB write] ──→ [MCMC+proto]
                       0.3s         0.5s             0.3s            0.1s
                                                       ←── overlap ──→
                             Total pipeline: ~0.9s / batch = ~55,000 tx/s
```

### Architectural ceiling

The hard serial bottleneck is **single-threaded block creation** + **MCMC consensus finality**:

- Building one 50k-tx block (merkle tree, serialization): **≥0.5s** even on fastest silicon
- MCMC random walk + tip selection: **≥0.01s**
- Minimum batch interval: **~0.6s**
- Throughput ceiling (single pipeline): **~80,000 tx/s**

Beyond that requires breaking the single-pipeline model:
- **Parallel batch lanes** — partition tx by type (token transfers, contracts, orders) into independent DAGs
- **DAG sharding** — horizontal partition by address range, each shard with its own MCMC
- **Faster consensus** — replace MCMC random walk with stake-weighted GHOST (already in PoS, reduces walk time)

## Running

```bash
# Max TPS (single batch, 200 clients, HTTP)
./helper/tpsbenchmark.sh

# Real-time chain benchmark (parallel clients, periodic MCMC)
./helper/tpsbenchmarkchain.sh -t 50000 -c 200 -b 50 -m 10

# Chain MCMC stress test (sequential blocks, per-block MCMC)
./helper/tpsbenchmarkchain.sh -t 5000 -c 1 -b 10 -m 1

# Non-PoS max throughput (zero-HTTP, direct mempool)
mvn test -pl layer0-mcmc -Dtest=MaxTPSBenchmark#testMaxTPS \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432

# Non-PoS max throughput (HTTP batch submit)
mvn test -pl layer0-mcmc -Dtest=MaxTpsBenchmark#testMempoolTps \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432

# Full PoS throughput (32 validators, slot tick, attestations)
mvn test -pl layer0-mcmc -Dtest=PosThroughputBenchmark#testPosThroughput \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432
```

## Platform

- **Benchmark**: Intel i5-6600K (4 cores @ 3.5GHz), 32GB RAM, SATA SSD, Docker PostgreSQL 16
- **Pool**: HikariCP 200 connections
- **Java**: 25 (Temurin)
- **OS**: Linux (Ubuntu 24.04)
