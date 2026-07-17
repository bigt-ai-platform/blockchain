# Design: Bigtangle Consensus — MCMC for DAG + PoS Beacon Chain

## Architecture: Two-Layer Consensus

Bigtangle runs **two consensus layers** that operate at different levels:

```
Beacon(n) ─────── Beacon(n+1) ─────── Beacon(n+2)    ← PoS beacon chain
    │                   │                   │           (GHOST fork choice
    v                   v                   v            + Casper finality)
  MCMC DAG            MCMC DAG            MCMC DAG     ← transaction DAG
  (two tips)          (two tips)          (two tips)     (MCMC random walk)
```

- **MCMC** selects two DAG tips (trunk + branch) for every transaction block. This is permanent — the DAG needs probabilistic tip selection for parallel throughput between beacon milestones.
- **Beacon chain** provides finality via LMD-GHOST fork choice and Casper FFG checkpointing. Validators produce one `BLOCKTYPE_BEACON` block per slot.

The two are complementary: MCMC handles the throughput layer, PoS handles the finality layer.

---

## 1. DAG Block Structure

Each block references two parents:

```
block.prevBlockHash      ──► main parent (linear chain)
block.prevBranchBlockHash ──► branch parent (DAG fork)
```

This creates a DAG where every block has two incoming edges. Multiple branches grow concurrently; branches merge when a block references two parents from different forks.

```
    ┌───┐     ┌───┐     ┌───┐
    │ G │────►│ A │────►│ B │────► ...
    └───┘     └───┘     └───┘
       \         \
        \         └───┐     ┌───┐
         └───┐    │ C │────►│ D │
              │    └───┘     └───┘
              │
              └───┐    ┌───┐
                  │ E │────► ...
                  └───┘
```

---

## 2. MCMC DAG Layer (Between Beacons)

### 2.1 Purpose

Between two beacon blocks, MCMC (Markov Chain Monte Carlo) random walks select two DAG tips for the next transaction block. This is the same algorithm that has always driven bigtangle's DAG — it is **not temporary** and will remain after full PoS activation.

The beacon chain provides the *finality layer*: each beacon block confirms a range of DAG blocks. MCMC provides the *throughput layer*: parallel branches, fast tip selection, and conflict-free appends.

### 2.2 Tip Selection (MCMC Walk)

MCMC selects two DAG tips as candidates for the next block:

```java
// net.bigtangle.mcmc.service.TipsService.getValidatedBlockPair()
Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
```

**Algorithm:**

1. **Entry points**: Pull two random blocks weighted by cumulative weight (higher-weight blocks more likely to be selected).

2. **Walk**: From each entry point, perform a biased random walk toward the tips. At each step:
   - Get all approvers (children) of the current block
   - For each child, compute transition weight:
     `P(child | parent) = exp(α × (rating(child) - rating(parent)))`
     where α = -0.05 (configurable), rating = cumulative weight score
   - Select the next block proportional to transition weights
   - Repeat until no further progress

3. **Validation**: The two selected tips must be distinct and pass eligibility checks (height, cutoff, type constraints).

### 2.3 MCMC Update Cycle

Every `mcmcrate` ms (default 1000ms), `ScheduleMCMCService` runs:

```java
// MCMCService.startSingleProcessDo()
1. Lock
2. update(store):
   a. updateWeightAndDepth()   ← recompute DAG weights
   b. updateRating()           ← recompute MCMC ratings
   c. deleteMCMC()             ← prune stale MCMC data
   d. evict caches
   e. calcNewBlockPrototype()  ← MCMC tip selection → TipsQueue
3. Unlock
```

The `TipsQueue` entry is consumed by the next block producer (wallet calls `getTip` HTTP → returns the prototype block with two MCMC-selected tips).

### 2.4 Transaction Flow

```
Client                          Server
  │                               │
  ├── HTTP submitTransaction ─────► Mempool
  │                               │
  │                               ├── batchBlocksFromMempool()
  │                               │   └── blocks queued to ChainBlockQueue
  │                               │
  │                               ├── MCMC update cycle
  │                               │   └── calcNewBlockPrototype()
  │                               │       └── tipsService picks two MCMC tips
  │                               │
  │                               ├── blockGraph.updateChain()
  │                               │   └── processChainConnected()
  │                               │       └── connectRewardBlock()
  │                               │           └── extends beacon chain
  │                               │
  ├── HTTP getOutputs ────────────► Return confirmed UTXOs
```

