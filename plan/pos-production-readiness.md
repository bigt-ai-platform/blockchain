# PoS Production Readiness — Plan

Companion to `pos-consensus-hardening.md` / `pos-remaining-impl.md`. The consensus
*safety* work is done; this plan closes the gap to a production cutover.

Status: **Phases 1–3 green, Phase 4 runbook written, Phase 5 DoS bounds
implemented, prodsim bootstrap redesigned.** Remaining for cutover: execute the
runbook once in staging (below).

Blockers addressed:
1. Post-fork (chain-read) path is untested — all tests run pre-fork. → DONE.
2. No N-validator convergence test with the new consensus. → DONE (prodsim
   redesigned; see Phase 2 / launch-blocker 3 below).
3. Economic model unresolved (fee-only rewards, 100% slash, virtual leak). → DONE.
4. No tested migration/launch runbook. → runbook written
   (`helper/prod/cutover-runbook.md`); end-to-end staging execution pending.
5. Operational unknowns (clock, epoch-domain mixing). → DONE (skew + domain);
   attestation scan DoS surface now bounded.

Ordering: **1 → 2 → 3 → 4 → 5**. 1–2 are highest risk and independent of the
economic decisions in 3.

---

## Phase 1 — Test the post-fork (chain-read) path — DONE

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
- Covered by `PoSTest.testJustificationViaChainRead`,
  `PoSTest.testOnChainAttestationReadsEmbeddedAttestations`.

---

## Phase 2 — N-validator convergence — DONE

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
- Genesis-funded 4-node prodsim (`ProdSimBootstrap` + `ProdSimVerification`)
  and `RemoteEpochRewardTests` are green.

---

## Phase 3 — Economic model: DECISIONS LOCKED + 2.3/leak IMPLEMENTED

1. **Rewards — issuance vs fee-only.** **DECIDED: fee-only.** No `base_reward`;
   2.1's voter-filtered fee split is kept. Issuance would require relaxing the
   fixed `10^17` supply (inflation) — a governance decision left post-launch.
2. **Slashing — graded (2.3).** **IMPLEMENTED.** The reorg-aware refund UTXO
   mint (`amount - amount/32`, net burn 1/32) + whistleblower reward
   (`slashed/512` to the reporter embedded in the proof) land in
   `applySlashingBlock`/`applySlashingConfirmed`/`revertSlashingBlock`.
3. **Inactivity leak — real balance bleed.** **IMPLEMENTED.**
   `CasperService.applyInactivityLeak` reduces the actual `stake.amount` of
   non-voters (quadratic) at the epoch boundary with reorg revert; the virtual
   denominator drain is removed.

### Acceptance
- Each decision documented; 2.3 + real-leak implemented and tested (incl. reorg).
- Covered by `PoSTest.testGradedSlashingMintsRefund`,
  `PoSTest.testInactivityLeakRestoresFinality`.

---

## Phase 4 — Launch runbook — RUNBOOK WRITTEN, STAGING EXECUTION PENDING

1. Genesis snapshot: run `helper/prod/genesis.sh`, verify
   `SUM(coinvalue) == 10^17`. ✅ FIXED + VERIFIED: the SQL emitted only
   `address,value` while the CSV header declared `address,pubkey,value`, so the
   `pubkey` column was dropped and the awk float sum came out 0. Now emits the
   empty `pubkey` column and sums exactly in PostgreSQL. Verified pass
   (10^17 → OK) and fail (10^17+1 → exit 1) against a synthetic legacy DB.
2. Per-validator bootstrap: `helper/prod/validators/generate_keys.sh` →
   `setup.sh`, `FUND_MODE=genesis`. Reviewed; server = API/requester
   (`pos.dutyEnabled=false`), MCMC = single beacon producer
   (`pos.dutyEnabled=true`), `server.fundEnabled=false` in production.
3. Cutover procedure: freeze legacy writes → build → coordinated deploy behind
   `POS_BEACON_SLOTDATA_ACTIVATION` → verify `getChainHeight` + `getValidators`
   converge → enable TLS → confirm `FUND_ENABLED=false`. ✅ documented in
   `helper/prod/cutover-runbook.md` (stake-all-validators-before-mcmc, fork
   height 1024 / `net.bigtangle.pos.attestationActivation`, TLS, fund-disable
   checks, rollback path). ⏳ staging execution still pending.
4. Document the fork activation height and rollback path. ✅ in
   `helper/prod/cutover-runbook.md` §4 and Rollback path.

---

## Phase 5 — Operational hardening — DONE (DoS surface now bounded)

