# Bigtangle Layered Architecture — Plan

> Status: DRAFT for review
> Decisions (confirmed): **(1)** same-repo, new-module split; **(2)** hybrid
> consensus (each L1 runs its own MCMC/reward/rollback, but periodically
> *anchors* a checkpoint into L0 so L0 finalizes L1 state); **(3)** subtangle
> bridge (`BLOCKTYPE_CROSSTANGLE`) extended to a bidirectional peg with
> M-of-N multisig vault for L1↔L0 value movement; **(4)** SPV anchor
> verification (Phase 2.5) must be complete before peg-out (Phase 3) ships —
> anchor key trust is a temporary Phase 2 window only.

---

## 1. Target Architecture

```
                          Layer 0  (the settlement chain)
   token issuance · transfer/payment · reward/mining · MCMC consensus
   ┌──────────────────────────────────────────────────────────────┐
   │  bigtangle-core   bigtangle-servercore                       │
   │  anchors: BLOCKTYPE_CROSSTANGLE carries L1 checkpoint hashes │
   └──────────────────────────────────────────────────────────────┘
            ▲ anchor post (L1→L0)        ▲ peg in/out (UTXO bridge)
            │                            │
   ┌────────┴───────────┐        ┌───────┴────────────────┐
   │ Layer 1: ordermatch│        │ Layer 1: contracts      │  ... more L1 chains
   │ own NetworkParams  │        │ own NetworkParams       │
   │ own genesis/DB     │        │ own genesis/DB          │
   │ own MCMC + reward  │        │ own MCMC + reward       │
   │ + rollback         │        │ + rollback              │
   │ BLOCKTYPE_ORDER_*  │        │ BLOCKTYPE_CONTRACT_*    │
   └────────────────────┘        └─────────────────────────┘
```

### 1.1 Definitions

- **Layer 0 (L0)** — the settlement/base chain. Block types: `BLOCKTYPE_INITIAL`,
  `BLOCKTYPE_TRANSFER`, `BLOCKTYPE_TOKEN_CREATION`, `BLOCKTYPE_REWARD`, and the
  *anchor* use of `BLOCKTYPE_CROSSTANGLE`. It is the only chain that mints the
  system coin (`BIG`/`bc`) and runs the canonical reward/milestone consensus.
- **Layer 1 (L1)** — a sub-blockchain. Each L1 is *analogous to L0*: its own
  `NetworkParameters` (distinct genesis/id), its own DB/schema, its own reward
  chain + MCMC + conflict resolution + rollback. An L1 is specialized: an
  order-match chain accepts only `BLOCKTYPE_ORDER_*`; a contract chain only
  `BLOCKTYPE_CONTRACT_*`.
- **Anchor** — an L1 milestone node posts a compact checkpoint (its current
  reward-head hash + height + a Merkle root of confirmed L1 blocks) into an L0
  `BLOCKTYPE_CROSSTANGLE` block. L0 confirmation of that block *finalizes* the
  referenced L1 state. This is the "L0 confirms anchors" hybrid.
- **Peg** — value moves between layers via the existing subtangle bridge,
  generalized to bidirectional: peg-in locks an L0 UTXO and issues a
  **wrapped** (deposit-receipt) token on L1 (not a native mint — L0 alone
  mints the system coin); peg-out burns the wrapped token on L1 and releases
  the original L0 UTXO. Anchors are what make peg-out safe (L0 won't release
  until the burn is anchored/final).
- **Vault** — the L0 UTXO address that holds locked peg-in collateral. The
  vault key scheme is: **(a)** a configurable threshold-m-of-n multisig of L1
  milestone node keys, **(b)** spendable only after the corresponding peg-out
  anchor is L0-confirmed, **(c)** enforced in `BridgeService` via an
  M-of-N signature check + anchor-finality gate before any L0 release
  transaction is constructed.

### 1.2 Why this fits the existing code

- The consensus core (`MCMCService`, `TipsService`, `ServiceBaseConfirmation`,
  `ServiceVerifyReward`) is already **stateless POJOs operated via a per-call
  `BlockStoreInterface`**. Running N consensus instances means N
  `NetworkParameters` + N stores, not rewriting the math.
- `bigtangle-subtangle` already demonstrates **own `ServerStart` + port + DB +
  config + HTTP parent link** — the L1 *process template*.