---

## 3. PoS Beacon Chain Layer

### 3.1 Slot-Based Block Production

The beacon chain is a linear chain of `BLOCKTYPE_BEACON` blocks produced by validators on a 12-second slot clock:

```
Slot 0          Slot 1          Slot 2          Slot 3
┌──────┐       ┌──────┐       ┌──────┐       ┌──────┐
│ B0   │──────►│ B1   │──────►│ B2   │──────►│ B3   │
│slot=0│       │slot=1│       │slot=2│       │slot=3│
│epoch=0│      │epoch=0│      │epoch=0│      │epoch=0│
└──────┘       └──────┘       └──────┘       └──────┘
```

Each beacon block:
- Carries a `RewardInfo` with `chainlength` (milestone number), `prevRewardHash`, and the set of DAG block hashes it confirms
- Contains `SlotData` with slot number, epoch, proposer index, RANDAO reveal, and DAG state root
- Is produced by the validator selected by `SlotService.selectProposer()` (RANDAO-based deterministic selection)
- Adds the DAG tips from MCMC as trunk and branch parents (using `Block.createBlock(r1, r2)`)

### 3.2 Validator Set

Validators register by depositing >= 32 BIG stake:

```
Deposit -> stake_deposits table (activated_epoch = -1)
  -> Activation (after ACTIVATION_DELAY epochs)
    -> Active (can propose + attest)
      -> Slashing (equivocation -> withdraw after 256 epochs)
        -> Withdrawal (funds released)
```

Stake is read by GhostService for vote weight and by CasperService for 2/3 supermajority calculations.

### 3.3 Fork Choice for Beacon Chain: LMD-GHOST

GHOST (Greedy Heaviest Observed SubTree) selects the canonical **beacon chain head**. It runs on the beacon chain (not the DAG):

```java
// GhostService.executeGhost(root, store)
Sha256Hash head = root;
while (true) {
    List<Sha256Hash> children = getChildren(head, store);
    if (children.isEmpty()) break;
    Sha256Hash bestChild = null;
    long bestWeight = -1;
    for (Sha256Hash child : children) {
        long weight = forkChoiceVotes.getOrDefault(child, 0L);
        if (weight > bestWeight) { bestWeight = weight; bestChild = child; }
    }
    if (bestChild == null) break;
    head = bestChild;
}
return head;
```

At each level, pick the child with the most attestation votes. Unlike MCMC, there is no randomness — GHOST deterministically converges to the chain with the most accumulated validator attestations.

**Votes are stake-weighted**: each validator's attestation weight equals their staked amount (read from `stake_deposits`), so larger validators have proportionally more influence.

### 3.4 Finality: Casper FFG

Casper the Friendly Finality Gadget runs over epoch boundaries (32 slots ~= 6.4 min):

```
Epoch N                     Epoch N+1
+----------+                +----------+
| B0..B31  |-- attest ---->| B32..B63 |
|          |   2/3+         |          |
|justified |                |justified |
|          |                |finalized | <- parent was justified
+----------+                +----------+
```

A checkpoint is:
- **Justified**: 2/3 of active stake attests to it
- **Finalized**: Its parent was already finalized

Once finalized, a beacon block can never be reverted (barring 1/3+ equivocation).

### 3.5 Beacon Block Proposal (Per Slot)

The selected proposer for slot N:

1. Calls `mcmcService.calcNewBlockPrototype()` — MCMC selects two DAG tips (trunk + branch)
2. Gets the current max confirmed reward (`cacheBlockService.getMaxConfirmedReward()`)
3. Creates a `Block.createBlock(r1, r2)` with the MCMC-chosen tips
4. Sets `BLOCKTYPE_BEACON` and builds a `RewardInfo` with `chainlength = prev + 1`
5. Adds RANDAO reveal, `SlotData`, attestation collection
6. Solves and saves via `blockSaveService.saveBlock()`
7. Casper/Ghost process attestations from the previous slot

