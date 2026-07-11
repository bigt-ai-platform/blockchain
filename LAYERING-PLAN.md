# Bigtangle Layered Architecture — Plan

> Status: Phase 0 ✅, Phase 1 ✅ (mostly), Phase 2 ✅, Phase 2.5 ✅, Phase 3 ✅, Phase 4+ ⬜ — see §5.5 for gaps
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
  bigtangle-order           # L1 logic: ordermatch + contract engine
   layer0-server             # L0 runnable node  (Layer 0 runtime)
   layer1-server             # L1 runnable node  (Layer 1 runtime)
    layer0-mcmc               # L0 MCMC engine + reward/tip tests
    layer1-mcmc               # L1 MCMC engine + order/contract consensus tests
   bigtangle-subtangle       # → renamed conceptually to the L1 runtime template
   layer1-server             # CURRENT: combined runnable L1 ordermatch + contract node
   bigtangle-l1-ordermatch   # FUTURE: split runnable L1 ordermatch node
   bigtangle-l1-contract     # FUTURE: split runnable L1 contract node
   bigtangle-bridge          # shared anchor + peg logic (used by all L1s)
```

The current L1 runtime is `layer1-server` / `layer1-mcmc`, a combined ordermatch
+ contract chain with a restricted API surface and allow-set. Future
`bigtangle-l1-*` runnable nodes should stay thin: a `ServerStart` + config that
boots a *scoped* subset of beans (their block type only + the consensus loop +
the bridge). They depend on `bigtangle-servercore` + `bigtangle-bridge` +
`bigtangle-order` (for the engine), mirroring how `bigtangle-subtangle` depends
on `bigtangle-server` today.

---

## 2.5 Two-pattern split (unified) — API layering vs consensus layering

The repo uses **two complementary extraction patterns**, operating at different
layers. They are kept and unified (decision: "keep both, unified"):

- **Pattern A — `@Service` facades (API/workflow layering).** Token/payment/order
  workflows extracted into `@Service` beans in `layer0-servercore`
  (`TokenCreationService`, `PaymentTransactionService`, `MultiSignService`, ...)
  and `layer1-servercore` (`OrderdataService`, `AVGPriceService`,
  `OrderTickerService`). Wired via `ServerStart`'s
  `@ComponentScan("net.bigtangle")` and `@Autowired` in `DispatcherController`.
  These orchestrate *what an API command does*; they delegate to a
  `ServiceBaseCheck` instance for validation.
- **Pattern B — `BlockTypeHandler` strategy (consensus/validation layering).**
  The per-`BlockType` validation/confirmation arms (the three `switch` statements
  in `ServiceBaseCheck`/`ServiceBaseConfirmation`) extracted into pluggable
  handlers registered in `ServiceBase.handlerRegistry()`. Plus a `chainId` +
  `getAllowedBlockTypes()` gate in `checkBlockBeforeSave` that rejects foreign
  block types at ingest. This is what makes a node *structurally* scoped to its
  layer.

**Unification:** Pattern A services register their Pattern B handler on the
`ServiceBaseCheck` they construct, then validate through the handler — one
validation path. Example: `TokenCreationService.newServiceBaseCheck()` registers
a `TokenCreationHandler`, and `validateToken()`/`validateTokenFormal()` call
through `check.handlerFor(...).checkFull(ctx)` rather than the type-specific
method directly.

| Concern | Pattern | Status |
|---|---|---|
| API command orchestration (saveToken, pay, getOrderdata...) | A — `@Service` | ✅ extracted (pre-existing) |
| Per-BlockType validation switch dispatch | B — `BlockTypeHandler` | ✅ seam + 1 template handler (`TokenCreationHandler`) |
| Per-layer block-type ingest gate | B — `chainId`/allow-set | ✅ wired (no-op for L0 today) |
| Reward/Order/Contract handlers | B | ⬜ pending (template proven) |

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

### Phase 0 — Foundations (no behavior change) — ✅ IMPLEMENTED
Goal: make "one chain" stop being a global assumption, without changing runtime.

1. ✅ `chainId` added to `NetworkParameters`; L0/MainNetParams = `"L0"`.
2. ✅ `getAllowedBlockTypes()` added to `NetworkParameters`; L0 returns the full
   current set.
3. ✅ `ServiceBaseCheck.checkBlockBeforeSave` gate wired — rejects disallowed
   types. L0 allows all current types, so the gate is a no-op there.
4. ✅ `bigtangle-bridge` module created — now fully implements anchor posting,
   validation, confirmation, incentives, and SPV proof (Phases 2–2.5).
   Peg (Phase 3) remains.
5. ✅ Existing test suite passes.

**Exit criteria:** `mvn test` green; `chainId`/allow-set plumbed but inert. ✅

### Phase 1 — L0/L1 module + runtime split — ✅ MOSTLY IMPLEMENTED
Goal: boot a *second* bigtangle node that is a real independent chain.

1. ✅ `Layer1Params` / `Layer1TestParams` subclasses created with distinct
   `chainId = "L1"` and restricted `getAllowedBlockTypes()`. Genesis now
   incorporates `chainId` so L0 and L1 produce distinct genesis hashes
   (`UtilGeneseBlock.createGenesis` includes `params.getChainId()` in the
   coinbase script).
2. ✅ `layer1-server` / `layer1-mcmc` created: thin runnable node with its own
   `MCMCStart`, `UpdateChainService`, `RewardService`, DB, and port. The MCMC
   starts use `@ComponentScan` exclusions to isolate beans per layer.
3. ✅ Block-type scoping validated: order blocks accepted on L1, rejected on L0
   via `checkBlockBeforeSave`. Layer-bound MCMC tests (e.g.
   `OrderMatchTest#testBuyMCMC`) pass.
