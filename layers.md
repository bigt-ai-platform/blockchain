# Bigtangle Layered Architecture — Concept & Technical Reference

This document describes the **multi-layer** design of Bigtangle: a settlement
chain (Layer 0), a family of purpose-specific application chains (Layer 1), and
the bridge/vault machinery that controls value flow between them.

> Status: implemented. The earlier `LAYERING-PLAN.md` roadmap has been fully
> executed and removed (see git history); this document is the reference for
> the current state, including the PoS rewrite (MCMC removed) and the
> vault-controlled L0↔L1 peg.

---

## 1. Concept

Bigtangle is not a single chain. It is a family of chains that share the same
consensus core and data model but are isolated from each other by a
`chainId` and a per-chain **allowed block-type set**. The chains are grouped
into two layers:

- **Layer 0 (L0)** — the *settlement chain*. It is the only chain that mints
  the system coin `bc` (`BIGTANGLE_TOKENID`). It hosts token creation,
  transfer/payment, governance, file/user data and the **PoS validator set**
  for the network. Its `chainId` is `"L0"`.
- **Layer 1 (L1)** — *application chains*. Each L1 is a separate chain with its
  own genesis hash, own database, own PoS validator set and a restricted block
  set specialised for one job (order matching, EVM contracts, payments, …).
  **No L1 mints `bc`** (`genesisMintsBIG() == false`).

### 1.1 Why two layers

1. **Throughput separation.** Order matching and smart-contract execution have
   very different resource profiles from settlement. Isolating them on
   dedicated chains keeps heavy L1 activity from congesting the settlement
   chain.
2. **Protocol specialisation.** Each L1 accepts only the block types it needs
   (`ServiceBaseCheck.checkBlockBeforeSave` rejects foreign types). A node is
   *structurally* scoped to its layer, not just by convention.
3. **Independent security.** Each chain runs its own PoS validator set and
   stake, so an application chain's failure (fork, reorg, slashing) does not
   consume L0 finality.
4. **Finality via anchoring.** L1 chains periodically post a signed checkpoint
   (anchor) to L0; once the anchor's L0 block reaches Casper **finality**, the
   referenced L1 state is final.

### 1.2 The vault controls all token flow between layers

`bc` and tokens exist natively only on L0. To use value on an L1 chain, the
value must cross the layer boundary, and every crossing is mediated by the
**vault**:

- **L0 → L1 (peg-in):** an L0 UTXO is locked to the vault address; the L1
  chain mints a **wrapped** token 1:1 backed by that lock.
- **L1 → L0 (peg-out):** the wrapped token is burned on L1, the burn is
  anchored to L0, and L0 releases the *exact* locked UTXO.

The bridge enforces two hard invariants (per L1 chain, per token):

1. **Wrapped supply == locked L0 collateral** — the L1 mint is bound 1:1 to a
   specific L0 vault lock and can never exceed it.
2. **L1→L0 ≤ L0→L1** — cumulative released value can never exceed cumulative
   locked value (`sumUnspentVaultValue` gate in `BridgeService`).

A consequence is that **minting `bc` directly in an L1 genesis (as the dev/test
harness does today) violates invariant 1** — it creates L1 `bc` with no L0
vault backing. The intended bootstrap is a vault peg-in (see §5.2 and §8).

---

## 2. Layer / chain catalogue

| Chain | Module | `chainId` | `genesisMintsBIG` | Purpose | Allowed block types (additions beyond the base set) |
|---|---|---|---|---|---|
| L0 | `layer0-server` | `"L0"` | **true** | settlement, token issuance, transfer, governance, PoS set | `TRANSFER, TOKEN_CREATION, BEACON, CROSSTANGLE, USERDATA, FILE, GOVERNANCE, STAKE, SLASHING, EXIT` |
| L1-ordermatch | `l1-order-server` | `"ordermatch"` | false | order book / matching | `TRANSFER, BEACON, CROSSTANGLE, TOKEN_CREATION, ORDER_OPEN, ORDER_CANCEL, STAKE, SLASHING, EXIT` |
| L1-social | `l1-social-server` | `"SOCIAL"` | false | social payments | `TRANSFER, BEACON, CROSSTANGLE, STAKE, SLASHING, EXIT` |
| L1-evm | `l1-evm-server` | `"EVM"` | false | EVM smart contracts | `TRANSFER, BEACON, CROSSTANGLE, TOKEN_CREATION, EVM_DEPLOY, EVM_CALL, STAKE, SLASHING, EXIT` |
| L1-contract | `l1-contract-server` | `"contract"` | false | generic contract events | `TRANSFER, BEACON, CROSSTANGLE, CONTRACT_EVENT, CONTRACTEVENT_CANCEL, STAKE, SLASHING, EXIT` |
| L1-payment | `l1-payment-server` | `"PAYMENT"` | false | payments | `TRANSFER, BEACON, CROSSTANGLE, STAKE, SLASHING, EXIT` |
| L1-pai | `l1-pai-server` | `"PAI"` | false | (payment-app-specific) | `TRANSFER, BEACON, CROSSTANGLE, STAKE, SLASHING, EXIT` |

