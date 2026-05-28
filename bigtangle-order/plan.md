# Plan: Split bigtangle-servercore Services into Verify vs Create

## Context

- **bigtangle-server** — Verifier node: receives blocks from peers, validates them, maintains chain state. Does NOT create new blocks.
- **bigtangle-mcmc** — Creator node: runs MCMC tip selection, creates reward blocks, order-execution blocks, contract-execution blocks, and templates for new blocks.
- **bigtangle-servercore** — Currently contains services for BOTH use cases mixed together.
- **bigtangle-order** — New module (already created) for block-creation services.

## Goal

Split `bigtangle-servercore` so that:
- `bigtangle-servercore` keeps only verification/shared services (used by both bigtangle-server and bigtangle-mcmc)
- `bigtangle-order` receives all block-creation services (used only by bigtangle-mcmc)

## Current Class Inventory & Classification

### Already moved to bigtangle-order
| Class | Purpose |
|-------|---------|
| `OrderExecutionService` | Creates order-execution blocks |
| `ContractExecutionService` | Creates contract-execution blocks |
| `ScheduleContractService` | Schedules contract execution |
| `ScheduleOrdermatchService` | Schedules order matching |

### Moved to bigtangle-order (already done)
| Class | Current Location | Reason |
|-------|-----------------|--------|
| `ServiceBaseReward` | `order/service/base/` | Creates reward blocks — only used by mcmc `RewardService` |
| `OrderExecutionService` | `order/service/` | Creates order-execution blocks |
| `ContractExecutionService` | `order/service/` | Creates contract-execution blocks |
| `ScheduleContractService` | `order/service/schedule/` | Schedules contract execution |
| `ScheduleOrdermatchService` | `order/service/schedule/` | Schedules order matching |

### Keep in bigtangle-servercore (VERIFY / SHARED)
| Class | Role |
|-------|------|
| `ServiceBase` | SHARED — abstract base for UTXO connect/disconnect |
| `ServiceBaseCheck` | VERIFY — validates blocks (signatures, solidity, time) |
| `ServiceBaseConfirmation` | VERIFY — conflict resolution during confirmation |
| `ServiceBaseConnect` | SHARED — connects/disconnects blocks, DAG traversal |
| `ServiceBaseOrder` | SHARED — order-book matching during confirmation |
| `ServiceContract` | SHARED — deterministic contract execution (used in verify too) |
| `ServiceOrderExecution` | SHARED — deterministic order matching (used in verify too) |
| `ServiceVerifyReward` | VERIFY — validates incoming reward blocks |
| `BlockService` | SHARED — main facade for block retrieval/evaluation |
| `BlockSaveService` | SHARED — saves blocks (used by both verify-sync and create) |
| `CacheBlockService` | SHARED — caching layer for blocks/evaluations/rewards |
| `StoreService` | SHARED — data store access |
| `SyncBlockService` | VERIFY — syncs blocks from remote peers |
| `MissingNumberCheckService` | VERIFY — checks reward chain integrity |
| `OrderdataService` | SHARED — read-only order queries |
| `OrderTickerService` | SHARED — read-only ticker queries |
| `AVGPriceService` | SHARED — average price calculations |
| `HeathCheckService` | SHARED — health monitoring |
| `OutputService` | SHARED — UTXO output queries |
| `TokensService` | SHARED — token queries |
| `UserDataService` | SHARED — user data queries |
| `MultiSignService` | SHARED — multi-signature handling |
| `PayMultiSignService` | SHARED — payment multi-sig |
| `TokenDomainnameService` | SHARED — domain name service |
| `SubtanglePermissionService` | SHARED — subtangle permissions |
| `AccessGrantService` | SHARED — access control |
| `AccessPermissionedService` | SHARED — permissioned access |
| `MinioService` | SHARED — object storage |

## Inheritance Concern

```
ServiceBase (abstract, SHARED)
  └─ ServiceBaseOrder (SHARED)
       └─ ServiceBaseConfirmation (VERIFY)
            └─ ServiceBaseConnect (SHARED, concrete)
                 └─ ServiceVerifyReward (VERIFY)
                      └─ ServiceBaseReward (CREATE) ← move to bigtangle-order
```

