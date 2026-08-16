# PoS Consensus Hardening Plan

Fix all identified deviations from Ethereum Gasper (Casper-FFG + LMD-GHOST) and
the concrete consensus bugs. Source of truth for Ethereum semantics:
ethereum.org Gasper / rewards-and-penalties docs and the consensus-specs.

Each item is tagged:

- `[BREAKING]` — changes chain-state determinism; requires a coordinated fork
  (all validators upgrade before a fixed activation epoch).
- `[LOCAL]`    — liveness/correctness fix that does not change consensus rules;
  safe to ship per-node.

---

## Phase 0 — Finality & safety (the "2/3 vs 2/9" fix)

### 0.1 Restore a pure 2/3-of-total justification threshold `[BREAKING]`
- **Where**: `CasperService.justificationThreshold` (CasperService.java:657),
  `INACTIVITY_WINDOW_EPOCHS` (646), `finalizeCheckpoint` (681).
- **Problem**: threshold = 2/3 of `max(onlineStake, totalStake/3)`; drops to
  2/9 of total stake when >1/3 is offline, and depends on node-local `latestVotes`.
- **Fix**:
  1. Replace the threshold with `2 * totalActiveStake / 3` (Ethereum's rule).
     `totalActiveStake` = `store.getActiveStakeDeposits()` sum (already available
     via `StakeService.getTotalActiveStake`).
  2. Delete the `latestVotes`-derived `referenceEpoch`/`activeFloor`/`onlineStake`
     logic and `INACTIVITY_WINDOW_EPOCHS` (fold into the real leak, 0.2).
- **Impact**: justification/finalization become deterministic chain-state
  functions. Fixes criticisms #1 and #2.

### 0.2 Implement a real inactivity leak `[BREAKING]`
- **Where**: new logic in `CasperService` (or a new `InactivityLeakService`),
  applied in `processEpoch`/`finalizeCheckpoint`.
- **Problem**: offline validators are ignored (denominator shrink) with no
  penalty, so they keep full stake and return at full weight. Ethereum drains
  non-voting validators after 4 epochs without finality (quadratic per-epoch).
- **Fix**:
  - Track `finalityDelay = currentEpoch - lastFinalizedEpoch`.
  - When `finalityDelay > 4` (Ethereum's `MIN_EPOCHS_TO_INACTIVITY_PENALTY`),
    for each active validator that did NOT vote for the target checkpoint,
    decrement its effective stake by `inactivity_penalty = base_reward_factor * finalityDelay^2 / 2`
    (Ethereum `get_inactivity_penalty_delta`), persisted as a stake-table
    adjustment (must be chain-derived, i.e. applied at the same chain position
    on every node — ride `confirmDo`/epoch processing, not the wall clock).
  - Justification continues to use 2/3 of the *reduced* total, so finality
    resumes once online validators hold 2/3.
- **Impact**: fixes the economic-security gap of #1; liveness recovers the way
  Ethereum's does (Medalla scenario).

### 0.3 Deterministic target checkpoint (no node-local transient) `[BREAKING]`
- **Where**: `CasperService.ensureCheckpoint(epoch, store)` transient path
  (268-283); `ValidatorDutyService.attest` target selection (241).
- **Problem**: unconfirmed boundary yields a per-node target hash → attestation
  target fragmentation.
- **Fix**: the epoch checkpoint is the block at the epoch boundary slot; derive
  it only once the boundary beacon is **confirmed**, and have validators attest
  the *previous* epoch's (already-confirmed) checkpoint as target until then,
  mirroring Ethereum (attest target = checkpoint of current epoch start, which is
  the last block of the prior epoch — already fixed). Remove the transient
  `confirmedHeadOrGenesis` fallback from attestation target; use the last
  confirmed boundary.

---

## Phase 1 — Correctness bugs

### 1.1 Fix proposer self-identification (snapshot vs live list) `[LOCAL]`
- **Where**: `ValidatorDutyService.performDuty` (200-205).
- **Problem**: indexes the 2-epoch snapshot's `proposerIdx` into the live set.
- **Fix**: reuse the same list `SlotService.proposeBeaconBlock` uses:
  `List<StakeRecord> validators = SlotService.selectionValidators(slot, store);`
  (expose a small helper if needed). Add a unit test where the live set ≠
  snapshot and assert `isProposer` matches beacon validation.

### 1.2 Close the double-vote slashing gap `[BREAKING]`
- **Where**: `SlashingService.checkDoubleVote` (187), `StakeService.applySlashingBlock`
  (541-542), `submitSlashing` (467).
- **Problem**: only "same slot + different head" is slashable; the
  same-target-epoch/different-target-root form (Ethereum's second double-vote
  form) is missed.
- **Fix**: add the condition `att1.targetEpoch == att2.targetEpoch &&
  !att1.targetCheckpoint.equals(att2.targetCheckpoint)` to both detection and the
  consensus application, in addition to the existing same-slot check.

### 1.3 Reorg-safe checkpoint invalidation `[BREAKING]`
- **Where**: `CasperService.checkpoints` map (`ensureCheckpoint`, 268; `restoreState`, 124).
- **Problem**: checkpoint cached once, never evicted on reorg → stale-hash votes
  stall finality after a reorg.
- **Fix**: on reward-chain reorg (the confirm/unconfirm path in
  `BlockStoreService.confirmDo`), drop cached checkpoints at/above the reorg
  point (remove map entries + `ckpt_` keys) so they re-derive from the new
  canonical boundary. Keep the "genesis is rooted" anchor.

---

## Phase 2 — Incentive / economic model

### 2.1 Per-attestation rewards and penalties `[BREAKING]`
- **Where**: `EpochRewardService` (currently fee-only) + `CasperService`/`StakeService`
  for balance application.
- **Problem**: no issuance and no penalty for missing votes → attestation is
  economically unpriced (#4).
- **Fix** (Ethereum Altair model):
  - `base_reward = effective_balance * 64 / (4 * sqrt(total_active_balance))`.
  - Weights: source 14, target 26, head 14 (proposer 8, sync 2 can be deferred).
  - Reward correct+timely source/target/head votes; apply the *symmetric* penalty
    for missing source/target votes; head is reward-only.
  - Inclusion-delay reward `base_reward / delay`.
  - Apply as chain-derived balance deltas in epoch processing (deterministic),
    not the fee-pool split. Keep fee redistribution as an additional payout.
- **Impact**: honest voting becomes the dominant strategy.

### 2.2 Effective-balance cap + hysteresis `[BREAKING]`
- **Where**: `StakeService.getEffectiveStake` (69), `StakeRecord` amount handling.
- **Problem**: full amount counts; concentration unbounded (#5).
- **Fix**: add `MAX_EFFECTIVE_BALANCE` (e.g. `32_000_000` satoshis = 32 BIG, matching
  `MIN_STAKE`) and cap the *effective* stake used in proposer selection, attestation
  weight and rewards, while keeping the full bonded amount on-chain. Add
  hysteresis (`effective_balance_upward/downward` adjustment) so it doesn't
  oscillate every epoch.

### 2.3 Graded slashing + whistleblower reward `[BREAKING]`
- **Where**: `StakeService.applySlashingBlock` (500), `confiscateBond` (708).
- **Problem**: 100% immediate confiscation, no correlation penalty, no proposer
  reward for including evidence (#6).
- **Fix**:
  - Initial burn = `effective_balance / 32`; keep the rest locked for the
    withdrawal delay; day-18 correlation penalty scaled by the total stake
    slashed in the prior window (Ethereum `process_slashings`).
  - Pay the includer a whistleblower reward (`slashed_balance / 512`) in the
    slashing block's coinbase/reward outputs.

---

## Phase 3 — Validator lifecycle

### 3.1 Activation delay `[BREAKING]`
- **Where**: `StakeService.applyStakeBlock` (250), `activateValidator` (675).
- **Problem**: near-instant activation; no time for the new deposit to propagate (#7).
- **Fix**: activation epoch = deposit epoch + `MAX_SEED_LOOKAHEAD` (4) + 1, and
  ignore the HTTP-supplied epoch (derive from chain position). Validators are
  inactive until that epoch.

### 3.2 Churn limit (enter/exit) `[BREAKING]`
- **Where**: deposit activation + `processWithdrawals` (938).
- **Problem**: unlimited set churn per epoch (#7, #9).
- **Fix**: cap validators entering and exiting per epoch at
  `churn_limit = max(MIN_PER_EPOCH_CHURN_LIMIT, active_count / CHURN_LIMIT_QUOTIENT)`
  (Ethereum: 4 and 65536). Queue deposits/exits beyond the limit.

### 3.3 Exit queue `[BREAKING]`
- **Where**: `StakeService.applyExitConfirmed` (853) / `processWithdrawals` (938).
- **Problem**: fixed 256-epoch delay but no queue; large simultaneous exits all
  release at once (vs Ethereum's 36-day queue) (#9).
- **Fix**: order exits by activation/exit epoch and bound per-epoch withdrawals by
  the churn limit; set withdrawable epoch to the validator's queue position.

---

## Phase 4 — Fork choice & networking

### 4.1 Proposer boost in LMD-GHOST `[BREAKING]`
- **Where**: `GhostService` heaviest-subtree walk (134).
- **Problem**: no proposer boost → ex-ante reorg cheaper (#10).
- **Fix**: add `PROPOSER_SCORE_BOOST = 40%` of total active stake to the current
  slot proposer's branch during fork-choice evaluation.

### 4.2 RANDAO withhold penalty + clock-skew tolerance `[BREAKING]`
- **Where**: `RandaoService.applyReveal` (243), `SlotService.getCurrentSlot` (84),
  `CasperService.processVote` far-future gate (522).
- **Problem**: skipping a reveal only empties the slot (no penalty); wall-clock
  slot timing + wall-epoch rejection is clock-skew sensitive (#11, minor).
- **Fix**: penalty for a proposer whose beacon omits a valid reveal (fold into the
  2.1 penalty model); widen the far-future attestation gate to `±1` epoch and
  derive epoch bounds from chain state, not wall clock, where feasible.

### 4.3 Attestation aggregation (long-term) `[BREAKING][OPTIONAL]`
- **Where**: `GossipService`/`CasperService.processVote` broadcast path.
- **Problem**: O(N) individual ML-DSA attestations per slot (#12).
- **Fix**: adopt BLS attestation signatures + per-committee aggregation
  (BLS infra already present via `RandaoService`). This is a large change; scope
  separately after Phase 0-2 land.

---

## Sequencing & risk

1. **Ship `[LOCAL]` first**: 1.1 (proposer self-id) is a no-fork liveness fix.
2. **Phase 0 + 1.2 + 1.3** are the safety-critical, coordinated fork: bundle
   behind an activation epoch (`NetworkParameters` fork height/epoch).
3. **Phase 2-3** follow in the same or a second fork (economic + lifecycle).
4. **Phase 4** last (performance / DoS hardening).

## Verification per item

- Unit: proposer selection parity (snapshot vs live), threshold math, slashing
  condition matrix, churn/queue arithmetic, reward/penalty symmetry.
- Integration: extend `PoSTest` / `StakeIT` / `ProdSimVerification` with
  (a) offline-majority inactivity-leak recovery, (b) same-target-double-vote
  slashing, (c) reorg checkpoint re-derivation, (d) proposer-boost fork choice.
- Regression: `helper/prod` validator scripts must still converge (N validators,
  `getValidators`, `getChainHeight`, finality advance).

## Ethereum parameter mapping (for reference)

| Ethereum | This plan |
|---|---|
| finality = 2/3 of total active balance | `2*getTotalActiveStake()/3` |
| `MIN_EPOCHS_TO_INACTIVITY_PENALTY` = 4 | `finalityDelay > 4` |
| `MAX_EFFECTIVE_BALANCE` = 32 ETH | 32 BIG = `MIN_STAKE` |
| `MIN_VALIDATOR_WITHDRAWABILITY_DELAY` = 256 epochs | keep `WITHDRAWAL_DELAY_EPOCHS=256` |
| `MAX_SEED_LOOKAHEAD` = 4 | activation +5 epochs |
| `CHURN_LIMIT_QUOTIENT` = 65536 | `/65536`, min 4 |
| `PROPOSER_SCORE_BOOST` = 40% | 40% of total stake |
| slash initial = effective/32 | `effective/32` |
| whistleblower = slashed/512 | `slashed/512` |