The `chainId` is an **identity that must be unique per chain**: anchors, vault
records and peg-in routing are all keyed on it (§5). `l1-social-server` and
`l1-payment-server` previously defaulted to the same id (`"PAYMENT"`); the
social defaults have been corrected to `"SOCIAL"` (`CHAIN_ID` env override).
Two live chains must never share a `chainId`, or a single L0 peg-in would mint
wrapped tokens on both and L0 could not distinguish their anchors.

`l1-nft-server` is also built by `helper/deploy.sh` but is a lighter node: it
registers `BLOCKTYPE_NFT`/`BLOCKTYPE_USERDATA` handlers (`Layer1HandlerConfiguration`)
and has no dedicated params class (no `chainId` override, no
`genesisMintsBIG()==false`), so it is **not yet a fully-specialised L1** and is
out of scope for the vault/bridge flow described here.

Every chain also allows `BLOCKTYPE_INITIAL` (the genesis block). `BlockType`
enum: `bigtangle-core/.../core/BlockType.java`.

- L0 params: `Layer0Params` / `Layer0TestParams`
  (`bigtangle-servercore/.../layer0/params/`), `chainId = "L0"`.
- L1 params live next to each server, e.g.
  `OrderMatchL1Params`/`OrderMatchL1TestParams`
  (`l1-order-server/.../layer1/params/OrderMatchL1Params.java`), chainId
  `"ordermatch"`.
- The `server.net=Test` property selects the `*TestParams` (unit-test genesis,
  ML-DSA-87 genesis pubkey) instead of the production params — see
  `Layer0NetworkConfiguration` and `OrderMatchL1NetworkConfiguration`.

Base params:
- `MainNetParams` (`bigtangle-core/.../params/MainNetParams.java`): `id =
  ID_MAINNET`, legacy EC genesis pubkey, 8 slots/epoch, DNS seeds.
- `TestParams`: `id = ID_UNITTESTNET` (`"Test"`), ML-DSA-87 genesis pubkey.
  `NetworkParameters.fromID("Test")` resolves to `MainNetParams` (genesis/address
  compatibility), while the layer modules map `server.net=Test` to their own
  `*TestParams`.

---

## 3. Architecture overview

```
                    Layer 0 — settlement chain (chainId "L0")
   token issuance · transfer/payment · governance · PoS validator set
   mints bc (genesisMintsBIG=true)
   ┌──────────────────────────────────────────────────────────────┐
   │  layer0-server  (runnable node)                              │
   │  bigtangle-servercore  (consensus/validation/staking core)   │
   │  L0AnchorHandler: validates & confirms L1 anchors (CROSSTANGLE)│
   └──────────────────────────────────────────────────────────────┘
            ▲ anchor post (L1 → L0, every ~30s)      ▲ peg-out release (L1→L0)
            │                                         │
   ┌────────┴─────────── bigtangle-bridge ────────────┴────────────┐
   │ AnchorService · BridgeService · L1CrosstangleHandler          │
   │ vault (peg-in locks L0 UTXOs; peg-out spends them)            │
   └───────────────────────────────────────────────────────────────┘
            ▲ wrapped issuance (L0→L1, peg-in)     ▲ L1 burn + anchor
            │
   ┌────────┴────────────────────────────────────────────────┐
   │ L1-ordermatch (chainId "ordermatch") · L1-evm "EVM" ·    │
   │ L1-social/payment "PAYMENT" · L1-contract · L1-pai        │
   │ own genesis · own DB · own PoS validator set              │
   │ genesisMintsBIG = false — bc only via vault peg-in        │
   └───────────────────────────────────────────────────────────┘
```

