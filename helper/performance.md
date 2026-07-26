# Performance Benchmark

## Overview

TPS benchmark using `MaxTpsBenchmark` — 5000 transactions submitted via in-process
`mempoolService.submitTransaction`, drained into parallel blocks, and confirmed via
MCMC.  Runs on a single node with PostgreSQL in Docker.

## Hardware

| Component | Detail |
|-----------|--------|
| CPU | Intel Core i5-6600K @ 3.50 GHz, 4 cores / 4 threads |
| RAM | 2 GB JVM heap (-Xmx2g) |
| Storage | Local SSD (single disk) |
| Database | PostgreSQL 16 in Docker, same host |

## Benchmark Flow

```
Pre-generate 5000 ML-DSA-only PQKeys  →  helper/testpq.json  (3s)
Load keys from JSON
Fund 5000 wallets with P2PK outputs     (saveBatchBlock)
Pre-create 5000 signed transactions      (off-clock, ML-DSA only)
  ── wallStart ──
Submit 5000 tx to mempool               (20 parallel clients)
  Batch: drain mempool → parallel blocks (BATCH_TX_PER_BLOCK adjustable)
  MCMC update
  Block prototype
  Chain update
  ── wallEnd ──
```

## Best Results

| Metric | Value |
|--------|-------|
| **Throughput** | **895 tx/s** |
| Total tx | 5000 OK, 0 fail |
| Total wall | 5,584 ms |

### Phase Breakdown (7 parallel groups, BATCH=833)

| Phase | Time (ms) | % of wall |
|-------|-----------|-----------|
| Submit (mempool insert) | ~1,600 | 29% |
| Batch (drain → blocks → DB) | ~3,700 | 66% |
| MCMC update | ~150 | 3% |
| Block prototype | ~50 | 1% |
| Chain update | ~10 | <1% |

## Bottleneck: Batch Phase

The batch phase (`batchBlocksFromMempool`) dominates at **~66 % of total wall time**.

```
batchBlocksFromMempool
├── drainAllByType()              ← mempool drain (fast)
└── for each parallel group:
    ├── open DB connection
    ├── create Block object
    ├── for each tx (×N): block.addTransaction()
    ├── setBlockType()
    └── saveBatchBlock():
        ├── addNonChain():
        │   ├── store.put(block)          ← serialize block bytes + SQL INSERT
        │   ├── cachePutBlock()           ← serialize + Hazelcast IPC
        │   └── solidifyBlock()           ← per-tx: UTXO spend/create, token balance
        ├── commitDatabaseBatchWrite()    ← PostgreSQL COMMIT (fsync)
        └── accumulateBlockFees()
```

### Sub-phase weightings

| Sub-phase | Type | Est. % of batch |
|-----------|------|-----------------|
| `solidifyBlock` (UTXO updates) | CPU + DB writes | ~35 % |
| PostgreSQL COMMIT | I/O (fsync) | ~30 % |
| Block serialization | CPU | ~15 % |
| Hazelcast cache put | CPU + IPC | ~10 % |
| Other | Mixed | ~10 % |

### Parallel group sweep (5000 tx)

| Groups | BATCH | TPS | Total wall (ms) |
|--------|-------|-----|-----------------|
| 1 | 5000 | 494 | 10,104 |
| 2 | 2500 | 708 | 7,058 |
| 3 | 1667 | 672 | 7,430 |
| 4 | 1250 | 740 | 6,754 |
| **5** | **1000** | **851** | **5,873** |
| **7** | **833** | **895** | **5,584** |
| 8 | 714 | 665 | 7,511 |
| 8 | 625 | 826 | 6,047 |
| 10 | 555 | 754 | 6,627 |
| 10 | 500 | 838 | 5,966 |
| 12 | 454 | 812 | 6,151 |

Sweet spot: **5–7 parallel groups**.  Fewer = not enough parallelism to hide I/O wait.
More = CPU contention on 4 cores.

## ML-DSA-only vs Dual Signing

| Metric | Dual (ML-DSA + SLH-DSA) | ML-DSA Only | Speedup |
|--------|--------------------------|-------------|---------|
| Key generation (5000 keys) | 563 s | 3 s | 188× |
| Transaction signing | ~1.5 s / tx | ~0.01 s / tx | ~150× |
| Throughput (200 tx) | 113 tx/s | 409 tx/s | 3.6× |
| Throughput (5000 tx) | timed out | **895 tx/s** | — |
| Quantum security | NIST Level 5 (dual) | NIST Level 5 | same |

ML-DSA-only removes the SLH-DSA hash-based signature (the slowest component) while
keeping the same NIST security level (Category 5).  ML-DSA is lattice-based and
~150× faster for signing on this hardware.

## Constraints

| Resource | Limit | Impact |
|----------|-------|--------|
| **CPU** | 4 cores (no HT) | >7 parallel groups causes contention |
| **DB I/O** | PostgreSQL in Docker, same disk | COMMIT fsync is ~30 % of batch time |
| **Memory** | 2 GB heap | Sufficient for 5000 tx |
| **DAG update** | Serial `updateChain()` | Hard limit per node |

## How to Run

```bash
# Start PostgreSQL + run benchmark
./helper/tpsbenchmark.sh
```

Expect ~30–40 s total runtime (including Spring context startup).
