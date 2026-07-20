# PAI — AI Provider L1 Chain

PAI (`chainId = "PAI"`) is a Layer 1 application chain for AI provider operations: staking, reputation scoring, and reward distribution.

## Architecture

PAI has no native token — all BIG and custom tokens enter exclusively via bridge peg-in from L0. The genesis block creates only the DAG root with no token outputs.

```
Layer 0 (settlement, BIG creation)
    │
    ├─ Bridge peg-in / peg-out
    │
    v
┌──────────────────────────────────────┐
│  PAI L1 (chainId="PAI")              │
│                                      │
│  Allowed block types:                │
│   INITIAL (bridge deposits)          │
│   TRANSFER (stake, receipts, reward) │
│   BEACON (identical to other L1s)    │
│   CROSSTANGLE (bridge sync)          │
│   CONTRACT_EVENT                     │
│   CONTRACTEVENT_CANCEL               │
│                                      │
│  ┌─ MCMC Consensus ────────────────┐ │
│  │  DagRewardService (reward chain) │ │
│  │  TipsService (random walk)       │ │
│  │  MCMCService.update()            │ │
│  └─────────────────────────────────┘ │
│                                      │
│  ┌─ Contract Layer ───────────────┐  │
│  │  PaiEngine dispatches to:       │  │
│  │   AiStakingContract              │  │
│  │   AiReputationContract           │  │
│  │   AiRewardContract               │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ Handlers ─────────────────────┐  │
│  │  PaiStakeHandler (active)       │  │
│  │  PaiCancelHandler (active)      │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ Services ─────────────────────┐  │
│  │  PaiProviderService              │  │
│  │  ProviderRewardService           │  │
│  │  PaiReputationService            │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

### Transaction Flow

```
CONTRACT_EVENT (user submit)
      │
      ▼
PaiStakeHandler
      │
      ▼
PaiEngine.dispatch()
      │
      ▼
AiStakingContract (or AiReputationContract / AiRewardContract)
      │
      ▼
State update (DB)
```

### State Ownership

| Data | Owner | Storage |
|------|-------|---------|
| Provider metadata (url, key, status) | `PaiProviderService` | DB table |
| Stake balance per provider | `AiStakingContract` | Contract state |
| Reputation score per provider | `AiReputationContract` | Contract state |
| Reward calculation results | `ProviderRewardService` | DB table |

`PaiProviderService` owns provider identity and status; contracts own the economic and scoring state.

## Allowed Block Types

| Type | Purpose |
|------|---------|
| `INITIAL` | Bridge peg-in deposits and account initialization |
| `TRANSFER` | Stake movement, bridge receipts, reward payouts |
| `BEACON` | Identical to other L1 beacons — no AI-specific metadata |
| `CROSSTANGLE` | Bridge synchronization only (not cross-L1 messaging) |
| `CONTRACT_EVENT` | Staking, reputation, and reward triggers |
| `CONTRACTEVENT_CANCEL` | Cancel a pending contract event |

`TRANSFER` is required for stake movement, bridge receipts, and reward payouts.

## Disallowed Block Types

- `BLOCKTYPE_TOKEN_CREATION` — L0-only
- `BLOCKTYPE_ORDER_OPEN` / `ORDER_CANCEL` — order-match L1 only
- `BLOCKTYPE_CONTRACT_EXECUTE` — contract L1 only
- `BLOCKTYPE_GOVERNANCE` — L0-only

## Modules

| Module | Port | Role |
|--------|------|------|
| `l1-pai-server` | 8087 | REST API + block production |
| `l1-pai-mcmc` | — | MCMC consensus + reward chain |

## Contract Engine

`PaiEngine` implements `ContractExecutor` and dispatches to three AI-specific contracts:

| Classname | Contract | Purpose |
|-----------|----------|---------|
| `CLASSNAME_STAKING` | `AiStakingContract` | AI provider token staking |
| `CLASSNAME_REPUTATION` | `AiReputationContract` | Provider reputation scoring |
| `CLASSNAME_REWARD` | `AiRewardContract` | Reward distribution to providers |

`AiRewardContract` is triggered via `CONTRACT_EVENT` (not automatic).

## Block Type Handlers

| Handler | Block Type | Status |
|---------|-----------|--------|
| `PaiStakeHandler` | `CONTRACT_EVENT` | Active |
| `PaiCancelHandler` | `CONTRACTEVENT_CANCEL` | Active |

## Staking Lifecycle

```
                 Bridge peg-in (BIG enters PAI)
                           │
                           v
                    Provider stakes
                           │
                           v
                     ┌───────────┐
                     │  staking  │
                     └───────────┘
                           │
                           v
                     ┌───────────┐
                     │  active   │ ← reputation updates, reward earning
                     └───────────┘
                           │
                           v
                   Unstake request
                           │
                           v
                   ┌───────────────┐
                   │  unlock period│  (configurable duration)
                   └───────────────┘
                           │
                           v
                     ┌───────────┐
                     │  withdraw │ → Bridge peg-out (BIG leaves PAI)
                     └───────────┘
