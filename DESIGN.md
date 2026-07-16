# Design: Bigtangle Consensus — MCMC + PoS (Coexistence)

## Overview

Bigtangle runs **two consensus modes concurrently**:

1. **MCMC Bridge Mode** — DAG-based tip selection via Markov Chain Monte Carlo (MCMC) random walk. Active by default for block propagation.
2. **Pure PoS** — Slot-based beacon blocks with LMD-GHOST fork choice, Casper FFG finality, and epoch-based validator rewards. All 8 PoS migration phases are implemented and operational.

Both modes share a common DAG (directed acyclic graph) block structure. The difference is *how* the canonical chain is selected and *when* blocks become final. PoS services coexist with MCMC — no hard fork required.

---

## 1. DAG Block Structure

Each block references two parents:

```
block.prevBlockHash      ──► main parent (linear chain)
block.prevBranchBlockHash ──► branch parent (DAG fork)
```

This creates a DAG where every block has two incoming edges. Validators append blocks to any tip — multiple branches grow concurrently. The DAG is not a tree: branches merge when a block references two parents from different forks.

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

## 2. Phase 1: MCMC Bridge Mode

### 2.1 Tip Selection (MCMC Walk)

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

**Optimization (bottleneck resolved):**

The walk was a major bottleneck — each step reads `getApproverBlockHashes` (SQL) and `getBlockMCMCAsObject` (MCMC data). With the approver hash cache (`@Cacheable("approverHashes")`) and warm MCMC object cache (`@Cacheable("BlockMCMCObject")`), the walk dropped from 8.8s to **19ms** (461x improvement).

### 2.2 Transaction Confirmation via Reward Blocks

In MCMC bridge mode, a transaction is *confirmed* when:

1. A block containing the transaction is accepted into the DAG
2. A reward block references the block (directly or via chain)
3. The MCMC walk selects the reward chain as canonical

**Reward chain**: A secondary chain of `BLOCKTYPE_REWARD` blocks that serve as checkpoints. Each reward block references the DAG state root and a set of "referenced blocks" (the tips at reward time). The reward chain is a simple linear chain (single parent), not a DAG.

**Confirmation flow:**

```
1. User submits transaction ──► block added to DAG
2. RewardService creates reward block ──► references the block
3. blockGraph.updateChain() ──► connects reward chain
4. Transaction output marked confirmed (confirmed=true in outputs table)
5. Wallet sees confirmed UTXO ──► spendable
```

**Timing**: On average, ~2-5 seconds from submission to confirmation (depends on MCMC schedule frequency and DAG depth).

### 2.3 MCMC Update Cycle

Every `mcmcrate` ms (default 1000ms), `ScheduleMCMCService` runs:

```java
// MCMCService.startSingleProcessDo()
1. Lock ──► selectLockobject(LOCKID)
2. update(store):
   a. updateWeightAndDepth() ──► recompute DAG weights
   b. updateRating() ──► recompute ratings
   c. deleteMCMC() ──► prune stale MCMC data
   d. evictBlockMCMC() ──► clear MCMC cache
   e. evictBlockMCMCObject() ──► clear object cache
   f. calcNewBlockPrototype() ──► run tip selection, store TipsQueue
3. unlock ──► deleteLockobject(LOCKID)
```

The `TipsQueue` entry is then consumed by the next block proposer (wallet calls `getTip` HTTP → returns the prototype block).

### 2.4 Throughput (MCMC Bridge Mode)

| Benchmark | Configuration | Throughput |
|-----------|--------------|-----------|
| Direct mempool injection | 50 clients, 5k tx/block, skip solidity | **1,587 tx/s** |
| HTTP mempool (batched) | 200 clients, 250 tx/batch | **4,920 tx/s** |
| HTTP single-tx (1 tx/block) | 10 clients, 1 tx/block | **120 tx/s** |

The 4,920 tx/s result is the **raw node throughput** — it measures how fast the server can ingest, batch, and process transactions through the full pipeline (HTTP → mempool → batch blocks → MCMC → chain update).

---

## 3. Phase 2: Pure PoS (Implemented)

### 3.1 Slot-Based Block Production

Pure PoS replaces the MCMC reward chain with a beacon chain:

```
Slot 0          Slot 1          Slot 2          Slot 3
┌──────┐       ┌──────┐       ┌──────┐       ┌──────┐
│ B0   │──────►│ B1   │──────►│ B2   │──────►│ B3   │
│slot=0│       │slot=1│       │slot=2│       │slot=3│
│epoch=0│      │epoch=0│      │epoch=0│      │epoch=0│
└──────┘       └──────┘       └──────┘       └──────┘
  │                            │
  └── DAG branch ──────────────┘
     (optional, for parallel
      validators in same slot)
```