`ServiceBaseReward` extends `ServiceVerifyReward`. After the move:
- `bigtangle-order` depends on `bigtangle-servercore` (already the case)
- `ServiceBaseReward` in `bigtangle-order` extends `ServiceVerifyReward` in `bigtangle-servercore` — this works because the dependency direction is correct

## Method-level Extract: BlockServiceCreate ✓

`BlockService` in servercore had 3 block-creation helper methods mixed with SHARED query methods:

| Method | Classification | Action |
|--------|--------------|--------|
| `adjustHeightRequiredBlocks` | CREATE (adjust block height + prototype during creation) | Extracted to `BlockServiceCreate` in order |
| `adjustPrototype` | CREATE (refresh block prototype if >2h old) | Extracted to `BlockServiceCreate` in order |
| `calcHeightRequiredBlocks` | CREATE (compute height from prev blocks) | Extracted to `BlockServiceCreate` in order |
| All other methods | SHARED/VERIFY | Stay in `BlockService` in servercore |

`CacheBlockPrototypeService` stays in servercore (SHARED) — used by `BlockServiceCreate` (order → servercore) and `MultiSignService.checkBlockPrototype` (servercore internal).

Updated callers:
- `RewardService` (mcmc), `ContractExecutionService` (order), `OrderExecutionService` (order), `DispatcherController` (server) — now use `BlockServiceCreate`

## Execution Steps

### Phase 1: Move ServiceBaseReward ✓
1. Move `ServiceBaseReward.java` from `bigtangle-servercore/service/base/` to `bigtangle-order/service/base/`
2. Update `SyncBlockService`, `BlockStoreService` to use `ServiceVerifyReward` instead
3. Remove `CacheBlockPrototypeService` autowiring from `BlockSaveService` (unused)

### Phase 2: Move OrderExecutionService, ContractExecutionService ✓
1. Move from `bigtangle-servercore/service/` to `bigtangle-order/service/`

### Phase 3: Move ScheduleContractService, ScheduleOrdermatchService ✓
1. Move from `bigtangle-server/service/schedule/` to `bigtangle-order/service/schedule/`
2. Add `bigtangle-order` dependency to `bigtangle-server/pom.xml`

### Phase 4: Extract BlockServiceCreate methods ✓
1. Create `BlockServiceCreate.java` in `bigtangle-order/service/` with `adjustHeightRequiredBlocks`, `adjustPrototype`, `calcHeightRequiredBlocks`
2. Remove these methods from `BlockService.java` in servercore
3. Update all callers to use `BlockServiceCreate`

### Phase 5: Verify no circular dependencies
- `bigtangle-core` ← `bigtangle-servercore` ← `bigtangle-order` ← `bigtangle-mcmc`
- `bigtangle-server` depends on `bigtangle-order` (for scheduling) and `bigtangle-servercore`
- No reverse dependency should exist

### Phase 6: Run tests
1. `mvn compile` — all modules
2. `mvn test -pl bigtangle-mcmc` — integration tests pass

## Dependency Graph (after split)

```
bigtangle-core
     ↑
bigtangle-servercore   (verify + shared: ServiceBaseCheck, ServiceBaseConnect, 
     ↑                  ServiceVerifyReward, BlockService, SyncBlockService, etc.)
     |
bigtangle-order        (create: ServiceBaseReward, BlockServiceCreate,
     ↑                  OrderExecutionService, ContractExecutionService)
     |
     ├── bigtangle-server  (verify node: only receives/validates/syncs blocks)
     └── bigtangle-mcmc    (creator node: MCMC, rewards, order/contract execution)
```

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| `ServiceBaseReward` extends `ServiceVerifyReward` — tight coupling | Dependency direction is correct (order → servercore) |
| `CacheBlockPrototypeService` is used by `BlockServiceCreate.adjustHeightRequiredBlocks` (order → servercore) and `MultiSignService.checkBlockPrototype` (servercore internal) | Keep in servercore as SHARED |
| Spring component scanning must find classes in bigtangle-order | Package name is unchanged; Spring Boot apps already scan `net.bigtangle` |
| `BlockSaveService` is used by both paths | Keep in servercore (shared) |