This preserves the DAG structure: MCMC picks the two parents, the beacon block records the milestone.

### 3.6 DAG Parallelism in PoS

The DAG between beacons enables parallel transaction processing:

```
Between B0 and B1:
    +---+     +---+
    | T1 |---->| T3 |
    +---+     +---+
       \
        +---+     +---+
            | T2 |---->| T4 |
            +---+     +---+
```

- Transactions T1/T2 can be produced in parallel (both reference the same pre-beacon state)
- T3/T4 reference the DAG tips from MCMC
- All are confirmed by the next beacon block's `RewardInfo.blocks` set
- If a validator misses its slot, the next slot simply builds on the previous beacon (no empty slot problem)

---

## 4. Implementation Status

### Implemented and Wired

| Component | Status | Details |
|-----------|--------|---------|
| **MCMCService** | Permanent | `calcNewBlockPrototype()` uses `tipsService.getValidatedBlockPair()` for DAG tip selection. Weight/depth/rating cycle runs every 1s. |
| **TipsService** | Permanent | MCMC random walk for two DAG tips. |
| **GhostService** | Wired | `getDagRoot()` starts from genesis. Votes are stake-weighted. DB-persisted via `attestation_votes` table. Restored on restart via `@PostConstruct`. |
| **CasperService** | Wired | `processVote()` persists to DB, reads actual stake from `StakeRecord.amount`. SlashingService wired in. State persisted to `pos_state` table. |
| **SlotService** | Wired | `proposeBeaconBlock()` creates proper `RewardInfo` for `saveChainConnected`. Uses MCMC-chosen tips as trunk + branch. |
| **SlotTickService** | Wired | `@Scheduled` at `pos.slotIntervalMs`. Calls `proposeBeaconBlock()` + `ValidatorDutyService.performDuty()` + epoch processing. |
| **StakeService** | Wired | `processDeposit()`, `activateValidator()`, `slashValidator()`, `getTotalActiveStake()`, `getEffectiveStake()`, `processWithdrawals()`. |
| **SlashingService** | Wired | `checkDoubleVote()` / `checkSurroundVote()` called from `CasperService.processVote()`. Auto-slash on double vote. State persisted to `pos_state` table. |
| **FeeService** | Wired | `updateBaseFee(txCount)` called after `batchBlocksFromMempool()`, gated by `pos.enabled`. |
| **RandaoService** | Wired | Commit/reveal/mix. Used by `SlotService.selectProposer()`. Mixes persisted to `pos_state` table. |
| **EpochRewardService** | Wired | `distributeEpochRewards()` called from `SlotService.processEpoch()`. Persists reward block to DB. |
| **ValidatorDutyService** | Wired | Checks proposer assignment each slot, proposes beacon blocks, signs + broadcasts attestations. Key configurable via `POS_VALIDATOR_KEY` env var or `POST /setValidatorKey`. |
| **GossipService** | Wired | HTTP broadcast of attestations, slashing proofs, beacon hashes to `pos.gossipPeers`. Called from `CasperService.processVote()`. |
| **DB persistence** | Added | `attestation_votes` table, `pos_state` KV store, `stake_deposits` CRUD, `getSummedAttestationVotes()` for GhostService recovery. |
| **REST endpoints** | Added | 10 endpoints covering attestation, staking, validator key management, fee queries, withdrawals, and slashing proof submission. |

### REST Endpoints

| `ReqCmd` | Body | Description |
|----------|------|-------------|
| `submitAttestation` | `AttestationData` JSON | Submit a validator attestation |
| `getAttestations` | `{"slot": N}` | Query attestations for a slot |
| `processWithdrawal` | `{"epoch": N}` | Trigger stake withdrawals |
| `submitSlashingProof` | `{"attestation1": ..., "attestation2": ...}` | Submit slashing evidence |
| `stakeDeposit` | `{"pubkey": "hex", "amount": "bigint"[, "withdrawalCredentials": "hex"]}` | Deposit BIG stake for a validator |
| `activateValidator` | `{"pubkey": "hex", "epoch": N}` | Activate a deposited validator |
| `getValidators` | (none) | List all active validators |
| `getBaseFee` | (none) | Return current base fee and FEE_DEFAULT |
| `setValidatorKey` | `{"privateKey": "hex"}` | Set the local validator private key |
| `getValidatorKey` | (none) | Return whether a validator key is configured + pubkey |

