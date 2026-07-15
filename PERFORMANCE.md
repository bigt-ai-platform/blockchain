# Performance

## MaxTPS Benchmark

`MaxTPSBenchmark` measures end-to-end throughput by submitting 50 concurrent clients × 1000 payments each (50,000 total) through the mempool + batch pipeline with zero HTTP overhead.

### Results

| Configuration | Throughput | Total wall | Chain update | Batch wall |
|--------------|-----------|-----------|-------------|------------|
| All operations enabled | 365 tx/s | 136,919 ms | 82,998 ms | 20,066 ms |
| Skip `updateTransactionOutputSpendPending` | 573 tx/s | 87,177 ms | 57,047 ms | 20,066 ms |
| Skip all 3 PoW ops (`spendPending` + `unConfirmedDo` + `confirmed`) | **1,364 tx/s** | **36,635 ms** | **7 ms** | 21,014 ms |

### Optimizations Applied

Three PoW-specific operations were removed (safe in PoS mode):

| Operation | Effect | Reason |
|-----------|--------|--------|
| `updateTransactionOutputSpendPending` | UTXO spend-pending flags | Mempool handles duplicate detection in PoS |
| `updateUnConfirmedDo` | Block solidity scan for MCMC | Casper finality replaces reward-chain confirmation |
| `updateConfirmed` | Reward-chain confirmation | Unnecessary — no PoW reward chain |

### Bottlenecks (after optimization)

| Phase | Time | % of wall | Notes |
|-------|------|-----------|-------|
| **Batch** | ~21s | 57% | `batchBlocksFromMempool()` — DB I/O creating blocks |
| **Prototype** | ~11s | 31% | `calcNewBlockPrototype` — tip creation + MCMC update |
| **Submit** | ~4s | 11% | Async submit to mempool |
| **Chain update** | 7ms | 0% | Only `processChainConnected` runs |
| **ECDSA** | 183s* | — | 50-way parallel, not on critical path |

### Real-World Full Node Estimate

The benchmark is a best case: single node, zero network, parallel submit.
A real PoS full node adds overhead:

| Factor | Impact | Reason |
|--------|--------|--------|
| **Block propagation** | −20% | Validators must gossip blocks before attesting |
| **Casper finality** | −30% | 2/3 attestation collection + 2-6 slot delay |
| **Single proposer** | −50% | Only one validator per slot (no parallel batch) |
| **Validator consensus** | −10% | BFT communication overhead with N validators |

**Estimated real-world throughput: ~350–550 tx/s**

### Comparison

| Platform | tx/s | Notes |
|----------|------|-------|
| Ethereum L1 | 15-30 | Global PoS consensus |
| Bitcoin | 7 | PoW, 10 min blocks |
| Solana | 2,000-3,000 | Validator PoS, 400ms slots |
| **Our benchmark** | **1,364** | Single node, MCMC bridge, parallel submit |
| **Our estimate (full node)** | **~450** | With Casper + validator consensus |
| Ethereum L2 (Arbitrum/Optimism) | 2,000-4,000 | Centralized sequencer |
| Visa | 1,700-24,000 | Global payment network |

### Running

```bash
mvn test -pl layer0-mcmc -Dtest=MaxTPSBenchmark -DfailIfNoTests=false
```

### Platform

- CPU: 8+ cores recommended (uses 50-thread pool)
- DB: PostgreSQL 16, Docker, default config
- Pool: HikariCP 50 connections (DBStoreConfiguration default)
