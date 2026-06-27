# Layer Architecture

## Overview

The Bigtangle blockchain is split into **Layer 0 (settlement)** and **Layer 1 (application)** chains. Each chain has its own `NetworkParameters`, genesis block, database, consensus (MCMC + reward), and port — running as an independent Spring Boot process. The split is enforced by a `chainId` + `getAllowedBlockTypes()` mechanism that gates block ingest at validation time.

```
                         Layer 0  (settlement)
  token creation · transfer/payment · reward · MCMC consensus
  ┌──────────────────────────────────────────────────────────┐
  │  layer0-server        layer0-mcmc                        │
  │  port 8081             port 8082                          │
  │  DB: layer0            DB: layer0                         │
  │  chainId: "L0"         chainId: "L0"                      │
  │  Layer0Params          Layer0Params                       │
  └──────────────────────────────────────────────────────────┘
           ▲ anchor post (L1→L0)        ▲ peg in/out (future)

  ┌────────┴──────────────────────────────────┐
  │ Layer 1  (order-match + contracts)         │
  │ ┌─────────────────────┐ ┌────────────────┐ │
  │ │ layer1-server        │ │ layer1-mcmc    │ │
  │ │ port 8083            │ │ port 8084      │ │
  │ │ DB: layer1           │ │ DB: layer1     │ │
  │ │ chainId: "L1"        │ │ chainId: "L1"  │ │
  │ │ Layer1Params         │ │ Layer1Params   │ │
  │ └─────────────────────┘ └────────────────┘ │
  └────────────────────────────────────────────┘
```

## Module Structure

| Module | Role | Runnable |
|--------|------|----------|
| `bigtangle-core` | Data model (`Block`, `Transaction`, `BlockType` enum) | No |
| `bigtangle-servercore` | Consensus/validation engine, storage, DB schema | No |
| `bigtangle-order` | Order-match data structures + service | No |
| `bigtangle-bridge` | Cross-layer anchor + peg logic (skeleton) | No |
| **`layer0-servercore`** | L0 services (`TokenCreationService`, `PaymentTransactionService`...) + `Layer0Params` | No |
| **`layer1-servercore`** | L1 services (`OrderdataService`, `AVGPriceService`...) + `Layer1Params` | No |
| **`layer0-server`** | L0 full node (REST API + mining) | **Yes** |
| **`layer0-mcmc`** | L0 consensus node (MCMC tip-selection + reward) | **Yes** |
| **`layer1-server`** | L1 full node (REST API + mining + order/contract engine) | **Yes** |
| **`layer1-mcmc`** | L1 consensus node (MCMC tip-selection + reward) | **Yes** |
| `bigtangle-server` | Legacy combined server (L0 + L1 in one process) | Yes |
| `bigtangle-mcmc` | Legacy combined MCMC node | Yes |
| `bigtangle-subtangle` | Subtangle/bridge instance (ported to L1 runtime template) | Yes |

## Key Design Mechanisms

### 1. `chainId` — chain identity

`NetworkParameters.chainId` (String) identifies which chain a node belongs to:
- L0: `"L0"`
- L1: `"L1"` (or `"ordermatch"` / `"contract"` when split, set via `layer1.chainId` property)

It is threaded through block storage, validation, and discovery. Each chain uses its own database (configured via `db.dbName`), providing full isolation without a shared-schema migration.

### 2. `getAllowedBlockTypes()` — ingest gate

`NetworkParameters.getAllowedBlockTypes()` returns the `EnumSet<BlockType>` accepted by a node. Enforced in `ServiceBaseCheck.checkBlockBeforeSave()` (`bigtangle-servercore/src/main/java/net/bigtangle/server/service/base/ServiceBaseCheck.java:1811`):

```java
if (!networkParameters.getAllowedBlockTypes().contains(block.getBlockType())) {
    throw new VerificationException(
        "Block type " + block.getBlockType() + " is not allowed on chain "
        + networkParameters.getChainId());
}
```

A Layer 0 node rejects L1 block types (ORDER/CONTRACT). A Layer 1 node rejects L0-only types (TOKEN_CREATION). This is a single gate enforced at block ingest — no other code path needs layer awareness.

