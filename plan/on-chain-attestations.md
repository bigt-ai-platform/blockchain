# On-Chain Attestations — Design (Ethereum model)

Make attestations part of the canonical chain, exactly like Ethereum commits
them into `BeaconBlockBody.attestations`. This is the prerequisite for the
still-open fixes 0.2 (inactivity leak), 2.1 (rewards/penalties), and for
deterministic finality/fork-choice that does not depend on node-local gossip.

Status: design only. No code yet.

---

## 1. Why (problem statement)

Today attestations are gossip-only and never enter the chain:

- `ValidatorDutyService.attest` signs an `AttestationData` (ML-DSA) and hands it
  to `CasperService.processVote`, which stores it in node-local `pos_state`
  (`casper/vote_*`, `evotes_*`, `ghostService.processAttestation`).
- `CasperService.votedStakeFor` counts votes from those **local** maps
  (`epochVoteTargets`/`epochVoteSources`).
- `GhostService` (LMD-GHOST) weights the fork choice from **local** votes.

Consequence: justification, finality, fork choice, rewards and any
inactivity-leak penalty are functions of *what each node happened to receive*,
not of chain state. That is the root cause of the 0.1 bug (non-deterministic
threshold) and blocks 0.2/2.1.

Ethereum solves this by putting attestations in blocks: the proposer of slot N
packs (aggregated) attestations into its block, so "who voted for what" is a
deterministic chain fact.

---

## 2. Ethereum reference (what "same as Ethereum" means)

1. Each validator has a **BLS** signing key (separate from withdrawal creds).
2. Validators are assigned to a **committee** per epoch (RANDAO shuffle) and to
   a specific **slot** within the epoch; each validator attests **once per epoch**.
3. An `AttestationData` is `(slot, index, beacon_block_root, source, target)`;
   validators sign it with BLS.
4. **Aggregators** (16 per committee, RANDAO-selected) collect single attestations,
   aggregate their BLS signatures into one `AggregateAndProof`, and gossip it.
5. The **proposer** includes up to `MAX_ATTESTATIONS` aggregated attestations in
   its `BeaconBlockBody`.
6. Inclusion is what makes finality, rewards, the inclusion-delay reward, and the
   inactivity leak deterministic; the proposer is rewarded for including, the
   attester for being included timely.

---

## 3. Current bigtangle state (what we already have)

- Every validator attests **every slot** (not once/epoch) — `performDuty`.
- Attestation signature is **ML-DSA** (`PQKey.sign`), verified via
  `PQScriptUtils.verifyPQ`. ML-DSA is **not** aggregatable.
- BUT each validator **already registers a BLS key** in its STAKE deposit:
  - `RandaoService.blsSecretScalar` derives a BLS-12-381 secret scalar
    deterministically from the ML-DSA private key.
  - `RandaoService.blsPubkey`/`blsProofOfPossession` are stored in
    `StakeRecord.blsPubkey` (`StakeService.applyStakeBlock`), validated + PoP-checked.
  - `BLS12_381BasicScheme` (BouncyCastle) is already in use for RANDAO reveals.
- Beacons already carry a `SlotData` transaction (`dataClassName == "SlotData"`)
  — a natural place to also carry the slot's attestations.
- The stake deposit already binds BLS→ML-DSA identity, so no new key ceremony is
  needed to switch attestations to BLS.

=> The foundation for BLS attestation + aggregation already exists.

---

## 4. Proposed design

### 4.1 Sign attestations with BLS (reuse the registered key)

- Change `AttestationData.signature` to a **BLS signature** over `getMessageHash()`
  using the validator's derived BLS key (already registered on-chain).
- Verify with `BLS12_381BasicScheme.verify(pk, msg, sig)` against
  `StakeRecord.blsPubkey`.
- Why: BLS signatures aggregate (same message → sum signatures; same key →
  sum messages), which is what makes "include N attestations per block" cheap.

### 4.2 Attestation content (Ethereum `AttestationData`)

