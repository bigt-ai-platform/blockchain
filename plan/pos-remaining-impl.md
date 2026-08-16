# PoS Hardening — Implementation Plan & Status

Status of the Ethereum-Gasper parity work, keyed to the findings in
`plan/pos-consensus-hardening.md`. All consensus changes are `[BREAKING]`
(fork-gated behind an activation epoch).

## Status

| Item | Status |
|------|--------|
| 0.1 pure 2/3-of-total justification threshold | ✅ implemented + tested |
| 0.2 inactivity leak (chain-derived) | ✅ implemented + tested |
| 1.1 proposer self-id (snapshot vs live) | ✅ implemented + tested |
| 1.2 double-vote slashing gap | ✅ implemented + tested |
| 1.3 reorg-safe checkpoint invalidation | ✅ implemented + tested |
| 2.1 per-attestation (voter-filtered) rewards | ✅ implemented + tested |
| 2.2 effective-balance cap | ✅ implemented + tested |
| 2.3 graded slashing + whistleblower | ⛔ blocked (see below) |
| 3.1 activation delay (MAX_SEED_LOOKAHEAD) | ✅ implemented + tested |
| 3.2 activation churn limit | ✅ implemented + tested |
| 3.3 exit queue (churn-capped withdrawals) | ✅ implemented + tested |
| 4.1 proposer boost (40%) | ✅ implemented + tested |
| 4.2 RANDAO-withhold penalty | ⏳ not started |
| 4.3 BLS attestation aggregation | ⏳ deferred (throughput only) |
| On-chain attestations (BLS → embed → root → validate → chain reads) | ✅ implemented + tested |

---

## 2.3 Graded slashing + whistleblower reward  (BLOCKED)

**Goal:** Ethereum-style slashing — initial burn `effective/32` (not 100%), plus a
whistleblower reward `slashed/512` to the reporter — instead of full confiscation.

**Blocker (from an attempted impl):** adding the refund as a plain transaction
output on the `BLOCKTYPE_SLASHING` transaction fails value conservation
(`InvalidTransaction "input and output values do not match"`), because the block is
not a minting block. The correct approach is a **store-level, reorg-aware refund
UTXO mint** keyed to the slashing block (`blockhash`/`txhash`/`index`), confirmed
when the slashing block confirms and reverted in `revertSlashingBlock` on unconfirm
(mirror `FundAddressesController.fund` but `confirmed=false` → normal confirm flow).

### Changes (revised)

1. **Refund mint (store-level, reorg-aware)** — in `applySlashingBlock` /
   `applySlashingConfirmed`, mint a UTXO of `slashedRefund(stake.amount)`
   (= `amount - amount/32`) to the validator's address, keyed to the slashing
   block; revert it in `revertSlashingBlock`. Keep `confiscateBond` burning the
   full bond, so net burn = 1/32. (`StakeService.SLASH_PENALTY_DIVISOR = 32` is
   already declared for this.)
2. **Whistleblower reward** — needs a reporter identity first (the SLASHING block
   has no creator/coinbase today). Add the block proposer/creator to the slashing
   block, then mint `slashed/512` to it the same way.
3. Correlation penalty (day-18) — defer (rolling slashing-history table).

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
   table) or defer. Recommend defer to keep 2.3 shippable.
2. **Fork epoch**: all completed changes are `[BREAKING]`; schedule behind
   `NetworkParameters` activation height, with the gossip fallback kept for the
   pre-fork window.

## Verification

- Unit: `mvn -pl bigtangle-servercore -am test -Dtest=PosConsensusHardeningTest`
- Integration: `helper/testall.sh "PoSTest,StakeIT,ValidatorDutyTest,SlotTickServiceTest,RewardServiceTest"`
- Full validator convergence: `helper/prod/validators/` scripts after all land.
