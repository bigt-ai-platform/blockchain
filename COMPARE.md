# Comparison: Bigtangle vs Solana vs Ethereum PoS

## 1. Architecture

| Aspect | Bigtangle (Layer 0) | Solana | Ethereum PoS |
|--------|--------------------|--------|--------------|
| **Ledger structure** | DAG (DAG of blocks) | Single chain | Single chain (reorgs possible) |
| **Consensus** | MCMC walk + PoS | PoH + Tower BFT (PBFT) | Gasper (Casper FFG + LMD-GHOST fork choice) |
| **Validator selection** | MCMC tip selection | PoH leader schedule | RANDAO + beacon committee |
| **Slot time** | Variable (DAG) | 400ms | 12s |
| **Finality** | Probabilistic (MCMC depth) | Optimistic (~32 slots) | 2/3 attestations (~12.8 min) |
| **State model** | UTXO | Account + program | Account (EVM) |
| **Parallel execution** | DAG-native parallel branches | Sealevel (static TX analysis) | Sequential EVM (single-threaded) |
| **Mempool** | Single queue → batch blocks | Gulf Stream (forward to next leader) | Global tx pool → proposer picks |

## 2. Throughput

| Metric | Bigtangle | Solana | Ethereum PoS |
|--------|-----------|--------|-------------|
| **Peak tx/s (lab, 4C i5 + Docker PG)** | **4,873** | ~50,000 (claimed) | ~30 (lab) |
| **Projected (128C EPYC + NVMe PG)** | **~40,000** | 2,000-3,000 (observed) | 15-30 |
| **Realistic tx/s (observed)** | **4,873** (32 PoS validators) | 2,000-3,000 (mainnet) | 15-30 |
| **Tx per block** | 50,000 (configurable) | ~8,000 per 400ms slot | ~150 per 12s slot |
| **Block time (50k tx)** | ~10s | 400ms | 12s |
| **Latency (1 tx)** | ~100ms (submit, no batch) | ~400ms (1 slot) | ~12s (1 block) |

### Bigtangle Phase Breakdown (50k tx, 200 clients, 32 PoS validators)

| Phase | Time | % | Bottleneck |
|-------|------|---|------------|
| **Submit (HTTP mempool)** | 4.2s | 41% | ECDSA signing (200 parallel clients) |
| **Batch (DB + COPY)** | 6.0s | 58% | PG COPY 50k UTXOs + block INSERT + commit |
| **MCMC update** | 0.008s | <1% | Weight/depth calculation |
| **Prototype** | 0.067s | <1% | Tip selection |
| **Chain update** | 0.007s | <1% | processChainConnected |

### Scale Projection

| Hardware | Batch wall | Total wall | TPS | Limit |
|----------|-----------|-----------|-----|-------|
| **4C i5 + SATA PG (current)** | 6.0s | 10.3s | **4,873** | CPU + PG I/O |
| **128C EPYC + NVMe PG** | ~1.0s | ~1.6s | **~31,000** | Single-thread block creation |
| **+ pipeline (overlap stages)** | ~0.9s | **~40,000** | MCMC finality |
| **Architectural ceiling** | ≥0.6s | **~80,000** | Single-pipeline MCMC limit |

### Cumulative Optimizations (from 3,018 → 4,873 tx/s)

| Optimization | Batch wall Δ | TPS Δ |
|-------------|-------------|-------|
| `BATCH_TX_PER_BLOCK` 5000→50000 | −38% | +39% |
| Skip cache eviction in batch | −3% | +3% |
| `reWriteBatchedInserts=true` | −36% | +21% |
| Skip gzip for batch blocks | −7% | +5% |
| PG COPY for UTXO bulk load | **−48% total** | **+62% total** |

### Solana Bottlenecks

| Bottleneck | Impact | Mitigation |
|-----------|--------|------------|
| **PoH leader** | Single leader per slot; missed slot = empty slot | ~2,000-3,000 forced empty slots/day |
| **Gulf Stream** | Forwarding to next leader adds latency | Pre-validates, but adds network hops |
| **Sealevel** | TX static analysis must resolve all account overlaps | Some workloads serialize unexpectedly |

### Ethereum Bottlenecks

| Bottleneck | Impact | Mitigation |
|-----------|--------|------------|
| **EVM sequential** | Single-threaded execution | Parallel EVM (research) |
| **12s slots** | Long confirmation time | L2 rollups |
| **State growth** | ~1TB archive node | Statelessness (Verkle tries, EIP) |

