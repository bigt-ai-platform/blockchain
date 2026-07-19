# Post-Quantum Cryptography Integration Plan

No backward compatibility, no hybrid mode, no ECDSA legacy. The chain goes pure post-quantum.

---

## 1. Algorithm Selection

Two NIST-standardized signature families targeting security category 5. Both are FIPS final. Both are always required. Each relies on a mathematically independent hardness assumption, so breaking one does not break the other.

| Algorithm | Standard | Category | Signature | Public Key | Assumption |
|-----------|----------|----------|-----------|------------|------------|
| ML-DSA-87 (Dilithium) | FIPS 204 | 5 | 4.6 KB | 2.5 KB | Lattice (Module-LWE / Module-SIS) |
| SLH-DSA-SHA2-256s (SPHINCS+) | FIPS 205 | 5 | 16 KB | 64 B | Hash function (SHA-256 only) |

Algorithms are identified by a protocol-level **algorithm suite identifier**, not hardcoded by name. The identifier for this pair is `SUITE_CAT5_DUAL_1`. Future category-5 replacements use a different suite ID without changing the wire format.

This document refers to ML-DSA and SLH-DSA concretely, but the encoding always routes through algorithm identifiers.

FN-DSA is excluded — it offers no advantage at category 5 and has significant implementation complexity.

---

## 2. KeyBundle and SignatureBundle

Keys and signatures are never encoded as raw concatenation. Both use versioned bundles with explicit algorithm identifiers.

### KeyBundle

```
KeyBundle:
  version:  uint8     (currently 1)
  entries:  uint8     (number of keys, currently 2)
  key_1:
    algorithm: uint8  (1 = ML-DSA-87)
    length:    uint16
    public_key: bytes[length]
  key_2:
    algorithm: uint8  (2 = SLH-DSA-SHA2-256s)
    length:    uint16
    public_key: bytes[length]
```

### SignatureBundle

```
SignatureBundle:
  version:  uint8     (currently 1)
  entries:  uint8     (number of signatures, currently 2)
  sig_1:
    algorithm: uint8  (1 = ML-DSA-87)
    length:    uint16
    signature: bytes[length]
  sig_2:
    algorithm: uint8  (2 = SLH-DSA-SHA2-256s)
    length:    uint16
    signature: bytes[length]
```

Future algorithm additions add entries. The version field allows format evolution.

---

## 3. Transaction Format

```
TransactionInput:
  key_bundle:      KeyBundle     (both public keys)
  signature_bundle: SignatureBundle  (both signatures)

Transaction:
  version:   uint8     (currently 1, PQ-only)
  inputs:    TransactionInput[]
  outputs:   TransactionOutput[]
```

Transaction version is explicit — no heuristic detection.

### Replay Protection

If this is a protocol upgrade (not genesis), the transaction digest computation must differ from the legacy format:

```
digest = SHA256(tx_version || inputs || outputs || domain_separator)
```

where `domain_separator = "BIGTANGLE-PQ-TX-v1"`. This prevents any legacy ECDSA transaction from being replayed as a PQ transaction.

---

## 4. Block Structure

```
BlockHeader:
  height:          uint64
  prev_block:      bytes[32]
  merkle_root:     bytes[32]
  timestamp:       uint64
  proposer_keys:   KeyBundle
  proposer_sigs:   SignatureBundle

Block:
  header:   BlockHeader
  txns:     Transaction[]
```

Both the proposer's key bundle and signature bundle are part of the header. MCMC tip selection validates both signatures before accepting a block.

---

## 5. Address Format

Versioned, algorithm-aware, full 256-bit hash.

```
Address:
  version:     uint8     (currently 1)
  network:     uint8     (0=mainnet, 1=testnet)
  suite:       uint8     (currently 1 = SUITE_CAT5_DUAL_1)
  hash:        bytes[32] (SHA-256 of canonical KeyBundle encoding)
```

The address commits to the entire KeyBundle (both public keys and their algorithm identifiers).

A future `suite = 2` could replace or add algorithms without changing the address format.

---

## 6. Consensus Rules

- Every input must carry a KeyBundle and SignatureBundle
- The bundle must contain both an ML-DSA-87 signature and an SLH-DSA-SHA2-256s signature
- Both signatures are verified independently; both must pass
- The address must match `SHA256(canonical_encoding(key_bundle))`
- No grace period, no legacy mode, no hybrid, no single-sig path
- Block proposers sign with both keys

