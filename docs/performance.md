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
| 200,000 tx (2026-08-28 max-resource, 8-core host) | 1 s | 3000 | 1,626 tx/s | 1,094.8 tx/s |
| **200,000 tx (2026-08-28 Aliyun `g8i.16xlarge`, 64 vCPU)** | 1 s | 3000 | **13,880 tx/s** | **2,756.5 tx/s** ✔ best |

All submitted transactions confirmed on-chain (10000/10000, 20000/20000,
50000/50000, 200000/200000). The 50k run's average is dominated by ramp-up;
once backlogged, the confirm pipeline drains at ~900–1,600 tx/s (30,680 tx
in 19 s observed). Run-to-run variance at 50k scale is significant on a
shared benchmark database: as `outputs` grows past ~300k rows across runs,
autovacuum lags and confirm cycles stretch — recreate the database for
comparable numbers.

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
### Turning the wave burst into sustained TPS

Root cause of the inter-wave dead time at depth: `connectRewardBlock` cost
per beacon grows with referenced-row volume (measured 4.3–9.4 s connecting
1000-tx batch blocks at 100k scale) — few huge beacons = long dead gaps.
Countermeasure: **many small beacons instead of few huge ones**, tuning
`batch.txPerBlock` down while raising slot cadence:

| Config (60–100k tx, embedded node) | Submit | **CONFIRMED sustained** |
|---|---|---|
| tier C: txPerBlock=2000 @4s | 1519 tx/s | 584.7 tx/s |
| + sweep/ref bounds (`pos.maxSweepCandidates`, `pos.maxNewRefsPerBeacon`) | same | 595.3 tx/s (bounds never engaged during drain — sweep wasn't the drain-phase bottleneck there) |
| **tier E: txPerBlock=500 @4s** | 1511 tx/s | **726.8 tx/s** ✔ best stable |
| tier F: txPerBlock=500 @**2s** | 1466 tx/s | peak **749.6 tx/s** @79 s, then host-saturation decay (avg drops) |

Conclusion: raw write capacity already exceeds 1,500 tx/s inside a single
connect wave; converting it to *constant* throughput requires shrinking
per-beacon connect volume (`batch.txPerBlock≈500`) and keeping slot cadence
moderate (4 s) so beacons flow densely without CPU starvation. Peak observed
**749.6 tx/s**; stable-best configured **726.8 tx/s** on this host.

### Depth-validation + production-hardening follow-up (same day)

100k-tx run ON THE PARALLEL-SOLIDIFY BUILD: submit 1,501 tx/s, peak-sustained
**720.7 tx/s**, but end-to-end decayed to 176 tx/s average over a 566 s wall
with 72 unconfirmed at timeout — reproducing the documented shared-database
variance (autovacuum lag behind ~400k-row `outputs` after several tier runs
on one `layer0` DB). Rule stands: recreate the database between campaigns
before comparing numbers.

Hardening shipped:
- `/fundAddresses` is now loopback-only unless `server.faucetPublic=true`
  (faucet mints spendable UTXOs — a misconfigured exposed node was an
  inflation vector).
- Actuator exposure restricted from `*` to `health,info,prometheus`;
  loggers/threaddump writes are no longer reachable on the API port
  (set `-Dmanagement.endpoints.web.exposure.include=*` when debugging).

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

## 2026-08-28 campaign: max-resource single-node sweep

Swept to the machine limit on the 8-core / 31 GB host (JDK 25, `-Xmx16–20g`,
dedicated `layer0` DB on the dockerized PostgreSQL, `bt4` mesh stopped to
free resources). Three configs probe the heap/GC wall and the per-beacon
critical path:

| Run | Load | txPerBlock | Slots | Heap | Result |
|---|---|---|---|---|---|
| 1 | 200k tx / 32 clients / batch 250 | 2000 | 1 s | 16 g | Submit 1,626 tx/s → **CONFIRMED 1,094.8 tx/s** (200k/200k in 182.7 s) ✔ |
| 2 | 400k tx / 48 clients | 10000 | 2 s | 20 g | **Stalled at 145,499/399,984**: heap OOM killed Hazelcast, chain froze |
| 3 | 300k tx / 40 clients | 5000 | 2 s | 18 g | Submit 1,436 tx/s → **peak 999.4 tx/s** (160,755/165,750; mempool rejects lost 44% of offered load) |

Run 1 is the single-node best on THIS host (1,094.8 tx/s confirmed, all 200k
on-chain; superseded by the Aliyun 64-core run below). Details and the failure
modes:

- **Submit ingest caps at ~1,600 tx/s** regardless of client count
  (32 vs 48 identical): server-side mempool admission (parse + double-spend
  checks per tx) is the ingest ceiling.
- **Serial per-beacon path dominates the wall.** With 1 s slots the ticker
  produced 120 proposals but only 43 connected (**64 % of slots skipped**):
  proposal reference sweep averaged **2.2 s** (max 6.1 s, DB scan of
  unconfirmed blocks each slot) and beacon connect averaged **5.2 s**
  (max 9.2 s; solidity + conflict + confirm). Bigger slots (2 s) change
  nothing — the cycle costs 4–15 s regardless.
- **Heap is the hard wall at scale.** 10k-tx blocks (run 2) pinned the 20 g
  heap: 527 GC pauses totaling 36.3 s (13 % of wall, max pause 984 ms,
  40 evacuation failures) → `OutOfMemoryError` inside `HazelcastCache.put`
  → `HazelcastInstanceNotActiveException` on every subsequent beacon
  proposal → proposals carried empty reference sets → confirmation stopped
  forever. The Hazelcast-backed Spring caches (`CacheBlockService` evicts)
  are a single point of failure: cache outage = beacon reference collection
  outage = chain stall, with no recovery path.
- **`MempoolService.pruneSpentOutpoints` storms the DB at backlog depth.**
  Every 60 s it walks ALL unconfirmed outpoints with one
  `getTransactionOutput` query each — O(backlog) sequential round-trips that
  saturate PostgreSQL for minutes and starve the beacon pipeline (observed
  at ~600k outstanding outpoints in run 2). Needs batched/`IN`-clause
  lookup or incremental pruning.
- **Mempool admission kills bench clients.** At `mempoolMaxTx` too small for
  the burst (run 3), `MempoolFullException` ends the client thread and its
  remaining slice is silently lost (165,750/300,000 submitted); the harness
  should retry-with-backoff instead of dying.
- **Confirm write path is not the limit**: inside one beacon connect the
  confirm step moved 110,000 txs in 12.3 s (~9k tx/s) in run 2 — the
  bottleneck is the fixed per-beacon cycle cost, not row writes.

Max confirmed TPS on this host: **~1,100 tx/s** (run 1). The chain-side
capacity is higher (~9k tx/s confirm writes) but unreachable while the
propose→sweep→connect cycle stays serial and per-beacon cost grows with
backlog.

### Shorter-epoch + optimistic-finality rollout (implemented)

Finality is FFG-shaped: ~2 epochs × `slotsPerEpoch` × slot interval
(32 × 12 s → 12.8 min). Implemented changes:

- **`slotsPerEpoch` is now a per-network consensus parameter**
  (`NetworkParameters.getSlotsPerEpoch()`): every `slot / 32` site in the
  epoch arithmetic (SlotService helpers, CasperService checkpoints/lookback,
  RANDAO mix epochs, validator snapshots, ServiceBaseCheck attestation
  sanity, BlockStoreService boundary duties) was parameterized. The
  attestation lookback stays 10 EPOCHS (80 slots on mainnet) — epoch-denoted
  windows keep their semantics, not their slot counts.
- **Mainnet ships 8-slot epochs** → finality ≈ 2 × 8 × 12 s = **3.2 min**
  (64 s at 4 s slots). Test net keeps 32 until its suites migrate. Consensus
  upgrade: all nodes must ship the same value in the same release —
  `att.getEpoch() == slot / slotsPerEpoch` is verified on-chain, so a
  mismatched node fragments quorum instead of following the chain.
- **`getOptimisticFinality` endpoint** (Layer-0, advisory): the confirmed
  head's vote weight vs total active stake (`supermajority` flag) plus the
  justified/finalized checkpoints — Solana-style fast-approval UX for
  exchanges/bridges without touching the FFG finality rules.

### Epoch-length-safe safety parameters (implemented)

Epoch-denominated safety constants were re-derived from slotsPerEpoch so
their wall-time semantics survive shorter epochs (and shorter slots). All
canonical values are slot-based; the epoch-denominated derivations are pure
functions of the per-net `slotsPerEpoch`:

| Parameter | Canonical | Derived value (32 / 8 / 4-slot epochs) | Why |
|---|---|---|---|
| Inactivity-leak grace (`inactivityPenaltyThresholdEpochs`) | 128 slots | 4 / 16 / 32 epochs | a hardcoded 4 epochs at 4-slot would start draining offline stake after ~96 s of stall |
| Leak quadratic divisor | normalized to canonical 32-slot epochs (`delay × slotsPerEpoch / 32`) | identical wall-time drain shape at any epoch length | raw `delay²` drains 4× faster per wall-minute at 8-slot epochs |
| Justified-switch window (`safeSlotsToUpdateJustified`) | 25 % of an epoch (min 1 slot) | 8 / 2 / 1 slots | with a hardcoded 8 slots, `slotsPerEpoch < 8` keeps the switch window open for the WHOLE epoch and silently disables the bouncing-attack defense |
| Withdrawal delay (`withdrawalDelayEpochs`) | 8192 slots ≈ 7.6 h @ 12 s | 256 / 1024 / 2048 epochs | a hardcoded 256 epochs at 4-slot would let a slashed/exiting validator unbond in ~1.7 h |

With these derivations, 4-slot epochs (48 s finality at 6 s slots) keep the
same operator-stake protections as the original 32-slot design; epoch-relative
parameters (RANDAO 2-epoch lag, `ATTEST_MAX_STALE_EPOCHS`,
`MAX_SEED_LOOKAHEAD`) were already epoch-relative and scale automatically.

Validation: `SlotEpochParameterTest` (epoch arithmetic + the derivation
table above), `PosConsensusHardeningTest`, the PoSTest suite — 59/59 green,
including the previously order-dependent `testInactivityLeakRestoresFinality`
(rewritten to finalize a live checkpoint anchor first — the genesis
checkpoint is pinned at chainlength 0 and can never pass the store-view
finalized lookup). Full `ConfirmedPaymentBenchmark` smoke passes end-to-end.

### Mesh churn campaign (leave/rejoin/new-node under load) — 2026-08-28

`testnodes.sh` 3- and 5-node meshes (local `phase4` image, 12 s slots, 8-slot
epochs, `server.mempoolMaxTx=40000`, `batch.txPerBlock=4000`), 260k funded
wallets, MeshBench phases with a signed leave / fresh rejoin / brand-new
node issued MID-LOAD:

- **Stable envelope confirmed**: while all nodes served, beacon cadence was
  exactly 12 s, finality tracked ~1.3–2 epochs behind the head, and one
  finalized root held across the mesh. Max clean offered rate 250 tx/s
  (45k/45k accepted, 0 dropped).
- **Confirm drain is wave-shaped**: flat gaps then bursts of ~450 tx/s;
  per-beacon ceiling ≈ 3–4 batch blocks × `batch.txPerBlock` — the
  single-builder gate drains each node's mempool only on its own proposer
  turn (every Nth slot).
- **Fixes verified live**: the epoch-safe safety derivations behaved (no
  leak/switch-window misbehavior), raised mempool + retry/backoff in the
  driver absorbed rejection storms without client death, and the formal
  leave completed under load without a finality stall.
- **Sixth bottleneck pinned (connection leak)**: under sustained rejection
  pressure the submit path leaks store connections (459 HikariPool leak /
  timeout events on one node; leak-detection fired exactly when the node
  went unresponsive) → pool exhaustion → every HTTP request blocks → API
  dead while background threads tick → missed proposer slots → 1-vote-per-
  branch fork tie → confirmation stall. This, not the (now-wrapped) cache,
  is the remaining max-load stability blocker. Next lever: find and close
  the leak in the admission path (`MempoolService.submitTransactions` et
  al.), then re-run this campaign.

## 2026-08-28 Aliyun campaign: 2,756 tx/s confirmed on 64 cores

`alitest.sh` (repo root) automates the whole cycle: create the best Aliyun ECS
instance (`ecs.g8i.16xlarge`, 64 vCPU / 256 GiB, cn-hangzhou, postpaid, 200G
ESSD) → provision JDK 25.0.4.1 + PostgreSQL 16 + maven → rsync the workspace →
build → run `ConfirmedPaymentBenchmark` with the best-known max-resource
config → parse Submit/CONFIRMED TPS → shut the instance down when finished
(KEEP/RELEASE variants). Re-running on a stopped instance
(`--instance-id … --key-file …`) reuses the warm `~/.m2`/JDK and finishes in
~7 min instead of ~35.

| Host | Config | Submit | **CONFIRMED** |
|---|---|---|---|
| 8-core / 31 GB (sweep run 1 above) | 200k tx / 32 clients / batch 250 / txPerBlock 2000 / 1 s slots / heap 16 g | 1,626 tx/s | 1,094.8 tx/s |
| **Aliyun `g8i.16xlarge` (64 vCPU / 256 GiB)** | same, heap 64 g, **mempoolMaxTx 200000** | **13,880 tx/s** | **2,756.5 tx/s** ✔ new best |

200,000/200,000 confirmed on-chain (fresh `layer0` DB per run, JDK 25.0.4.1,
PostgreSQL 16). Submit completed in 14.4 s; the entire 200k backlog confirmed
within 58 s of submit completion. Full log:
`logs/alitest-20260828-192001.bench.log`.

Findings:

- **First run on the big instance aborted at 11,750/200,000** with
  `MempoolFullException`: 32 clients × 250-tx batches offer 8,000 tx instantly,
  the default `mempoolMaxTx=4000` fills mid-burst, and the rejected payload
  kills the client threads (the documented failure mode above) — the run
  "finishes" with 94% of the load never offered and 126.5 tx/s confirmed.
  `server.mempoolMaxTx` must cover the whole burst at this scale (set to
  200,000); the harness retry-with-backoff fix remains outstanding.
- **Submit ingest scales with cores**: 1,626 → 13,880 tx/s (~8.5× on 8× the
  cores). The mempool admission path (parse + double-spend checks) parallelizes
  well and was never DB-bound.
- **Confirmed TPS 1,094.8 → 2,756.5 (~2.5×)**: the serial per-beacon
  propose→sweep→connect cycle still bounds the drain — it speeds up with more
  cores but remains the ceiling, exactly as the sweep concluded. The confirm
  waves now move ~2.8k tx/s end-to-end instead of ~1.1k.

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

## Remaining bottlenecks (confirm path) — updated by the 2026-08-28 campaign

The earlier "cadence-bound" hypothesis is **disproven** (tier B, and again
by the 08-28 run 1). The measured ceiling mechanism is the beacon propose →
reference-sweep → single-threaded `pos-chain` connect cycle: its cost grows
with unconfirmed backlog (`conflictMs=2279ms` at depth; sweeps ms→seconds),
so at high offered rates the pipeline spends most wall-time inside
ever-larger connect phases instead of confirming more often. During those
cycles host CPU saturates (java 2.3–3.3 cores busy in PQ crypto +
serialization on an 8-core box) while PG idles.

Ranked leverage, replacing the old list:

1. **Decouple cache availability from consensus.** The Hazelcast-backed
   Spring caches must never fail the beacon reference sweep: at heap
   exhaustion in run 2 a single `OutOfMemoryError` in `HazelcastCache.put`
   made every later proposal carry an empty reference set and froze
   confirmation permanently. Cache outage must degrade to uncached
   (slower) reads, not to lost references.
2. **Cap reference-set size per beacon** — keep every connect cycle short
   and fixed-cost so confirmation cadence tracks slot cadence exactly.
3. **Parallelize/incrementalize the reference sweep** (`addAllUnconfirmedBlocks`
   `load=` phase dominates at depth) and the single-threaded `pos-chain`
   connect work across independent sibling blocks.
4. **Batch `pruneSpentOutpoints`** — replace the per-outpoint
   `getTransactionOutput` walk (one query per unconfirmed input, every 60 s)
   with a single `IN`-list/batched lookup; at 400k-tx scale it becomes a
   minutes-long DB storm that starves the beacon pipeline.
5. **Raise the ingest ceiling (~1,600 tx/s)** — mempool admission and
   double-spend checks are the submit cap; look at parallel admission or
   cheaper conflict indexing if the confirm path ever exceeds it.
6. **Multi-node remaining gap** (~1/4 of single-node sustained under churn):
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

# 8-core max-resource sweep run 1 (CONFIRMED 1,094.8 tx/s)
mvn test -pl layer0-server -am -Dtest=ConfirmedPaymentBenchmark \
  -Ddb.port=21532 -Ddb.dbName=layer0 \
  -Dbench.tx=200000 -Dbench.clients=32 -Dbench.batch=250 \
  -Dbatch.minTx=3000 -Dbatch.maxBatchAgeMs=1500 -Dpos.slotIntervalMs=1000 \
  -Dserver.mempoolMaxTx=100000 \
  -Dperf.confirmLogMinTx=50 -Dperf.sweepLogMinBlocks=10 -Dperf.connectLogMinRefs=10 \
  -DargLine="-Xmx16g"

# current best (2026-08-28 Aliyun g8i.16xlarge, 64 vCPU: CONFIRMED 2,756.5 tx/s)
# — ./alitest.sh provisions the instance and runs exactly this
mvn test -pl layer0-server -am -Dtest=ConfirmedPaymentBenchmark \
  -Ddb.port=5432 -Ddb.dbName=layer0 \
  -Dbench.tx=200000 -Dbench.clients=32 -Dbench.batch=250 \
  -Dbatch.minTx=3000 -Dbatch.maxBatchAgeMs=1500 -Dpos.slotIntervalMs=1000 \
  -Dbatch.txPerBlock=2000 -Dserver.mempoolMaxTx=200000 \
  -DargLine="-Xmx64g"
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
