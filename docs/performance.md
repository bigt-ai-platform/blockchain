# Performance

Measured throughput, bottleneck analysis, and the optimizations applied to
raise max TPS. Original numbers are from a single local node
(`helper/fulltest/benchmarklocal.sh`, PostgreSQL 16 in Docker). The
2026-08-27 load-hardening campaign below was re-measured on an 8-core host
and adds multi-node (`helper/prod/testnodes.sh`) data plus two consensus-path
fixes.

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

## 2026-08-27 campaign: cadence is irrelevant; wave dynamics are the ceiling

Re-measured on the 8-core host with `ConfirmedPaymentBenchmark` in-process
(embedded node, `db.port=21532`, `-Xmx12g`), stepping load and slot cadence:

| Tier | Config | Submit TPS | CONFIRMED TPS |
|---|---|---|---|
| A | 20k tx / 40 clients / batch 500 / **12 s slots** | 1,192 tx/s | **666.6 tx/s** |
| B | same but **4 s slots** (3× cadence) | 1,298 tx/s | **675.9 tx/s** (+1%) |
| C | **100k tx** / 64 clients / batch 1000 / 4 s slots | 1,519 tx/s | **584.7 tx/s end-to-end**, up to **1,587 tx/s between confirm waves** |

100% of offered transactions confirmed at every scale
(20000/20000, 99968/99968 — the few missing were duplicate client ids).

Key findings:

- **Slot cadence does not move confirmed TPS.** Tripling slots changed the
  result by ~1%. The "first confirming beacon after assembly" absorbs the
  entire pending reference set in one connect regardless of when it fires.
- Confirmation proceeds as **discrete mega-waves**: e.g. +10,000 outputs in
  6.3 s (1,587 tx/s) inside one beacon connect, then a flat gap while that
  beacon's own connect cycle completes, then the next wave. Latency between
  waves grows with backlog size.
- Postgres peaked at **26 % CPU** — never the constraint. Host CPU did: load
  11–14 on 8 cores, java alone burning 2.3–3.3 cores.

### Multi-node mesh (`testnodes.sh`) and mempool hardening

Using `MeshBench` (benchmark-replica against a running mesh over HTTP,
disposable one-UTXO wallets so no client-side double-spend risk):

| Nodes | Offered burst | Peak confirm slope | End state |
|---|---|---|---|
| 1 | 651 tx/s → MempoolFull | 483 tx/s | (pre-fix run) intake wedge |
| 3 | 512 tx/s → MempoolFull | 618 tx/s | plateau |
| 5 | 416 tx/s → MempoolFull | 479 tx/s | fork churn |

Confirm capacity is **~const 500 tx/s regardless of node count** — matching
the single-node benchmark; extra nodes add resilience, not slot throughput.

Two servercore fixes from this campaign:

1. **Atomic batch admission** (`MempoolService.submitTransactions`): a
   mid-payload failure used to leave the earlier half admitted while clients
   discarded the whole flush, orphaning ~4000 spend-pending claims and
   permanently wedging intake ("already spent by pending" forever). Now any
   admission failure rolls back exactly what this call added.
2. **Failed-save requeue** (`BlockSaveService` batch windows +
   `MempoolService.requeue`): drained transactions whose batch-block save
   failed are requeued with restored conflict bookkeeping instead of being
   silently vaporised.

After deploying both on N=1 under paced load: `Mempool double-spend`
rejections dropped to **zero**, and `MempoolFull` now rejects whole payloads
cleanly once instead of wedging intake forever.

Also: bootstrap warmup default removed (`pos.warmupSlots=0`) — it was 2 s-era
fork insurance; all cold boots at 12 s slots converge cleanly without it.

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
  the backlog without bound. **Fix**: `server.mempoolMaxTx` (default
  4,000; raise via `-Dserver.mempoolMaxTx` for bench runs) — submissions
  beyond capacity are rejected with
  `VerificationException.MempoolFullException` as a whole payload (atomic),
  shedding load at the edge.

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

## Remaining bottlenecks (confirm path) — updated by the 2026-08-27 campaign