Each L1 node pulls L0's non-chain (side) blocks via `blocksFromNonChainHeight`
for cross-layer context (order blocks, anchored state) and runs its own beacon
chain.

---

## 4. Consensus — Proof of Stake on every chain

Each chain runs the same PoS engine, parameterised by its own `NetworkParameters`
and configured validator key.

### 4.1 Slots and epochs

- **Slot**: one time slot per proposer turn. Slot tick:
  `pos.slotIntervalMs` (default **12000 ms**), epoch base
  `1532896109000` (ms) — `SlotService` (`bigtangle-servercore/.../service/SlotService.java`).
- **Epoch**: `slotsPerEpoch` slots. Production sets **8** slots/epoch
  (`MainNetParams.setSlotsPerEpoch(8)`, ~1.6 min/epoch at 12 s slots).
  `SlotService.slotsPerEpoch()` reads `NetworkParameters.getSlotsPerEpoch()`
  (default **32**). Note: several comments/sites still hardcode `32` (e.g. the
  wall-clock `slot/32` division in `ValidatorDutyService`), so epoch arithmetic
  is not yet uniformly parameterised — the chain-epoch used by consensus is
  derived from confirmed chain length (`chainlength / slotsPerEpoch`).
- **Beacon**: each epoch produces a `BLOCKTYPE_BEACON` (reward block)
  referencing the previous beacon and the side blocks included in its slot.
  Beacon `chainlength` drives confirmation and finality.
- **Confirmation model**: blocks are confirmed when they are referenced by the
  beacon chain; confirmation is rolled back on reorg. `CHAINLENGTH_CUTOFF = 40`
  bounds the non-chain sync window.
- **Finality (Casper FFG)**: on top of (reversible) confirmation, a
  `CasperService` checkpoint is justified and then finalized (2/3 vote, FFG
  link rules) — `getLastFinalizedCheckpoint(store)` returns the immutable
  finalized checkpoint. The finalized branch can never be reorged; this is the
  finality that gates value flows out of the vault (§5.3, §11).

### 4.2 Staking (`StakeService`)

- **Minimum stake**: `MIN_STAKE = 32,000,000` BIG; maximum effective balance =
  same (`StakeService.java:48-56`).
- Deposit flow: a signed `stakeDeposit` transaction (data class
  `StakeDeposit`) spending a confirmed `bc` UTXO of the validator address,
  recorded in the stake table and locked until the withdrawal delay
  (canonical 256 epochs × 32 slots ≈ 8192 slots bond).
- **Activation epoch**: the deposit's chain epoch + `MAX_SEED_LOOKAHEAD` + 1,
  chain-derived (deterministic on every node) — `StakeService.java:145`.
- Deposit amount **accumulates** for an already-deposited pubkey; a reorg
  reverts the deposit (`StakeService.java:769`).
- Churn limit bounds validators entering/exiting per epoch
  (`MIN_PER_EPOCH_CHURN_LIMIT = 4`, else `activeCount / CHURN_LIMIT_QUOTIENT`).

### 4.3 Validator duty (`ValidatorDutyService`)

- **Proposer election** is deterministic from chain state (slot % active
  validator count, ordered by pubkey), not wall-clock leader election.
- **Warmup**: below `pos.warmupSlots` (default 32) confirmed chain length, only
  the **first** selection validator — the lexicographically-lowest pubkey among
  the validator set — proposes, so genesis grows linearly and deterministically
  (`ValidatorDutyService.java:49, 240, 314`).
- Validators propose beacons and attest to the current head; a validator
  activating on an L1 chain registers its own stake in that L1's chain DB.

### 4.4 Slashing / exit

`BLOCKTYPE_SLASHING` (misbehaviour proof) and `BLOCKTYPE_EXIT` (voluntary
withdrawal) are allowed on L0 and every L1. Slashing burns a fraction of the
validator's bond (`amount/32`, see `StakeService`); `SlashingService` reports
equivocation (conflicting votes from the same validator). The withdrawal delay
is defined in slots (`WITHDRAWAL_DELAY_SLOTS` ≈ 256 epochs × 32 slots) and
converted to epochs per network via `withdrawalDelayEpochs(slotsPerEpoch)`.

