# Performance

## Benchmarks

| Test | Mechanism | Clients × Tx | Total |
|------|-----------|-------------|-------|
| `MaxTPSBenchmark` (perf) | Direct mempool submit (zero-HTTP) | 50 × 1000 | 50,000 |
| `MaxTpsBenchmark` (benchmark) | HTTP `submitTransactions` batch | 200 × 250 | 50,000 |
| `PosThroughputBenchmark` | HTTP batch + 32 PoS validators | 200 × 250 | 50,000 |

## Results

| Configuration | Batch wall | Total wall | Throughput |
|--------------|-----------|-----------|------------|
| **MaxTPSBenchmark** (perf — zero-HTTP) | | | |
| Before optimizations | 13,960 ms | 18,424 ms | 2,713 tx/s |
| After optimizations | 9,167 ms | 13,266 ms | 3,769 tx/s |
| **MaxTpsBenchmark** (benchmark — HTTP batch) | | | |
| Before optimizations | 10,512 ms | 15,512 ms | 3,223 tx/s |
| + `reWriteBatchedInserts` | 6,779 ms | 11,721 ms | 4,265 tx/s |
| + skip gzip + PG COPY | 6,331 ms | 11,196 ms | 4,465 tx/s |
| **PosThroughputBenchmark** (32 validators, full PoS) | | | |
| Before optimizations | 11,457 ms | 16,563 ms | 3,018 tx/s |
| After optimizations | 6,012 ms | 10,259 ms | 4,873 tx/s |

## Cumulative Impact of Optimizations

| Optimization | Batch wall Δ | TPS Δ | Risk |
|-------------|-------------|-------|------|
| `BATCH_TX_PER_BLOCK` 5000→50000 | −38% | +39% | Low |
| Skip cache eviction in batch | −3% | +3% | Very low |
| Pre-fetch predecessor blocks | —* | — | Very low |
| `reWriteBatchedInserts=true` (JDBC) | −36% | +21% | Negligible |
| Skip gzip for batch blocks | −7% | +5% | Very low |
| PG COPY for UTXO bulk load | **−48% from baseline** | **+62% from baseline** | Medium |

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

### Skip gzip for batch blocks
`DatabaseFullBlockStoreBase.SKIP_GZIP` thread-local flag. Batch blocks are transient mempool dumps — gzip adds CPU cost with no benefit. `Gzip.decompressOut()` already handles uncompressed data via gzip magic byte detection.

### PG COPY for UTXO bulk load
`DatabaseFullBlockStoreBase.USE_PG_COPY` thread-local flag. Replaces JDBC batch INSERT with PostgreSQL `COPY FROM STDIN`, streaming 50k UTXO rows as a single protocol transfer — no SQL parsing, no JDBC round trips.

## Bottlenecks

| Phase | After opt (non-PoS) | After opt (PoS) | % of wall (PoS) |
|-------|--------------------|------------------|------------------|
| **Submit** | ~4.4s | ~4.2s | 41% |
| **Batch** | ~6.3s | ~6.0s | 58% |
| **MCMC update** | ~3ms | ~8ms | <1% |
| **Prototype** | ~24ms | ~67ms | <1% |
| **Chain update** | ~6ms | ~7ms | <1% |

The **batch** phase remains dominant (58% of wall), driven by PostgreSQL WAL write + transaction commit. The UTXO INSERT is now PG COPY (streaming), and block storage skips gzip. Next bottleneck is the block INSERT + PG commit.

## Comparison

| Platform | tx/s | Notes |
|----------|------|-------|
| Ethereum L1 | 15-30 | Global PoS consensus |
| Bitcoin | 7 | PoW, 10 min blocks |
| Solana | 2,000-3,000 | Validator PoS, 400ms slots |
| **Our benchmark (non-PoS)** | **4,465** | Single node, MCMC bridge, parallel submit |
| **Our benchmark (32 validators)** | **4,873** | Full PoS: slot tick, Casper attestations, fee updates |
| Ethereum L2 | 2,000-4,000 | Centralized sequencer |
| Visa | 1,700-24,000 | Global payment network |

## Scale Projection — Big Hardware

Current benchmark platform: **Intel i5-6600K (4 cores @ 3.5GHz), SATA SSD, Docker PostgreSQL**.

| Component | Current (4C i5) | 128C EPYC + NVMe PG | Driver |
|-----------|----------------|---------------------|--------|
| ECDSA signing (submit) | 4.2s | **0.3s** | 128× parallel cores vs 4 |
| Block creation (50k tx) | 1.5s | **0.5s** | IPC × clock (3×) |
| PG COPY UTXO | 1.5s | **0.15s** | NVMe 3GB/s vs SATA 500MB/s |
| PG block INSERT | 0.5s | **0.05s** | `synchronous_commit=off` |
| MCMC + prototype | 0.08s | **0.03s** | IPC |
| PG transaction commit | 0.5s | **0.05s** | NVMe + tuning |
| Other overhead | 1.5s | **0.5s** | IPC |
| **Total wall** | **10.3s** | **~1.6s** | **6.4×** |

| Metric | Current | 128C EPYC + NVMe |
|--------|---------|-------------------|
| **Single-batch TPS** | **4,873** | **~31,000** |
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

## Comparison

| Platform | tx/s | Notes |
|----------|------|-------|
| Ethereum L1 | 15-30 | Global PoS consensus |
| Bitcoin | 7 | PoW, 10 min blocks |
| Solana | 2,000-3,000 | Validator PoS, 400ms slots |
| **Our benchmark (4C i5, Docker PG)** | **4,873** | Full PoS: 32 validators, Casper, slot tick |
| **Our projection (128C EPYC + NVMe)** | **~40,000** | Pipelined, PG tuned, 32 PoS validators |
| **Architectural ceiling** | **~80,000** | Single-pipeline MCMC limit |
| Ethereum L2 | 2,000-4,000 | Centralized sequencer |
| Visa | 1,700-24,000 | Global payment network |

## Running

```bash
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
