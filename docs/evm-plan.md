# EVM Integration Plan

Status: **all phases complete** (last updated 2026-08-02).

Goal: bring EVM smart-contract execution (Solidity/bytecode) to BigTangle by
extending the existing `l1-contract` chain, built as a chain-agnostic
`bigtangle-evm` library so a dedicated `l1-evm` chain can be extracted later.

## 0. Decision

| | New `l1-evm` chain | Integrate into `l1-contract` |
|---|---|---|
| Consensus infra | Clone the server modules, new genesis + chain ID, new bridge/validator bootstrap | Reuse the running chain |
| Effort to first EVM tx | High (all boilerplate above) | Low |
| Isolation | Clean (own block types, state, perf) | Shared with lottery engine |
| Fits "many L1 chains" vision | Yes | Yes (L1-contract = general contract chain) |

**Chosen:** build the EVM engine once as a chain-agnostic component
(`bigtangle-evm` library), **integrate into `l1-contract` first** — the
`ContractExecutor` SPI exists precisely for plugging in executors. Prove
consensus determinism there, then optionally extract a dedicated `l1-evm`
chain (same engine, new params/genesis) in Phase 7. The EVM machinery is
identical either way.

## Current architecture (relevant points)

- `l1-contract` chain: `l1-contract-server` (port 8085). Chain params: `ContractL1Params` (chain id `L1-contract`).
- Contract execution is deterministic and re-run on every node:
  `ContractEngine` (`l1-contract-server/.../layer1/contract/ContractEngine.java`)
  implements `ContractExecutor` (`bigtangle-servercore/.../handler/ContractExecutor.java`)
  and is registered via `ContractExecutorRegistry` (`ContractEngineRegistrar.java`).
- The engine is invoked from `ServiceBaseConfirmation.confirmContractExecution`
  (`bigtangle-servercore/.../ServiceBaseConfirmation.java:1341`) when a beacon
  reward block confirms. It receives the previous `Contractresult` + the set of
  referenced blocks and must produce a byte-for-byte identical
  `ContractExecutionResult` (`bigtangle-core/.../core/ContractExecutionResult.java`)
  on every node.
- Contract event blocks enter the DAG as `BLOCKTYPE_CONTRACT_EVENT` /
  `BLOCKTYPE_CONTRACTEVENT_CANCEL` and are collected via `dagBlockHashesFrom`.
- The chain is UTXO-based (Bitcoin-style) with PoS beacon finality over the DAG
  (stake-weighted GHOST fork choice + Casper FFG); contract payouts are emitted
  as UTXO transactions.

## Consensus constraint (the hard part)

EVM must produce **deterministic results on every node**. EVM execution is
deterministic given `(world state, ordered tx list, block context, gas)`, so:

1. The `Contractresult` chain (each result references the previous) acts as the
   **EVM block chain**: one state root per `Contractresult`.
2. EVM transactions are collected from confirmed DAG blocks and executed in a
   **canonical order**: sort by `(blockHash, output index)`, recorded in the
   result's `referencedBlocks` so every node derives the same order.
3. The new **state root** is stored in the `ContractExecutionResult` blob, so a
   node that computes a different root disagrees on consensus.

## Phase 1 — EVM interpreter (new `bigtangle-evm` module) ✅ complete

Chain-agnostic library module (like `bigtangle-bridge`), no server.

- `EVMInterpreter` interface + minimal deterministic interpreter:
  stack / memory / storage / gas / logs / return data, core opcode subset.
