# Performance

Measured throughput, bottleneck analysis, and the optimizations applied to
raise max TPS. Numbers are from this development machine (4 cores) against a
single local node (`helper/fulltest/benchmarklocal.sh`, PostgreSQL 16 in
Docker).

## Current results (ConfirmedPaymentBenchmark)

| Run | Slots | batch.minTx | Submit TPS | CONFIRMED TPS |
|---|---|---|---|---|
| 10,000 tx | 12 s | 3000 | 2,069 tx/s | **554 tx/s** |
| 20,000 tx | 6 s | 5000 | 1,854 tx/s | **626 tx/s** |
| 50,000 tx | 6 s | 10000 | 1,029 tx/s | **468 tx/s avg, ~1,600 tx/s steady-state drain** |

All submitted transactions confirmed on-chain (10000/10000, 20000/20000,
50000/50000). The 50k run's average is dominated by ramp-up; once backlogged,
the confirm pipeline drains at ~900–1,600 tx/s (30,680 tx in 19 s observed).
Run-to-run variance at 50k scale is significant on a shared benchmark
database: as `outputs` grows past ~300k rows across runs, autovacuum lags
and confirm cycles stretch — recreate the database for comparable numbers.

## Bottlenecks found and fixed

### 0. Congestion collapse under overload (fixed)

At 50k pending transactions the system degraded non-linearly: beacon-connect
cycles slowed from ~2 s to 107 s as the unconfirmed DAG grew (only 8
`updateChain` cycles in 421 s), submit collapsed to 418 tx/s, and only
11,457/50,000 tx confirmed. Root causes, both fixed:

- **O(backlog) re-verification at beacon connect.** Batch blocks skip tx
  checks at save time (every tx was already mempool-verified on this node),
  but `solidifyWaiting` re-ran the *full* solidity check — all ~2,000 txs per
  referenced block — on every slot until confirmation. Similarly,
  `checkReferencedBlockRequirements` deserialized every ancestor block per
  required hash.
  **Fix**: `CacheBlockService` now tracks a `txValidated` registry of block
  hashes whose transactions this node has fully verified (local batch saves
  and peer blocks that passed the full check at ingest). `solidifyWaiting`
  short-circuits marked blocks straight to the solidify side effects, and the
  requirements walk uses evaluation-only existence lookups. Purely local
  validation bookkeeping (like the signature cache) — acceptance decisions
  are unchanged; after a restart the backlog is re-verified once.
- **Unbounded mempool.** No admission cap existed, so sustained overload grew
  the backlog without bound. **Fix**: `server.mempoolMaxTx` (default 50,000)
  — submissions beyond capacity are rejected with
  `VerificationException.MempoolFullException`, shedding load at the edge.

Result: the same 50k run now confirms **50,000/50,000** with median
`updateChain` cycles of ~2 s (max 8.6 s) and zero OOM events. Note: the
benchmark JVM needs an explicit heap (`-Xmx4g`) at this scale — the default
Surefire heap OOMs regardless of node tuning.

### 1. SLH-DSA signature verification dominated CPU (fixed)

Every standard transfer key was dual post-quantum (ML-DSA-87 +
SLH-DSA-SHA2-256s), verified per input. Measured cost per verify:

| Algorithm | Cost |
|---|---|
| ML-DSA-87 verify | ~306 µs |
| SLH-DSA-SHA2-256s verify | ~2,016 µs (**87% of the total**) |
| Dual verify per input | ~2,322 µs |

At 4 cores the parallel ceiling was **~545 tx/s from crypto alone**, before
any DB or network work.

**Fix** (`PQScriptUtils.verifyPQ`): suite-gated verification mirroring the
existing proposer-sig pattern — ML-DSA always required; a provided SLH-DSA
signature is always validated; a *missing* one only fails once the dual suite
is activated via `net.bigtangle.pq.dualActivationHeight`. `PQKey.sign(input)`
follows the same governance, so default keys sign ML-only (~8 KB smaller per
input). Explicit `sign(input, true)` still produces dual signatures.

### 2. Every transaction verified twice, no cache (fixed)

