# Proof-of-Stake Migration

## Current Architecture (MCMC bridge)

The system runs MCMC tip selection as a bridge to maintain parallel transaction throughput while PoS infrastructure is completed.

```
wallet.pay() → getTip HTTP → TipsQueue → TipsService.getValidatedBlockPair → MCMC walk
                                          ↓
                               calcNewBlockPrototype()
                               picks two DAG tips (trunk + branch)
                                          ↓
                               Block.createBlock(r1, r2)
                               parallel branches process txs concurrently
```

## PoS Services

All services cherry-picked from the `layers` branch. GhostService is fully implemented
(with DB-backed `getChildren()` and two-pass tip selection) but **not yet wired** into
block production (`calcNewBlockPrototype` still uses MCMC).

| Service | File | Status |
|---------|------|--------|
| **GhostService** | `server/service/GhostService.java` | ✅ **Full implementation** — `getChildren()` queries `store.getBlocksByPrevHash()`, `getTwoTips()` runs GHOST twice for trunk+branch, `executeGhost()` walks vote-weighted tree. Tested via `PoSTest.testLmdGhostEmpty`. **Not yet wired** into `calcNewBlockPrototype`. |
| **CasperService** | `server/service/CasperService.java` | ✅ Implemented. `processSlot()` handles justification/finalization. Tested via `PoSTest.testCasperCheckpoint`. |
| **StakeService** | `server/service/StakeService.java` | ✅ Implemented. Deposit, withdraw, reward distribution. Tested via `PoSTest.testStakeActivateAndSlash`. |
| **SlotService** | `server/service/SlotService.java` | ✅ Implemented. Slot assignment via `selectProposer()`. Tested via `PoSTest.testSlotCalculation`. |
| **SlashingService** | `server/service/SlashingService.java` | ✅ Implemented. Double-vote and surround-vote detection. Tested via `PoSTest.testSlashingDoubleVote`. |
| **FeeService** | `server/service/FeeService.java` | ✅ Implemented. Fee collection and validator payout. Tested via `PoSTest.testFeeDistribution`. |
| **RandaoService** | `server/service/RandaoService.java` | ✅ Implemented. RANDAO reveal and mixing. Tested via `PoSTest.testRandaoReveal`. |
| **SlotTickService** | `server/service/schedule/SlotTickService.java` | Schedule-based slot clock. Not wired. |

### GhostService Details

`GhostService` implements the Greediest Heaviest Observed SubTree (GHOST) fork-choice rule:

- **`processAttestation(att)`** — records a validator's attestation for a block hash in an in-memory vote map
- **`executeGhost(root, store, excludeSubtree)`** — walks from root, at each level picks the child with the most attestation votes. Can exclude a subtree for two-pass tip selection.
- **`getTwoTips(store)`** — runs GHOST twice: first pass gets the heaviest tip, second pass gets the next heaviest (excluding the first tip's subtree). Returns two distinct hashes for DAG trunk + branch.
- **`getChildren(hash, store)`** — queries `store.getBlocksByPrevHash(hash)` which runs `SELECT hash FROM blocks WHERE prevblockhash = ? OR prevbranchblockhash = ?`
- **`collectSubtree(root, out, store)`** — recursively collects all hashes in a subtree for exclusion
- **Vote weights** — stored in `ConcurrentHashMap<Sha256Hash, Long>` (in-memory; not persisted)

Key difference from MCMC:

| | MCMC | GHOST |
|---|---|---|
| Selection | Random walk weighted by cumulative work | Deterministic — highest validator vote count |
| Weight source | Block's cumulative approvers (MCMC tables) | Validator attestations (stake-weighted) |
| DB cost | Heavy — weight/depth/rating tables, complex topology queries | Light — single SQL query per level |
| Determinism | Non-deterministic (random walk) | Deterministic (same votes → same result) |

## Phase Plan

### Phase 1: PoW cleanup ✅
- Remove `checkProofOfWork`, `powEnabled`, `calculatePoWHash`
- Remove `MCMCService` dead code, `ScheduleMCMCService`, `ScheduleRewardService`
- Disable PoW-specific tests (MCMCServiceTest, TipsServiceTest, etc.)

### Phase 2: MCMC bridge (current)
- Keep MCMC-based tip selection to enable parallel transaction processing
- `calcNewBlockPrototype()` still calls `tipsService.getValidatedBlockPair()`
- GhostService, CasperService etc. are fully implemented but unused in block production
- All existing wallet, token, order-match flows continue to work
- PoS consensus tests (PoSTest, 15 tests) pass against PoS services

### Phase 3: Wire GhostService into block production
Replace `tipsService.getValidatedBlockPair()` in `calcNewBlockPrototype()` with `ghostService.getTwoTips()`:

```java
// Instead of:
Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
Block b = Block.createBlock(params, tips.getLeft().getBlock(), tips.getRight().getBlock());

// Use:
List<Sha256Hash> tips = ghostService.getTwoTips(store);
Block r1 = store.get(tips.get(0));
Block r2 = store.get(tips.get(1));
Block b = Block.createBlock(params, r1, r2);
```

Fallback: if GHOST returns `ZERO_HASH` (empty tree), use genesis block.
This preserves DAG structure (two tips) while switching weight source from MCMC to attestations.

### Phase 4: Full PoS
- Wire SlotService: validators produce one block per slot
- Wire CasperService: validator attestations finalize blocks (2/3 supermajority)
- Wire StakeService: deposit/withdraw/reward flow
- Wire SlashingService: equivocation detection and punishment
- Wire FeeService: distribute transaction fees to validators
- Wire RandaoService: select validator for each slot

## Key Design Decisions

- **MCMC stays until validator set is live** — keeps DAG parallelism during transition
- **Block prototype uses two tips** — `Block.createBlock(r1, r2)` for DAG structure
- **GHOST runs twice for two tips** — first pass gets the heaviest, second pass excludes its subtree to get a second distinct tip
- **getTip HTTP endpoint** — unchanged interface; wallet doesn't care about consensus
- **`@DirtiesContext`** — requestExecutor changed from `static` to instance-level to avoid shutdown across test contexts
- **Connection pool** — `HikariCP.maximumPoolSize=50`; PostgreSQL `max_connections=100`

## Test Counts (current)

| Module | Tests | Pass | Skip |
|--------|-------|------|------|
| layer0-mcmc | 154 | 118 | 36 |
| l1-order-mcmc | 31 | 31 | 0 |
| l1-contract-mcmc | 4 | 0 | 4 |
| **Total** | **189** | **149** | **40** |

## Remaining Skipped Tests

| Class | Skip | Reason |
|-------|------|--------|
| MCMCServiceTest | 11 | MCMC tip selection — PoW only |
| TipsServiceTest | 8 | Tip conflicts — PoW only |
| RewardService2Test | 3 | Mining rewards — PoW only |
| ValidatorServiceTest | 2 | Height-based double-spend, unsolid predecessor |
| AnchorRoundTripTest | 12 | BridgeConfiguration classloading |
| l1-contract all | 4 | Spring annotation resolution |