**Key differences from MCMC bridge:**

| Aspect | MCMC Bridge | Pure PoS |
|--------|-------------|----------|
| Block trigger | MCMC timer (~1s) | Slot clock (12s) |
| Leader | None (anyone can append) | Round-robin by stake weight |
| Fork choice | MCMC walk (random) | LMD-GHOST (attestation votes) |
| Finality | Probabilistic (reward chain depth) | Casper FFG (2/3 attestations) |
| Confirmation time | ~2-5s | ~12-24s (1-2 slots) |
| Order matching | Off-chain or on L1 | L1 order chain |

### 3.2 Validator Set

Validators register by depositing stake:

```java
// StakeService.processDeposit()
// Creates BLOCKTYPE_STAKE block with 32 BIG minimum stake
// Record stored in stake_deposits table
```

Validator lifecycle:
1. **Deposit**: `stake_deposits` entry created (activated_epoch = -1)
2. **Activation**: Activated after N epochs (activated_epoch = currentEpoch + ACTIVATION_DELAY)
3. **Active**: Can propose blocks and attest
4. **Slashing**: Equivocation or surround vote → slashed, withdraw after delay
5. **Withdrawal**: After WITHDRAWAL_DELAY_EPOCHS (256 epochs ≈ 55 min)

33 active validators on L0, each with 32 BIG minimum stake = ~1,056 BIG security deposit.

### 3.3 Slot Clock

```java
// SlotService
SLOT_DURATION_MS = 12_000L     // 12 seconds
SLOTS_PER_EPOCH = 32           // ~6.4 minutes per epoch
EPOCH_DURATION_MS = 384_000L   // 384 seconds
```

**Proposer selection** for each slot:

```java
proposerIndex = RANDAO_mix XOR slot % numActiveValidators
```

Deterministic per slot, unpredictable in advance (RANDAO mix changes each slot).

### 3.4 Block Production (Per Slot)

For each slot, the selected proposer:

1. Collects mempool transactions
2. Gets DAG root from GHOST: `ghostService.getDagRoot(store)`
3. Gets attestations from previous slot: `ghostService.collectAttestations(slot, store)`
4. Builds beacon block with `SlotData` (slot, epoch, proposerIndex, randaoReveal, dagStateRoot)
5. Includes up to TX_PER_SLOT (configurable, default 500) transactions
6. Signs and broadcasts

**All validators attest** after seeing the beacon block:

```java
AttestationData att = new AttestationData();
att.setSlot(slot);
att.setEpoch(epoch);
att.setBeaconBlockHash(block.getHash());
att.setValidatorPubkey(validator.getPubKey());
att.setSignature(validator.sign(block.getHash()));
casperService.processVote(att, store);
ghostService.processAttestation(att, store);
```

### 3.5 Fork Choice: LMD-GHOST

GHOST (Greedy Heaviest Observed SubTree) selects the canonical head:

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
    if (bestChild == null || bestWeight <= 0) break;
    head = bestChild;
}
return head;
```

At each level, pick the child with the most attestation votes. This is a greedy algorithm — it always follows the heaviest branch. Unlike MCMC, there's no randomness: GHOST converges deterministically to the chain with the most accumulated attestations.

**Two-tip selection for DAG**: `getTwoTips()` runs GHOST twice — the second pass excludes the first tip's subtree, ensuring two distinct DAG branches.

### 3.6 Finality: Casper FFG

Casper the Friendly Finality Gadget runs over epochs:

```java
// CasperService.finalizeCheckpoint(epoch, store)
Checkpoint target = checkpoints.get(epoch);
Checkpoint source = checkpoints.get(epoch - 1);
BigInteger totalStake = stakeService.getTotalActiveStake(store);
BigInteger votedStake = getVotedStake(source.blockHash, target.blockHash, store);
BigInteger twoThirds = totalStake.multiply(2).divide(3);

if (votedStake.compareTo(twoThirds) >= 0) {
    target.justified = true;
    if (source.finalized) {
        target.finalized = true;
    }
}
```

A checkpoint is:
- **Justified**: 2/3 of active validators attest to it
- **Finalized**: Its parent checkpoint was already finalized

Once finalized, a block can never be reverted (unless 1/3+ of validators equivocate and get slashed).

**Typical timing:**
- Slot 0-31: Epoch N blocks produced
- Slot 32: Attestations counted, checkpoint justified
- Slot 64: Previous epoch checkpoint finalized (2 epochs ≈ 12.8s)

### 3.7 DAG Parallelism in PoS

The DAG structure is preserved in PoS. Multiple validators can produce blocks in the same slot (branches). GHOST resolves the ambiguity:

```
Slot 5:
    ┌───┐
    │ B │──► Head if votes=12
    ├───┤
    │ C │──► Head if votes=15
    └───┘
