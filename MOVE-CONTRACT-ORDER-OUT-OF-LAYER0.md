# Plan: Move Contract & Order Code Out of Layer 0 Into Layer 1

## Goal

Remove all contract-execution and order-matching code from `layer0-*` modules so that Layer 0 becomes a pure settlement chain (token creation, transfers, rewards, anchors). Contract and order logic lives exclusively in `layer1-*` modules and the dedicated `bigtangle-l1-contract` / `bigtangle-l1-ordermatch` runnable nodes.

---

## Current Contract/Order Code in Layer 0

### 1. `layer0-mcmc` — `RewardService.java`

**`executeContractsInline()` (lines 330–377)** — executes contracts inline during L0 reward creation. References:
- `ContractExecutionResult`, `Contractresult`, `ContractConnectSupport`, `ContractExecutorRegistry`
- `TokenType.contract`, `BlockType.BLOCKTYPE_CONTRACT_EVENT`, `BLOCKTYPE_CONTRACTEVENT_CANCEL`

**`createMiningRewardBlock()` (lines 246–255)** — calls `serviceBase.generateOrderMatching()` and `executeContractsInline()` when `enableOrderMatchExecutionChain` is `false` (L0 path).

### 2. `layer0-servercore` — `RewardHandler.java` (line 33)

Calls `base.confirmOrderMatching()` during reward confirmation — this is order-matching confirmation logic on L0.

### 3. `layer0-servercore` — `TokensService.java` (line 40–41)

`getContractTokensList()` — queries tokens of type `TokenType.contract`. Used by the `searchContractTokens` API endpoint.

### 4. `layer0-server` — `DispatcherController.java` (lines 238–239)

`case searchContractTokens` — REST endpoint that calls `tokensService.getContractTokensList()`.

### 5. `layer0-mcmc` tests — various test files