### Configuration

```yaml
pos:
  enabled: ${POS_ENABLED:false}           # Activate SlotTickService + beacon production
  slotIntervalMs: ${POS_SLOT_INTERVAL_MS:12000}
  slotsPerEpoch: ${POS_SLOTS_PER_EPOCH:32}
  validatorKey: ${POS_VALIDATOR_KEY:}      # hex-encoded validator private key
  gossipPeers: ${POS_GOSSIP_PEERS:}        # comma-separated host:port for attestation gossip
```

The fee default is configurable via JVM system property:
```bash
java -Dbigtangle.fee.default=2000 -jar ...
```

### PoSTest Coverage

`PoSTest` (15 tests) covers all PoS services:

| Test | What it covers |
|------|---------------|
| `testLmdGhostEmpty` | GHOST walk on empty DAG |
| `testCasperCheckpoint` | Checkpoint justification/finalization math |
| `testStakeActivateAndSlash` | Stake deposit -> activation -> slashing |
| `testSlotCalculation` | Slot/epoch math |
| `testSlashingDoubleVote` | Double-vote detection (now verifies returned boolean + slashing) |
| `testFeeDistribution` | Fee calculation |
| `testRandaoReveal` | RANDAO commit/reveal/mix |
| (8 more) | Edge cases and combinations |

All existing tests (wallet, token, order-matching, cross-chain) continue to pass — PoS services are additive and non-interfering.

### Gating: `pos.enabled`

| Config | Env Var | Default | Effect |
|--------|---------|---------|--------|
| `pos.enabled` | `POS_ENABLED` | `false` | Activates `SlotTickService.tick()` |
| `pos.slotIntervalMs` | `POS_SLOT_INTERVAL_MS` | `12000` | Slot duration in ms |
| `pos.slotsPerEpoch` | `POS_SLOTS_PER_EPOCH` | `32` | Slots per epoch |

When `pos.enabled=false` (default), the system runs MCMC-only — beacon blocks are still produced via the existing reward chain, but slot-based validator production is inactive.

---

## 5. Two-Layer Architecture Summary

| Aspect | DAG Layer (MCMC) | Beacon Chain (PoS) |
|--------|-----------------|-------------------|
| **Purpose** | Transaction throughput, parallel branches | Finality, checkpointing |
| **Block type** | Regular blocks (transfer, token, order, etc.) | `BLOCKTYPE_BEACON` |
| **Structure** | DAG (two parents per block) | Linear chain (one parent) |
| **Tip selection** | MCMC random walk (cumulative weight) | LMD-GHOST (attestation votes) |
| **Finality** | Probabilistic (milestone depth) | Deterministic (Casper 2/3) |
| **Trigger** | MCMC timer (~1s) | Slot clock (12s) |
| **Producer** | Any node | Selected validator per slot |
| **Persistence** | Permanent | Permanent |

---

## 6. Architectural Notes (Not Gaps)

These are known design simplifications that work correctly but could be enhanced:

| Area | Current Behavior | Enhancement |
|------|-----------------|-------------|
| **Proposer selection** | Uses only 4 bytes of RANDAO seed (2^32 outcomes) | Use full 256-bit hash for uniform distribution |
| **Token locking** | `processDeposit` records intent but doesn't spend the UTXO on-chain | Full on-chain locking via UTXO spend in deposit block |
| **Epoch reward pool** | Uses PoW-era `REWARD_AMOUNT_BLOCK_REWARD` | Switch to accumulated fee pool from `FeeService` |
| **Beacon header validation** | `connectRewardBlock` doesn't validate slot/proposer/RANDAO | Add full PoS header verification |

None affect correctness — the system is consistent within its design scope.

---

## 7. Key Design Decisions

### Why not replace MCMC with GHOST entirely?

