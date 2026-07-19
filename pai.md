# PAI — AI Provider L1 Chain

PAI (`chainId = "PAI"`) is a Layer 1 application chain for AI provider operations: staking, reputation scoring, and reward distribution.

## Architecture

PAI has no native token — all BIG and custom tokens enter exclusively via bridge peg-in from L0. The genesis block creates only the DAG root with no token outputs.

```
Layer 0 (settlement, BIG creation)
    │
    ├─ Bridge peg-in/peg-out
    │
    v
┌──────────────────────────────────┐
│  PAI L1 (chainId="PAI")          │
│                                  │
│  Allowed block types:            │
│   INITIAL, TRANSFER, BEACON,     │
│   CROSSTANGLE, CONTRACT_EVENT,   │
│   CONTRACTEVENT_CANCEL           │
│                                  │
│  ┌─ MCMC Consensus ────────────┐ │
│  │  MCMCService.update()        │ │
│  │  TipsService (random walk)   │ │
│  │  RewardService               │ │
│  └─────────────────────────────┘ │
│                                  │
│  ┌─ Contract Layer ───────────┐  │
│  │  PaiEngine: dispatches to   │  │
│  │   AiStakingContract          │  │
│  │   AiReputationContract       │  │
│  │   AiRewardContract           │  │
│  └────────────────────────────┘  │
│                                  │
│  ┌─ Handlers ─────────────────┐  │
│  │  PaiStakeHandler (active)   │  │
│  │  PaiCancelHandler (active)  │  │
│  └────────────────────────────┘  │
│                                  │
│  ┌─ Services ─────────────────┐  │
│  │  PaiProviderService         │  │
│  │  PaiRewardService           │  │
│  │  PaiReputationService       │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

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

## Block Type Handlers

| Handler | Block Type | Status |
|---------|-----------|--------|
| `PaiStakeHandler` | `CONTRACT_EVENT` | Active |
| `PaiCancelHandler` | `CONTRACTEVENT_CANCEL` | Active |

## Disallowed Block Types

- `BLOCKTYPE_TOKEN_CREATION` — L0-only
- `BLOCKTYPE_ORDER_OPEN` / `ORDER_CANCEL` — order-match L1 only
- `BLOCKTYPE_CONTRACT_EXECUTE` — contract L1 only
- `BLOCKTYPE_GOVERNANCE` — L0-only

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