- `Layer0BlockTypeScopingTest.java` — verifies L0 rejects ORDER/CONTRACT block types (should stay, it's a negative test)
- `FullPrunedBlockGraphTest.java` — has `testConfirmOrderMatchUTXOs2` (disabled, marked for L1)
- `ValidatorServiceTest.java` — has order solidity tests (should move to layer1-mcmc tests)
- `PerformanceRemote.java`, `CheckpointRemote.java` — reference order/contract types

---

## Plan: Move Contract & Order Code Out of Layer 0

### Phase 1 — Remove inline contract execution from L0 reward creation

**Move `executeContractsInline()` from `layer0-mcmc` to `layer1-mcmc`**

| File | Lines | What |
|------|-------|------|
| `layer0-mcmc/.../RewardService.java` | 330–377 | `executeContractsInline()` method |
| `layer0-mcmc/.../RewardService.java` | 30, 50–54 | Imports for contract types |
| `layer0-mcmc/.../RewardService.java` | 251–255 | Call to `executeContractsInline()` |

**Steps:**
1. Remove `executeContractsInline()` method from `layer0-mcmc/.../RewardService.java`
2. Remove the call at line 251–255 and the `currRewardInfo.setContractResult(...)` / `tx.setData(...)` that follows
3. Remove unused imports: `ContractExecutionResult`, `Contractresult`, `ContractConnectSupport`, `ContractExecutorRegistry`, `TokenType`, `Token`
4. The method already exists in spirit in `layer1-mcmc` — but `layer1-mcmc`'s `RewardService` does NOT have it (L1 uses separate execution blocks). So this method is L0-specific and should be **removed entirely**, not moved. L0 should no longer execute contracts inline.

### Phase 2 — Remove order matching from L0 reward creation

**`layer0-mcmc/.../RewardService.java` lines 246–255** — the `!enableOrderMatchExecutionChain` branch that calls `generateOrderMatching()` and `executeContractsInline()`.

**Steps:**
1. Remove the entire `if (!serviceBase.enableOrderMatchExecutionChain(block)) { ... } else { ... }` block
2. Replace with just the `else` branch logic (check for empty blocks when `onlyWithreferenced`)
3. Remove unused imports: `OrderMatchingResult`, `Contractresult`, `ContractExecutionResult`, `ContractConnectSupport`, `ContractExecutorRegistry`, `TokenType`, `Token`

### Phase 3 — Remove order-matching confirmation from L0 RewardHandler

**`layer0-servercore/.../RewardHandler.java` line 32–34:**
```java
if (!ctx.base().enableOrderMatchExecutionChain(ctx.block())) {
    base.confirmOrderMatching(w, ctx.confirmation(), ctx.store());
}
```

**Steps:**
1. Remove the `if` block and the `confirmOrderMatching()` call
2. The handler becomes a pure reward confirmation handler

### Phase 4 — Remove contract token listing from L0

**`layer0-servercore/.../TokensService.java` lines 40–41:**
```java
public AbstractResponse getContractTokensList(BlockStoreInterface store) throws BlockStoreException {
    List<Token> list = new ArrayList<>(store.getTokenTypeList(TokenType.contract.ordinal()));
```

**`layer0-server/.../DispatcherController.java` lines 238–239:**
```java
case searchContractTokens:
    AbstractResponse response = tokensService.getContractTokensList(store);
```

**Steps:**
1. Remove `getContractTokensList()` from `layer0-servercore/.../TokensService.java`
2. Remove the `searchContractTokens` case from `layer0-server/.../DispatcherController.java`
3. Ensure `layer1-server/.../DispatcherController.java` already has this endpoint (it should, since it's a copy)

### Phase 5 — Move order/contract test code from layer0-mcmc tests to layer1-mcmc tests

**Files to move:**
- `layer0-mcmc/src/test/java/net/bigtangle/mcmc/test/ValidatorServiceTest.java` — order solidity tests (lines ~2243–2547+)
- `layer0-mcmc/src/test/java/net/bigtangle/performance/PerformanceRemote.java` — contract/order performance tests
- `layer0-mcmc/src/test/java/net/bigtangle/performance/CheckpointRemote.java` — order data queries

**Files to keep (negative tests that verify L0 rejects order/contract types):**
- `layer0-mcmc/src/test/java/net/bigtangle/mcmc/test/Layer0BlockTypeScopingTest.java` — stays in layer0 (verifies L0 rejects ORDER/CONTRACT types)

**Files to update (disabled tests that reference order/contract):**
- `layer0-mcmc/src/test/java/net/bigtangle/mcmc/test/FullPrunedBlockGraphTest.java` — `testConfirmOrderMatchUTXOs2` is already `@Disabled` with a comment pointing to L1

### Phase 5 — Update `enableOrderMatchExecutionChain` behavior

The `enableOrderMatchExecutionChain()` method in `ServiceBase` controls whether order matching and contract execution happen inline (L0, `false`) or in separate execution blocks (L1, `true`). After removing contract/order code from L0:

1. L0's `createMiningRewardBlock()` should no longer call `generateOrderMatching()` or `executeContractsInline()`
2. The `!enableOrderMatchExecutionChain` branch in `layer0-mcmc/.../RewardService.java` should be removed entirely
3. L0's `RewardHandler.confirm()` should no longer call `confirmOrderMatching()`

### Phase 5 — Update `bigtangle-order` module if needed

The `bigtangle-order` module currently contains `ServiceBaseReward` (moved from servercore). Check if any additional order/contract creation services need to move from layer0 to `bigtangle-order` or `layer1-servercore`.

### Phase 6 — Update `bigtangle-l1-contract` and `bigtangle-l1-ordermatch` modules

Ensure the dedicated L1 runnable nodes have all the contract/order logic they need:
- `bigtangle-l1-contract` — should have `ContractEngine` (already in `layer1-servercore`)
- `bigtangle-l1-ordermatch` — should have `OrderMatchingEngine` (already in `layer1-servercore`)

### Phase 7 — Move test code

| Test File | Current Location | Action |
|-----------|-----------------|--------|
| `Layer0BlockTypeScopingTest.java` | `layer0-mcmc` | **Keep** — verifies L0 rejects ORDER/CONTRACT types |
| `FullPrunedBlockGraphTest.java` (`testConfirmOrderMatchUTXOs2`) | `layer0-mcmc` | Already `@Disabled` — move to `layer1-mcmc` tests |
| `ValidatorServiceTest.java` (order solidity tests) | `layer0-mcmc` | Move order-specific tests to `layer1-mcmc` tests |
| `PerformanceRemote.java` | `layer0-mcmc` | Move contract/order performance tests to `layer1-mcmc` |
| `CheckpointRemote.java` | `layer0-mcmc` | Move order data query tests to `layer1-mcmc` |

### Phase 8 — Verify and test

1. `mvn compile` — all modules compile
2. `mvn test -pl layer0-mcmc` — L0 tests pass (excluding moved tests)
3. `mvn test -pl layer1-mcmc` — L1 tests pass (including moved tests)
4. `mvn test -pl layer0-servercore` — L0 servercore tests pass
5. Verify no circular dependencies: `layer0-*` should not depend on `layer1-*` or `bigtangle-l1-*`

---

## Dependency & Risk Analysis

| Risk | Impact | Mitigation |
|------|--------|------------|
| Removing `executeContractsInline()` from L0 changes L0 consensus behavior | L0 would no longer execute contracts during reward creation | This is the intended behavior per LAYERING-PLAN.md Phase 1 — contracts belong on L1 |
| Removing `confirmOrderMatching()` from L0 RewardHandler | L0 would no longer confirm order matching results | This is the intended behavior — order matching belongs on L1 |
| `ServiceBase.generateOrderMatching()` still exists in servercore | Dead code path on L0 | Keep in servercore (shared); L1 still uses it. The L0 path is simply not called anymore |
| Tests reference order/contract types in layer0-mcmc | Tests fail after removal | Move tests to layer1-mcmc; keep only negative tests (L0 rejects ORDER/CONTRACT) in layer0 |

---

## Summary of Files to Change

| # | File | Change |
|---|------|--------|
| 1 | `layer0-mcmc/.../RewardService.java` | Remove `executeContractsInline()` method, remove order matching call, remove contract/order imports |
| 2 | `layer0-servercore/.../RewardHandler.java` | Remove `confirmOrderMatching()` call |
| 3 | `layer0-servercore/.../TokensService.java` | Remove `getContractTokensList()` method |
| 4 | `layer0-server/.../DispatcherController.java` | Remove `searchContractTokens` case |
| 5 | `layer0-mcmc/.../test/ValidatorServiceTest.java` | Move order solidity tests to `layer1-mcmc` |
| 6 | `layer0-mcmc/.../test/FullPrunedBlockGraphTest.java` | Move `testConfirmOrderMatchUTXOs2` to `layer1-mcmc` |
| 7 | `layer0-mcmc/.../performance/PerformanceRemote.java` | Move contract/order perf tests to `layer1-mcmc` |
| 8 | `layer0-mcmc/.../performance/CheckpointRemote.java` | Move order data tests to `layer1-mcmc` |

---

## Verification

After each phase:
1. `mvn compile -pl layer0-servercore,layer0-mcmc,layer0-server,bigtangle-order,bigtangle-servercore` — compiles
2. `mvn test -pl layer0-mcmc` — L0 tests pass
3. `mvn test -pl layer1-mcmc` — L1 tests pass
4. `mvn test -pl bigtangle-servercore` — servercore tests pass
