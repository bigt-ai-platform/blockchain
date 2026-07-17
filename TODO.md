# TODO

Remaining gaps after layered architecture implementation (see LAYERING-PLAN.md, now removed).

## P1 — L1 token fixtures use gated native mint instead of bridged tokens
The `signToken` endpoint on L1 is gated to reject `BLOCKTYPE_TOKEN_CREATION`, but L1 tests still create tokens locally via a gated path for setup. L1 tokens should only arrive via peg-in from L0 (bridge/wrapped tokens). Requires multi-node L0+L1 test infrastructure.

## Phase 4 — M-of-N vault multisig
Peg-out releases locked L0 UTXOs from a vault. Currently uses a single key (`vaultPriKeyHex`/`vaultPubKeyHex`). Should be threshold M-of-N multisig — N milestone-node keys, requiring M signatures to release. Prevents a single compromised key from draining the vault.

## Phase 4 — General contract model
The contract L1 only has a hardcoded Lottery contract path. Needs a generalized execution model so arbitrary contract types can be defined and executed on `l1-contract-server`.

## Phase 4 — Anchor liveness fallback
If the L1 milestone node stops posting anchors to L0, peg-out stalls. No degraded mode detects the gap, suspends peg-out, and auto-recovers when anchors resume.

## Phase 4 — Light-client sync
An L1 light node should sync by downloading only L0 anchors and verifying SPV proofs, without replaying full L1 history. `MerkleProof` exists but the sync path doesn't.

## Phase 4 — Observability
Per-chain metrics (sync health, anchor latency, consensus state) and seed discovery by `chainId`. No dashboards or registry extension for L1 nodes to advertise their chain ID.
