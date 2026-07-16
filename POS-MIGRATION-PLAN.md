# Proof-of-Stake Migration Plan

## Current Architecture

BigTangle uses **MCMC (Markov Chain Monte Carlo) consensus on a DAG**:
- Block creation via PoW (difficulty target, nonce search)
- Tip selection via MCMC random walks (cumulative weight + alpha parameter)
- Finality via milestone/reward blocks (MILESTONE_CUTOFF=40)
- No staking, no slashing, no economic finality

## Target: Ethereum-Style PoS (Gasper)

- **Validators** stake BIG tokens
- **Slot/epoch** timing (12s slots, 32-slot epochs)
- **LMD-GHOST** fork choice (latest message driven greedy heaviest observed subtree)
- **Casper FFG** finality (2/3 supermajority checkpoints)
- **Slashing** for equivocation and surround votes

## Migration Plan

### Phase 1: Staking Contract (L0)

Add a `BLOCKTYPE_STAKE` block type. Stakers deposit BIG into a stake contract UTXO set.

**New files:**
```
bigtangle-core/.../BlockType.java          — add BLOCKTYPE_STAKE
bigtangle-core/.../StakeRecord.java         — stake deposit/withdraw record
bigtangle-core/.../ValidatorStatus.java     — active/slashed/withdrawn
bigtangle-servercore/.../StakeService.java  — stake/unstake/slash logic
bigtangle-servercore/.../stake.sql          — stake tables
```

**Stake flow:**
```
User creates BLOCKTYPE_STAKE with tx:
  input: 32 BIG UTXO
  output: stake record (locked for N epochs)
  data: validator pubkey, withdrawal credentials

Validator set is rebuilt at epoch boundaries from StakeRecord table.
Minimum stake: 32 BIG (one validator).
```

Tables:
```sql
CREATE TABLE stake_deposits (
    pubkey BYTEA PRIMARY KEY,
    amount BIGINT NOT NULL,
    withdrawal_credentials BYTEA NOT NULL,
    activated_epoch BIGINT DEFAULT -1,
    slashed BOOLEAN DEFAULT FALSE,
    withdrawable_epoch BIGINT DEFAULT -1
);
CREATE TABLE validator_set (
    epoch BIGINT,
    pubkey BYTEA,
    balance BIGINT,
    PRIMARY KEY (epoch, pubkey)
);
```

### Phase 2: Slot/Epoch Timing Alongside MCMC Schedule

Add slot-driven scheduling alongside MCMC. Both run concurrently — MCMC handles DAG tip selection, slot schedule drives beacon block production.

**Changes:**
```
layer0-mcmc/.../ScheduleMCMCService.java   — KEEP (DAG consensus)
layer0-mcmc/.../SlotTickService.java        — NEW: ticks every 12s
layer0-mcmc/.../SlotService.java            — NEW: slot/epoch state machine
```

**SlotService:**
```
Every 12s (slot):
  1. Select beacon proposer from validator set (committees shuffled via RANDAO)
  2. Proposer creates beacon block (equivalent to current reward block)
  3. All validators attest to head (LMD-GHOST vote)

Every 32 slots (epoch):
  1. Process finality (Casper FFG)
  2. Rotate committees
  3. Process withdrawals/slashing
  4. Rebuild validator set
```

The existing DAG continues for transaction blocks. The beacon chain is a new linear chain that finalizes the DAG.

### Phase 3: RANDAO — Validator Selection

Replace MCMC tip selection with RANDAO-based proposer selection.

**New file:**
```
bigtangle-core/.../RandaoReveal.java
bigtangle-servercore/.../RandaoService.java
```

**Flow:**
```
Each validator pre-commits a secret (hash of future random value).
At assigned slot, validator reveals the secret → mixes into RANDAO.
Next epoch's proposer order is derived from RANDAO beacon.
```

Implementation: SHA-256 hash chain, mixed via XOR on each reveal.

### Phase 4: LMD-GHOST Fork Choice

Replace MCMC weight/depth calculation with LMD-GHOST.

**Changes:**
```
layer0-mcmc/.../MCMCService.java       — REPLACE with ForkChoiceService
layer0-mcmc/.../TipsService.java       — REPLACE with GhostService
```

**LMD-GHOST:**
```
Each validator's latest attestation = vote for a beacon block.
Fork choice: at each height, pick the child with the most attestation weight.
Walk from root to tip → canonical head.
```

Simpler than MCMC: no random walks, no alpha parameter, no cumulative weight recomputation. Just count attestations per block.

