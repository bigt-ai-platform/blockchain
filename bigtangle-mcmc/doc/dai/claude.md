# BIGT — Proof of Useful Stake (Revised: GPU-Free Validators)

The core change is simple: validators are **service providers**, not compute providers. A validator's job is to reliably route AI job requests to an inference backend, return the output, and commit to it on-chain. Where the compute actually runs is the validator's business — they can own a GPU, rent one, or call an API. The protocol only cares that the output is correct and the commitment is honest.

---

## What changes and why

In the original design, validators had to run inference locally and generate ZK proofs of their own computation. This created a high hardware barrier and made the validator set small and centralised around people who could afford or run GPUs. The revised model separates two concerns that were previously bundled together:

- **Routing and commitment** — the validator's on-chain responsibility
- **Inference execution** — an off-chain implementation detail

A validator commits to an output. If the output is wrong, they get slashed. How they produce the output is up to them. This is the same trust model used by oracle networks like Chainlink — the node operator is economically responsible for correctness without the protocol dictating their internal architecture.

---

## How a validator provides the service

A validator runs a lightweight **router node** — a small Go or Rust process that does the following:

1. Receives an AI job request from the mempool (a prompt, a model ID, optional parameters)
2. Forwards the request to one or more inference backends over HTTP/gRPC
3. Receives the output tokens
4. Hashes the output and signs the commitment with their validator key
5. Broadcasts the signed commitment to the network

The inference backend can be any of the following:

- A local GPU running llama.cpp or Ollama
- A remote GPU rented from RunPod, Lambda Labs, or Vast.ai
- A commercial API such as Together.ai, Fireworks.ai, or Groq
- A self-hosted cluster owned by the validator
- A decentralised compute network such as Akash or io.net

The protocol is agnostic to all of these. The validator's bond is what makes them economically accountable regardless of which backend they use.

---

## Verifiability without ZK proofs

Since validators no longer generate ZK proofs of their own computation, the verifiability mechanism shifts from cryptographic to economic-and-redundant. Three mechanisms replace the ZK layer:

**Redundant multi-validator execution.** Every job is independently routed and answered by three validators selected by VRF. All three submit their output commitments (the hash of the output token sequence) on-chain. If all three match, the job is accepted and all three are rewarded. This is sufficient for the vast majority of jobs.

**Deterministic model outputs.** Open models with temperature=0 (greedy decoding) are deterministic given the same weights and tokenizer. The protocol registers specific quantized model versions in the ModelRegistry (by weights hash). When validators use the same registered model, their outputs should match bit-for-bit. Any divergence is therefore suspicious and triggers the dispute process.

**Optimistic fraud proofs for disputes.** When one validator's commitment diverges from the other two, a 7-slot challenge window opens. The diverging validator must either:

- Submit a valid justification (e.g. the model version was different due to a recently approved registry update, which is verifiable on-chain), or
- Lose the challenge by default, resulting in partial slashing (10% of bond)

A full slashing (100% of bond) is reserved for provably malicious behaviour — specifically, submitting a commitment that does not match the output actually returned to the requesting user, which is detectable because the user receives the output and can submit a dispute with the plaintext.

**User-submitted disputes.** A user who receives an output that does not match the on-chain commitment hash can submit a dispute transaction containing the plaintext output. The hash is recomputed on-chain and compared to the commitment. If they don't match, the validator is slashed immediately and the user receives a portion of the slashed bond as compensation.

---

## The routing validator node in practice

A minimal validator implementation requires only three components:

**1. Chain listener.** Watches the mempool for AI job requests addressed to the validator's slot. When a job arrives, it extracts the prompt, model ID, and parameters. This is a standard JSON-RPC subscription to the node's mempool, about 50 lines of code.

**2. Inference router.** Sends the job to the configured backend. The recommended interface is the OpenAI-compatible REST API, which is supported by llama.cpp server, Ollama, Together.ai, Fireworks.ai, Groq, and most other inference providers. This means the router code is the same regardless of backend — about 30 lines of HTTP client code.

**3. Commitment broadcaster.** Takes the output, computes `keccak256(output_tokens || job_id || validator_pubkey)`, signs the hash with the validator's BLS key, and submits the commitment transaction on-chain. About 40 lines using ethers.js or web3.py.

The total validator client is approximately 500–800 lines of code, not counting the consensus engine. This is an order of magnitude simpler than a ZK-proof-generating validator.

---

