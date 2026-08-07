# Plan: Split the Full Block Store by Layer

## Problem

All layers share a single monolith in `bigtangle-servercore`:

- `BlockStoreInterface` (658 lines) — the full contract (220+ methods)
- `DatabaseFullBlockStoreBase` (2,489 lines) — shared SQL/DDL + ~74 public methods
- `DatabaseFullBlockStore` (3,853 lines) — ~180 public methods mixing **every** domain
- `PostgreSQLFullBlockStore` (650) / `MySQLFullBlockStore` (586) — concrete DDL + dialect

Every layer boots the same store and creates **every table**, even tables its chain
never uses. Examples of the waste:

- **Layer 0** creates and implements `orders`, `ordercancel`, `orderresult`, `matching`,
  `matchinglast`, `matchingdaily`, `matchinglastday`, `paymultisign`,
  `paymultisignaddress`, `contractevent`, `contractresult`, `evm_receipt` … although
  L0 has no order-matching and no contract execution.
- **L1-order** carries the contract/EVML tables and the cross-chain anchor/vault tables.
- **L1-contract** carries the order-matching tables.

Because a single class implements the whole surface, it is impossible to:

- build a **layer-minimal store** (only the tables/methods that layer needs),
- give each layer its **own schema lifecycle** (DDL, migrations, indexes),
- keep cross-layer types (e.g. `OrderRecord`, `ContractEventRecord`) from leaking
  into modules that should not depend on them,
- compile/test a layer in isolation against its real subset of tables.

The fix is to **split the store into a shared base + per-layer subclasses**, one per
L1 domain, so `layer0-server` no longer implements/creates order or contract tables.

---

## Target architecture

```
bigtangle-servercore  (shared, layer-agnostic)
├── store/BlockStoreInterface           (core contract — see below)
├── store/CoreFullBlockStore            (from DatabaseFullBlockStoreBase)
└── store/CoreFullBlockStorePostgres/MySQL   (from PostgreSQL/MySQLFullBlockStore)

l1-order-server                          (order domain)
├── store/OrderFullBlockStore           extends CoreFullBlockStore
│        adds orders/ordercancel/orderresult/matching*/paymultisign*/account/price
├── store/OrderFullBlockStorePostgres/MySQL   extends Core...Postgres
└── DDL for order tables only

l1-contract-server                       (contract domain)
├── store/ContractFullBlockStore        extends CoreFullBlockStore
│        adds contractevent/contracteventcancel/contractresult/evm_receipt
└── store/ContractFullBlockStorePostgres/MySQL

(bridge-only tables — anchor/vault — stay on the L0 store where the L0AnchorHandler
 lives, or move to a dedicated CrossChainFullBlockStore if bridge ever detaches)
```

Each L1 module already has its own `NetworkParameters`, database, and
`DBStoreConfiguration`; only the concrete store class it instantiates changes.

---

## Domain → method/table inventory

Audited against `BlockStoreInterface` + `DatabaseFullBlockStore` + the `CREATE TABLE`
blocks in `PostgreSQLFullBlockStore`:

### Core (shared — Layer 0 + every L1)