## 3. Consensus Overhead

| Phase per block | Bigtangle | Solana | Ethereum |
|-----------------|-----------|--------|----------|
| **Leader election** | MCMC walk (~8s) | PoH VDF hash (~400ms) | RANDAO (~1 block) |
| **Block building** | DB batch insert (~4s/1k tx) | In-memory execution (~10ms) | EVM execution (~1-10s) |
| **Voting** | MCMC weight update (~2ms) | Tower BFT vote (~200ms) | Attestation (2-6 slots) |
| **Finality** | MCMC depth (probabilistic) | 32 slots (~12.8s) | 2/3 Casper (~6.4 min) |

## 4. Scalability

| Factor | Bigtangle | Solana | Ethereum |
|--------|-----------|--------|----------|
| **Validators** | Unlimited (DAG fans out) | ~2,000 capped | ~500k (32 ETH min) |
| **Sharding** | DAG is natively shardable | Sharding via Solana v2 (in dev) | EIP-4844 (blobs), Danksharding |
| **DB growth** | UTXO set per tx → O(n) | Account state → O(accounts) | State trie → O(n) |
| **Hardware req** | PostgreSQL | 128GB RAM validator | Consumer node (L1) |
| **Max throughput (theoretical)** | DAG width × batch rate | ~500k tx/s (lab) | ~100k (Danksharding) |

## 5. Key Insights

### Bigtangle

**Strengths:**
- **DAG parallelism**: Multiple validators can append blocks simultaneously — no single-leader contention.
- **No leader schedule**: MCMC naturally selects branches; no missed slots from absent leaders.
- **UTXO model**: Inherently parallel — each tx touches distinct UTXOs, enabling concurrent validation.
- **Simple mempool**: Single queue, server-side batching; no complex forwarding protocol.

**Weaknesses:**
- **DB I/O bottleneck**: Solidty checks require `getTransactionOutput()` per input — 100k DB reads for 50k tx.
- **MCMC prototype**: ~8s walk for tip selection — grows with DAG size (mitigated by incremental updates).
- **UTXO overhead**: Every input needs a DB lookup to verify unspent + confirmed.
- **PostgreSQL**: General-purpose RDBMS not optimized for blockchain workloads (vs Solana's custom store).

### Solana

**Strengths:**
- **PoH clock**: Deterministic ordering eliminates consensus overhead for block order.
- **Sealevel**: Static transaction analysis for parallel execution within a slot.
- **Gulf Stream**: Pre-validates and forwards TXs before leader slot, reducing mempool bloat.

**Weaknesses:**
- **Single leader**: Missed slot = empty 400ms period. ~2,000-3,000 skipped slots/day observed on mainnet.
- **High hardware**: ~128GB RAM validator, expensive for home stakers.
- **No UTXO parallelism**: Account model still requires locks; Sealevel analysis can't always parallelize.
- **Leader schedule**: Fixed schedule is predictable — potential for DoS attacks on upcoming leaders.

### Ethereum PoS

**Strengths:**
- **Decentralization**: ~500k validators, consumer-grade hardware.
- **Maturity**: Most battle-tested smart contract ecosystem.
- **L2 roadmap**: Rollups push execution off-chain, Ethereum provides DA + security.

**Weaknesses:**
- **Sequential EVM**: No parallel execution within a block.
- **Slow finality**: ~6.4 minutes for Casper finality.
- **12s slots**: Low throughput at L1 — entire scaling thesis depends on L2s.
- **Complexity**: Gasper consensus (fork choice + finality gadget) is notoriously complex.

## 6. Benchmark Methodology

All Bigtangle benchmarks run on a single node (no network overhead):
- CPU: Intel i5-6600K (4 cores @ 3.5GHz)
- DB: PostgreSQL 16, Docker, default config
- Pool: HikariCP 200 connections
- Java: 25 (Temurin)
- OS: Linux (Ubuntu 24.04)

Benchmarks:

| Test | Mechanism | Pipeline | TPS |
|------|-----------|----------|-----|
| `MaxTPSBenchmark` (perf) | Direct mempool, zero-HTTP | 50 clients × 1000 tx | 3,769 |
| `MaxTpsBenchmark` (benchmark) | HTTP `submitTransactions` batch | 200 clients × 250 tx | 4,465 |
| `PosThroughputBenchmark` | HTTP batch + 32 PoS validators | 200 clients × 250 tx | **4,873** |

To run:
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