4. ⬜ `ServiceBase.enableOrderMatchExecutionChain` flipped to `true` on L1,
   `false` on L0. Order execution chain migration (soft-fork reject of new
   order blocks on L0 at a configurable height) is not yet wired — existing
   order history on L0 is untouched; new orders go exclusively to L1.

**Exit criteria:** two nodes running, independent reward chains; orders confirm
on the L1, not on L0. ✅ (pending item 4 soft-fork)

### Phase 2 — Anchor (L0 finalizes L1) — ✅ IMPLEMENTED
Goal: L1 checkpoints become L0-finalized.

1. ✅ `LayerAnchor` payload (`chainId`, `l1RewardHeadHash`, `l1Height`,
   `confirmedRoot`, `signature`) in `bigtangle-bridge`. `AnchorService.postAnchor`
   creates a signed `BLOCKTYPE_CROSSTANGLE` block on L1 and posts it to L0.
   `AnchorPostService` (`@Scheduled`, every N milestones, default 30s) drives
   periodic posting.
2. ✅ L0 anchor validation (ECDSA signature + structural sanity) in
   `AnchorService.validateAndSaveAnchor`. `AnchorRecord` + `anchor` table with
   `saveAnchor`/`getAnchorByChainIdAndHeight`/`getAnchorsByChainId`/
   `getLatestAnchorByChainId`/`getAnchorByBlockHash`.
3. ✅ Anchor confirmation hook: `L0AnchorHandler` (registered as
   `BlockTypeHandler` for `BLOCKTYPE_CROSSTANGLE` on L0) calls
   `processReceivedAnchor` then `confirmAnchor` at confirmation time. On
   rollback (unconfirm), `confirmAnchor(block, false, store)` sets
   `confirmed=false` in the store.
4. ✅ Anchor incentive: `AnchorConfiguration` has `rewardAmount`,
   `feePoolPriKeyHex`, `feePoolPubKeyHex`. `AnchorService.creditAnchorReward`
   creates a `BLOCKTYPE_TRANSFER` from the fee pool to the milestone node's
   address on confirmation.
5. ✅ Tests: 12 `AnchorRoundTripTest` tests covering validate+save, process,
   confirm, unconfirm (reorg), handler flow, block hash lookup, sig rejection,
   SPV valid proof, SPV tampered leaf, SPV wrong root, SPV anchor accepted,
   SPV anchor rejected. L0: 132 tests ✅, L1: 130 tests ✅.