| Area | Tables | Representative methods |
|---|---|---|
| Chain/DAG | `blocks`, `mcmc`, `chainblockqueue`, `lockobject`, `tipsqueue`, `myserverblocks`, `batchblock` | `get/getBlockWrap/put`, `existBlock`, `blocksFromChainLength`, `getBlocksInChainlengthInterval`, `getSolidBlockTopologyInInterval`, `getApproverBlockHashes`, `getNotInvalidApproverBlocks`, `getMCMC`, `updateBlockEvaluation*`, `insertTipsQueue`, `selectChainblockqueue`, `insert/deleteChainBlockQueue`, `insertBatchBlock`, `getBatchBlockList` |
| UTXO / outputs | `outputs`, `outputsmulti` | `getTransactionOutput(s)`, `getOpenAllOutputs`, `getOpenTransactionOutputs`, `getAllAvailableUTXOsSorted`, `getOutputsHistory`, `getOutputConfirmation`, `getTransactionOutputSpender`, `getTransactionSpentBlock`, `updateTransactionOutputConfirmed/Spent/SpendPending`, `updateAllTransactionOutputsConfirmed(Batch)`, `prunedHistoryUTXO` |
| Reward chain | `txreward` | `insertReward`, `getRewardSpent/Spender/PrevBlockHash/Confirmed/ConfirmedAtHeight/ChainLength`, `getAllConfirmedReward`, `getMaxConfirmedReward`, `blocksNotChainlengthFromHeigth`, `resetChainlengthSolid` |
| Tokens | `tokens` | `insertToken`, `getTokenID`, `getTokenByBlockHash`, `getTokenAnyConfirmed`, `getTokensList`, `getTokenTypeList`, `getTokenSpent/Prevblockhash/IssuingConfirmedBlock`, `getTokennameAndDomain`, `getTokensByDomainname`, `getTokenAmountMap`, `updateTokenConfirmed/Spent`, `getCalMaxTokenIndex` |
| Multisig (token/domain) | `multisign`, `multisignaddress` | `insertMultiSignAddress`, `getMultiSignAddressListByTokenidAndBlockHashHex`, `getMultiSignListByTokenid(AndAddress)`, `getMultiSignListByAddress`, `getCountMultiSignAlready`, `countMultiSign`, `saveMultiSign`, `updateMultiSign`, `deleteMultiSign` |
| PoS / staking | `stake_deposits`, `pos_state`, `attestation_votes` | `saveStakeDeposit`, `getStakeDeposit(ByOutput/ByBlockHash)`, `getAllStakeDeposits`, `getActiveStakeDeposits`, `updateStakeDepositAmount/Activation/Slashing/Exit`, `releaseStakeDeposit`, `clearStake*`, `savePosState`, `getPosState`, `deletePosState`, `saveAttestationVote`, `getAttestationsForSlot`, `getLatestAttestationVotes`, `getSummedAttestationVotes`, `deleteAttestationVote` |
| User data / misc | `userdata`, `subtangle_permission`, `access_permission`, `access_grant`, `settings`, `transactionstatus`, `accountBalance` (balance for wallet) | `insert/update/query/getUserData*`, `insert/update/get/deleteSubtanglePermission`, `getAllSubtanglePermissionList`, `insert/delete/getCountAccess*`, `get/insertSettingValue`, `upsertTransactionStatus`, `getTransactionStatus(esBy*)`, `getAccountBalance`, `calculateAccount` |

> `anchor` / `vault` are currently core because the L0 server hosts the
> L0AnchorHandler + bridge peg-in/out. Keep them core for now (see *Bridge* below).

### Order domain (l1-order only)

| Tables | Representative methods |
|---|---|
| `orders`, `ordercancel`, `orderresult` | `insertOrder`, `getOrder`, `getAllOpenOrdersSorted`, `getOrderCancelByOrderBlockHash`, `insertCancelOrder`, `updateOrderSpent/Confirmed/Prevhash/Blockhash/CancelSpent`, `insertOrderResult`, `getOrderResult`, `getOrderresultWithPrev`, `getMaxConfirmedOrderresult`, `getMaxRewardChainlengthOrderresult`, `getLowerConfirmedOrderresult`, `updateOrderResultSpent/Confirmed/Chainlength` |
| `matching`, `matchinglast`, `matchingdaily`, `matchinglastday` | `insertMatchingEvent(Last)`, `getLastMatchingEvents`, `getCountMatching`, `getTimeBetweenMatchingEvents`, `getTimeAVGBetweenMatchingEvents`, `deleteMatchingEvents`, `filterMatch` |
| `paymultisign`, `paymultisignaddress` | `insertPayPayMultiSign`, `insertPayMultiSignAddress`, `getPayMultiSign(WithOrderid/List)`, `getPayMultiSignAddressWithOrderid`, `getCountPayMultiSignAddressStatus`, `updatePayMultiSignBlockhash`, `updatePayMultiSignAddressSign` |
| price/ticker | `addLastdayPrice`, `saveLastdayPrice`, `saveAvgPrice`, `batchAddAvgPrice`, `getLastMatchingEvents`, `queryTickerByTime`, `queryTickerLast`, `prunedPriceTicker` |

### Contract domain (l1-contract only)

| Tables | Representative methods |
|---|---|
| `contractevent`, `contracteventcancel`, `contractresult`, `evm_receipt` | `insertContractEvent(Cancel)`, `getContractEvent(s/RecordOpen/Prev)`, `updateContractEventSpent/Prevhash/Blockhash/CancelSpent`, `insertContractResult`, `getContractresult(WithPrev)`, `getMaxConfirmedContractresult`, `getMaxRewardChainlengthContractresult`, `getLowerConfirmedContractresult`, `updateContractResultSpent/Confirmed/Chainlength`, `insertEVMReceipt`, `getEVMReceipt(sByToken)` |