MCMC is purpose-built for DAG tip selection in a parallel-branch environment. It handles the "which two blocks should the next block reference?" question probabilistically, which is exactly what a DAG needs. GHOST is designed for chain-level fork choice: "which chain head is canonical?" Replacing MCMC with GHOST for tip selection would:
- Lose the probabilistic exploration that keeps all DAG branches viable
- Require every transaction block to carry attestations (bloat)
- Not improve throughput or security in the DAG layer

### Why a beacon chain instead of reward blocks?

The beacon chain serves the same structural role as the old reward chain (linear milestones that confirm DAG blocks), but with:
- **Validator-based production** (instead of PoW mining)
- **Deterministic finality** via Casper (instead of probabilistic depth)
- **Stake-weighted security** (instead of hash power)
- **Slashing** for equivocation (instead of no penalty)

### Why DAG, not single chain?

The DAG enables parallel block production — multiple validators can append simultaneously within the same beacon interval. In Solana, a missed slot = wasted time. In Bigtangle, the DAG absorbs missed slots naturally: the next block references the DAG tips, not a fixed slot number.

### Why 12-second slots?

12 seconds is Ethereum-compatible. It allows:
- Sufficient time for block propagation across global validators
- Attestation collection and aggregation
- RANDAO reveal processing
- Compatibility with Ethereum's clock for cross-chain anchors

Actual beacon block processing is ~300ms. The remaining time is buffer for network latency.

---

## How to Enable PoS

```bash
# Enable the slot tick (beacon block production)
export POS_ENABLED=true
export POS_SLOT_INTERVAL_MS=12000
export POS_SLOTS_PER_EPOCH=32

# Set the validator private key (optional -- can also use POST /setValidatorKey)
export POS_VALIDATOR_KEY=<hex-encoded-private-key>

# MCMC still runs for DAG tip selection between beacons
export SERVICE_MCMC=true
export SERVICE_MCMC_RATE=1000

# Configure gossip peers for attestation broadcast (comma-separated host:port)
export POS_GOSSIP_PEERS=peer1:8088,peer2:8088
```

---

## 8. Layer Architecture

### 8.1 Overview

BigTangle is split into **Layer 0 (settlement)** and **Layer 1 (application)** chains. Only Layer 0 has a genesis block that creates the native token (BIG) and allows custom token creation. L1 chains have no native token — BIG and all other tokens reach L1 exclusively via the bridge peg mechanism. Each layer has its own database and consensus (MCMC + reward), with block-type isolation enforced by `chainId` + `getAllowedBlockTypes()` at block ingest.

```
                          Layer 0  (settlement chain)
   NATIVE TOKEN CREATION (genesis BIG mint)
   CUSTOM TOKEN CREATION (BLOCKTYPE_TOKEN_CREATION)
   transfer/payment · reward · MCMC consensus
   anchors: BLOCKTYPE_CROSSTANGLE carries L1 checkpoint hashes
   ┌──────────────────────────────────────────────────────────────┐
   │  layer0-server (port 8081)    layer0-mcmc (port 8082)        │
   │  chainId: "L0"                Layer0Params                   │
   │  ALLOWS: BLOCKTYPE_TOKEN_CREATION                            │
   └──────────────────────────────────────────────────────────────┘
            ▲ anchor post (L1→L0)        ▲ peg-in (L0 BIG → L1)
            │                             │
   ┌────────┴──────────────────┐   ┌──────┴───────────────────────┐
   │ L1-ordermatch             │   │ L1-contract                  │
   │ NO native token           │   │ NO native token              │
   │ BIG + tokens via peg only │   │ BIG + tokens via peg only    │
   │ l1-order-server (8083)    │   │ l1-contract-server (8085)    │
   │ l1-order-mcmc (8084)      │   │ l1-contract-mcmc (8086)      │
   │ chainId: "ordermatch"     │   │ chainId: "L1-contract"       │
   │ OrderMatchL1Params        │   │ ContractL1Params             │
   │ NO BLOCKTYPE_TOKEN_CREATION  │  NO BLOCKTYPE_TOKEN_CREATION  │
   └───────────────────────────┘   └─────────────────────────────┘
                                ▲
                          ┌─────┴─────────────┐
                          │ bigtangle-bridge  │
                          │ anchors · SPV ·   │
                          │ peg vault         │
                          └───────────────────┘
```