- Full Solidity support can later swap in Hyperledger Besu `evm`
  (Apache-2.0, compatible with this repo's AGPL-3.0) behind the same interface.
- `EVMTxProcessor`: execute an ordered tx list against a `WorldState` →
  receipts + new state root.
- EVM address = hash of the ML-DSA public key (keeps cross-layer identity
  consistent with the UTXO layer).

### Files

- `bigtangle-evm/pom.xml` — jar, depends on `bigtangle-core`.
- `net.bigtangle.evm.Word` — 32-byte word, 256-bit arithmetic.
- `net.bigtangle.evm.Address` — 20-byte account address (from pubkey hash).
- `net.bigtangle.evm.EVMAccount` — `{nonce, balance, codeHash, storageRoot}`.
- `net.bigtangle.evm.EVMStorage` — key/value storage, `byte[]` keys.
- `net.bigtangle.evm.WorldState` — account + storage sets, immutable snapshots.
- `net.bigtangle.evm.EVMStateRoot` — deterministic state-root computation
  (sorted Merkle tree over accounts/storage with `Sha256Hash`).
- `net.bigtangle.evm.EVMInterpreter` — the interpreter.
- `net.bigtangle.evm.EVMTxProcessor` — ordered tx application → receipts + root.
- Tests: opcode/gas vectors, state-root determinism.

### Delivered (27 tests green, full reactor compiles)

- `Word` — 256-bit big-endian word with wrap-around arithmetic, shifts, SAR.
- `Keccak` — self-contained Keccak-256 sponge (verified against known vectors:
  empty, `abc`, fox; needed for `SHA3`, CREATE address derivation, ABI).
- `Address` / `Rlp` — 20-byte address + minimal RLP for CREATE address.
- `EVMAccount` / `EVMStorage` / `WorldState` — account/storage model with
  deep-copy snapshots for deterministic rollback.
- `EVMStateRoot` — deterministic state root (sorted leaves over
  address|nonce|balance|codeHash|storageRoot).
- `MinimalEVMInterpreter` — core opcode subset: arithmetic/comparison/bitwise,
  SHA3, environment + block context, memory/stack/jumps, storage, LOG0..4,
  CALL/CALLCODE/DELEGATECALL/STATICCALL with snapshot rollback, CREATE/CREATE2,
  RETURN/REVERT, SELFDESTRUCT; deterministic simplified gas schedule.
- `EVMTxProcessor` — fee-upfront / value-in-execution semantics, nonce checks,
  intrinsic gas, failure rollback preserving fee+nonce, code-deposit cap.
- `EVMTx` / `EVMTxReceipt` / `EVMBatchResult` / `EVMInvalidTxException`.
- Tests: `KeccakTest`, `WordTest`, `MinimalEVMInterpreterTest`
  (arithmetic, storage, calldata, value-forwarding CALL, REVERT reason,
  CREATE-from-contract, static-call storage protection),
  `EVMTxProcessorTest` (value transfer, deploy+call, failure rollback,
  invalid nonce, state-root determinism across identical batches).

## Phase 2 — Deterministic world state ✅ complete

- World state (accounts + storage) is serialized deterministically by
  `EVMStateCodec` (sorted accounts/storage) and persisted inside the
  `ContractExecutionResult.extraData` blob (the `contractresult` table), so
  every node re-derives the same state and the state root is the consensus
  output. A `evm_receipt` table (`evm_receipt` DDL in the Postgres store)
  persists one receipt per EVM transaction block for RPC.

## Phase 3 — Block types + encoding ✅ complete

- New `BlockType`s: `BLOCKTYPE_EVM_DEPLOY`, `BLOCKTYPE_EVM_CALL` (appended, so
  the enum ordinals of existing types are unchanged). Payload:
  `EVMTransactionInfo` (`bigtangle-core`) with modes deploy / call / withdraw /
  deposit.
- Wired into `MempoolService.getTransactionType`, `BlockSaveService.setBlockTypeFromTransactions`,
  `ServiceBaseCheck` (formal + full EVM checks incl. deposit == burned UTXOs),
  `ServiceBaseConnect`, `ServiceBaseOrder`, `ServiceBaseReward`, `ServiceBase`,
  `BlockWrap`, `SlotService`, `BlockTypeHandler` registry, and the chain params.
- Canonical EVM ordering: referenced EVM blocks sorted by block hash — stable
  across nodes.

## Phase 4 — `EVMContractEngine` ✅ complete

- `ContractExecutorRegistry` now dispatches by contract classname; the
  `EVMContractEngine` (l1-contract-server) is registered under
  `net.bigtangle.l1.evm.EVMContract` alongside the lottery `ContractEngine`.
- `ServiceBaseConfirmation.confirmEVMExecution` (beacon confirmation) collects
  the EVM blocks, groups by contract token, runs the engine and persists the
  Contractresult (keyed by `evmContractResultKey(beaconHash, tokenid)`), the
  world-state snapshot, the receipts and the withdrawal payouts. A missing
  `insertContractResult` call in the legacy lottery path was left untouched;
  EVM records its result explicitly.

## Phase 5 — RPC + tooling ✅ complete

- `EVMQueryService` + `EVMController` on l1-contract-server: JSON-RPC-style
  `POST /evm/rpc` with `getBalance`, `getNonce`, `getStorageAt`, `getCode`,
  `getStateRoot`, `getBlockNumber`, `getReceipt`, `getLogs`, `call`,
  `getEVMAddress`, `getTransactionCount`.
- `Wallet.evmTransaction(...)` builds/signs/submits EVM block transactions
  (deposit/call/deploy/withdraw) with the deposit burned as UTXOs.

## Phase 6 — Tests ✅ complete

- `bigtangle-evm`: Keccak/Word/interpreter/processor suites (27 tests).
- `EVMContractEngineTest` (l1-contract-server, PostgreSQL): deploys an EVM
  contract token, submits deploy + call blocks through the real pipeline, runs
  the engine and verifies deployed code, storage writes, EVM balances and
  deterministic state roots across repeated executions.

## Phase 7 — dedicated `l1-evm` chain ✅ complete

- New module `l1-evm-server` (port 8093) reuses the L1-contract implementation
  (EVM engine, RPC, services) with
  `EVML1Params`/`EVML1TestParams` (chain ID `EVM`, EVM block types +
  token creation). README and parent POM updated.

## Top risks

- DAG → sequential ordering determinism (mitigated by Phase 3).
- Gas metering / fee funding of validators (gas is metered deterministically;
  fees currently stay inside the EVM world state, forward funding to the
  validator fee pool is future work).
- EVM state growth on a shared chain (mitigated by Phase 7, the dedicated
  `l1-evm` chain).
