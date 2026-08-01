# Plan: ML-DSA-87 (FIPS 204) only — with height-based switch to dual later

Status: **Implemented** (see `docs/technical.md` §Governance for the final design)

## 1. Objective

Ship the chain with **ML-DSA-87 as the only signature scheme** (no SLH-DSA),
and keep a **consensus-governed switch** so that after a chosen chain length
(block height `H`) the network can require SLH-DSA-SHA2-256s again
(the "dual" suite `SUITE_CAT5_DUAL_1`) without a new chain.

This is the "ML-DSA now, dual later" path already sketched in
`docs/technical.md` §Governance (`PQSuite` activation). It is a
**consensus / root-of-trust decision**, not a test optimisation.

## 2. Current state (facts)

| Area | Today | Location |
|---|---|---|
| Tx/UTXO verification | ML-DSA **always** required; SLH-DSA required **only if the key bundle carries an SLH entry**. ML-DSA-only UTXOs already verify. | `PQScriptUtils.verifyPQ` — `bigtangle-core/.../crypto/pq/PQScriptUtils.java:104` |
| Proposer verification | Requires **both** ML-DSA and SLH-DSA whenever the proposer key has an SLH entry. This is the SLH-DSA-mandated path. | `verifyProposerSignature` — `PQScriptUtils.java:147`; called from `Block.verifyProposer()` — `Block.java:615` |
| Genesis + domain root | Locked to a **dual** key (`TestParams.genesisPub`, `MainNetParams.genesisPub`). Every genesis-coinbase spend / domain op needs dual signing. | `TestParams.java:32`, `MainNetParams.java:45` |
| Key factory | `PQKey.fromSeeds(ml, slh)` and `PQKey.fromPrivateKeyHex` (64-byte seed) are **dual**. ML-DSA-only exists via `fromMLDSA` / `createNewMLDSA`. | `PQKey.java:95,82,105` |
| `PQKey.createNew()` | ML-DSA-only **only if** `-Dnet.bigtangle.pq.mldsaOnlyDefault=true` (test flag). Production default is dual. | `PQKey.java:70` |
| Validator key | `pos.validatorKey` must be a 64-byte seed → dual. | `ValidatorDutyService.java:54,63` |
| Governance scaffolding | `NetworkParameters.pqSuites` list is **dead code** — never populated, never consulted. | `NetworkParameters.java:105-115` |
| Signing | `PQKey.sign` emits SLH-DSA whenever the key has an SLH private key; no height awareness. | `PQKey.java:154` |
| Block proposer signing | Proposer key/sig fields are parsed + verified but **not wired to block creation yet** (no setter call site). | `Block.java:905-913`; `SlotService.proposeBeaconBlock` — `SlotService.java:88` |
| Suite IDs | `SUITE_CAT5_DUAL_1 = 1`, `SUITE_ML_DSA_ONLY = 2`; addresses encode the suite. | `PQConstants.java:25-29`, `PQKey.toAddress` — `PQKey.java:170` |

**Key insight:** the tx path is already ML-DSA-first and per-key-bundle, so it
needs **no height awareness**. Only the **proposer path**, **genesis/domain
root**, and **key/defaults** must change. This keeps the change small.

## 3. Design

### 3.1 Governance timeline (suite → activation height)

Replace the dead `pqSuites` list with an activation map:

```java
// NetworkParameters
/** suiteId -> activation height (0 = from genesis). Absent = never active. */
protected Map<Integer, Long> pqSuiteActivation = new HashMap<>();

public boolean isPqSuiteActive(int suiteId, long height);
public long getPqSuiteActivationHeight(int suiteId); // -1 if never
```

- `SUITE_ML_DSA_ONLY` → `0` (active from genesis) on all networks.
- `SUITE_CAT5_DUAL_1` → **not present by default** (never active), so the
  chain is ML-DSA-only. It is enabled by a governance/config decision at a
  specific height `H`.

### 3.2 The switch (after chain length `H`)