**Layer 0 allow-set** (`Layer0Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_TOKEN_CREATION,
BLOCKTYPE_REWARD, BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_USERDATA,
BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE
```

**Layer 1 allow-set** (`Layer1Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_REWARD,
BLOCKTYPE_CROSSTANGLE,
BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL, BLOCKTYPE_ORDER_EXECUTE,
BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_CONTRACTEVENT_CANCEL, BLOCKTYPE_CONTRACT_EXECUTE
```

### 3. `@Primary` bean override — layer scope via Spring

Each runnable node module defines a `@Configuration` class that overrides the `networkParameters` bean with its layer-specific `NetworkParameters` subclass. The `@Primary` annotation ensures this bean wins over the base `NetConfiguration` in `bigtangle-servercore`.

```
bigtangle-servercore/NetConfiguration
    networkParameters() → MainNetParams / TestParams     (base, not @Primary)

layer0-server/Layer0NetworkConfiguration
    @Primary networkParameters() → Layer0Params          (overrides)

layer1-server/Layer1NetworkConfiguration
    @Primary networkParameters() → Layer1Params(chainId) (overrides)
```

This means no code in the consensus/validation stack needs to know about layers — it just calls `networkParameters.getAllowedBlockTypes()` and gets the right set for the running node.

### 4. Consensus isolation — separate MCMC nodes

Each layer has a dedicated MCMC node (`layer0-mcmc`, `layer1-mcmc`) that runs the full consensus loop (MCMC tip-selection + reward/rollback) against its own database. The MCMC `@ComponentScan` excludes the server's own schedules and MCMC services (same filter as `bigtangle-mcmc/MCMCStart`), so the mcmc module's versions are used.

The `service.schedule.mcmc: true` flag in the mcmc node's `application.yml` enables the MCMC schedule; the server node sets it to `false` and runs mining instead.

### 5. Two-pattern code organization

The layering is achieved by two complementary patterns operating at different levels:

**Pattern A — `@Service` facades (API/workflow layering):**
Domain logic extracted into `@Service` beans in `layer0-servercore` and `layer1-servercore`. These orchestrate what an API command does and are wired via `@ComponentScan("net.bigtangle")`.

**Pattern B — `BlockTypeHandler` strategy (consensus/validation layering):**
Per-`BlockType` validation/confirmation handlers registered in `ServiceBase.handlerRegistry()`. Services register their handler on the `ServiceBaseCheck` they construct, then validate through the handler. The `ServiceBaseCheck.checkBlockBeforeSave` gate rejects foreign block types at ingest.

## Node Configuration

Each runnable node has its own `application.yml` with independent defaults:

| Setting | layer0-server | layer0-mcmc | layer1-server | layer1-mcmc |
|---------|:---:|:---:|:---:|:---:|
| `server.port` | 8081 | 8082 | 8083 | 8084 |
| `db.dbName` | layer0 | layer0 | layer1 | layer1 |
| `service.schedule.mcmc` | false | true | false | true |
| `service.schedule.mining` | true | false | true | false |
| `layer1.chainId` | — | — | L1 (env) | L1 (env) |

## How to Run

```bash
# Layer 0
mvn -pl layer0-server   spring-boot:run  # L0 full node
mvn -pl layer0-mcmc     spring-boot:run  # L0 consensus

# Layer 1
mvn -pl layer1-server   spring-boot:run  # L1 full node (order + contract)
mvn -pl layer1-mcmc     spring-boot:run  # L1 consensus

# Or build fat JARs
mvn -pl layer0-server package -DskipTests
java -jar layer0-server/target/layer0-server-0.5.0-exec.jar
```

All four nodes can run simultaneously on different ports, each against its own database.

## Adding a New L1 Chain

1. Create a `NetworkParameters` subclass with the new chain's `getAllowedBlockTypes()` and `chainId`
2. Place it in its own `layer*-servercore` module (or reuse `layer1-servercore` with a distinct chainId)
3. Create a thin runnable node module with:
   - `ServerStart` / `MCMCStart` (annotated with `@ComponentScan("net.bigtangle")`)
   - `NetworkConfiguration` (overrides `networkParameters` with `@Primary`)
   - `application.yml` (distinct port + DB)
   - `pom.xml` depending on `bigtangle-servercore` + the core module

No changes to consensus/validation code are needed — the `getAllowedBlockTypes()` gate handles block-type scoping automatically.

## Implementation Status

| Component | Status |
|-----------|--------|
| `chainId` field in `NetworkParameters` | Done |
| `getAllowedBlockTypes()` with ingest gate | Done |
| `Layer0Params` / `Layer1Params` | Done |
| `layer0-servercore` / `layer1-servercore` modules | Done |
| `layer0-server` / `layer0-mcmc` nodes | Done |
| `layer1-server` / `layer1-mcmc` nodes | Done |
| `BlockTypeHandler` strategy | Done (template handler) |
| `bigtangle-bridge` module | Skeleton |
| Anchor posting (L1→L0) | Pending |
| SPV anchor verification | Pending |
| Bidirectional peg (L0↔L1) | Pending |
| Separate ordermatch / contract L1 chains | Pending |