- `bigtangle-order` is already being extracted to depend only on
  `bigtangle-servercore` (recent commits `fe3251292`, `4ea793f13`) — the L1
  *logic* module template.
- `BLOCKTYPE_CROSSTANGLE` + `Transaction.toAddressInSubtangle` already carry
  cross-tangle payload — the anchor/peg *transport*.

---

## 2. Module / Repo Structure (same repo)

Keep the monorepo. Add a layer concept by **packaging** and a `chainId`
scoping, not by moving code between modules.

```
blockchain/
  bigtangle-core            # UNCHANGED data model + BlockType (add 1 type)
  bigtangle-servercore      # consensus + validation, parameterized by chainId
  bigtangle-mcmc            # MCMC engine (already per-call store)
  bigtangle-order           # L1 logic: ordermatch + contract engine
  bigtangle-server          # L0 runnable node  (Layer 0 runtime)
  bigtangle-subtangle       # → renamed conceptually to the L1 runtime template
  bigtangle-l1-ordermatch   # NEW: runnable L1 ordermatch node
  bigtangle-l1-contract     # NEW: runnable L1 contract node
  bigtangle-bridge          # NEW: shared anchor + peg logic (used by all L1s)
```

New runnable nodes (`bigtangle-l1-*`) are thin: a `ServerStart` + config that
boots a *scoped* subset of beans (their block type only + the consensus loop +
the bridge). They depend on `bigtangle-servercore` + `bigtangle-bridge` +
`bigtangle-order` (for the engine), mirroring how `bigtangle-subtangle` depends
on `bigtangle-server` today.

---

## 3. Key Design Mechanisms

### 3.1 `chainId` — the one new concept threaded everywhere

Add a `chainId` (String, e.g. `"L0"`, `"ordermatch"`, `"contract"`) to:
- `NetworkParameters` (a field; L0 = `"L0"`, each L1 its own).
- `BlockStoreInterface` / `DatabaseFullBlockStoreBase` — either **(a)** a
  `chain_id` column on every table (preferred long-term) or **(b)** one
  schema/DB-prefix per chain (lowest-risk for phase 1). Decision: start with
  **(b) per-DB**, add `chain_id` columns in a later phase.
- The DB locks (`LOCKID`) — already per-DB, so naturally isolated under (b).

This dissolves the "there is one chain" assumption without rewriting the math.

### 3.2 Block-type scoping per layer

- `NetworkParameters.getAllowedBlockTypes()` → `Set<BlockType>`.
  - L0: `{INITIAL, TRANSFER, TOKEN_CREATION, REWARD, CROSSTANGLE(anchor)}`
  - ordermatch L1: `{INITIAL, TRANSFER, REWARD, ORDER_OPEN, ORDER_CANCEL, ORDER_EXECUTE, CROSSTANGLE(peg)}`
  - contract L1: `{INITIAL, TRANSFER, REWARD, CONTRACT_EVENT, CONTRACTEVENT_CANCEL, CONTRACT_EXECUTE, CROSSTANGLE(peg)}`
- `ServiceBaseCheck.checkBlockBeforeSave` rejects blocks whose type isn't in the
  set. One gate, enforced at ingest.

### 3.3 Anchor (L1 → L0 checkpoint)

- New payload class `LayerAnchor` (in `bigtangle-bridge`):
  `{ chainId, l1RewardHeadHash, l1Height, confirmedRoot, sig }`, signed by the
  L1 milestone node's key. Carried in a `BLOCKTYPE_CROSSTANGLE` tx `data`.
  In Phase 2.5 this is extended with `{ spvProof }`.
  The `confirmedRoot` is a Merkle root of all L1 blocks confirmed in the anchor
  window; it enables SPV verification and future light-client sync.
- L1 schedules an anchor post every N reward milestones (configurable, default
  N≈10) into L0. N is a trade-off: lower = faster finality but more L0
  overhead; higher = cheaper but longer peg-out latency.
