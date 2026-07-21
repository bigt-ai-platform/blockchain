# Cross-Chain Integration Test

## Overview

`CrossChainFlowTest` validates the full L0 ↔ L1 token bridge and order matching pipeline in a single JUnit test. It runs inside the L0 Spring Boot context (`Layer0MCMCStart`) and simulates L1 operations by writing directly to L0's store (since L0 and L1 are separate Spring Boot apps with separate databases).

## Test Flow

```
L0 genesis ──► pay BIG to bob ──► peg-in (CROSSTANGLE + vault)
                                        │
                                        ▼
                               L1 simulated issuance
                               (wrapped BIG tokens)
                                        │
                                        ▼
                               L1 order matching
                               (simulated)
                                        │
                                        ▼
                               Peg-out (vault marked spent)
```

### Phase 1: L0 Payment
Creates BIG tokens from genesis and pays them to a test key (`bob`). This uses the standard wallet HTTP API through the embedded server.

### Phase 2: Peg-in (L0 → L1)
Creates a `BLOCKTYPE_CROSSTANGLE` block that locks bob's BIG UTXO to the bridge vault address. A `VaultRecord` is saved tracking the locked amount, token ID, and the L1 beneficiary address.

The CROSSTANGLE block is stored directly (bypassing the full solidity pipeline). In production, `BridgeService.processPegIn()` handles this with a full saveBlock call.

### Phase 3: L1 Simulated Issuance
Creates another CROSSTANGLE block that issues wrapped BIG tokens on the simulated L1 side. In production, `BridgeService.processPegInFromL0()` on the L1 side would:
1. Observe the locked UTXO on L0 via HTTP
2. Issue wrapped tokens on L1
3. Anchor back to L0

### Phase 4: Peg-out (L1 → L0)
Marks the vault UTXO as spent, simulating a successful peg-out where L1 burned the wrapped tokens and the L0 vault releases them. In production, `BridgeService.processPegOut()` handles this with SPV proof validation.

## How to Run

```bash
# Single run with fresh database
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres -c "DROP DATABASE IF EXISTS info;"
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres -c "CREATE DATABASE info;"
mvn test -pl layer0-mcmc -Dtest=CrossChainFlowTest -DfailIfNoTests=false
```

## Test File

**`layer0-mcmc/src/test/java/net/bigtangle/mcmc/test/CrossChainFlowTest.java`**

Key dependencies:
- Extends `AbstractIntegrationTest` (L0)
- `@SpringBootTest(classes = Layer0MCMCStart.class)` with `bridge.active=true`
- Uses `CacheBlockPrototypeService`, `BlockStoreInterface`, `VaultRecord`

## Coverage Gap

The test validates the data model (CROSSTANGLE blocks, vault records, peg-out marking) but does NOT run the full solidity pipeline for CROSSTANGLE blocks. This is because:

1. CROSSTANGLE blocks need fee handling that the solidity check enforces
2. The `batch=true` path (skip solidity) is not used by the `saveBlock` HTTP endpoint
3. L0 and L1 are separate Spring Boot apps with separate databases — running both simultaneously in one JUnit is not supported

To close this gap, the bridge's `processPegIn`/`processPegOut` methods need to use the batch-skip path (similar to `saveBatchBlock`) or full production flow with proper fee handling.

## Existing Tests Reference

| Test | Module | Coverage |
|------|--------|----------|
| `OrderMatchTest` | `l1-order-mcmc` | 17 active tests for order placement, matching, cancellation |
| `BridgeServiceTest` | `layer0-mcmc` | Vault/guard queries (no successful peg-out test) |
| `AnchorRoundTripTest` | `layer0-mcmc` | **Disabled** (`@Disabled("PoS conversion")`) |
| `CrossChainFlowTest` | `layer0-mcmc` | Full L0→peg-in→L1→peg-out flow (data model only) |