---

## 5. The bridge, vault and cross-layer flow (`bigtangle-bridge`)

### 5.1 Anchor — L1 → L0 checkpoint (`AnchorService`)

Every L1 node schedules `postAnchor` (`anchor.postIntervalMs`, default 30 s):

1. Computes the max confirmed reward head, collects confirmed L1 block hashes
   in the window and builds a **Merkle root** (`confirmedRoot`) plus an SPV
   path proof.
2. Builds a `LayerAnchor` payload `{ chainId, l1RewardHeadHash, l1Height,
   confirmedRoot, spvProof, burn (optional), signature }` and signs it with the
   L1 anchor key(s) (`anchor.priKeyHex`, or an M-of-N set — see below).
3. Wraps it in a `BLOCKTYPE_CROSSTANGLE` block and posts it to L0
   (`POST batchBlock`).
4. L0's `L0AnchorHandler` validates the anchor, records an `AnchorRecord`, and
   at **confirmation** marks it confirmed. On reorg the anchor is unconfirmed
   again. Confirmation is not sufficient to release value: peg-out additionally
   requires the anchor's L0 block to be **finalized** (§5.3).

Anchor authentication supports an **M-of-N quorum**, not just a single key:
`anchor.chainPubKeys` is a per-`chainId` registry of authorized signer public
keys and `anchor.chainSignersRequired` the threshold (default 1, single-key
fallback to the global `anchor.pubKeyHex`). `validateAnchor` verifies the
quorum via `LayerAnchor.verifyQuorum`. A single compromised key therefore
cannot forge an anchor for a chain that has its own registry entry.

An anchor reward (`anchor.rewardAmount`) is **not credited** in the current
code: `AnchorService.creditAnchorReward` is deliberately disabled because a
backed fee-pool spend was never implemented (the old path minted unbacked
value). It is a no-op today.

Because the anchor payload carries a Merkle proof of L1 state, L0 finalizes L1
state **without replaying L1**. L0 verifies the anchor signature (quorum), the
chain-id binding, and that the SPV proof is *internally consistent*
(`spvProof.verify(l1RewardHeadHash, confirmedRoot)`), but it cannot
independently check that `confirmedRoot` matches real L1 state — that guarantee
rests on the honesty of the anchor signer set (see §11, trust assumptions).
Peg-out is gated on the anchor's L0 **finality** (§5.3).

### 5.2 Peg-in — L0 → L1 (`BridgeService.processPegIn`, `processPegInFromL0`)

L0 side:
1. Caller submits a **signed** transaction (HTTP `processPegIn`) that spends one
   confirmed L0 UTXO and pays the **vault script** 1:1 (same amount *and* token).
   The input scriptSig is verified against the UTXO's scriptPubKey (ownership
   proof) — `BridgeService.java:188-240`.
2. The tx must declare the L1 beneficiary in `toAddressInSubtangle` and the
   destination L1 chain in `PegInInfo` data (`chainId`) — both covered by the
   input signature (`BridgeService.java:253-280`).
3. The tx is wrapped in a `BLOCKTYPE_CROSSTANGLE` block and saved; a
   `VaultRecord` (chain id, source outpoint, value, token, beneficiary) is
   recorded. Replay guard: a source outpoint can be locked only once.

L1 side (`PegInWatcherService.pollPegIns` → `processPegInFromL0`, every
`bridge.pegInPollMs`, default 15 s):
1. Queries L0 for the vault address's confirmed UTXOs.
2. For each unissued lock, hash-verifies the locking block, confirms the lock
   tx actually pays the configured vault for the same value, and that the lock
   declares **this** L1 chain — otherwise a single L0 deposit would mint on
   every L1 (1:N collateral multiplication is rejected).
3. `issueWrappedTokens` mints wrapped tokens to the beneficiary as a
   **zero-input CROSSTANGLE** issuance block, signed by the dedicated L1
   **issuance key** (`bridge.issuancePriKeyHex`; the vault key stays on L0).
4. The lock is recorded as issued (spent vault set) so it is never minted twice.