The earlier "cadence-bound" hypothesis is **disproven** (tier B). The measured
ceiling mechanism is the beacon propose → reference-sweep → single-threaded
`pos-chain` connect cycle: its cost grows with unconfirmed backlog
(`conflictMs=2279ms` at depth; sweeps ms→seconds), so at high offered rates
the pipeline spends most wall-time inside ever-larger connect phases instead
of confirming more often. During those cycles host CPU saturates (java
2.3–3.3 cores busy in PQ crypto + serialization on an 8-core box) while PG idles.

Ranked leverage, replacing the old list:

1. **Cap reference-set size per beacon** — keep every connect cycle short and
   fixed-cost so confirmation cadence tracks slot cadence exactly.
2. **Parallelize/incrementalize the reference sweep** (`addAllUnconfirmedBlocks`
   `load=` phase dominates at depth) and the single-threaded `pos-chain`
   connect work across independent sibling blocks.
3. **Multi-node remaining gap** (~1/4 of single-node sustained under churn):
   kafka fanout × peer re-validation, plus shared-DB contention when all mesh
   nodes target one postgres. Dedicated DB per node is mandatory for scale.

## Reproducing

### Single node (embedded, ConfirmedPaymentBenchmark)

```bash
# baseline
mvn test -pl layer0-server -am -Dtest=ConfirmedPaymentBenchmark \
  -Ddb.port=21532 -DargLine="-Xmx6g"

# max-scale tier from the 2026-08-27 campaign (100k tx)
mvn test -pl layer0-server -am -Dtest=ConfirmedPaymentBenchmark \
  -Ddb.port=21532 -DPOS_SLOT_INTERVAL_MS=4000 \
  -Dbench.tx=100000 -Dbench.clients=64 -Dbench.batch=1000 \
  -Dserver.mempoolMaxTx=300000 \
  -DargLine="-Xmx12g"
```

The test needs a reachable PostgreSQL (`-Ddb.port`, database `layer0`).

### Multi-node mesh (testnodes.sh + MeshBench)

```bash
NNODES=5 bash helper/prod/testnodes.sh up          # warmup off by default
NNODES=5 IMAGE=ghcr.io/bigt-ai-platform/layer0-server:bench \
  bash helper/prod/testnodes.sh stake

# fund N disposable one-UTXO wallets, then run + confirm-poll
java -cp '<tlclasses>:<exec-jar BOOT-INF>/*' MeshBench fund 10000 25 250 \
     http://127.0.0.1: 3500000000
java -cp ... MeshBench run 10000 25 250 http://127.0.0.1: 3500000000 \
     -Dload.nnodes=5 [-Dbench.rate=150]
python3 scale_confirm.py meshwallets.txt 5        # true confirmed TPS ticker
bash helper/prod/watch_tps.sh                      # chain-wide live ticker
```

`scale_confirm.py`/`watch_tps.sh` count only outputs whose container block is
on the confirmed beacon chain (`outputs ⋈ blocks.confirmed`) — raw row counts
are misleading because unconnected DAG writes land in `outputs` immediately.

Relevant tunables: `-Dbench.tx` / `-Dbench.clients` / `-Dbench.batch` /
`-Dbench.rate` (benchmark), `-Dbatch.minTx` / `-Dbatch.maxBatchAgeMs` /
`-Dbatch.maxConnectQueueDepth` (mempool drain + backpressure),
`-Dpos.slotIntervalMs` / `-Dpos.warmupSlots` (cadence; warmup default 0),
`server.mempoolMaxTx` (admission cap), `-Ddb.pool.mainMaxSize` /
`-Ddb.pool.posMaxSize` (connection pools), `net.bigtangle.pq.dualActivationHeight`
(re-enables mandatory SLH-DSA).

## Future direction: server-side token assembly

Token creation currently requires wallets to assemble a `TOKEN_CREATION`
block skeleton (tip positions via `getTips`, re-parented by the server at
`signToken`). End-state option: wallets submit only a signed token request
payload and the node assembles the block itself once multi-signatures are
complete — deleting wallet-side block handling entirely. Deferred: the
multi-signature gate would move in front of batch assembly, which needs
careful failure semantics so an insufficiently-signed request cannot reject
an unrelated batch block.