The same signatures were verified at mempool ingest
(`MempoolService.verifyTransaction`) and again at block solidity check
(`ServiceBaseCheck` Verifier) — doubling the entire crypto bill.

**Fix**: Bitcoin-style success-only signature cache in `PQScriptUtils`
(100k entries, keyed by pubkey+signature+sighash+policy). Re-verification is
now a cache hit: **~13 µs vs ~481 µs cold (~36×)**.

### 3. Parsed public keys rebuilt per verify (fixed)

`BcPQSignatureProvider` deserialized ~2.5 KB of ML-DSA public-key vectors on
every verification.

**Fix**: memoize parsed `MLDSAPublicKeyParameters` / `SLHDSAPublicKeyParameters`
(10k-entry caches).

### 4. Script-verifier pool churn (fixed)

`ServiceBaseCheck` instances are created per call and used to
`shutdownNow()` their executor after every block, then lazily recreate it.

**Fix**: JVM-lifetime static pool sized `availableProcessors()`.

### 5. Synchronous Kafka on the ingest path (fixed)

`KafkaMessageProducer.send()` blocked the request thread on
`future.get()` with `acks=all`.

**Fix**: fire-and-forget send with callback logging; delivery stays
`acks=all` + retries on the producer IO thread.

### Measured effect of the fixes

| Metric | Before | After |
|---|---|---|
| Parallel PQ verify ceiling (4 cores) | ~545 tx/s | **~4,700–5,500 tx/s** (ML-only, cold) |
| Block-check re-verification | full crypto cost again | ~13–29 µs cached |
| Tx size (default keys) | +7.9 KB SLH sig/input | removed |
| End-to-end submit TPS | ~545-bound | 1,029–2,069 tx/s |
| 50k-tx run | collapsed: 11.5k/50k confirmed, ~170 tx/s peak | **50k/50k confirmed**, ~1,600 tx/s drain |

## Remaining bottlenecks (confirm path)

With submit fast and the collapse fixed, the confirm path is cadence-bound:
`updateChain` cycles run serialized on the single `pos-chain` thread behind
the global `"chain"` DB lock (`BlockStoreService.updateChainConnected`),
median ~2 s each at 50k scale (max 8.6 s). The DB mechanics themselves have
large headroom — `confirmBlocksSorted` processes ~20k tx/s once a cycle runs.

Candidate next steps, in order:

1. **Overlap confirm cycles with slot cadence** — pipeline confirmation of
   batch *N* against beacon *N+1* instead of strict serialization on
   `pos-chain`.
2. **Shrink beacon `solidify`** — re-verifies proposer signatures and
   attestations per referenced block; scales with ref count.
3. **Diagnose submit-side drop at scale** (2,069 → 1,029 tx/s from 10k to
   50k clients/tx) — likely Hikari/DB contention between ingest and confirm.

## Reproducing

```bash
# single-server benchmark (boots DB, builds deps)
helper/fulltest/benchmarklocal.sh -t 20000 -m 5000 -S 6000

# 50k scale needs a bigger benchmark JVM heap:
mvn test -pl layer0-server -Dtest=ConfirmedPaymentBenchmark \
  -Dbench.tx=50000 -Dbench.clients=40 -Dbatch.minTx=10000 \
  -Dpos.slotIntervalMs=6000 \
  -DargLine="-Xmx4g --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED"
```

Relevant tunables: `-Dbench.tx` / `-Dbench.clients` / `-Dbench.batch`
(benchmark), `-Dbatch.minTx` / `-Dbatch.maxBatchAgeMs` (mempool drain),
`-Dpos.slotIntervalMs` (slot cadence), `server.mempoolMaxTx` (mempool
admission cap), `net.bigtangle.pq.dualActivationHeight` (re-enables mandatory
SLH-DSA).

## Future direction: server-side token assembly

Token creation currently requires wallets to assemble a `TOKEN_CREATION`
block skeleton (tip positions via `getTips`, re-parented by the server at
`signToken`). End-state option: wallets submit only a signed token request
payload and the node assembles the block itself once multi-signatures are
complete — deleting wallet-side block handling entirely. Deferred: the
multi-signature gate would move in front of batch assembly, which needs
careful failure semantics so an insufficiently-signed request cannot reject
an unrelated batch block.
