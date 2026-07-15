# Performance

## MaxTPS Benchmark

`MaxTPSBenchmark` measures end-to-end throughput by submitting 50 concurrent clients × 1000 payments each (50,000 total) through the mempool + batch pipeline with zero HTTP overhead.

| Configuration | Throughput | Chain update | Batch wall | Date |
|--------------|-----------|-------------|------------|------|
| All operations enabled | 365 tx/s | 82,998 ms | 20,066 ms | Before optimization |
| Skip `updateTransactionOutputSpendPending` | **573 tx/s** | 57,047 ms | 20,066 ms | +57% |

### Bottlenecks

| Phase | Time | % of wall | Notes |
|-------|------|-----------|-------|
| **Chain update** | ~57s | 65% | `updateUnConfirmedDo` + `processChainConnected` + `updateConfirmed`. Processes the entire DAG (50k blocks). |
| **Batch** | ~20s | 23% | `batchBlocksFromMempool()` creates blocks from mempool txs. DB I/O limited. |
| **Prototype** | ~5s | 6% | `calcNewBlockPrototype` creates new block tip. |
| **ECDSA** | 232s* | — | 50-way parallel, not on critical path (50 clients run concurrently). |
| **Submit** | ~5s | 6% | Non-blocking async submit to mempool. |

### Before PoW Cleanup

Before removing `Block.powEnabled`, three operations were skipped entirely when `powEnabled=false`:
- `updateTransactionOutputSpendPending` — per-block spend state
- `updateUnConfirmedDo` — full unconfirmed DAG scan
- `updateConfirmed` — block confirmation

Skipping these gave ~3000 tx/s. The current 573 tx/s is with `updateTransactionOutputSpendPending` disabled.
Restoring the other two skips with a `posMode` flag would bring throughput back to ~3000 tx/s.

### Running

```bash
mvn test -pl layer0-mcmc -Dtest=MaxTPSBenchmark -DfailIfNoTests=false
```

### Platform

- CPU: 8+ cores recommended (uses 50-thread pool)
- DB: PostgreSQL 16, Docker, default config
- Pool: HikariCP 50 connections (DBStoreConfiguration default)
