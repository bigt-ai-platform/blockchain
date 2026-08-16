# PoS Production Readiness — Plan

Companion to `pos-consensus-hardening.md` / `pos-remaining-impl.md`. The consensus
*safety* work is done; this plan closes the gap to a production cutover.

Blockers addressed:
1. Post-fork (chain-read) path is untested — all tests run pre-fork.
2. No N-validator convergence test with the new consensus.
3. Economic model unresolved (fee-only rewards, 100% slash, virtual leak).
4. No tested migration/launch runbook.
5. Operational unknowns (clock, epoch-domain mixing).

Ordering: **1 → 2 → 3 → 4 → 5**. 1–2 are highest risk and independent of the
economic decisions in 3.

---

## Phase 1 — Test the post-fork (chain-read) path

**Problem:** `POS_BEACON_SLOTDATA_ACTIVATION = 1024` is a hardcoded chainlength;
the test suite never crosses it, so the deterministic chain-read code
(`votedStakeFor`, `votersForEpoch`, `recentVoters`, `chainForkChoiceVotes`,
`verifyEmbeddedAttestations`) is never exercised end-to-end.

### Changes

1. Make the activation height **configurable**: read
   `POS_BEACON_SLOTDATA_ACTIVATION` from a system property/env with the default
   `1024` (e.g. `net.bigtangle.pos.attestationActivation`), so a test can lower it
   to a small value (64–128). Keep the constant as the production default.
2. Add `OnChainAttestationIT` (layer0-mcmc) that:
   - sets the activation height low (e.g. 64),
   - drives a beacon chain with `SlotData` + embedded BLS attestations across the
     height (reuse `proposeBeaconBlock` / a helper),
   - asserts a checkpoint is **justified via the chain-read** path (not gossip),
   - asserts the epoch-start reward pays only `votersForEpoch(epoch-2)`,
   - asserts the inactivity leak uses the chain-derived `recentVoters`.
3. Add a pure unit test for `verifyEmbeddedAttestations` (root mismatch rejected,
   unsigned attestation rejected).

### Acceptance
- `helper/testall.sh "PoSTest,OnChainAttestationIT"` green, with the chain-read
  path actually executed (assert on embedded-attestation-driven justification).

---

## Phase 2 — N-validator convergence

**Problem:** proposer selection, justification, rewards, activation and fork
choice all changed; single-node tests don't prove multi-node convergence.

### Changes

1. Re-enable and run the remote harness (`layer0-mcmc/.../remote/`,
   `RemoteEpochRewardTests`, `ProdSimBootstrap`/`ProdSimVerification`) against
   this build.
2. Drive `helper/prod/validators/` (4 nodes) and assert:
   - every node's `getValidators` returns the same active set,
   - `getChainHeight` converges,
   - finality advances (justified/finalized epochs increase) on all nodes.
3. Fix any divergence found (most likely candidate: the save-order/confirm-order
   interaction around stake/attestation application).

### Acceptance
- 4-node convergence runs clean; finality advances on every node.

---

## Phase 3 — Economic model: decide, then implement

Three decisions to lock first, then implement (already specced in
`pos-remaining-impl.md`):

1. **Rewards — issuance vs fee-only.**
   - `[A]` Fee-only (status quo): no `base_reward`; keep 2.1's voter-filtered
     fee split. Simplest, but weak incentive in low-fee periods.
   - `[B]` Issuance: add `base_reward = effective_balance·2⁶/√total` + per-flag
     source/target/head weights + inclusion-delay reward. Requires relaxing the
     fixed `10^17` supply (inflation) — a governance decision.
2. **Slashing — graded (2.3).** Implement the reorg-aware refund UTXO mint
   (`effective/32` burn, refund 31/32) + a reporter identity for the whistleblower
   reward. Blocked on: refund-mint lifecycle + reporter identity (see plan).
3. **Inactivity leak — real balance bleed.** Replace the virtual denominator
   drain with an actual `stake.amount` reduction of non-voters (quadratic), applied
   deterministically at the epoch boundary with reorg revert.

### Acceptance
- Each decision documented; 2.3 + real-leak implemented and tested (incl. reorg).

---

## Phase 4 — Launch runbook

1. Genesis snapshot: run `helper/prod/genesis.sh`, verify
   `SUM(coinvalue) == 10^17`.
2. Per-validator bootstrap: `helper/prod/validators/generate_keys.sh` →
   `setup.sh`, `FUND_MODE=genesis`.
3. Cutover procedure: freeze legacy writes → build → coordinated deploy behind
   `POS_BEACON_SLOTDATA_ACTIVATION` → verify `getChainHeight` + `getValidators`
   converge → enable TLS → confirm `FUND_ENABLED=false`.
4. Document the fork activation height and rollback path.

---

## Phase 5 — Operational hardening

1. **Clock tolerance** — replace raw `System.currentTimeMillis()` slot derivation
   with a bounded-skew window (tolerate ±1 slot) and/or document NTP requirement.
2. **Epoch-domain consistency** — make activation/withdrawal use one epoch domain
   (chain-derived) instead of mixing block-time slot epoch and `chainlength/32`.
3. **DoS surface** — bound `getPosStateByService("attestation")` scan cost (done
   via pruning in Phase 1 follow-up); verify proposer path stays O(window).

### Acceptance
- Skew tolerance and domain consistency tests added.

---

## Definition of production-ready

- Phases 1–2 green (post-fork path + convergence).
- Phase 3 economic decisions recorded and (for 2.3 + leak) implemented.
- Phase 4 runbook executed once end-to-end in a staging environment.
- Phase 5 skew/domain tests pass.

Until 1–2 land, treat the codebase as **consensus-safety-complete but launch-blocked**.