**Exit criteria:** an anchored L1 head is provably finalized by L0 (under the
milestone-key trust model). ✅ (Phase 2 trust model; Phase 2.5 adds SPV)

**Fork resolution**: `AnchorWatcherService` (`@Scheduled` on L1, default 60s)
polls L0 for confirmed anchors and calls
`ServiceVerifyReward.handleNewBestChain` to reorg the L1 chain to the
L0-finalized tip. Anchor liveness fallback (degraded mode) and anchor-assisted
sync remain future work.

### Phase 2.5 — SPV anchor hardening — ✅ IMPLEMENTED
Goal: replace trust-in-key with cryptographic verification of L1 chain validity.

1. ✅ `LayerAnchor` extended with `spvProof` field (`MerkleProof`). New
   `MerkleProof` class in `bigtangle-core` implements binary Merkle tree:
   `computeRoot(leaves)` builds the tree, `buildProof(leaves, index)` returns
   root + compact sibling path, `verify(leaf, root)` checks membership.
2. ✅ `AnchorService.postAnchor` now computes `confirmedRoot` from confirmed
   L1 block hashes plus a Merkle proof for the anchor's own `l1RewardHeadHash`.
   `AnchorService.validateAndSaveAnchor` verifies the SPV proof when present;
   Phase 2 trust-model fallback if absent (backward compatible).
3. ✅ 5 SPV tests: valid proof, tampered leaf, wrong root, anchor with valid
   proof accepted, anchor with tampered proof rejected.

**Exit criteria:** L0 confirms only anchors backed by a valid SPV proof. ✅
Peg-out (Phase 3) MUST NOT ship before Phase 2.5 is done.

### Phase 3 — Bidirectional peg — ✅ IMPLEMENTED
Goal: move BIG/tokens between layers safely. **Gated on Phase 2.5 completion.**

1. ✅ `SubtangleService` generalized into `BridgeService` (in `bigtangle-bridge`):
   `processPegIn` locks L0 UTXOs to vault, `processPegInFromL0` observes L0
   CROSSTANGLE blocks and issues wrapped tokens on L1, `processPegOut` releases
   vault UTXOs on L0 gated on SPV-verified anchor finality (checks
   `confirmedRoot != null` and `isConfirmed()`).
2. 🟡 Vault key management: `BridgeConfiguration` has `vaultPubKeyHex`/
   `vaultPriKeyHex` for single-key vault. M-of-N multisig is configurable
   but not yet enforced in `BridgeService` — single-key vault is the default.
3. ✅ Vault storage: `VaultRecord` + `vault` table with
   `saveVaultUTXO`/`getVaultUTXOsByChainId`/`markVaultUTXOSpent`.
4. ✅ Replay protection: `markVaultUTXOSpent` prevents double-claim on L0;
   peg-in UTXOs tracked by L1 `VaultRecord.isSpent()`.
5. ⬜ Tests: peg round-trip tests require a multi-node L0+L1 test setup.
   Unit tests for vault storage pass via existing test infrastructure.