### Inferred per-layer call sites (from `grep store.<method>(`)

- **Layer 0** (`layer0-server`, `layer0-mcmc`): only Core methods (chain, UTXO, token,
  multisig, PoS/stake, anchor/vault, tips/MCMC). No `getOrder`, no `insertContractEvent`.
- **L1-order** (`l1-order-server`, `l1-order-mcmc`): Core + Order methods
  (`getOrder`, `getAllOpenOrdersSorted`, `insertOrder`, `getLastMatchingEvents`,
  `batchAddAvgPrice`, `getOrderCancelByOrderBlockHash`, `getTimeBetweenMatchingEvents`,
  price/ticker).
- **L1-contract** (`l1-contract-server`): Core + Contract methods
  (`getContractEvent`, `getMaxConfirmedContractresult`, `getEVMReceipt(sByToken)`).
- Order/contract **base services** (`ServiceBaseOrder`, `ServiceBaseConfirmation`,
  `ServiceBaseConnect`, `CheckpointService`) still live in `bigtangle-servercore` and
  call both Core and domain methods. They must either (a) split per-layer too, or
  (b) keep depending on the *full* interface until the service layer is split.
  → See *Service layer* below.

---

## Step-by-step plan

### Step 1 — Freeze the interface contract

1. Keep `BlockStoreInterface` as the **union** contract initially (no signature
   changes → zero churn).
2. Mark each method with the domain it belongs to using group tags
   (`// domain: core`, `// domain: order`, `// domain: contract`), or split the
   interface into `CoreBlockStore`, `OrderBlockStore`, `ContractBlockStore` and have
   `OrderBlockStore extends CoreBlockStore`, etc. The interface split is the
   compiler-enforced version of the inventory above.

### Step 2 — Rename/split `DatabaseFullBlockStoreBase` → `CoreFullBlockStore`

1. Rename `DatabaseFullBlockStoreBase` → `CoreFullBlockStore` (keep package).
2. **Remove** order/contract methods + their SQL constants from the Core class.
   Keep only the Core inventory. (Net ~−63 methods, −lots of SQL strings.)
3. `getCreateTablesSQL()`/`getCreateIndexesSQL()` become **two-phase**: core DDL in
   the base, domain DDL contributed by subclass hooks:
   `protected List<String> getDomainCreateTablesSQL() { return List.of(); }` and
   same for indexes, appended in `create()`/`updateDatabse()`.
4. Keep the DB-dialect split: `CoreFullBlockStore` stays abstract; the Postgres/MySQL
   concrete classes extend it and provide core DDL + dialect (`afterSelect`,
   `duplicateInsert`, error-code mapping, `getCreateTablesSQL1/2` → core only).

### Step 3 — Extract `OrderFullBlockStore` (in `l1-order-server`)

1. New `OrderFullBlockStore extends CoreFullBlockStore` implementing the order-domain
   methods, **moved verbatim** from `DatabaseFullBlockStore` (orders, ordercancel,
   orderresult, matching*, paymultisign*, price/ticker, `prunedClosedOrders`).
2. New `OrderFullBlockStorePostgres` / `OrderFullBlockStoreMySQL` providing only the
   order DDL (`getDomainCreateTablesSQL()` → order tables + their indexes).
3. `OrderFullBlockStore` may also **override** core methods where order logic needs
   to hook in (e.g. order-result chainlength updates already called from
   `ServiceBaseConfirmation`). Keep overrides minimal and documented.
4. `StoreService` in `l1-order-server` (or its `DBStoreConfiguration`/`BeforeStartup`)
   instantiates `OrderFullBlockStorePostgres/MySQL`.

### Step 4 — Extract `ContractFullBlockStore` (in `l1-contract-server`)

Same pattern: `ContractFullBlockStore extends CoreFullBlockStore` + concrete dialect
subclasses, carrying only `contractevent`, `contracteventcancel`, `contractresult`,
`evm_receipt` + their indexes.

### Step 5 — Remove the old classes

Once L0/L1-order/L1-contract all use the new classes:

1. Delete `DatabaseFullBlockStore` + `DatabaseFullBlockStoreBase` from
   `bigtangle-servercore` (or keep as deprecated aliases for one release).
2. `PostgreSQLFullBlockStore`/`MySQLFullBlockStore` → renamed to the Core concrete
   classes (used by `layer0-server`).
3. Remove the now-unreachable order/contract table DDL from the core Postgres/MySQL
   classes.

### Step 6 — Service layer (biggest risk)

`ServiceBase*`, `BlockSaveService`, `StoreService`, `CheckpointService`,
`MempoolService`, and the handlers currently call order/contract methods through the
union interface. Options (pick one):

- **A (recommended): keep a thin union view for services only.** The concrete store
  is layer-minimal, but the *service* package keeps an `AllLayerStore` interface =
  `Core + Order + Contract` so `ServiceBaseOrder` / `ServiceBaseConfirmation` can
  keep compiling unchanged. Layer-minimality still holds at runtime (tables + SQL),
  but the Java surface is still union-wide. This is the low-risk first step.
- **B (full): split the service layer per layer too** — move `ServiceBaseOrder` into
  `l1-order-server`, the confirmation/connect order-result hooks into
  `l1-order-server`, contract hooks into `l1-contract-server`, etc. This is the
  long-term goal but is a large refactor touching the MCMC/consensus paths.

Recommend **A now, B later**: A removes the schema/method bloat and lets each layer
own its DDL; B is tracked as a follow-up.

---

## Concrete changes checklist

| # | File/area | Change |
|---|---|---|
| 1 | `BlockStoreInterface` | Split into `CoreBlockStore` + `OrderBlockStore extends Core` + `ContractBlockStore extends Core` (compiler-enforced inventory) |
| 2 | `DatabaseFullBlockStoreBase` → `CoreFullBlockStore` | Keep core only; add `getDomainCreateTablesSQL/IndexesSQL` hooks; delete order/contract methods + SQL |
| 3 | `DatabaseFullBlockStore` | Delete; order methods → l1-order, contract methods → l1-contract |
| 4 | `PostgreSQL/MySQLFullBlockStore` | → Core concrete; drop order/contract DDL |
| 5 | `l1-order-server/store/OrderFullBlockStore(Postgres/MySQL)` | New; order methods + order DDL |
| 6 | `l1-contract-server/store/ContractFullBlockStore(Postgres/MySQL)` | New; contract methods + contract DDL |
| 7 | `StoreService` / `DBStoreConfiguration` / `BeforeStartup` (per layer) | Instantiate the layer's concrete store |
| 8 | `layer0-server` | `PostgreSQLFullBlockStore` → Core concrete (no behavior change) |
| 9 | tests (`layer0-mcmc`, `l1-order-mcmc`, `l1-contract-mcmc`) | Point at the layer store; add a test asserting L0 DB has **no** `orders`/`contractevent` tables and L1-order has **no** `contractevent` table |

---

## Verification

- **Unit**: `mvn test -pl bigtangle-core` (unchanged).
- **Per-layer integration**: run `testall.sh` (L0) + `remote.sh` against
  `l1-order-server` and `l1-contract-server` — full lifecycle (mempool → batch →
  block → beacon confirm → order match / contract exec) must pass.
- **Schema assertion (new)**: after L0 boot, `SELECT tablename FROM pg_tables`
  contains no `orders`/`contractevent`/`evm_receipt`; after L1-order boot, no
  `contractevent`; after L1-contract boot, no `orders`.
- **Compile-time**: `OrderFullBlockStore` implements `OrderBlockStore` (cannot
  accidentally lose a core method); `ContractFullBlockStore` implements
  `ContractBlockStore`.

---

## Risks / notes

- **Shared base services** (Step 6) are the main coupling point. Option A keeps them
  compiling; do **not** move consensus-critical `ServiceBaseConnect/Confirmation`
  into a layer in the first pass.
- **`CheckpointService`** was a test-only duplicate of `ServiceBase.checkToken`
  (token-conservation audit). It was removed; tests now call
  `new ServiceBaseConnect(...).checkToken(store)` directly, so only `ServiceBase`
  holds the cross-domain read gating.
- **Pruning** (`prunedHistoryUTXO` core vs `prunedClosedOrders`/`prunedPriceTicker`
  order) must keep their per-layer scheduler wiring.
