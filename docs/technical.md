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