- **Validation levels** (3 tiers, phased):
  1. **Signature + structural** (Phase 2): L0 verifies the anchor's signature
     against the registered L1 milestone key and checks field sanity.
  2. **SPV path proof** (Phase 2.5, in scope): The anchor payload is extended
     with a compact SPV proof (a Merkle path from the anchor's `confirmedRoot`
     back to the L1 genesis, plus a chain of milestone headers). L0 verifies
     this proof to gain *programmatic confidence* that the anchor references a
     valid L1 chain without replaying it. This moves the anchor from "trust the
     key" to "verify the chain."
  3. **Full fraud proof** (future): L0 accepts fraud proofs submitted by L1
     watchers; a valid fraud proof invalidates an anchor retroactively.
  The Phase 2 milestone node key is an explicit trust root, upgraded to SPV
  verification in Phase 2.5 before peg-out goes live in Phase 3.
- **Anchor incentive**: the L1 milestone node that posts the anchor is credited
  a small L0 reward (configurable amount, deducted from a per-L1 anchor-fee
  pool pre-funded at L1 genesis). This aligns the milestone node's incentive
  with anchor liveness.
- **Fork resolution**: an L1 can fork before an anchor is posted. Peers resolve
  pre-anchor forks via the standard MCMC tip-selection weight (highest
  cumulative weight wins). Once an anchor is L0-confirmed, the anchored branch
  is *canonical* — all L1 nodes MUST switch to it. A rollback on L0 that
  orphans an anchor causes the corresponding L1 tip to lose canonical status;
  the L1 then falls back to the pre-anchor fork-choice rule.
- **Anchor liveness fallback**: if anchors stop for > T seconds (configurable,
  default T≈10× the expected anchor interval), the L1 enters a **degraded
  mode**: consensus continues but peg-out is suspended and a warning flag is
  raised. On anchor resumption, the L1 posts a catch-up anchor covering the
  gap. Peg-out remains gated on anchor finality — there is no timeout that
  bypasses it.

### 3.4 Peg (bidirectional, via generalized subtangle bridge)

- **Peg-in**: L0 `BLOCKTYPE_CROSSTANGLE` locks UTXO to a vault address +
  sets `toAddressInSubtangle` (existing field) → L1 bridge observes it, issues
  the wrapped token on L1 (`giveMoney`, existing `SubtangleService.giveMoney`).
- **Peg-out**: L1 burn tx → included in an L1 anchor → L0 bridge, once the
  anchor is L0-confirmed, releases the locked L0 UTXO to the requester. This is
  the new direction; today subtangle is effectively one-way.

### 3.5 L1 Bootstrapping & Sync

A new L1 node must catch up to the current tip. Two sync paths, used together:

1. **Peer sync (fast)**: the L1 node connects to L1 peers and requests the full
   block history from genesis to current tip. Blocks are validated against the
   L1's own `NetworkParameters` and consensus rules. This is the primary sync
   path and reuses the existing block-propagation protocol.
2. **Anchor-assisted sync (trust-minimized)**: once caught up via peers, the
   node cross-references L0 anchor blocks (keyed by `chainId`) to verify the
   canonical tip. If the peer-supplied chain diverges from the L0-anchored
   chain, the node discards the divergent branch and re-syncs from an L0 anchor
   checkpoint. This is a safety net, not the primary sync path.

The anchor payload's `confirmedRoot` also enables **light-client sync**: a
light node can download only L0 anchors and verify SPV proofs to confirm L1
state without replaying the full L1 history. This is a Phase 4+ feature.

### 3.6 Resource Model & Scale

Phase 1–3 targets **≤5 L1 chains** with each L1 in its own JVM process
(one `ApplicationContext` per chain). This is the simplest isolation model.

For scale beyond ~10 L1s, the per-process model becomes resource-heavy. The
design preserves two future consolidation paths:
- **Shared process, scoped beans**: multiple `ApplicationContext` instances in
  one JVM, each with its own DB connection pool and thread pool.
- **`chain_id` column migration**: one shared DB with a `chain_id` column on
  every table (deferred from Phase 1 per section 3.1). Enables a single DB
  server for multiple chains.

Neither path changes the consensus logic — only the deployment topology.

---

## 4. Phased Roadmap

### Phase 0 — Foundations (no behavior change)
Goal: make "one chain" stop being a global assumption, without changing runtime.

1. Add `chainId` to `NetworkParameters`; L0/MainNetParams = `"L0"`.
2. Add `getAllowedBlockTypes()` to `NetworkParameters`; L0 returns the full
   current set (so nothing is rejected yet).
3. Add the gate `ServiceBaseCheck.checkBlockBeforeSave` → reject disallowed
   types (no-op for L0 since L0 allows all current types).