Data structures:
```sql
CREATE TABLE attestations (
    slot BIGINT,
    validator_pubkey BYTEA,
    beacon_block_hash BYTEA,
    PRIMARY KEY (slot, validator_pubkey)
);
CREATE TABLE fork_choice_votes (
    block_hash BYTEA PRIMARY KEY,
    weight BIGINT DEFAULT 0
);
```

### Phase 5: Casper FFG Finality

Replace milestone/reward blocks with Casper FFG checkpoints.

**Changes:**
```
bigtangle-servercore/.../ServiceVerifyReward.java     — MODIFY for Casper
bigtangle-servercore/.../ServiceBaseCheck.java        — REMOVE MCMC checks
```

**Casper FFG:**
```
Epoch boundary block = checkpoint.
Validators vote on checkpoint pairs (source → target).
If 2/3 of total stake votes for (s → t), then t is justified.
If t is justified AND s is the previous justified checkpoint → t is finalized.
```

This replaces the entire `MILESTONE_CUTOFF=40` mechanism. Finality is economic (2/3 stake) instead of depth-based.

### Phase 6: Slashing

Add slashing conditions and enforcement.

**New file:**
```
bigtangle-servercore/.../SlashingService.java
```

**Slashable offenses:**
```
1. Double vote (two attestations in the same slot)
2. Surround vote (attestation that surrounds another)
3. Proposer equivocation (two beacon blocks in same slot)
4. Inactivity leak (prolonged offline)

Penalty: 1/32 of stake + 3x the correlation penalty (like Ethereum).
```

When a slashing condition is proven via BLOCKTYPE_SLASHING:
```sql
INSERT INTO slashings (proven_at_epoch, offender_pubkey, penalty, reporter_reward)
VALUES (:epoch, :pubkey, :penalty, :reward);

UPDATE stake_deposits SET slashed = TRUE, withdrawable_epoch = :epoch + 8192
WHERE pubkey = :pubkey;
```

### Phase 7: Rewards

Replace `PER_BLOCK_REWARD` and `REWARD_AMOUNT_BLOCK_REWARD` with epoch-based validator rewards.

**Changes:**
```
bigtangle-core/.../NetworkParameters.java  — add REWARD_PER_SLOT, BASE_REWARD
bigtangle-servercore/.../RewardService.java    — MODIFY for PoS rewards
```

**Reward formula (per epoch):**
```
base_reward = effective_balance * base_reward_factor // 2^20
inclusion_reward = base_reward * 8 // attester_inclusion_time
proposer_reward = base_reward // 8 (per attested vote)
```

Rewards paid from:
- Existing block reward pool (re-purposed)
- Transaction fees (fee mechanism already exists)
- Inactivity leak penalties redistributed

### Phase 8: Fee Market (EIP-1559)

Replace the current fixed `FEE_DEFAULT` with a dynamic base fee.

**Changes:**
```
bigtangle-core/.../Coin.java              — replace FEE_DEFAULT with base fee
bigtangle-servercore/.../FeeService.java   — NEW: base fee calculation
```

**Formula:**
```
base_fee[t+1] = base_fee[t] * (1 + (gas_used - target) / target * 0.125)
target = block_gas_limit // 2
```

Transaction now includes:
- `maxFeePerGas` (total the user is willing to pay)
- `maxPriorityFeePerGas` (tip to proposer)
- Gas used (proportional to tx size/sigops)



## Summary: Key Differences From Current

| Aspect | Current (MCMC + PoW) | Target (Ethereum PoS) |
|--------|---------------------|----------------------|
| Block creation | PoW (nonce search) | Slot-based proposer |
| Tip selection | MCMC random walk | LMD-GHOST (attestation votes) |
| Finality | Depth-based (40 blocks) | Economic (2/3 stake) |
| Reward | Per-block mining | Per-epoch validator |
| Fee | Fixed FEE_DEFAULT | EIP-1559 dynamic base |
| Sybil resistance | PoW hash power | BIG stake (32 min) |
| Timing | Fixed delay schedule | 12s slot / 32 epoch |

## Effort Estimate

| Phase | Files | Complexity | Timeline |
|-------|-------|-----------|----------|
| 1. Staking contract | 10 files | Medium | 2 weeks |
| 2. Slot/epoch timing | 5 files | Medium | 1 week |
| 3. RANDAO | 3 files | Low | 1 week |
| 4. LMD-GHOST | 4 files | Medium | 2 weeks |
| 5. Casper FFG | 6 files | High | 3 weeks |
| 6. Slashing | 3 files | Medium | 1 week |
| 7. Rewards | 4 files | Medium | 1 week |
| 8. Fee market | 3 files | Medium | 1 week |
| 9. Migration/hard fork | 2 files | High | 2 weeks |
| 10. Cleanup | 10 files | Low | 1 week |
| **Total** | **50 files** | | **15 weeks** |
