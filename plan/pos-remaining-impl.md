# PoS Hardening — Implementation Plan & Status

Status of the Ethereum-Gasper parity work, keyed to the findings in
`plan/pos-consensus-hardening.md`. All consensus changes are `[BREAKING]`
(fork-gated behind an activation epoch).

## Status

| Item | Status |
|------|--------|
| 0.1 pure 2/3-of-total justification threshold | ✅ implemented + tested |
| 0.2 inactivity leak (chain-derived) | ✅ REAL balance bleed implemented + tested (see below) |
| 1.1 proposer self-id (snapshot vs live) | ✅ implemented + tested |
| 1.2 double-vote slashing gap | ✅ same-slot form enforced; same-target-epoch form (b) DISABLED (see below) |
| 1.3 reorg-safe checkpoint invalidation | ✅ implemented + tested |
| 2.1 per-attestation (voter-filtered) rewards | ✅ implemented + tested |
| 2.2 effective-balance cap | ✅ implemented + tested |
| 2.3 graded slashing + whistleblower | ✅ refund mint + whistleblower reward implemented (correlation penalty deferred) |
| 3.1 activation delay (MAX_SEED_LOOKAHEAD) | ✅ RE-ENABLED in the chain epoch domain (see note below) |
| 3.2 activation churn limit | ✅ implemented + tested |
| 3.3 exit queue (churn-capped withdrawals) | ✅ implemented + tested |
| 4.1 proposer boost (40%) | ✅ implemented + tested |
| 4.2 RANDAO-withhold penalty | ⏳ not started |
| 4.3 BLS attestation aggregation | ⏳ deferred (throughput only) |
| On-chain attestations (BLS → embed → root → validate → chain reads) | ✅ implemented + tested |

## Epoch-domain consistency (activation) — FIXED

`applyStakeDeposit` used to set `activatedEpoch` from the deposit block's
WALL-CLOCK slot epoch (`epochAt(blockTime)`), while every other epoch in the
system — withdrawals, slashing, `currentChainEpoch`, `processWithdrawals` —
is CHAIN-derived (`chainlength/32`). Mixing the two domains was the root of
the original 3.1 bug: a wall-clock epoch (thousands) compared against a chain
epoch (small) meant a fresh deposit was never active.

Now the activation epoch is chain-derived: `depositActivationEpoch` computes the
deposit block's chain epoch (parent beacon `chainlength / SLOTS_PER_EPOCH`),
adds `MAX_SEED_LOOKAHEAD + 1`, and the delay is ENFORCED everywhere the active
set is read for consensus (proposer-selection snapshot + bootstrap fallback,
attestation weight, justification denominator, reward-split validator set, total
active stake). Deposits made while the chain is still within its FIRST epoch
(chain epoch 0 — the genesis bootstrap window) activate immediately, so a fresh
network still produces its first beacons. All enforcement is
activation-delay-aware `getActiveStakeDeposits(currentChainEpoch)`.

## Follow-up fixes (implemented after re-review)