### 8.2 Module Structure

| Module | Role | Runnable |
|--------|------|----------|
| `bigtangle-core` | Data model (`Block`, `Transaction`, `BlockType`) | No |
| `bigtangle-servercore` | Consensus/validation engine, DB schema, services | No |
| `bigtangle-bridge` | Cross-layer anchors + peg logic | No |
| `bigtangle-subtangle` | Subtangle bridge (superseded by bridge) | Yes |
| `layer0-server` | L0 full node (REST API + mining) | **Yes** (port 8081) |
| `layer0-mcmc` | L0 consensus node (MCMC + reward) | **Yes** (port 8082) |
| `l1-order-server` | L1 order-match full node | **Yes** (port 8083) |
| `l1-order-mcmc` | L1 order-match consensus | **Yes** (port 8084) |
| `l1-contract-server` | L1 contract full node | **Yes** (port 8085) |
| `l1-contract-mcmc` | L1 contract consensus | **Yes** (port 8086) |

### 8.3 Token Supply Model

- **Layer 0 genesis** mints the entire BIG supply (`NetworkParameters.BigtangleCoinTotal`). All `BLOCKTYPE_TOKEN_CREATION` (custom tokens) happens on L0 only.
- **L1 chains have no native BIG.** Their genesis blocks create only the DAG root block — no token outputs. L1's BIG from L0 must flow through the bridge peg-in: L0 locks BIG to the vault address with `toAddressInSubtangle` set to the L1 beneficiary, and L1's `BridgeService.processPegInFromL0()` polls L0 and issues wrapped BIG.
- **L1 `BLOCKTYPE_TRANSFER`** moves BIG that came via peg — it cannot originate BIG.

### 8.4 Key Design Mechanisms

#### 8.4.1 `chainId` — chain identity

`NetworkParameters.chainId` identifies the chain:
- L0: `"L0"`
- L1-ordermatch: `"ordermatch"`
- L1-contract: `"L1-contract"`

Each chain has its own database (configured via `db.dbName`), providing full isolation.

#### 8.4.2 `getAllowedBlockTypes()` — ingest gate

Enforced in `ServiceBaseCheck.checkBlockBeforeSave()` (`bigtangle-servercore/.../ServiceBaseCheck.java:1811`):

```java
if (!networkParameters.getAllowedBlockTypes().contains(block.getBlockType())) {
    throw new VerificationException("Block type " + block.getBlockType()
        + " is not allowed on chain " + networkParameters.getChainId());
}
```