```

## Reputation

`AiReputationContract` maintains per-provider scores:

| Property | Detail |
|----------|--------|
| Score range | 0 – 1000 |
| Initial | 100 |
| Update trigger | Completed jobs (via `CONTRACT_EVENT`) |
| Weighting | Stake-weighted — higher stake amplifies score changes |
| Decay | 5 % per month without activity |
| Persistence | Stored per provider in contract state, survives restarts |

## Reward Model

Rewards redistribute bridged BIG (or supported tokens); the chain does not mint new assets.

| Property | Detail |
|----------|--------|
| Funding source | Bridge-funded (fees collected on L0, pegged in as reward pool) |
| Trigger | `CONTRACT_EVENT` submitted to `AiRewardContract` |
| Calculation | Proportion of reputation score × stake amount |
| Emission model | No inflation — fixed pool, distributed periodically |
| Payout | `TRANSFER` block from reward pool to provider address |

## Network Parameters

| Parameter | Value |
|-----------|-------|
| `chainId` | `"PAI"` |
| `genesisMintsBIG` | `false` |
| Server port | 8087 |
| Database name | `pai` |
| Allowed block types | 6 |

## Configuration

```yaml
# PAI server
pai:
  server:
    port: 8087
  db:
    dbName: pai
    server: localhost
    port: 5432
  service:
    schedule:
      mcmc: false
      mining: true

# PAI MCMC
pai:
  mcmc:
    server:
      port: 0
    db:
      dbName: pai-mcmc
      server: localhost
      port: 5432
    service:
      schedule:
        mcmc: true
        mining: false
```

## How to Run

```bash
mvn -pl l1-pai-server  spring-boot:run
mvn -pl l1-pai-mcmc   spring-boot:run
```

## Lifecycle Diagram

```
Bridge Peg-In (INITIAL / TRANSFER)
      │
      v
Provider receives BIG
      │
      v
Stake CONTRACT_EVENT
      │
      v
Provider Active (earning, reputation updates)
      │
      v
Reputation Updates (CONTRACT_EVENT)
      │
      v
Reward Distribution (CONTRACT_EVENT → TRANSFER)
      │
      v
Unstake Request (CONTRACTEVENT_CANCEL)
      │
      v
Unlock Period
      │
      v
Withdraw (TRANSFER)
      │
      v
Bridge Peg-Out (CROSSTANGLE)
```

## Test Coverage

12 tests in `l1-pai-mcmc/src/test/`, all passing:

| Test Class | Tests | Coverage |
|-----------|-------|----------|
| `PaiEngineTest` | 4 | Contract dispatch |
| `PaiFullFlowTest` | 2 | Multi-block chain |
| `PaiStakingTest` | 1 | Chain setup |
| `PaiRewardTest` | 2 | Reward cycles |
| `PaiReputationTest` | 2 | DAG branching |
| `PaiBenchmarkTest` | 2 | Performance |
| `Layer1BlockTypeScopingTest` | 1 | Block type validation |

### Suggested Additional Tests

| Test | Purpose |
|------|---------|
| Double stake | Same provider submits duplicate stake — reject |
| Reputation rollback | Event → cancel → score restored |
| Invalid bridge asset | Unsupported token peg-in → reject |
| Reward determinism | Multiple MCMC nodes compute identical allocations |
| Unauthorized contract | `CLASSNAME_UNKNOWN` → fail |