Consensus binding — `L1CrosstangleHandler.validateIssuance`
(`L1CrosstangleHandler.java:208`):
- zero-input value creation is only legal as an authenticated issuance: correct
  data class, declared `chainId ==` this chain, valid signature under
  `bridge.issuancePubKeyHex`, and **exactly one output** that matches the
  declared `lockAmount`, `lockTokenId` and `lockBeneficiary` 1:1.
- **Replay guard (R3)**: at confirmation the lock is recorded in the
  chain-derived `pos_state` table (`issuedlock_<chain:blockhash:index>`); a
  different block trying to re-issue the same lock is vetoed. An unconfirm
  frees the lock.

Result: **wrapped supply == locked L0 collateral** for every token.

### 5.3 Peg-out — L1 → L0 (`BridgeService.processPegOut`)

1. The L1 user burns the wrapped token; the burn (`AnchorBurn`: vault ref,
   amount, token, recipient) is embedded in the next anchor.
2. L0 (`AnchorWatcherService`/manual `processPegOut`) finds the anchor for that
   L1 chain and its embedded burn, resolves the `VaultRecord` and checks:
   - anchor is **confirmed** and its L0 block is **finalized** (Casper FFG —
     the anchor's confirmed chainlength is at or below the last finalized
     checkpoint; `BridgeService.isAnchorFinalized`), and the chain is not
     frozen (`anchor.disabledChains` freeze list),
   - burn amount == vault amount (all-or-nothing, **R5**; partial burns are
     rejected because the change UTXO would have no unspent `VaultRecord` and
     could never be released),
   - burn token == vault token,
   - **FLOW INVARIANT**: `burn.amount ≤ sumUnspentVaultValue(chainId, token)`
     — cumulative L1→L0 can never exceed cumulative L0→L1
     (`BridgeService.java`).
3. The release block spends the *actual* vault output (peg-in block hash, tx
   hash, index 0) to the burn recipient. The input is signed by the vault key
   (legacy P2PKH) or `vaultM` of the ordered multisig keys (P2SH M-of-N) —
   `signVaultRelease`. Unsigned/invalid releases are rejected by L0 consensus.
4. The vault is marked spent; a retry service (`PegOutRetryService`,
   `bridge.pegOutRetryMs`, default 30 s) re-attempts failed peg-outs
   idempotently.

The finality gate makes the release **reorg-safe**: because the release is only
attempted once the anchor's L0 block is under the immutable finalized
checkpoint, a later reorg cannot unconfirm the anchor after the vault was
already released and marked spent. (Before this gate, the release fired on
mere confirmation, which is optimistic and reversible.) `PegOutRetryService`
re-attempts every confirmed anchor with a burn until the finality condition
passes, so releases are delayed — not lost — while finality catches up.

### 5.4 The vault address

- Single-key mode: a P2PKH to `bridge.vaultPubKeyHex` (private key
  `bridge.vaultPriKeyHex`). This is the *weakest* custody: one key (held in
  JVM config on the L0 node) can release all locked collateral.
- Multisig mode (preferred): P2SH over `vaultPubKeyHexList` (sorted) with
  threshold `bridge.vaultM` and the corresponding `vaultPriKeyHexList` signer
  keys. An M-of-N vault requires `vaultM` signatures, so a single key (or node)
  cannot move collateral.
- Both modes use post-quantum (`PQKey`/ML-DSA) signatures — the vault is **not**
  legacy-EC — although the private keys are still held as hex in configuration.
- The vault script is the *single source of truth* for "what is locked":
  peg-in must pay it, peg-out must spend it.

---

## 6. Cross-layer data flow

- **L1 → L0**: anchors (checkpoint + optional burn) posted as CROSSTANGLE
  blocks; L0 validates and confirms them.
- **L0 → L1**: the L1 node's `server.requester` points at L0;
  `SyncBlockService.requestNonChainBlocks` pulls L0 non-chain (side) blocks in
  pages (`blocksFromNonChainHeight`, page limit 300, max 500 pages) and applies
  them into the L1 graph with `blockgraph.addBlock` — this is how L0 order/token
  context reaches the L1 chain. Genesis blocks are not re-imported (height > 0).
- **Vault observations**: `PegInWatcherService` (L1) polls L0 `getBalances`
  for the vault address; peg-in L0-side runs on the L0 node itself.

---

## 7. Genesis & bootstrapping

### 7.1 Genesis construction (`UtilGeneseBlock.createGenesis`)

