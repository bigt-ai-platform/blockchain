# Layer Architecture

## Overview

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
   │ chainId: "ordermatch"     │   │ chainId: "contract"          │
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

## Module Structure

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

## Token Supply Model

- **Layer 0 genesis** mints the entire BIG supply (`NetworkParameters.BigtangleCoinTotal`). All `BLOCKTYPE_TOKEN_CREATION` (custom tokens) happens on L0 only.
- **L1 chains have no native BIG.** Their genesis blocks create only the DAG root block — no token outputs. L1's getBIG from L0 must flow through the bridge peg-in: L0 locks BIG to the vault address with `toAddressInSubtangle` set to the L1 beneficiary, and L1's `BridgeService.processPegInFromL0()` polls L0 and issues wrapped BIG.
- **L1 `BLOCKTYPE_TRANSFER`** moves BIG that came via peg — it cannot originate BIG.

## Key Design Mechanisms

### 1. `chainId` — chain identity

`NetworkParameters.chainId` identifies the chain:
- L0: `"L0"`
- L1-ordermatch: `"ordermatch"`
- L1-contract: `"contract"`

Each chain has its own database (configured via `db.dbName`), providing full isolation.

### 2. `getAllowedBlockTypes()` — ingest gate

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
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_REWARD,
BLOCKTYPE_CROSSTANGLE,
BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL
```

**L1-contract allows** (`ContractL1Params`):
```
BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER, BLOCKTYPE_REWARD,
BLOCKTYPE_CROSSTANGLE,
BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_CONTRACTEVENT_CANCEL,
BLOCKTYPE_CONTRACT_EXECUTE
```

`BLOCKTYPE_TOKEN_CREATION` is **L0-only**. L1 nodes reject it at the ingest gate.

### 3. Cross-layer communication via BLOCKTYPE_CROSSTANGLE

The sole cross-layer primitive is `BLOCKTYPE_CROSSTANGLE`. It carries two payload types:

**Anchors (L1 → L0 checkpointing):** Every N milestones, `AnchorPostService` on L1 creates a CROSSTANGLE block with a `LayerAnchor` JSON payload containing `{ chainId, l1RewardHeadHash, l1Height, confirmedRoot, signature, spvProof }` and HTTP POSTs it to L0. L0 validates the ECDSA signature + SPV Merkle proof and saves the anchor.

**Pegs (value transfer):** `toAddressInSubtangle` on the transaction specifies the L1 beneficiary. L0 locks UTXOs to a vault address; L1's `BridgeService` polls L0 and issues wrapped tokens. This is the only way BIG or custom tokens enter L1.

### 4. Anchor watcher — L1 follows L0 finality

`AnchorWatcherService` on L1 polls L0 for confirmed anchors. If the L0-anchored L1 tip is ahead, L1 reorgs to the anchored chain. This enforces: *"Once an anchor is L0-confirmed, the anchored branch is canonical."*

### 5. Bridge module

`bigtangle-bridge` implements:
- `AnchorService` — post anchors, validate, confirm, reward
- `BridgeService` — bidirectional peg (peg-in / peg-out)
- `AnchorPostService` — `@Scheduled` anchor posting on L1 (every 30s)
- `AnchorWatcherService` — `@Scheduled` anchor watching on L1 (every 60s)

### 6. Consensus isolation

Each layer runs independent MCMC consensus. L1 reorgs to L0-anchored tips via `AnchorWatcherService`, but MCMC tip-selection, weight/depth/rating, and reward creation are per-layer.

### 7. Bridge-only token transfer path

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

## Node Configuration

| Setting | layer0-server | layer0-mcmc | l1-order-server | l1-order-mcmc | l1-contract-server | l1-contract-mcmc |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|
| Port | 8081 | 8082 | 8083 | 8084 | 8085 | 8086 |
| `chainId` | L0 | L0 | ordermatch | ordermatch | contract | contract |
| `service.schedule.mcmc` | false | true | false | true | false | true |
| `service.schedule.mining` | true | false | true | false | true | false |

## How to Run

```bash
mvn -pl layer0-server      spring-boot:run
mvn -pl layer0-mcmc        spring-boot:run
mvn -pl l1-order-server    spring-boot:run
mvn -pl l1-order-mcmc      spring-boot:run
mvn -pl l1-contract-server  spring-boot:run
mvn -pl l1-contract-mcmc    spring-boot:run
```

All six nodes can run simultaneously on different ports, each against its own database.

## Implementation Status

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
