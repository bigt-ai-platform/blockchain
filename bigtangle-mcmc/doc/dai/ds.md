Here's a complete protocol design — **BIGT PoUS** (Proof of Useful Stake) — synthesizing the best ideas from Ethereum 2.0, Cardano Ouroboros, Cosmos Tendermint, Algorand, and Avalanche, then closing every known attack vector.

---

## Layer 0 — Economic architecture

The system has three token flows: users pay BIGT monthly subscriptions into an **escrow pool**; validators stake BIGT into a **bond contract** to be eligible; rewards are distributed from the pool to validators each epoch. Slashing burns stake from the bond.

Let me start with the top-level structural layout:---

## Layer 1 — Validator lifecycle & selection

Drawing from **Cardano Ouroboros** (slot leaders) + **Algorand** (VRF secret ballot) + **Ethereum 2.0** (committee attestation):

**Validator requirements:**
- Minimum stake: `10,000 BIGT` (prevents Sybil attacks at the economic level)
- Hardware attestation: validators register a GPU fingerprint on-chain (TEE-signed)
- Unbonding period: `21 days` (prevents long-range attacks via stake withdrawal)
- Max delegation cap: no single validator controls >5% of total stake

**VRF-based selection (per slot):**
1. Each slot, every eligible validator computes a local VRF output: `VRF(privkey, epoch_seed || slot_number)`
2. If `VRF_output < threshold(stake / total_stake)`, the validator is selected as **proposer**
3. A separate 128-validator committee is selected for attestation — no one knows their role until the slot begins (prevents targeted attacks)

Now let's look at how a single block round works — the consensus loop:---

## Layer 2 — Verifiable AI work (the novel core)

The hardest problem in this design is making GPU inference **verifiable without re-running it**. Here's the solution stack:

**Commitment scheme:** Before inference, each worker commits `H(model_weights || job_id || worker_pubkey)` on-chain. This binds them to a specific model version — you can't swap weights after seeing the job.

**ZK proof of inference:** Workers generate a zk-SNARK (Plonky2 or Halo2) proving `f(input) = output` where `f` is the quantized model. The proof is succinct (~200KB), verifiable in ~50ms by any node, and computationally binding. This is the same approach used by zkML projects like EZKL.

**Redundant execution + Merkle disagreement:** Every job is independently run by 3 workers. Their Merkle roots over output tokens must match. If they diverge, a **fraud challenge** window opens: the disputing worker submits a bisection proof to isolate the first diverging step. The loser is slashed. This is identical to Optimism's dispute game but applied to inference steps.

**Model registry:** Only governance-approved model hashes can be used. This prevents validators from running backdoored models that produce subtly wrong outputs undetectably.

---

## Layer 3 — Attack resistance matrix---

## Layer 4 — Epoch & parameter summary

| Parameter | Value | Rationale |
|---|---|---|
| Slot time | 16 seconds | Enough for ZK proof generation on consumer GPU |
| Epoch length | 2,400 slots (~11 hours) | Validator set reshuffled; rewards distributed |
| Min stake | 10,000 BIGT | Sybil cost floor; competitive with hardware cost |
| Unbonding | 21 days | Longer than any known long-range attack window |
| Committee size | 128 validators | 2/3+1 = 86 honest needed; statistically robust |
| ZK redundancy | 3 independent workers | Bisection game resolves disagreements in O(log n) |
| Slashing: equivocation | 100% bond | Existential deterrent |
| Slashing: invalid ZK | 1 BIGT | Proportional to marginal fraud gain |
| Inactivity leak | Quadratic drain | From Ethereum 2.0; allows offline recovery |
| Checkpoint interval | 256 blocks | L1 anchor every ~68 minutes |
| Max validator share | 5% of stake | Cartel-resistance threshold |

---

## What makes this different from plain PoS

The core novelty is that **the work product of consensus is AI inference**, not just hashing or signing. The ZK proof of inference turns a normally unverifiable GPU computation into a verifiable statement. The commit-reveal scheme prevents proposers from profiting by reading prompt content, and the 3-worker redundancy + bisection game closes the "fake output" attack that would otherwise be the dominant exploit in any system like this.

The closest real-world analogues are **Bittensor** (validator-run AI inference) and **Gensyn** (verifiable ML training), but neither combines BFT single-slot finality with ZK output proofs and full slashing — this design does all three.