- Coinbase input script embeds `params.getChainId()` → **every chain has a
  distinct genesis hash**.
- `genesisMintsBIG()` true (L0): mint the total supply `BigtangleCoinTotal =
  10^(11+8)` sat (= 100 billion BIG) to `genesisPub`.
- If a **distribution CSV** is configured (`-Dbigtangle.genesis.csv`), the CSV
  entries (`address|pubkey, value`) are minted instead, regardless of
  `genesisMintsBIG()`. This is the dev/test bootstrap for L0 *and* L1. **Note:**
  this path bypasses the `genesisMintsBIG()==false` gate, so a CSV-minted L1
  creates unbacked `bc` — see §8 and §11 (invariant 2 is convention, not
  enforced by this code path).

### 7.2 Spendability of genesis outputs (`DatabaseFullBlockStoreBase`)

Genesis coinbase outputs are persisted as spendable UTXOs when
`genesisMintsBIG()` **or** the genesis coinbase has outputs (i.e. a CSV minted
them on a non-minting side chain) — `DatabaseFullBlockStoreBase.java:812-820`.
On a plain L1 genesis (no CSV) the coinbase has zero outputs, so L1 starts
empty; the intended way to give L1 spendable value is a vault peg-in (§5.2).

### 7.3 Validator bootstrapping (dev/test)

- Stake must be ≥ 32,000,000 BIG in the validator address's *confirmed* L1
  UTXOs; then `stakeDeposit` + `activateValidator` (`l1-order-server
  DispatcherController`) register it, and the warmup proposer rule starts the
  beacon chain.
- Test keys derive validator/genesis wallets from ML-DSA-87 seeds (see
  `helper/test/`), with genesis distributions in
  `helper/test/TestGenesisOutput.csv` (L0) and `TestGenesisOutputL1.csv` (L1).

---

## 8. Known divergence in the dev/test harness

The remote test harness (`helper/fulltest/remote.sh`) starts L0 with
`-Dbridge.active=false -Danchor.active=false` and passes
`-Dbigtangle.genesis.csv=TestGenesisOutputL1.csv` to the L1 server. This mints
`bc` directly into the **L1 genesis** so the L1 validator can stake and the
order-test wallets are funded.

This works, but it **bypasses the vault**: the L1 `bc` has no L0 vault backing,
violating the "wrapped supply == locked L0 collateral" invariant for the `bc`
token (§5.2). The design-consistent bootstrap is instead:

1. Drop the L1 genesis CSV (L1 genesis has no `bc`).
2. Enable the bridge on L0 and L1 (`bridge.active=true`, vault + issuance keys,
   `anchor.l0Url`, …).
3. On L0, submit `processPegIn` transactions that lock ≥ 32,000,000 + fee `bc`
   to the vault with `chainId` = the L1 chain and `toAddressInSubtangle` = the
   L1 validator (and test wallets) as beneficiary.
4. `PegInWatcherService` mints the wrapped `bc` on L1; `stakeDeposit` +
   `activateValidator` then run unchanged, fully vault-backed.

---

## 9. Module map

| Module | Role |
|---|---|
| `bigtangle-core` | data model (`Block`, `Transaction`, `Coin`, `BlockType`), crypto (PQ/EC, ML-DSA-87), params, genesis |
| `bigtangle-servercore` | consensus/validation (`ServiceBaseCheck`, `ServiceBaseConfirmation`, `BlockTypeHandler`), staking (`StakeService`), duty (`ValidatorDutyService`), slots, sync, L0 params, base dispatcher |
| `bigtangle-bridge` | anchors (`AnchorService`, `LayerAnchor`), vault & peg (`BridgeService`), `L1CrosstangleHandler`, scheduled watchers (`AnchorPostService`, `AnchorWatcherService`, `PegInWatcherService`, `PegOutRetryService`) |
| `layer0-server` | L0 runnable node (`DispatcherController`, `L0AnchorHandler`) |
| `l1-*-server` | L1 runnable nodes (order / social / evm / contract / payment / pai) — own `NetworkParameters`, DB, validator set, bridge wiring |
| `l1-nft-server` | lighter node registering `NFT`/`USERDATA` handlers; no dedicated params (not yet a full L1 — see §2) |

---

## 10. Configuration keys (JVM `-D...` / Spring)

