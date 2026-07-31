# Technical Design

## Confirmation

Confirmation is handled **solely by MCMC**, not by the block save path. When a block is saved via `saveBlock` or `saveBlockPermissive`, UTXOs are created with `confirmed=false`. MCMC's reward block processing calls `updateAllTransactionOutputsConfirmed(true)` to mark them spendable.

### Key principle

`calculateAllSpendCandidates` filters on `confirmed=true`. Unconfirmed UTXOs are invisible to wallet operations until MCMC processes a reward block that references the block and confirms its outputs.

### Code

```java
// ServiceBaseConnect.connectUTXOs — creates UTXOs with confirmed=false
UTXO newOut = new UTXO(..., false, false, false, ...);
//                           ^spent ^confirmed ^spendPending

// Only confirmed=true UTXOs are spendable
// OutputService.java
if (output.isSpent() || !output.isConfirmed())
    continue;

// MCMC confirms them via reward chain
// ServiceBaseConfirmation.confirmBlockTransactionWithType
blockStore.updateAllTransactionOutputsConfirmed(block.getBlock().getHash(), confirmation);
```

## Milestone = Reward Chain Length

Reward blocks form a chain where each points to the previous via `prevRewardHash`. The `milestone` field equals the reward chain length. Block N in the reward chain has milestone N.

### Implications

1. **hasSpentInputs with checkMilestone=true**: If a block was already confirmed by milestone N, it cannot be referenced by milestone N+1. The verification removes already-confirmed blocks rather than throwing.

2. **MCMC creates reward blocks faster than UpdateChain processes them**. The queue builds up; each reward block references only unprocessed blocks. Already-confirmed blocks are skipped.

### Code

```java
// Reward block creation — milestone = chain length
// ServiceVerifyReward.verifyRewardChainConfirmReferenced
long milestoneNumber = store.getRewardChainLength(newMilestoneBlock.getHash());

// Conflict check — skip already-confirmed blocks
// ServiceVerifyReward.verifyRewardChainConfirmReferenced
if (hasSpentInputs(allApprovedNewBlocks, true, store)) {
    allApprovedNewBlocks.removeIf(bw -> bw.getBlockEvaluation().getMilestone() > 0);
    if (allApprovedNewBlocks.size() <= 1) return; // nothing new to confirm
}
```

## saveBlockPermissive