A single property + per-network default controls the future switch:

- `-Dnet.bigtangle.pq.dualActivationHeight=H` (system property) or a
  `NetworkParameters.dualActivationHeight` field read from config
  (`validator.env` / `application.properties`).
- At `height >= H`, `isPqSuiteActive(SUITE_CAT5_DUAL_1, height)` becomes true.
- The switch is **one-way and additive**: ML-DSA stays mandatory forever;
  SLH-DSA is added. No downgrade path exists (old ML-DSA-only UTXOs and blocks
  remain valid because verification is per-key-bundle).

### 3.3 Required-algorithms rule

```
requiredAlgs(height) =
    { ML_DSA_87 }
    + SLH_DSA_256S if isPqSuiteActive(SUITE_CAT5_DUAL_1, height)
```

Verification enforces: every entry in `requiredAlgs(height)` must be present
and valid. Entries the key *also* carries beyond `requiredAlgs` are still
verified if present (fail-closed, never ignored once on the wire).

## 4. Implementation phases

### Phase 1 — Governance timeline (`bigtangle-core`)

1. `NetworkParameters`: add `pqSuiteActivation` map + height-aware
   `isPqSuiteActive(int, long)`; keep old no-height methods as thin wrappers
   (`isPqSuiteActive(suiteId)` = active at `Long.MAX_VALUE` / "current").
2. `PQConstants`: add `DUAL_SUITE_DEFAULT_ACTIVATION = -1` (sentinel "never")
   and a `dualActivationHeight` JVM-default read:
   `Boolean`/`Long.getLong("net.bigtangle.pq.dualActivationHeight", -1)`.
3. `TestParams` / `MainNetParams`: populate `SUITE_ML_DSA_ONLY → 0`;
   set `dualActivationHeight` from the property (default never).

### Phase 2 — Make ML-DSA-87 the default everywhere (`bigtangle-core`)

4. `PQKey.createNew()` → **always** ML-DSA-only (drop the opt-in flag; keep
   `fromSeeds`/`fromPrivateKeyHex` for explicit dual).
5. `PQKey.fromPrivateKeyHex`: accept **both** 32-byte (ML-DSA-only) and
   64-byte (dual) seeds; document that dual seeds carry SLH but only sign it
   once the suite is active (Phase 3).
6. `PQKey.sign(input, includeSlhDsa)` — new selector overload: emits SLH-DSA
   **iff** the key has an SLH priv key **and** `includeSlhDsa` is true. The
   caller computes `includeSlhDsa = isPqSuiteActive(SUITE_CAT5_DUAL_1, height)`
   for the proposer path. Keep existing `sign(input)` = `sign(input, true)` for
   tx signing (a dual key's tx always carries both → per-key-bundle
   verification stays sound).

### Phase 3 — Height-gated proposer path (`bigtangle-core` + `servercore`)

7. `verifyProposerSignature(keyBundle, sigBundle, signingHash, requireSlhDsa)`:
   ML-DSA always; SLH-DSA required iff `requireSlhDsa` is true **and** the key
   carries an SLH entry. `Block.verifyProposer()` computes
   `requireSlhDsa = isPqSuiteActive(SUITE_CAT5_DUAL_1, block.height)` and
   threads it in.
8. Keep the existing 3-arg overload delegating with `requireSlhDsa=true` so
   standalone/tests still compile, and deprecate it for consensus use.
9. When proposer signing is finally wired into block creation
   (`SlotService.proposeBeaconBlock`, `SlotService.java:88`), sign with
   `validatorKey.sign(signingHash, isPqSuiteActive(SUITE_CAT5_DUAL_1, block.height))`.

### Phase 4 — Genesis / domain root → ML-DSA-only (new chain only)

10. `TestParams.genesisPub` and `permissionDomainname` (`TestParams.java:32-33`)
    become ML-DSA-only key bundles. `MainNetParams` equivalent when mainnet is
    launched. **This changes the genesis hash** → only valid for a not-yet-
    launched chain (or testnet). Existing chains cannot adopt this.