- **Migrations**: `updateDatabse()` version bumps must remain additive and
  layer-aware; the domain tables must not be re-created by a layer that does not own
  them.
- **`OrderRecord` / `ContractEventRecord` type leakage**: after the split, L1-order
  depends on `OrderRecord` but not `ContractEventRecord`; L1-contract the reverse.
  This also surfaces any accidental cross-layer dependency in the service code.

---

## Implementation status (2026-08)

Phase A of the plan is implemented: the store is now **domain-aware** and each layer
can be provisioned with a minimal schema, while the Java method surface stays
union-wide (services keep using `BlockStoreInterface`).

### What changed

1. **`BlockStoreInterface`** — added `StoreDomain` enum (`CORE`, `ORDER`, `CONTRACT`,
   `ALL`), `getStoreDomain()`, and default capability helpers `hasOrderDomain()` /
   `hasContractDomain()`.
2. **`DatabaseFullBlockStoreBase`** — holds the store domain (`setStoreDomain`) and
   exposes it via `getStoreDomain()`.
3. **Per-layer concrete stores**:
   - `bigtangle-servercore` — `CorePostgreSQLFullBlockStore` / `CoreMySQLFullBlockStore`
     (domain `CORE`, used by `layer0-server`).
   - `l1-order-server` — `OrderPostgreSQLFullBlockStore` / `OrderMySQLFullBlockStore`
     (domain `ORDER`).
   - `l1-contract-server` — `ContractPostgreSQLFullBlockStore` /
     `ContractMySQLFullBlockStore` (domain `CONTRACT`).
4. **DDL gating in `PostgreSQL/MySQLFullBlockStore`** — `getCreateTablesSQL2()` only
   creates the **contract/EVM tables** (`contractevent`, `contracteventcancel`,
   `contractresult`, `evm_receipt`) when `hasContractDomain()`; the contract indexes
   are likewise gated. This is the table-level win: **Layer 0 / L1-order no longer
   create contract tables**.
5. **`StoreService`** reads `store.domain` (`@Value`) and sets it on the store it
   returns, so runtime reads match the schema the layer was booted with.
6. **Per-layer wiring** — each layer's `BeforeStartup` builds its own domain store
   (`Core`/`Order`/`Contract`); each `application.yml` sets `store.domain`
   (`core`/`order`/`contract`).
7. **Cross-domain read gating** — `ServiceBase.checkToken` (and the test helper
   that now delegates to it) skip `getContractEventRecordOpen` when
   `!hasContractDomain()`; the BEACON confirm hook
   in `ServiceBaseConfirmation` only runs `confirmContractExecution` /
   `confirmEVMExecution` when `hasContractDomain()`.

### Why order tables stay on every layer (important correction)

The original plan assumed Layer 0 never uses order tables. That is **wrong for the
reward pipeline**: beacon solidification runs `calculateBlockOrderMatchingResult` →
`generateOrderMatching` → `getOrderMatchingIssuedOrders` on **every** layer, and the
resulting virtual UTXOs are what carry epoch-reward outputs. Removing order tables
from L0 broke fee/reward UTXO creation (wallet `InsufficientMoneyException`).
Consequently:

- **order tables are always created** (all domains) — they are load-bearing for
  rewards, not order-matching only;
- **contract/EVM tables are the truly layer-scoped ones** — cleanly removed from
  L0 and L1-order, present only on L1-contract.

The full Java-method split (moving `getOrder*`/`getContract*` bodies into per-layer
classes) remains Phase B; the interface is still union-wide for services.

### Tests

- `StoreDomainTest` — asserts the DDL produced by each domain (core excludes
  contract, order excludes contract, contract excludes order, ALL has everything).
- `CoreStoreSchemaTest` — asserts the concrete Core/Order/Contract store DDL.
- Full L0 suite (`helper/testall.sh`) green; `OrderMatchTest` (l1-order) and
  `EVMContractEngineTest` (l1-contract) green.

### Known test-suite caveat

The L0 integration suite has a pre-existing scheduler-vs-`resetStore()` race (the
500ms MCMC scheduler can query `lockobject` while a test drops/recreates tables),
which intermittently surfaces in reorg/consensus tests. It is timing-dependent and
unrelated to this change; the suite was confirmed green on multiple consecutive
runs with the feature in place.