| Item | Status |
|------|--------|
| Activation churn determinism (remove save-order `countActivatingAt` loop; keep delay) | ✅ fixed; hard churn cap → activation queue (deferred) |
| Hard fork-epoch gate for chain reads (`onChainAttestationActive`, replaces "non-empty" heuristic) | ✅ implemented |
| Discard equivocating validators from LMD-GHOST (PR #2845) | ✅ implemented |
| Bouncing-attack defense (justified-checkpoint switch window) | ✅ implemented |

## Convergence-test fixes (multi-node `remote.sh` harness, Phase 2)

The `remote.sh` epoch-reward convergence test exposed two consensus bugs that the
single-node unit/integration suite could not catch. Both are fixed and the test
now passes (`RemoteEpochRewardTests` → SUCCESS).

1. **3.1 activation delay — RE-ENABLED (chain epoch domain).** The original
   `MAX_SEED_LOOKAHEAD` implementation compared the *wall-clock slot epoch* of
   the deposit block (`activatedEpoch = epochAt(block time)`, huge) against the
   *chainlength epoch* (`currentChainEpoch = chainlength/32`, small) — two
   different domains — so a freshly deposited validator was never "active".
   Activation is now CHAIN-derived (`deposit block parent chainlength / 32`
   + `MAX_SEED_LOOKAHEAD + 1`), enforced everywhere the active set is read,
   with the chain-epoch-0 genesis bootstrap window activating deposits
   immediately so a fresh network still starts. A proper chain-ordered
   activation queue (beyond the delay) is still tracked with the churn cap.
2. **1.2 same-target-epoch double-vote form — DISABLED (again).** The
   re-enabled form (b) (same target epoch, two different target checkpoints)
   falsely slashed ALL four honest validators in the 4-node prodsim within
   seconds. The cause: attestations target the CURRENT wall-clock epoch, whose
   boundary (`epochBoundaryHash` = last confirmed beacon below the epoch
   boundary) is the MOVING chain tip while the epoch is live — so an honest
   validator attesting twice within one epoch legitimately produces two
   different targets and is falsely slashed. The determinism argument only holds
   for a boundary the chain has already crossed. Form (b) stays disabled (only
   the same-slot form is enforced); it may only be re-enabled once attestations
   target a STABLE past boundary (e.g. the epoch-two-behind chain-epoch
   checkpoint used by the selection snapshot / reward lookback). The surround
   vote form (strict epoch containment) is DISABLED for the same reason: its
   SOURCE is the justified-checkpoint epoch, which can regress after a reorg
   invalidates the justified checkpoint while the wall-clock TARGET never
   regresses — the strict containment test then falsely surrounds honest votes.
   Both forms re-enable together once attestations target stable chain-epoch
   boundaries.

## 2.3 Graded slashing + whistleblower reward  (IMPLEMENTED)

Ethereum-style slashing — initial burn `effective/32` (not 100%) plus a
whistleblower reward to the reporter — replaces full confiscation:

1. **Refund mint (store-level, reorg-aware)** — `confiscateBond` still burns the
   full bonded output, but `applySlashingBlock` now mints a store-level refund
   UTXO of `amount - amount/32` back to the slashed validator, keyed to the
   slashing block (proof-tx hash + index 1). It is `confirmed` when the slashing
   block confirms (`applySlashingConfirmed` restores/confirms it) and CANCELLED
   on unconfirm (`revertSlashingBlock`). Net burn = 1/32. A plain transaction
   output failed value conservation (SLASHING is not a minting block), hence the
   store-level mint — exactly mirroring `FundAddressesController.fund` but with
   the normal confirm lifecycle.
2. **Whistleblower reward** — the proposing node embeds its configured validator
   pubkey as `reporter` in the slashing proof; `applySlashingBlock` mints
   `slashed/512` (= `(amount/32)/512`) to it (index 2), reverted alongside the
   refund. No protocol value is created or destroyed — the reward is carved out
   of the 1/32 penalty.
3. **Correlation penalty (day-18) — deferred** (rolling slashing-history table).

## Real inactivity leak  (IMPLEMENTED)

The virtual denominator drain is replaced by a REAL balance bleed:
`CasperService.applyInactivityLeak` reduces the ACTUAL `stake.amount` of each
active validator with no on-chain vote for the just-ended epoch by the quadratic
factor `amount * 64 / (64 + delay^2)` (delay = epochs since finality, beyond the
4-epoch threshold). It runs at the epoch boundary (first beacon of the following
epoch confirms), keyed to that beacon for idempotency, and is reverted by
`CasperService.revertInactivityLeak` when the boundary beacon unconfirms.
`leakedTotalStake` (the justification denominator) now just sums the reduced
balances, so leaked validators lose weight, rewards and withdrawn bond. Only
runs post-fork where the chain-read voter set is authoritative.

---

## 4.2 RANDAO-withhold penalty  (NOT STARTED)

A proposer whose beacon omits/never includes a valid reveal is already rejected
(slot empty). Add an economic penalty: mark that slot's proposer as having a missed
proposal and exclude it from that epoch's reward share (fold into the 2.1
voter-filtered reward). Requires deterministic **missed-slot detection** — scan an
epoch's slots (via `slotsight_<slot>` / confirmed beacons) for the elected proposers
that produced no beacon, at the epoch boundary.

---

## 4.3 BLS attestation aggregation  (DEFERRED)

Large change (committees + aggregate-and-proof + `MAX_ATTESTATIONS_PER_BLOCK`).
Not required for correctness; revisit only for throughput. Keep as a tracked
follow-up.

---

## Decisions still to lock

1. **2.3 correlation penalty**: implement now (needs a 36-day slashing history
   table) or defer. Recommend defer to keep 2.3 shippable. **DECIDED: defer.**
2. **Rewards — issuance vs fee-only**: fee-only is retained (no `base_reward`;
   the fixed `10^17` supply is preserved). Issuance requires relaxing the fixed
   supply — a governance decision left for after launch.
3. **Fork epoch**: all completed changes are `[BREAKING]`; schedule behind
   `NetworkParameters` activation height, with the gossip fallback kept for the
   pre-fork window. The network has NOT launched, so these land before cutover.

## Verification

- Unit: `mvn -pl bigtangle-servercore -am test -Dtest=PosConsensusHardeningTest`
- Integration: `helper/testall.sh "PoSTest,StakeIT,ValidatorDutyTest,SlotTickServiceTest,RewardServiceTest"`
- Full validator convergence: `helper/prod/validators/` scripts after all land.