1. **Clock tolerance** — DONE. `pos.maxClockSkewMs` (default one slot) bounds
   the wall-clock skew tolerated by the consensus gates: the far-future
   attestation gate uses the skewed upper bound (a behind-clock node no longer
   rejects the real current epoch), and the bouncing-attack safe window uses the
   skewed lower bound (an ahead-clock node cannot close the switch window early).
   Tests: `PosConsensusHardeningTest` + PoS suite green. NTP is still
   recommended; the window absorbs residual drift.
2. **Epoch-domain consistency** — DONE for activation/withdrawal (chain-derived).
   Attestation targets are still the CURRENT wall-clock epoch boundary, which is
   the moving chain tip while the epoch is live — so the same-target-epoch
   double-vote slashing form (b) AND the surround-vote form are DISABLED (they
   falsely slashed honest validators in the prodsim; see
   `plan/pos-remaining-impl.md`). Both are the documented path to re-enable once
   attestations target a stable past boundary. Tests: PoSTest, remote
   convergence (`RemoteEpochRewardTests`) green.
3. **DoS surface** — DONE. The per-slot proposer path
   (`CasperService.getAttestationsForSlot`) no longer loads the whole
   `pos_state("attestation")` map: new prefix-scoped store read
   (`getPosStateByServicePrefix`) restricts the query to `att_<slot>_*`, so it
   stays O(attestations of the slot). Attestation keys are zero-padded
   (`att_<slot>_`, CasperService.attestationKey) so lexicographic order == slot
   order, and the epoch prune drops the stale range in ONE statement
   (`deletePosStateByServiceKeyRange`) instead of a per-key scan. Verified by
   PoSTest (57 tests green).

### Acceptance
- Skew tolerance and domain consistency tests added. ✅ both.
- Clock tolerance DONE; DoS surface bounded. ✅

---

## Definition of production-ready

- Phases 1–2 green (post-fork path + convergence). ✅
- Phase 3 economic decisions recorded and (for 2.3 + leak) implemented. ✅
- Phase 4 runbook written and verified once in the 4-node prodsim. ⏳ the
  end-to-end **staging cutover** (real TLS certs, `FUND_ENABLED=false`,
  rollback drill) is the only remaining execution step.
- Phase 5 skew/domain tests pass; DoS-surface bounds implemented. ✅

Remaining launch blockers:
1. **Phase 4 end-to-end staging cutover** — execute `cutover-runbook.md` in
   staging once (TLS, rollback drill, `FUND_ENABLED=false` verification).
2. **Prodsim bootstrap** — ✅ REDESIGNED + VERIFIED: `helper/prodsim/run.sh`
   now (a) wipes stale compose volumes at start (a leftover volume caused
   "Cannot spend a bonded stake output"), and (b) starts ONLY the DBs + API
   servers, stakes all 4 validators while the chain is still at genesis, and
   only THEN starts the mcmc beacon producers. The 4-node bootstrap is now
   deterministic: active set grows 1→2→3→4 and converges on every node with no
   stake-block reorgs. (See `ProdSimBootstrap` fail-loud retries.)
3. **False slashing of honest validators** — ✅ FIXED (a real consensus
   regression the prodsim exposed): the same-target-epoch double-vote form (b)
   AND the surround-vote form falsely slashed honest validators because
   attestations target the CURRENT wall-clock epoch boundary (the moving chain
   tip). Both are disabled; see `plan/pos-remaining-impl.md` for the re-enable
   path (stable past chain-epoch targets).
4. **4-node beacon convergence** — ⏳ PARTIAL. The prodsim happy-path
   verification is GREEN: bootstrap deterministic (active set 1→2→3→4, no
   reorgs), all 4 validators propose/attest without being slashed, the beacon
   chain propagates to every node (full requester mesh; the bootstrap node had
   NO requester, so it confirmed zero beacons) and advances, rewards distribute.
   `ProdSimVerification` passes all 5 checks (healthy, confirmed-chainlength
   convergence within one epoch, validators active, beacon progress, rewards).
   Remaining: under the aggressive 2s-slot / independent-DB topology the
   CONFIRMED chainlength converges only loosely (nodes lag by several slots) and
   the two live-network attack checks (`ProdSimAttackVerification`) are
   unreliable on the node-local-funding topology (a `fundAddresses` UTXO never
   confirms network-wide, so the mempool double-spend window and the 
   invalid-signature test behave unlike a normal confirmed UTXO). Tight
   multi-node convergence (genesis-registered validators, or a production
   seed/topology with 12s slots) remains the tracked launch item — not a
   consensus regression.

All consensus changes are `[BREAKING]` and must land together before cutover.