Used by `MultiSignServiceCreate.signTokenAndSaveBlock` for token creation blocks. The block has already passed `checkFullTokenSolidity` but strict predecessor validation in `addBlock` would reject it (the prototype block's predecessors may not be fully stored).

### What it does

1. `addNonChain(block, true, store, true, true)` — stores block with lenient validation (allows unsolid, allows missing predecessors, batch mode)
2. Sets `solid=2`, `weight=1`, `depth=1` — required because MCMC's `getSolidBlockTopologyInInterval` filters on `solid=2`, and weight/depth make the block a valid tip candidate
3. `accumulateBlockFees` + `broadcastBlock`

### What it does NOT do

- **No immediate UTXO confirmation** — MCMC handles this via reward blocks
- **No TipsQueue insertion** — MCMC inserts its own prototypes via `calcNewBlockPrototype`
- **No connectTypeSpecificUTXOs** — handled by MCMC's solidify path

### Why solid=2 and weight/depth are needed

```java
// DatabaseFullBlockStoreBase.java
final String SELECT_SOLID_BLOCK_TOPOLOGY_INTERVAL_SQL =
    "SELECT ... FROM blocks WHERE height > ? AND height <= ? AND solid = 2";
```

Without `solid=2`, MCMC cannot find the block. Without `weight=1`/`depth=1`, the block has no MCMC weight and won't be selected as a tip by `TipsService.getValidatedBlockPair`.

## MCMCService

The `layer0-mcmc` module's MCMC service runs scheduled updates that:

1. **updateWeightAndDepth** — processes blocks with `solid=2` in the height interval, builds approver graph, sets weight/depth
2. **updateRating** — runs MCMC random walks from entry points to rank tips
3. **calcNewBlockPrototype** — creates a new tip from the best pair, inserts into TipsQueue
4. **RewardService.createReward** — creates a BEACON reward block with `collectedBlocks` from `dagBlockHashesFrom`

### NPE fix

`subUpdateRating` accessed `approvers.get(currentBlock.getBlockHash())` without null check, which threw when a block was already processed (approvers entry removed). Fixed by guarding all `approvers.get()` calls with null checks.

## UpdateChainService

Scheduled service in `bigtangle-servercore` that runs every 10 seconds (when `initsync=true`):

```java
@Scheduled(fixedDelayString = "10000")
public void updateChain() {
    if (scheduleConfiguration.isMilestone_active() && serverConfiguration.checkService()) {
        blockGraph.updateChain();
    }
}
```

### Process

1. `updateChainConnected()` — acquires lock, calls `processChainConnected`
2. `processChainConnected()` — iterates `ChainBlockQueue`, calls `saveChainConnected` for each
3. `saveChainConnected()` — deserializes block, calls `solidifyBlocks`, `checkChainSolidity`, `connectRewardBlock`
4. `connectRewardBlock()` — calls `verifyRewardChainConfirmReferenced`
5. `verifyRewardChainConfirmReferenced()` — validates referenced blocks, calls `confirmBlocksSorted`

### Importance of `initsync=true`

The `ScheduleInitService.syncService()` runs at startup with `@Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE)`. It checks `isInitSync()` which defaults to `false`. Without `initsync=true`:
- `AbstractScheduleInitService.initializeService()` never runs
- `serviceReady` stays `false` (default)
- `UpdateChainService.updateChain()` checks `serverConfiguration.checkService()` which returns `false`
- Reward blocks queue up but are never processed
- UTXOs are never confirmed

## Block Lifecycle

```
saveBlockPermissive
  │
  ├─ addNonChain → solidifyBlock → solid=1 (MissingCalculation)
  │                                  or solid=2 (Success)
  │
  ├─ store.updateBlockEvaluationSolid(block.getHash(), 2)
  ├─ store.updateBlockEvaluationWeightAndDepth(...)
  │
  └─ accumulateBlockFees + broadcastBlock
       │
       ▼
  MCMC next update cycle
       │
       ├─ updateWeightAndDepth → discovers solid=2 blocks
       ├─ updateRating → MCMC walk from entry points
       ├─ calcNewBlockPrototype → new tip prototype
       │
       ▼
  RewardService.createReward
       │
       ├─ dagBlockHashesFrom → collectedBlocks
       ├─ createMiningRewardBlock → BEACON block
       └─ saveBlock → addChain → saveChainBlockQueue
            │
            ▼
  UpdateChainService (every 10s)
       │
       ├─ processChainConnected
       ├─ verifyRewardChainConfirmReferenced
       │    ├─ hasSpentInputs → skip confirmed
       │    └─ removeIf(milestone > 0)
       │
       └─ confirmBlocksSorted
            └─ updateAllTransactionOutputsConfirmed(true)
```

## Test Dependencies

### Remote test (`remote.sh`)
- Docker PostgreSQL on port 5432
- `layer0-server` (HTTP, port 8089)
- `layer0-mcmc` (MCMC, port 8091)
- `l1-order-server` (L1 order API, port 8086)
- All started with `initsync=true`, `mcmc=true`, `blockbatch=true`, `microbatch=true`

### Run tests
```sh
# Unit tests
bash helper/testall.sh

# Remote integration tests
bash layer0-mcmc/src/test/java/net/bigtangle/mcmc/remote/remote.sh
```

## Post-Quantum Dual Signature Design

BigTangle uses **two independent NIST post-quantum signature schemes** on the same data. This is defense in depth: the schemes rest on completely different mathematics, so they do not share the same failure mode.

| | ML-DSA-87 (FIPS 204) | SLH-DSA-SHA2-256s (FIPS 205) |
|---|---|---|
| Math | Lattice (Module-LWE) | Hash-based (SPHINCS+ stateless Merkle) |
| Signature size | ~4.6 KB | ~30 KB |
| Sign speed | ~5 ms | ~1,700 ms (340x slower) |
| Verify speed | ~1 ms | ~2 ms (both fast) |
| Security assumption | Hardness of lattice problems | Security of SHA-2 (most conservative) |
| Risk | Could be broken by future quantum lattice attack | No known structural risk |

### Why dual ("AND", not "OR")

A transaction is authentic only if **both** signatures verify. Breaking the lattice scheme *alone* is not enough, because the hash-based signature still authenticates the data. This protects the chain against a cryptanalytic break of either family of mathematics.

### Key concepts in code

- **Key bundle** (`KeyBundle`) — a versioned, algorithm-sorted list of public keys. A *dual* key has two entries (`ALG_ML_DSA_87` + `ALG_SLH_DSA_SHA2_256S`); an ML-DSA-only key has one.
- **Signature bundle** (`SignatureBundle`) — a versioned list of signatures, one per algorithm.
- **Dual key creation**: `PQKey.fromSeeds(mlDsaSeed, slhDsaSeed)` generates both keypairs.
- **Dual signing**: `PQKey.sign()` signs the same message twice (once per scheme).
- **Dual verification**: `PQScriptUtils.verifyPQ` (transactions/UTXOs) and `verifyProposerSignature` (block proposers) require both entries to verify (AND logic).

### When SLH-DSA is required

- `verifyPQ` — **SLH-DSA required only if the key bundle has an SLH-DSA entry**. ML-DSA-only keys are fully valid and produce ML-DSA-only signatures that pass.
- `verifyProposerSignature` — **always requires both** ML-DSA and SLH-DSA (block proposer/consensus path). This is the truly SLH-DSA-mandated path.

### Where dual keys are used in production

- **Block proposers/validators** — `ValidatorDutyService` loads its proposer key from a 128-hex dual seed (`PQKey.fromPrivateKeyHex`).
- **Genesis / domain-permission root** — `TestParams.genesisPub` and `permissionDomainname` are locked to a dual key; the genesis coinbase script is built from it.
- Everything else (wallets, payees, token owners) uses ML-DSA-only keys.

### The dual suite identity

`PQConstants.SUITE_CAT5_DUAL_1 = 1` (dual ML-DSA-87 + SLH-DSA-SHA2-256s, category 5).
`PQConstants.SUITE_ML_DSA_ONLY = 2` (ML-DSA-87 only, category 5).

A dual key's address uses `SUITE_CAT5_DUAL_1`; an ML-DSA-only key's address uses `SUITE_ML_DSA_ONLY` (`PQKey.toAddress`).

## Governance: `pqSuites` (ML-DSA now, SLH-DSA later)

`NetworkParameters` declares a **governance suite list** intended to activate/sunset algorithms at runtime:

```java
/** Supported post-quantum algorithm suite IDs (e.g. SUITE_CAT5_DUAL_1 = 1).
 *  Empty list means PQ is disabled.  Governance activates suites by
 *  adding entries.  A suite is sunset by removing it. */
protected List<Integer> pqSuites = new ArrayList<>();
public boolean isPqSuiteActive(int suiteId) { return pqSuites.contains(suiteId); }
```

**Currently this is dead scaffolding** — nothing populates it and no verification consults it. `verifyPQ` / `verifyProposerSignature` hard-code the SLH-DSA requirement based on the key bundle's contents.

### Intended path: "ML-DSA now, dual later"

1. **Wire verification to governance**: gate the SLH-DSA requirement on `isPqSuiteActive(SUITE_CAT5_DUAL_1)`. When `SUITE_ML_DSA_ONLY` is active and `SUITE_CAT5_DUAL_1` is not, proposer/domain signatures need ML-DSA only.
2. **Start the chain ML-DSA-only**: define genesis with an ML-DSA-only `genesisPub` and `permissionDomainname`, and activate `SUITE_ML_DSA_ONLY`.
3. **Later, governance flips to dual**: `addPqSuite(SUITE_CAT5_DUAL_1)`; new blocks/domain ops must carry SLH-DSA. Old ML-DSA-only UTXOs stay valid (verification is per-key-bundle), and new dual keys coexist.

### Trade-offs / constraints

- **Security**: shipping without the SLH-DSA backstop on the domain root means single-fault until governance activates dual. Going single→dual is strictly an upgrade (no downgrade risk).
- **Genesis immutability**: genesis + `permissionDomainname` are fixed at chain launch. This only applies to a **new chain** (or the test net), not an existing one.
- **Cross-platform vectors**: `PQCrossPlatformCompareTest`, `PQACVPVectorsTest`, and genesis-hash tests reference the dual genesis vectors and must be updated consistently.
- **Proposer path**: `verifyProposerSignature` becomes suite-gated, so a dual-seed proposer key (`fromPrivateKeyHex`) must align with the active suite.

This is a **consensus/security decision**, not a test optimization — it changes what the chain's root-of-trust signs with.

## Test-suite performance work

`testall.sh` runs `bigtangle-core` tests (no DB) then `layer0-mcmc` integration tests (PostgreSQL, single surefire fork). Original runtime was ~11:17 with a surefire fork timeout failure (600 s).

### Bottleneck

CPU profiling (Java Flight Recorder) showed **88-97% of CPU in SLH-DSA signing** (`SLHDSASigner.generateSignature`, ~1.7 s/sign). Every token/domain/fee operation in the tests signs with a dual key.

### Changes applied

1. **`PQKey.createNew()` ML-DSA-only opt-in** (`-Dnet.bigtangle.pq.mldsaOnlyDefault=true`) — when enabled, `createNew()` returns ML-DSA-only keys. `verifyPQ` accepts ML-DSA-only bundles, so this is safe; production defaults to dual-key. Dual-key SLH-DSA coverage is preserved via the genesis wallet and the dedicated crypto tests.
2. **Signature memoization in `BcPQSignatureProvider`** — ML-DSA and SLH-DSA are deterministic (same key+message ⇒ same signature), so results are cached by `(privateKey, message)`. Bounded Guava caches.
3. **`layer0-mcmc/pom.xml` property-driven argLine** — the surefire `<argLine>` was hard-coded to `-Xmx2g`, which *overrode* `testall.sh`'s `-DargLine`. It is now `${bigtangle.mcmc.argLine}` with an identical default, so the ML-DSA-only flag reaches the fork (this also fixed the root cause of earlier parallel-fork failures).
4. **`testall.sh`** — passes `-Dnet.bigtangle.pq.mldsaOnlyDefault=true` and sets `-Dbigtangle.mcmc.argLine` for the fork.
5. **`pom.xml` fork timeout** — `forkedProcessTimeoutInSeconds` raised 600 → 1500 s (the suite legitimately needs ~6-11 min under PQ crypto).
6. **`ValidatorService2Test`** — 12 token-owner keys switched from `wallet.walletKeys().get(0)` (dual genesis) to `PQKey.createNew()` (ML-DSA-only).

### Results

| | Before | After |
|---|---|---|
| Full suite | ~11:17, BUILD FAILURE (fork timeout) | ~5:50-6:13, BUILD SUCCESS |
| TokenTest | ~136 s | ~56-67 s |
| ValidatorService2Test | ~71 s | ~30-41 s |

All 168 layer0 tests + core crypto tests pass. Dual-key SLH-DSA coverage is retained through the genesis wallet and `PQSignatureProviderTest` / `PQACVPVectorsTest` / `PQCrossPlatformCompatTest`.

### What cannot be fixed in tests

The remaining SLH-DSA (~93% of TokenTest CPU) is tied to the **dual genesis/domain-root key**:

- `wallet.feeTransaction` / `wallet.saveToken` — wallet spendable UTXOs are genesis-coinbase-locked (dual).
- `payBigTo` — the genesis key signs the funding tx, and change outputs stay dual-locked.
- `pullBlockDoMultiSign(wallet.walletKeys().get(0))` — domain-permission multisig, protocol-required with the dual genesis key.

An attempt to fund an ML-DSA-only spend key in `setUp` made no improvement (change outputs still route back to the genesis key, and the extra funding tx added its own dual sign) and was reverted.

The only lever that removes this cost at the source is the **`pqSuites` governance change** (make the genesis/domain root ML-DSA-only, flip to dual later) — a protocol/consensus decision, not a test-harness change. It has not been applied.