| Prefix | Keys | Meaning |
|---|---|---|
| `server.` | `net`, `port`, `requester`, `mineraddress`, `permissioned` | node identity; `net=Test` selects Test params |
| `CHAIN_ID` | env var read by each L1 `NetworkConfiguration` (e.g. `SocialL1NetworkConfiguration` default `SOCIAL`, `PaymentL1NetworkConfiguration` default `PAYMENT`) | the chain's identity; must be unique per chain (§2) |
| `pos.` | `validatorKey`, `slotIntervalMs`, `warmupSlots`, `dutyEnabled`, `gossipPeers` | per-chain PoS |
| `anchor.` | `active`, `l0Url`, `priKeyHex`, `pubKeyHex`, `rewardAmount`, `feePoolPriKeyHex`, `feePoolPubKeyHex`, `postIntervalMs`, `watchIntervalMs`, `chainSignersRequired`, `chainPubKeys`, `disabledChains` | anchor post/confirm, M-of-N quorum, freeze list |
| `bridge.` | `active`, `vaultPubKeyHex`, `vaultPriKeyHex`, `vaultPubKeyHexList`, `vaultM`, `vaultPriKeyHexList`, `issuancePubKeyHex`, `issuancePriKeyHex`, `burnAddress`, `l1Url`, `pegInPollMs`, `pegOutRetryMs` | vault + peg |
| `bigtangle.` | `genesis.csv` | dev/test genesis distribution |

`slotsPerEpoch` is a **consensus code parameter** (`NetworkParameters.setSlotsPerEpoch`,
default 32, mainnet 8), not a `-D` config key — every node must ship the same
value in the same release.

---

## 11. Security invariants (summary)

1. **Per-chain block-type gate** — foreign block types are rejected at ingest
   (`checkBlockBeforeSave`).
2. **No L1 mints `bc`** — `genesisMintsBIG()==false` on every L1; value enters
   only through the vault. *(Convention, not enforced: the `genesis.csv` path
   mints regardless of `genesisMintsBIG()` — §7.1, §8.)*
3. **Anchor finality gates peg-out** — a burn is honoured only after its anchor
   is L0-confirmed **and L0-finalized** (Casper FFG, `isAnchorFinalized`);
   confirmation alone is reversible, finality is not.
4. **Lock-backed issuance** — L1 mint output must equal the declared L0 lock
   (amount, token, beneficiary), signed by the issuance key, replay-guarded at
   confirmation.
5. **FLOW INVARIANT** — cumulative L1→L0 ≤ cumulative L0→L1 per (chain, token);
   per-vault releases are all-or-nothing.
6. **Ownership proof on peg-in** — the locking transaction's scriptSig must
   spend the UTXO; a raw outpoint + beneficiary can never lock someone else's
   coins.
7. **Dedicated keys** — the vault key lives on L0 (signs releases); the L1
   issuance key signs mints; the anchor key signs checkpoints. No key is
   reused across roles.

### Trust assumptions (explicit)

- **Anchor signers are trusted for L1 state.** L0 verifies the anchor's
  signature quorum and the *internal* consistency of its SPV proof, but cannot
  check `confirmedRoot` against real L1 state without replaying L1. The anchor
  signer set (ideally `chainSignersRequired`-of-N) is the root of trust for
  peg-out. Mitigation: M-of-N quorum (`anchor.chainPubKeys`), the freeze list
  (`anchor.disabledChains`), and the FLOW INVARIANT bounding total outflow to
  locked collateral.
- **The issuance key is currently single-key** (`bridge.issuancePubKeyHex`).
  Compromising it mints unbacked *wrapped* tokens on L1 (inflating L1 supply),
  but it cannot alone drain L0: a peg-out also needs a valid, finalized anchor
  with a burn signed by the anchor quorum, and is bounded by
  `sumUnspentVaultValue`. A threshold issuance scheme is a future hardening.
- **The vault is operator-custodial** — releases are driven by the L0 node
  holding the vault key(s) (`AnchorWatcherService`/`PegOutRetryService`). Use
  M-of-N multisig so no single key/node controls collateral.
- **L1 issuance trusts L0's `getBalances` response** (`PegInWatcherService`) as
  "confirmed", though the locking block is fetched and hash-verified. Binding
  issuance to L0 *finality* (as peg-out now is) is a further hardening left
  open.