4. Introduce `bigtangle-bridge` module (empty skeleton + `LayerAnchor` data class).
5. Tests: existing suite must pass unchanged.

**Exit criteria:** `mvn test` green; `chainId`/allow-set plumbed but inert.

### Phase 1 — L0/L1 module + runtime split
Goal: boot a *second* bigtangle node that is a real independent chain.

1. Generalize `bigtangle-subtangle`'s `ServerStart`/config into a reusable
   **L1 node bootstrapper**: own `NetworkParameters` subclass (distinct genesis
   via `UtilGeneseBlock.createGenesis` with new params), own DB, own port.
2. Create `bigtangle-l1-ordermatch`: thin runnable node, allow-set =
   `{ORDER_*}`. It runs the full consensus loop (its own `MCMCStart` +
   `UpdateChainService` + `RewardService`) against its own DB.
3. Validate block-type scoping end-to-end: an order block is accepted on the
   ordermatch L1, rejected on L0.
4. Flip `ServiceBase.enableOrderMatchExecutionChain` to true *only* on the L1
   (via `NetworkParameters`), so order results form the L1's own confirmed chain.
   On L0, this flag remains `false` — L0 no longer processes order blocks.
   **Migration**: L0 nodes soft-fork to reject new order blocks at a
   configurable block height. Existing confirmed order history on L0 remains
   immutable; new orders go exclusively to the L1.

**Exit criteria:** two nodes running, independent reward chains; orders confirm
on the L1, not on L0.

### Phase 2 — Anchor (L0 finalizes L1)
Goal: L1 checkpoints become L0-finalized.

1. Implement `LayerAnchor` posting from L1 → L0 (scheduled, every N milestones).
2. L0 anchor validation (signature + structural) + storage index
   (`store.saveAnchor`).
3. Anchor confirmation hook in `ServiceBaseConfirmation` (CROSSTANGLE arm): mark
   the referenced L1 head as `L0-confirmed`.
4. Implement anchor incentives: the L1 milestone node posting the anchor
   receives a small L0 reward from the per-L1 anchor-fee pool.
5. Tests: anchor round-trip; reorg on L0 invalidates a not-yet-confirmed anchor;
   fork resolution picks the anchored branch.

**Exit criteria:** an anchored L1 head is provably finalized by L0 (under the
milestone-key trust model).

### Phase 2.5 — SPV anchor hardening
Goal: replace trust-in-key with cryptographic verification of L1 chain validity.

1. Extend `LayerAnchor` payload with compact SPV proof (Merkle path from
   `confirmedRoot` to L1 genesis + chain of milestone headers).
2. L0 anchor validation verifies the SPV proof — structural pass *and* chain
   consistency pass required for confirmation.
3. Tests: valid anchor confirmed; anchor with tampered SPV proof rejected;
   anchor referencing a fork not rooted in genesis rejected.

**Exit criteria:** L0 confirms only anchors backed by a valid SPV proof.
Peg-out (Phase 3) MUST NOT ship before Phase 2.5 is done.

### Phase 3 — Bidirectional peg
Goal: move BIG/tokens between layers safely. **Gated on Phase 2.5 completion.**

1. Generalize `SubtangleService` into `BridgeService` (in `bigtangle-bridge`):
   peg-in (existing one-way) + peg-out (new, gated on SPV-verified anchor
   finality).
2. Implement vault key management: configurable threshold M-of-N multisig of L1
   milestone node keys. `BridgeService` constructs L0 release transactions only
   after collecting M signatures AND confirming the anchor is L0-finalized with
   a valid SPV proof.
3. Vault address + lock/burn transaction validation in `ServiceBaseCheck`
   (CROSSTANGLE arm).
4. Replay protection: a peg-in UTXO can be claimed on L1 only once (enforced by
   a spent-UTXO set on the L1 bridge); a peg-out burn only released on L0 once
   (enforced by the L0 vault UTXO set).
5. Tests: peg-in then peg-out round-trip; double-claim rejection; peg-out
   blocked before anchor finality; peg-out with insufficient multisig signatures
   rejected.

**Exit criteria:** value can move L0→L1→L0 with no inflation/loss.

### Phase 4 — Second L1 (contracts) + hardening
Goal: prove the template generalizes; productionize.