## What backend should validators use

This is a business decision for each validator. The economics work as follows: validators earn BIGT rewards for every job they correctly serve. Their cost is whatever they pay for inference. The margin is the reward minus the compute cost.

Validators with low traffic will prefer pay-per-token APIs like Together.ai or Fireworks.ai, where they only pay when a job arrives. Validators with high traffic will find it cheaper to rent dedicated GPU capacity from RunPod or Vast.ai on a monthly basis. Very high-volume validators may eventually buy hardware outright.

The protocol does not enforce any of this. What it enforces is correctness — if your backend returns wrong outputs and you commit to them, your bond is at risk.

---

## Changes to the consensus and slot structure

The slot time can now be reduced. Without ZK proof generation (which takes 8–12 seconds), a slot only needs to accommodate:

- Network round-trip to inference backend: 1–3 seconds
- BFT committee attestation: 3–4 seconds
- Block propagation: 1–2 seconds

A **6-second slot** is achievable. This is close to Ethereum's current 12-second slot, so the same engineering assumptions apply.

The five-phase slot structure simplifies to three phases:

**Phase 1 (0–1s): Commit.** Users submit job requests. Proposer seals the batch with a commitment root. Commit-reveal still applies — the proposer cannot read prompt content before the batch is sealed.

**Phase 2 (1–4s): Route and respond.** The three selected worker validators independently route each job to their backends and collect outputs. Each submits an output commitment (hash) on-chain.

**Phase 3 (4–6s): BFT commit.** 128-validator committee checks that commitments are well-formed and that at least 2 of 3 workers agree, then finalises the block via Tendermint prevote/precommit. Any commitment mismatch is flagged for the dispute window.

---

## Attack surface specific to routing validators

Two new attack vectors arise from the routing model that did not exist in the GPU model.

**Backend manipulation.** A validator's inference backend could be compromised (API key stolen, self-hosted machine hacked) and return subtly wrong outputs. The validator is still economically responsible because they signed the commitment. This is the correct incentive — validators should secure their backends. Redundant execution across three independent validators means a single compromised backend does not corrupt the final result, because the other two validators will disagree and trigger a dispute.

**Latency-based censorship.** A validator could deliberately use a slow backend so their commitment always arrives too late to be included in the block, effectively censoring jobs assigned to their slot without appearing to. The mitigation is a per-slot commitment deadline: commitments arriving after the deadline are treated as missing, triggering the inactivity penalty exactly as if the validator were offline. A validator who consistently misses deadlines is eventually ejected by the inactivity leak.

**API key exposure.** Validators using commercial APIs have API keys that, if leaked, allow attackers to burn their compute budget without their knowledge. This is a standard operational security concern, not a protocol-level attack. The protocol has no visibility into it. Validators are responsible for key rotation and rate limiting on their backend accounts.

---

## Updated parameter table

| Parameter | Value | Notes |
|---|---|---|
| Slot time | 6 seconds | No ZK generation needed |
| Epoch length | 14,400 slots (~24h) | Daily reward distribution |
| Min stake | 5,000 BIGT | Lower barrier, no GPU hardware cost |
| Redundancy | 3 validators per job | Majority-agreement verification |
| Dispute window | 7 slots (~42s) | Time to submit user plaintext dispute |
| Slash: wrong commitment | 100% bond | Provably malicious |
| Slash: unmatched output | 10% bond | Disputed, not yet proven malicious |
| Commitment deadline | 4 seconds into slot | Late = treated as missing |
| Inactivity threshold | 4 consecutive epochs | Before quadratic drain starts |
| Max validator share | 5% of total stake | Unchanged |
| Unbonding period | 21 days | Unchanged |

---

## Summary of what is simpler and what remains the same

What became simpler: no GPU required, no ZK proof generation, no complex circuit compilation, no Plonky2/Halo2 toolchain, validator client reduced to ~800 lines, slot time halved to 6 seconds.

What stayed the same: BFT consensus (CometBFT), VRF slot election, commit-reveal for MEV protection, slashing for equivocation, inactivity leak, KES key evolution, weak subjectivity checkpoints anchored to L1, model registry with governance approval, 5% validator cap, and the three-way redundant execution model.

The protocol becomes significantly more accessible to run while retaining the same economic security guarantees. The trust assumption shifts from "I can verify your computation" to "I can verify your commitment matches your output and punish you if it doesn't" — which is a well-understood and widely deployed model.