11. Update every genesis-hash / cross-platform vector test
    (`PQCrossPlatformCompareTest`, `PQACVPVectorsTest`, genesis hash tests,
    `AbstractIntegrationTest` genesis setup) to the new ML-DSA-only vectors.

### Phase 5 — Validator key config (`servercore`)

12. `ValidatorDutyService` (`ValidatorDutyService.java:54-63`): accept
    ML-DSA-only (64 hex chars) or dual (128 hex chars) validator keys; create
    via the Phase 2 factory. Default example config in `validator.env` uses an
    ML-DSA-only seed.

### Phase 6 — Tests (`bigtangle-core` + `layer0-mcmc`)

13. New boundary tests, `ActivationHeightTest`:
    - height `H-1`: proposer must verify ML-DSA-only; dual-proposer with
      ML-DSA-only sig passes; SLH not required.
    - height `H`: SLH-DSA becomes required for dual-key proposers; an
      ML-DSA-only proposer key is rejected (no downgrade).
    - a pre-`H` dual-signed block still verifies at/after `H`.
    - old ML-DSA-only UTXO spends still verify after `H`.
14. `PQScriptUtilsTest` / `PQScriptTest`: add height-aware proposer cases;
    keep existing coverage green via the deprecated overload.
15. `helper/testall.sh`: drop the now-default `-Dnet.bigtangle.pq.mldsaOnlyDefault`
    flag (or keep it as a no-op); add an optional `DUAL_H=<height>` env to run
    the suite in post-activation mode.
16. Performance regression: confirm ML-DSA-only runtimes (see
    `helper/performance.md` — SLH-DSA removal is the ~2x test-suite win).

### Phase 7 — Docs & config

17. Update `docs/technical.md` §Governance to describe the height map (no
    longer "dead scaffolding").
18. Update `pubkey.md` algorithm column ("ML-DSA-87 only, SLH-DSA optional at
    height H"), `blockchain.md` / translations, `validator.env` example.
19. Add a `helper/` note or doc on how to pick and announce `H` before launch
    (activation must be set **before** the network reaches `H` on every node).

## 5. Rollout checklist

- [x] `NetworkParameters` height map + wrappers
- [x] `PQConstants` sentinel + property read
- [x] `PQKey.createNew()` ML-DSA-only default
- [x] `PQKey.fromPrivateKeyHex` 32/64-byte support
- [x] `PQKey.sign(input, includeSlhDsa)` selector overload
- [x] `verifyProposerSignature` height-aware
- [x] `Block.verifyProposer()` passes height
- [x] Genesis/domain-root → ML-DSA-only (new chain)
- [x] Genesis/vector tests updated
- [x] Validator key config accepts ML-DSA-only
- [x] `SuiteActivationTest` boundary tests
- [x] `testall.sh` + performance check
- [x] Docs + config updated

## 6. Compatibility & risks

- **No downgrade path**: after `H`, ML-DSA-only proposer keys are rejected.
  Validators must hold dual-seeded keys (or the network must never activate).
  Recommend: validators provision 64-byte seeds from day one even though only
  ML-DSA is signed until `H`.
- **One fault vs dual**: until `H`, the chain relies solely on lattice-based
  ML-DSA-87 (single-fault). This is the accepted trade-off of "ML-DSA now".
- **Genesis immutability**: Phase 4 only applies to a chain not yet launched.
- **Proposer signing not yet wired**: Phase 3 adds the verifier gate now; the
  matching signer call lands when `SlotService.proposeBeaconBlock` is
  completed — both must land before any post-genesis block carries proposer
  fields, or `verifyHeader` (`Block.java:602`) will reject every block.
- **Config divergence**: every node must agree on `dualActivationHeight`.
  Put it in `NetworkParameters` (per-chain constant) rather than free-form
  properties to keep consensus consistent.