1. `bigtangle-l1-contract` runnable node, allow-set = `{CONTRACT_*}`.
2. Generalize the existing hardcoded Lottery contract path; define the contract
   L1's execution model.
3. Observability: per-chain metrics, sync health, anchor latency.
4. Documentation + the `bigtangle-seeds` discovery extended to register L1 nodes
   by `chainId` (the seed already supports `{url, servertype}` — `servertype`
   becomes the chainId).

**Exit criteria:** two distinct L1 chains running, each independently
consensus-secured and L0-anchored.

---

## 5. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **`BlockType` ordinal is persisted in DB** — adding types must append only | consensus break | Add new L1-only types at the *end* of the enum (contract already says so). L0 nodes simply never see them. |
| **Single `NetworkParameters` Spring bean** wired everywhere | blocks multi-chain boot | Phase 0 plumbs `chainId`; Phase 1 uses per-context params (one Spring `ApplicationContext` per chain = one process per chain, lowest-risk). |
| **Anchor key compromise before SPV** (Phase 2 milestone-key trust window) | invalid L1 state could be L0-finalized | The trust window exists only in Phase 2; Phase 2.5 adds SPV verification. Peg-out (Phase 3) is gated on Phase 2.5 completion. The window is explicitly temporary. |
| **Vault key compromise** (multisig threshold breached) | permanent loss of all pegged value | Vault uses threshold M-of-N multisig; M is configurable (recommended M > N/2). Keys are held by independent L1 milestone node operators. A future upgrade can add timelock + social recovery. |
| **Anchor liveness failure** (milestone node offline) | peg-out stalls; L1 enters degraded mode | Degraded mode allows consensus to continue; peg-out is suspended but no funds are at risk. Anchor incentive aligns milestone node operators with liveness. |
| **Reward chain references L1 block types today** (`ServiceBaseReward.getListedBlockOfType`) | L0 reward would try to include L1 blocks | Phase 1 scopes `getListedBlockOfType` by the chain's allow-set. |
| **Confirmation switches reference order/contract arms** (`ServiceBaseConfirmation`) | dead arms on L0 | Leave arms in place but unreachable on L0 (gated by allow-set). No deletion needed. |
| **DB lock collisions** if chains share a DB | cross-chain corruption | Phase 1 mandates one DB/schema per chain; `chain_id` columns deferred. |
| **Peg-out before anchor final** | funds loss | Peg-out release is *gated on L0 confirmation of the anchor with valid SPV proof* — enforced in `BridgeService`. No SPV proof = no release. |
| **SPV proof size / verification cost** | L0 anchor processing overhead | SPV proof is logarithmic in L1 chain length (Merkle path). L0 verifies only the proof, not the full L1 chain. Acceptable overhead for the anchor interval rate. |

---

## 6. Out of Scope (explicitly)

- No smart-contract VM (contracts remain predefined types; a VM is a separate
  future effort).
- No sharding of a single L1; each L1 is one independent chain.
- No change to the MCMC algorithm itself (`TipsService.performTransition` etc.
  unchanged).
- No cross-L1 bridges (L1↔L1) — only L1↔L0.
- No `chain_id` column migration in phase 1 (per-DB isolation instead).

---

## 7. Concrete starting points (Phase 0, file-level)

1. `bigtangle-core/.../params/NetworkParameters.java` — add `protected String
   chainId = "L0";` + `public Set<BlockType> getAllowedBlockTypes()`.
2. `bigtangle-core/.../core/BlockType.java` — no change in Phase 0. New L1-only
   block types (if needed beyond the existing ORDER/CONTRACT set) are appended
   at the end of the enum in Phase 1, *before* the L1 nodes go live. Existing
   ORDER/CONTRACT types suffice for the first L1s but additional types (e.g.
   `ORDER_FILL`, `CONTRACT_DEPLOY`) may be needed; the decision is made per-L1
   during Phase 1 based on the actual ordermatch/contract engine needs.
3. `bigtangle-servercore/.../service/base/ServiceBaseCheck.java`
   `checkBlockBeforeSave` (line ~1788) — add allow-set gate.
4. New module `bigtangle-bridge/pom.xml` + `LayerAnchor.java` skeleton.
5. Root `pom.xml` — register `bigtangle-bridge` module.

Each is small and independently testable.