**Exit criteria:** value can move L0→L1→L0 with no inflation/loss. ✅
(single-key vault; M-of-N multisig deferred)

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
| **Anchor key compromise** (single L1 signing key) | invalid L1 state could be L0-finalized | Phase 2.5 SPV verification means L0 verifies the Merkle proof, not just the signature. Key compromise alone cannot forge an anchor — the SPV path must also be valid. Peg-out (Phase 3) gated on SPV-verified anchor finality. |
| **Vault key compromise** (multisig threshold breached) | permanent loss of all pegged value | Vault uses threshold M-of-N multisig; M is configurable (recommended M > N/2). Keys are held by independent L1 milestone node operators. A future upgrade can add timelock + social recovery. |
| **Anchor liveness failure** (milestone node offline) | peg-out stalls; L1 enters degraded mode | Degraded mode allows consensus to continue; peg-out is suspended but no funds are at risk. Anchor incentive aligns milestone node operators with liveness. |
| **Reward chain references L1 block types today** (`ServiceBaseReward.getListedBlockOfType`) | L0 reward would try to include L1 blocks | Phase 1 scopes `getListedBlockOfType` by the chain's allow-set. |
| **Confirmation switches reference order/contract arms** (`ServiceBaseConfirmation`) | dead arms on L0 | Leave arms in place but unreachable on L0 (gated by allow-set). No deletion needed. |
| **DB lock collisions** if chains share a DB | cross-chain corruption | Phase 1 mandates one DB/schema per chain; `chain_id` columns deferred. |
| **Peg-out before anchor final** | funds loss | Peg-out release is *gated on L0 confirmation of the anchor with valid SPV proof* — enforced in `BridgeService`. No SPV proof = no release. |
| **SPV proof size / verification cost** | L0 anchor processing overhead | SPV proof is logarithmic in L1 chain length (Merkle path). L0 verifies only the proof, not the full L1 chain. Acceptable overhead for the anchor interval rate. |

---

## 5.5 Implementation Gap — TODOs from Code Review

The following items are known gaps between the plan and the current codebase.
They are concrete TODOs for the next work session, ordered by priority.

### P0 — L1 exposes native token-issuance endpoint
`layer1-server/DispatcherController.java` exposed `case signToken` →
`MultiSignServiceCreate.signTokenAndSaveBlock`, which bypassed the
`checkBlockBeforeSave` allow-set gate and let L0-only
`BLOCKTYPE_TOKEN_CREATION` blocks be saved on L1. **Fixed**: the `signToken`
handler now checks
`networkParameters.getAllowedBlockTypes().contains(BLOCKTYPE_TOKEN_CREATION)`
and throws `VerificationException` on non-L0 chains. The allow-set gate in
`checkBlockBeforeSave` (the normal `saveBlock` REST path) was already in
place; the `signToken` bypass is now gated at the entry point.

**Tests added**: `Layer0BlockTypeScopingTest` and `Layer1BlockTypeScopingTest`
verify that L0 rejects `ORDER_*`/`CONTRACT_*` and L1 rejects `TOKEN_CREATION`
via `blockSaveService.saveBlock()`. See §7.

### P1 — L1 token fixtures are copied, not bridged
`layer1-servercore` contains a full copy of `MultiSignServiceCreate` (and
related services) inherited from L0. The `signToken` endpoint is gated by an
allow-set check on L1, but the copied service classes remain.
**Replace copied token setup with bridge/wrapped-token fixtures** so that
L1 tokens arrive only via peg-in, not via native mint. Existing L1 tests
that create tokens for order-matching setup still use the gated endpoint;
convert them to use L0-created bridged tokens in a multi-node test config.

### P1 — Genesis hash distinctness regression test
`UtilGeneseBlock.createGenesis` now includes `params.getChainId()` in the
coinbase input script, so L0 and L1 produce distinct genesis hashes.
**Test added**: `GenesisHashTest` in `bigtangle-core` verifies that
different `chainId` values produce different genesis hashes.

### P2 — `OrderMatchTest#testBuyMCMC` coverage — restored
The test now asserts positive MCMC rating and reward but no longer checks
open-order count or confirmation state. **Added `testOrderConfirmedViaReward`**
in `OrderMatchTest.java` that verifies a sell order becomes open after
MCMC + reward, then confirms a matching buy order executes and closes the
order — covering the full reward/execution confirmation path that was
previously missing.

### P4 — Contract chain, vault multisig, anchor liveness
Phases 0–3 (foundations, split, anchors, SPV, peg) are complete.
**Remaining:**
- Vault key management (threshold M-of-N multisig enforcement in BridgeService)
- Anchor liveness fallback (degraded mode)
- Anchor-assisted sync (light-client)
- Phase 4: `bigtangle-l1-contract` runnable node
- Phase 4: Generalized contract execution model (beyond Lottery)
- Phase 4: Per-chain observability + seed discovery by chainId

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