**L0 allows** (`Layer0Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_TOKEN_CREATION,
BLOCKTYPE_REWARD, BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_USERDATA,
BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE
```

**L1-order-match allows** (`OrderMatchL1Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_BEACON,
BLOCKTYPE_CROSSTANGLE,
BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL
```

**L1-contract allows** (`ContractL1Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_BEACON,
BLOCKTYPE_CROSSTANGLE,
BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_CONTRACTEVENT_CANCEL,
BLOCKTYPE_CONTRACT_EXECUTE
```

`BLOCKTYPE_TOKEN_CREATION` is **L0-only**. L1 nodes reject it at the ingest gate.

#### 8.4.3 Cross-layer communication via BLOCKTYPE_CROSSTANGLE

The sole cross-layer primitive is `BLOCKTYPE_CROSSTANGLE`. It carries two payload types:

**Anchors (L1 → L0 checkpointing):** Every N milestones, `AnchorPostService` on L1 creates a CROSSTANGLE block with a `LayerAnchor` JSON payload containing `{ chainId, l1RewardHeadHash, l1Height, confirmedRoot, signature, spvProof }` and HTTP POSTs it to L0. L0 validates the ECDSA signature + SPV Merkle proof and saves the anchor.

**Pegs (value transfer):** `toAddressInSubtangle` on the transaction specifies the L1 beneficiary. L0 locks UTXOs to a vault address; L1's `BridgeService` polls L0 and issues wrapped tokens. This is the only way BIG or custom tokens enter L1.

#### 8.4.4 Anchor watcher — L1 follows L0 finality

`AnchorWatcherService` on L1 polls L0 for confirmed anchors. If the L0-anchored L1 tip is ahead, L1 reorgs to the anchored chain. This enforces: *"Once an anchor is L0-confirmed, the anchored branch is canonical."*

#### 8.4.5 Bridge module

`bigtangle-bridge` implements:
- `AnchorService` — post anchors, validate, confirm, reward
- `BridgeService` — bidirectional peg (peg-in / peg-out)
- `AnchorPostService` — `@Scheduled` anchor posting on L1 (every 30s)
- `AnchorWatcherService` — `@Scheduled` anchor watching on L1 (every 60s)

#### 8.4.6 Consensus isolation

Each layer runs independent MCMC consensus. L1 reorgs to L0-anchored tips via `AnchorWatcherService`, but MCMC tip-selection, weight/depth/rating, and reward creation are per-layer.

#### 8.4.7 Bridge-only token transfer path

```
L0 genesis:       mints 10^17 BIG, saved as UTXO for genesis key
                  BLOCKTYPE_TOKEN_CREATION creates custom tokens

L0 user → L1 peg-in:
  L0: BLOCKTYPE_CROSSTANGLE with
      tx.toAddressInSubtangle = L1 beneficiary address hash
      tx.addOutput(value, vaultAddress)
  L1: BridgeService.processPegInFromL0() polls L0,
      finds CROSSTANGLE blocks, issues wrapped BIG/tokens

L1 → L0 peg-out:
  L1: burn wrapped BIG (included in an anchor)
  L0: AnchorWatcherService sees confirmed anchor,
      BridgeService.processPegOut() releases vault UTXO

No BIG or custom token exists on L1 without going through this peg.
```

### 8.5 Node Configuration

| Setting | layer0-server | layer0-mcmc | l1-order-server | l1-order-mcmc | l1-contract-server | l1-contract-mcmc |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| Port | 8081 | 8082 | 8083 | 8084 | 8085 | 8086 |
| `chainId` | L0 | L0 | ordermatch | ordermatch | L1-contract | L1-contract |
| `service.schedule.mcmc` | false | true | false | true | false | true |
| `service.schedule.mining` | true | false | true | false | true | false |

### 8.6 Layer Implementation Status

| Component | Status |
|-----------|--------|
| `chainId` + `getAllowedBlockTypes()` | Done |
| Layer-specific `NetworkParameters` | Done |
| `layer0-server` / `layer0-mcmc` | Done |
| `l1-order-server` / `l1-order-mcmc` | Done |
| `l1-contract-server` / `l1-contract-mcmc` | Done |
| `BLOCKTYPE_CROSSTANGLE` + handler | Done |
| `AnchorService` + `AnchorPostService` | Done |
| SPV Merkle proof verification | Done |
| `BridgeService` peg-in/peg-out | Done |
| `AnchorWatcherService` L1 reorg | Done |
| L1 no native BIG (token creation L0-only) | Done |
| Bridge-only token transfer path | Done (peg v1) |

### 8.7 How to Run

```bash
mvn -pl layer0-server      spring-boot:run
mvn -pl layer0-mcmc        spring-boot:run
mvn -pl l1-order-server    spring-boot:run
mvn -pl l1-order-mcmc      spring-boot:run
mvn -pl l1-contract-server  spring-boot:run
mvn -pl l1-contract-mcmc    spring-boot:run
```

All six nodes can run simultaneously on different ports, each against its own database.

---

## Current Test Results

| Module | Tests | Pass | Skip | Notes |
|--------|-------|------|------|-------|
| bigtangle-core | 240 | 232 | 8 | MnemonicCodeTest known BIP39 issue |
| layer0-mcmc | 178 | 175 | 3 | RewardService2Test skipped (needs HTTP server) |
| l1-contract-mcmc | 31 | 31 | 0 | |
| l1-order-mcmc | 31 | 31 | 0 | |
| **Total** | **480** | **469** | **11** | |

## Remaining Work

See [TODO.md](TODO.md) for remaining gaps: M-of-N vault multisig, general contract model, anchor liveness fallback, light-client sync, observability, and bridged token fixtures for L1 tests.