---

## 7. Script

`OP_CHECKSIG` consumes two stack items:

```
Stack:
  top:   SignatureBundle
  next:  KeyBundle
```

The interpreter:

1. Deserializes both bundles by version
2. For each entry in `signature_bundle`, finds the matching algorithm entry in `key_bundle`
3. Dispatches to the correct verifier via `SignatureProvider`
4. All entries must pass; any failure rejects the input

No new opcodes. The key contents determine verification logic.

---

## 8. Key Derivation

From a BIP39 seed with explicit HKDF parameters:

```
seed = MnemonicCode.toSeed(mnemonic, passphrase)

PRK = HKDF.extract(HKDF.sha256(), seed, salt = "BIGTANGLE-PQ-v1")

OKM = HKDF.expand(PRK, info = "wallet root", L = 64)

ml_dsa_seed  = OKM[0:32]
slh_dsa_seed = OKM[32:64]

ml_dsa_key  = MLDSAKey.fromSeed(ml_dsa_seed)
slh_dsa_key = SLHDSAKey.fromSeed(slh_dsa_seed)
```

Using explicit salt and info strings provides domain separation from any other protocol that may derive keys from the same seed.

---

## 9. Dual-Signature Operational Cost

Requiring two independent signatures per input is the most conservative choice available. It carries costs:

- **Verification throughput:** two verify operations per input (ML-DSA + SLH-DSA). Mitigated by fork-join parallel verification and a native provider path.
- **Implementation surface:** two provider implementations to audit. Failure in either freezes the chain. Mitigated by the `SignatureProvider` abstraction — providers can be independently tested, validated with NIST KAT vectors, and hot-swapped during maintenance windows.
- **Transaction size:** ~21 KB per input. See block size budget below.

These costs are accepted in exchange for the guarantee that breaking both lattice assumptions AND hash function security simultaneously is required to forge a transaction.

---

## 10. Block Size Budget

| Component | Size |
|-----------|------|
| ML-DSA-87 public key | 2.5 KB |
| SLH-DSA-256s public key | 64 B |
| KeyBundle overhead | ~6 B |
| ML-DSA-87 signature | 4.6 KB |
| SLH-DSA-256s signature | 16 KB |
| SignatureBundle overhead | ~6 B |
| **Per input total** | **~23 KB** |
| Proposer dual sigs (header) | ~23 KB |

| Scenario | Sig data | Block total (est.) | Feasible? |
|----------|----------|--------------------|-----------|
| 100 tx/block | 2.3 MB | ~3 MB | Yes, 20 MB limit |
| 500 tx/block | 11.5 MB | ~12 MB | Yes, 20 MB limit |
| 2,000 tx/block | 46 MB | ~48 MB | No — needs 100 MB limit |
| 5,000 tx/block | 115 MB | ~118 MB | Unlikely without sharding |

The current 20 MB `MAX_DEFAULT_BLOCK_SIZE` supports up to ~500 tx/block. Scaling beyond that requires a block limit increase (100+ MB) or a higher block rate. Actual limits should be set after benchmarking propagation latency, validator bandwidth, and storage growth.

---

## 11. Provider Abstraction

```
SignatureProvider (interface)
  verify(bundle: SignatureBundle, key: KeyBundle) -> boolean
  Supported algorithms: ML-DSA-87, SLH-DSA-SHA2-256s, ...
```

Implementations:

- `BcSignatureProvider` — Bouncy Castle 1.78+, covers all required algorithms
- `NativeSignatureProvider` — future JNI acceleration via libpqcrypto or similar

Consensus code depends only on `SignatureProvider`. Provider selection is deployment configuration.

---

## 12. Risk

| Risk | Mitigation |
|------|------------|
| Lattice cryptography broken (ML-DSA) | SLH-DSA depends only on SHA-256; chain survives |
| Implementation bug in either provider freezes chain | Independent KAT validation; canary testing on testnet; governance upgrade path |
| 23 KB per input increases block propagation | Benchmark with realistic network topology; adjust block rate or size |
| Verification throughput bottleneck | Parallel verify across inputs; native provider |
| Hardware wallet cannot store 2.5 KB key | Signing oracle / cold-key pattern for initial deployment |
| Future NIST deprecates or replaces ML-DSA-87 | Algorithm suite ID allows clean replacement; old UTXOs remain spendable under old suite |
