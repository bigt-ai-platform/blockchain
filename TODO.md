# TODO

Remaining gaps after layered architecture implementation (see LAYERING-PLAN.md, now removed).

## P1 — L1 token fixtures use gated native mint instead of bridged tokens
The `signToken` endpoint on L1 is gated to reject `BLOCKTYPE_TOKEN_CREATION`, but L1 tests still create tokens locally via a gated path for setup. L1 tokens should only arrive via peg-in from L0 (bridge/wrapped tokens). Requires multi-node L0+L1 test infrastructure.

## Phase 4 — M-of-N vault multisig — DONE
Peg-out releases locked L0 UTXOs from a vault. Implemented as an M-of-N P2SH vault: `vaultPubKeyHexList` (N keys) + `vaultM` (threshold) + `vaultPriKeyHexList` (the M private keys a node holds); legacy single-key mode remains when the list is empty. The peg-in binds to the vault script program (P2PKH or P2SH), and the release carries M ordered signatures + the redeem script, which passes L0 CROSSTANGLE consensus. Follow-up: true distributed M-of-N signing ceremony across N nodes (currently one node holds all M keys).

## Phase 4 — General contract model
Partially done: a `ContractExecutorRegistry` + `ContractEngineRegistrar` dispatch by contract classname (default native `ContractEngine` + `EVMContractEngine`). The native engine still hardcodes only the `LotteryContract` path (`// TODO run others`) — generalize it so arbitrary native contract types can be registered/executed on `l1-contract-server`.

## Phase 4 — Anchor liveness fallback
If the L1 milestone node stops posting anchors to L0, peg-out stalls. No degraded mode detects the gap, suspends peg-out, and auto-recovers when anchors resume.

## Phase 4 — Light-client sync
An L1 light node should sync by downloading only L0 anchors and verifying SPV proofs, without replaying full L1 history. `MerkleProof` exists but the sync path doesn't.

## Phase 4 — Observability
Per-chain metrics (sync health, anchor latency, consensus state) and seed discovery by `chainId`. Implementation plan drafted (JSON metrics endpoints + `AnchorRecord` timestamps + chainId in peer seed discovery); `spring-boot-starter-actuator` is already a dependency but no metrics endpoints exist yet.