```

If a validator misses its slot (offline, network partition), the next slot's proposer builds on the previous slot's head (no empty slot). This is a key advantage over Solana's single-leader model (which has ~2,000-3,000 empty slots/day).

### 3.8 Throughput (PoS Mode)

| Configuration | Throughput | Slot time | Notes |
|--------------|-----------|-----------|-------|
| 50 validators, 100 tx/slot | **434 tx/s** | 230ms | MCMC every 10 slots |
| 50 validators, 500 tx/slot | **580 tx/s** | 861ms | More tx amortize per-slot overhead |
| 32 validators, 64 slots (GHOST) | **1,892ms** | — | No attestation overhead measured |
| Projected (GHOST + skip solidity) | **~3,500 tx/s** | ~300ms | Realistic full-node estimate |

---

## 4. Transaction Lifecycle (Complete)

### 4.1 MCMC Bridge Mode (Active)

```
Client                          Server
  │                               │
  ├── HTTP submitTransaction ─────► Mempool
  │                               │
  │                               ├── batchBlocksFromMempool()
  │                               │   ├── create blocks (5k tx each)
  │                               │   └── saveBatchBlock (skip solidity)
  │                               │
  │                               ├── MCMC update
  │                               │   ├── updateWeightAndDepth()
  │                               │   ├── updateRating()
  │                               │   └── calcNewBlockPrototype()
  │                               │
  │                               └── blockGraph.updateChain()
  │                                   └── saveChainConnected()
  │
  ├── HTTP getOutputs ────────────► Return confirmed UTXOs
  │
  └── Done
```

### 4.2 Pure PoS Mode (Implemented)

```
Client                          Proposer(s)                   Attesters
  │                               │                             │
  ├── HTTP submitTransaction ─────► Mempool                     │
  │                               │                             │
  ├── Slot N ─────────────────────► Proposer                     │
  │                               ├── collect mempool tx        │
  │                               ├── get DAG root (GHOST)     │
  │                               ├── create beacon block      │
  │                               ├── broadcast                 │
  │                               │                             │
  │                               ├─────────────────────────────► receive block
  │                               │                             ├── verify
  │                               │                             ├── attest (sign)
  │                               │                             └── submit attestation
  │                               │                             │
  ├── Slot N+1 ──────────────────► Next proposer               │
  │                               ├── collect attestations     │
  │                               ├── build on GHOST head      │
  │                               └── ...                      │
  │                                                             │
  ├── Epoch boundary               │                             │
  │                               ├── Casper finalize check    │
  │                               │   └── 2/3 votes? final!   │
  │                                                             │
  └── HTTP getOutputs ────────────► Return confirmed UTXOs      │
                                                               │
```

---

## 5. Key Design Decisions

### Why MCMC + PoS coexistence?

MCMC and PoS run side by side — no hard fork needed. MCMC handles DAG tip selection as a fallback, while PoS services (GHOST, Casper, staking) provide deterministic finality and validator-based rewards. The 8 PoS phases were implemented incrementally on a working MCMC foundation, allowing continuous testing at each step.

### Why DAG, not single chain?

The DAG enables parallel block production — multiple validators can append simultaneously without waiting for a slot. In Solana, a missed slot = wasted time. In Bigtangle, the DAG absorbs missed slots naturally (the next block references the previous tip, not a fixed slot number).

The DAG also enables the MCMC walk to function as a probabilistic fork choice without requiring global consensus on block order.

### Why GHOST over MCMC in PoS?

MCMC is probabilistic — two nodes may select different tips from the same DAG state. GHOST is deterministic given the same attestation votes. In PoS, attestations provide an unambiguous signal (which validator voted for which block), making GHOST the natural fork choice.

MCMC remains useful as a fallback during the transition (when attestation data is sparse or unavailable).

### Why 12-second slots?

12 seconds is Ethereum-compatible. It allows:

- Sufficient time for block propagation across global validators
- Attestation collection and aggregation
- RANDAO reveal processing
- Compatibility with Ethereum's clock for cross-chain anchors

The actual slot processing time (wall clock) is ~300ms for 500 tx/slot. The remaining time is buffer for network latency and clock drift.
