# P2PK vs P2PKH — Post-Quantum Comparison

## Design decision

This codebase uses **P2PK exclusively** for post-quantum (PQ) key outputs.
There is no P2PKH. The `Address` type survives only for P2SH scripts.

| Aspect | This codebase | Bitcoin | Ethereum |
|--------|---------------|---------|----------|
| **Live?** | Yes — active from launch | Research only | Research only |
| **Algorithm** | ML-DSA-87 now; dual ML-DSA-87 + SLH-DSA-256s after activation height H | Undecided | Undecided |
| **Standard** | FIPS 204 (SLH-DSA: FIPS 205, optional) | — | — |
| **Security level** | Category 5 | — | — |
| **Sig per input** | ~4.6 KB (ML-DSA-87) | Would need &lt;1 KB | Gas-constrained |
| **Key size** | ~2.6 KB | N/A | N/A |
| **Address** | 35-byte SHA-256 of KeyBundle | Would use hash of pubkey | Account-based |
| **Script** | P2PK (`<pubkey> OP_CHECKSIG`) | P2PKH-like (hash in output) | No script |
| **Migration** | Active from launch | Hard fork needed | Could use account abstraction |

## Why P2PK works here

The traditional Bitcoin argument for P2PKH — hiding the public key until
spend time to protect against quantum key-recovery attacks — does not apply
to PQ keys. ML-DSA-87 and SLH-DSA-SHA2-256s are designed to resist
quantum cryptanalysis regardless of key exposure.

P2PK is the simpler choice:

| Metric | P2PK | P2PKH |
|--------|------|-------|
| Locking script ops | 2 | 5 |
| Locking script size | ~2.6 KB + 1 byte | 25 bytes |
| Unlocking script | `<sig>` only | `<sig> <pubkey>` |
| Pubkey revealed | Always on-chain | At spend time |
| PQ security benefit | Same | None for PQ keys |
| Mistake surface | `sha256hash160(pubkey) == expected` | `hash == expected` (same) |

## What Bitcoin and Ethereum are doing

Both Bitcoin and Ethereum are **deferring** PQ migration, betting that more
compact signature schemes will standardise before quantum computers become
a practical threat (estimated 10–20 years).

**Bitcoin:** The 4 MB block weight limit cannot accommodate ~23 KB PQ
signatures per input at current transaction volumes. Likely candidates:

- **FALCON** (~1 KB signature, not yet a NIST final standard)
- **SQiSign** (~200 bytes, still in research phase)
- A new segwit version or taproot upgrade would be required for deployment

**Ethereum:** Gas costs make large PQ signatures prohibitive. Likely path:

- **Account abstraction** (ERC-4337) allows gradual per-account migration
- **STARK-based aggregation** or BLS migration
- No hard fork required for individual accounts

## Trade-off

This codebase pays the PQ cost up front — every transaction input carries
~4.6 KB of ML-DSA-87 signature — in exchange for active post-quantum security
from launch. Bitcoin and Ethereum are optimising for today's throughput and
deferring the cost, betting on smaller signatures arriving in time.

The chain ships ML-DSA-87 only (FIPS 204) and can add the SLH-DSA-SHA2-256s
backstop at a governance-chosen chain height (see `docs/technical.md`
§Governance and `docs/pq-mldsa-only-plan.md`): an attacker would then need to
break both lattice-based and hash-based cryptography simultaneously to forge a
signature. The switch is one-way and additive — ML-DSA-only UTXOs remain valid
forever.