Keep the existing `AttestationData` fields (they already map closely to
Ethereum's):

| Ethereum | bigtangle today |
|---|---|
| `slot` | `slot` |
| `beacon_block_root` | `beaconBlockHash` (head vote) |
| `source.epoch/root` | `sourceEpoch` / `sourceCheckpoint` |
| `target.epoch/root` | `targetEpoch` / `targetCheckpoint` |
| committee index | (none — see 4.3) |
| signature | switch to BLS |

Add nothing structurally; the change is the signature scheme + committee/index
(if we adopt committees).

### 4.3 Committees & cadence (decision point)

**Option A — full Ethereum model (recommended, matches request):**
- Validators are shuffled into committees per epoch (reuse the RANDAO mix + the
  existing `selectProposerForSlot` machinery to derive a deterministic shuffle).
- Each validator attests **once per epoch**, at its assigned slot.
- Aggregators aggregate per committee.

**Option B — minimal: keep per-slot cadence, add aggregation + inclusion.**
- Every validator still attests each slot; the proposer aggregates all slot-N
  attestations into one BLS aggregate per (source,target,head) group.
- Less disruptive, still gives deterministic on-chain votes.

> Recommend A for the end state (it's what makes 0.2/2.1 clean), but B can be
> shipped first as a stepping stone. This doc assumes A in the data flow.

### 4.4 Block inclusion (the critical part)

- Add a beacon transaction `dataClassName == "Attestations"` (or extend
  `SlotData`) carrying the (aggregated) attestations the proposer includes for
  that slot:
  - a list of aggregated `(AttestationData, aggregate signature, participation bits)`
    records, bounded by `MAX_ATTESTATIONS_PER_BLOCK`.
- The proposer of slot N includes attestations it received for slot N-1 (and any
  still-unincluded earlier ones), ordered deterministically (by slot, then by
  hash) so every node derives the same on-chain set.
- These become the **canonical vote record**: no more `pos_state` as the source
  of truth for justification/fork choice.

### 4.5 Deterministic vote reading

Replace the node-local reads with chain reads:

- `CasperService.votedStakeFor(...)` → walk the confirmed beacon chain and sum
  effective stake of validators whose attestation (with matching source/target)
  is **included** in a block.
- `GhostService` LMD-GHOST weight → read head votes from **included**
  attestations, not `saveAttestationVote` local rows.
- Justification threshold stays the pure `2/3 * totalActiveStake` from 0.1.

### 4.6 Enables the open fixes

- **0.2 inactivity leak**: non-voting = no *included* attestation for the target
  over `MIN_EPOCHS_TO_INACTIVITY_PENALTY`; apply a deterministic stake penalty at
  the epoch boundary (chain-derived, reversible on reorg like `applySlashingBlock`).
- **2.1 rewards/penalties**: reward timely included source/target/head votes,
  penalize missing source/target; all computed from the on-chain set. Requires a
  supply decision (see §7).
- **2.3 / 3.x / 4.x**: unchanged by this doc; still separate.

### 4.7 Slashing

- Surround/double-vote detection keeps working, but evidence now comes from
  **included** attestations (the chain set) rather than the local gossip window
  (`SlashingService.voteHistory` can remain as a gossip early-warning, but the
  consensus `applySlashingBlock` already validates both attestations independently).

---

## 5. Data flow (after)

```
validator (BLS sign)
   │  gossip "attestation" (single)
   ▼
aggregator (BLS aggregate + proof)
   │  gossip "aggregate and proof"
   ▼
proposer slot N
   │  packs ≤ MAX attestations into beacon "Attestations" tx
   ▼
beacon block ──▶ every node confirms → on-chain attestation set
   │
   ├─▶ CasperService.votedStakeFor   (justification/finality)
   ├─▶ GhostService                  (LMD-GHOST weight)
   ├─▶ RewardService / Leak          (2.1 / 0.2)
   └─▶ SlashingService               (evidence from chain)
```

---

## 6. Files impacted

| Area | File | Change |
|---|---|---|
| Attestation signing/verify | `AttestationData`, `ValidatorDutyService.attest`, `CasperService.verifyAttestation`, `ServiceBaseCheck` | BLS sign/verify; use `StakeRecord.blsPubkey` |
| Key reuse | `RandaoService` | expose `blsSecretScalar`/sign for attestation (already derivable) |
| Block body | `SlotService.proposeBeaconBlock`, `Block`, transaction data class | add `"Attestations"` tx + deterministic ordering |
| Aggregation | new `AttestationAggregator` | BLS aggregate per (source,target,head) |
| Vote counting | `CasperService.votedStakeFor`, `persistEpochVotes`, `processVote` | read from chain set |
| Fork choice | `GhostService` | read head votes from chain set |
| Committee/shuffle | `SlotService` | per-epoch committee + attestation-slot assignment (Option A) |
| Finality/leak/reward | `CasperService.finalizeCheckpoint`, `EpochRewardService` | chain-derived (enables 0.2/2.1) |
| Migration | `NetworkParameters` | fork epoch gate |

---

## 7. Open decisions

1. **Committee model (A) vs per-slot cadence (B)** — A is "same as Ethereum",
   B is less disruptive. Recommend A, phased.
2. **Supply economics for 2.1** — fixed `10^17` total supply vs. minting base
   reward (inflation) vs. fee-only. Ethereum mints; bigtangle's total supply is
   fixed, so rewards must be fee-funded or a new issuance schedule approved.
3. **Aggregation necessity** — if per-slot cadence (B) is kept with ≤N validators,
   un-aggregated inclusion is feasible but O(N) block size; aggregation is the
   scaling path.
4. **Inclusion-delay reward** — needs a "timely vs late" definition tied to slot
   distance, which requires the committee/slot assignment from A.
5. **Fork epoch & back-compat** — gossip-based votes remain valid pre-fork;
   post-fork, only on-chain attestations count.

---

## 8. Migration plan (summary)

1. Ship BLS attestation signing (backward-compatible: verify either ML-DSA or BLS
   during transition).
2. Add `"Attestations"` inclusion to the beacon + deterministic ordering.
3. Flip `CasperService`/`GhostService` to read from chain set.
4. Activate at a fork epoch; then implement 0.2 and 2.1 on top.
