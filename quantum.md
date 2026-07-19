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

SLH-DSA-SHA2-256s (the "small" variant) was chosen over the "fast" variant. The "s" variant has a larger signature but a faster signing time and slightly smaller public key. For maximum security at the same category, the choice is moot — both target category 5 — but the small variant reduces per-key storage for the public key (64 B vs 128 B).

FN-DSA is excluded — it offers no advantage at category 5 and has significant implementation complexity.

---

## 2. KeyBundle and SignatureBundle

Keys and signatures are never encoded as raw concatenation. Both use versioned bundles with explicit algorithm identifiers.

Entries within each bundle are sorted by algorithm ID. This ensures the canonical encoding is deterministic for hashing.

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
  key_bundle:       KeyBundle       (both public keys)
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

The address commits to the entire KeyBundle (both public keys and their algorithm identifiers). Because the KeyBundle entries are sorted by algorithm ID, the canonical encoding is deterministic and all nodes produce the same hash.

A future `suite = 2` could replace or add algorithms without changing the address format.

---

## 6. Signature Domain Separation

Each algorithm's signature must be computed over a domain-separated digest to prevent cross-protocol leakage. Signing the same raw transaction digest with both algorithms opens the possibility of cross-algorithm manipulation.

```
tx_digest      = SHA256(tx_version || inputs || outputs || "BIGTANGLE-PQ-TX-v1")

mldsa_sig_hash  = SHA256("MLDSA-SIG-DOMAIN"  || tx_digest)
slhddsa_sig_hash = SHA256("SLHDSA-SIG-DOMAIN" || tx_digest)

mldsa_sig  = MLDSA.sign(priv_mldsa, mldsa_sig_hash)
slhddsa_sig = SLHDSA.sign(priv_slhddsa, slhddsa_sig_hash)
```

Each algorithm signs a domain-separated hash of the transaction. There is no shared randomness or shared digest across algorithms.

---

## 7. Consensus Rules

- Every input must carry a KeyBundle and SignatureBundle
- The bundle must contain both an ML-DSA-87 signature and an SLH-DSA-SHA2-256s signature
- Each signature is verified against the domain-separated hash of the transaction for its algorithm
- Both signatures must pass independently
- The address must match `SHA256(canonical_encoding(key_bundle))`
- No grace period, no legacy mode, no hybrid, no single-sig path
- Block proposers sign with both keys
- Block merkle root is computed with domain separator `"BIGTANGLE-MERKLE-v1"`

---

## 8. Script

`OP_CHECKSIG` consumes two stack items:

```
Stack:
  top:   SignatureBundle
  next:  KeyBundle
```

The interpreter:

1. Deserializes both bundles by version
2. For each entry in `signature_bundle`, finds the matching algorithm entry in `key_bundle`
3. Computes the domain-separated sighash for that algorithm
4. Dispatches to the correct verifier via `SignatureProvider`
5. All entries must pass; any failure rejects the input

No new opcodes. The key contents determine verification logic.

---

## 9. Key Derivation

### Entropy Requirements

The BIP39 mnemonic must contain at least 256 bits of entropy (24 words). A 12-word mnemonic has only 128 bits, which is insufficient for category 5 security. The wallet must reject seeds with less than 256 bits.

The seed must be generated from a CSPRNG (Cryptographically Secure Pseudorandom Number Generator), such as `java.security.SecureRandom`. Custom entropy sources are disallowed.

### Derivation

```
seed = MnemonicCode.toSeed(mnemonic, passphrase)

// HKDF with SHA-256, explicit salt and info for domain separation
PRK = HKDF.extract(HKDF.sha256(), seed, salt = "BIGTANGLE-PQ-v1")

OKM = HKDF.expand(PRK, info = "wallet root", L = 64)

ml_dsa_seed  = OKM[0:32]
slh_dsa_seed = OKM[32:64]

ml_dsa_key  = MLDSAKey.fromSeed(ml_dsa_seed)
slh_dsa_key = SLHDSAKey.fromSeed(slh_dsa_seed)
```

Each 32-byte key is independently generated from the OKM. There is no shared entropy path between the two keys.

### Child Key Derivation

For deterministic wallets, derive child keys per suite:

```
child_seed = HKDF.expand(PRK, info = "child-" || index || "-" || suite_id, L = 64)

child_mldsa_key  = MLDSAKey.fromSeed(child_seed[0:32])
child_slhddsa_key = SLHDSAKey.fromSeed(child_seed[32:64])
```

The suite ID in the info string ensures keys from different suites cannot collide.

---

## 10. Dual-Signature Operational Cost

Requiring two independent signatures per input is the most conservative choice available. It carries costs:

- **Verification throughput:** two verify operations per input (ML-DSA + SLH-DSA). Mitigated by fork-join parallel verification and a native provider path.
- **Implementation surface:** two provider implementations to audit. Failure in either freezes the chain. Mitigated by the `SignatureProvider` abstraction — providers can be independently tested, validated with NIST ACVP test vectors, and hot-swapped during maintenance windows.
- **Transaction size:** ~23 KB per input. See block size budget below.
- **Side-channel exposure:** Java BigInteger operations are not constant-time. Private key operations must run in an isolated signing oracle (cold signer) to minimize timing side-channel risk. Hot wallet signing is not recommended for production use.

These costs are accepted in exchange for the guarantee that breaking both lattice assumptions AND hash function security simultaneously is required to forge a transaction.

---

## 11. Block Size Budget

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

## 12. Provider Abstraction

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

## 13. Testing and Validation

All implementations must pass NIST ACVP test vectors for both algorithms.

Required test suites:

| Test | Source | Coverage |
|------|--------|----------|
| ML-DSA-87 KeyGen/Sign/Verify | NIST ACVP FIPS 204 vectors | All 3 operations |
| SLH-DSA-SHA2-256s KeyGen/Sign/Verify | NIST ACVP FIPS 205 vectors | All 3 operations |
| KeyBundle canonical encoding | Custom vectors | Deterministic ordering |
| SignatureBundle deserialization | Custom vectors | Invalid version, algorithm ID, length |
| Address derivation | Custom vectors | Canonical hash of KeyBundle |
| Sighash domain separation | Custom vectors | Per-algorithm digest isolation |
| Replay protection | Cross-version vectors | Legacy vs PQ digest rejection |

Providers must produce identical results for the same seed. Test vectors must be cross-validated between the BC provider and any native provider.

---

## 14. Governance Upgrade Path

If one algorithm is catastrophically broken:

1. Governance proposal to activate a new `suite` ID that removes the broken algorithm
2. The broken algorithm's entries in new KeyBundles and SignatureBundles are ignored
3. Existing UTXOs remain spendable under their original suite ID (the old suite's valid entries are still accepted for old UTXOs)
4. New UTXOs use only the updated suite
5. After a sunset period, the broken suite is fully deprecated

This path is exercised periodically in testnet to ensure the mechanism works before it is needed.

---

## 15. Risk

| Risk | Mitigation |
|------|------------|
| Lattice cryptography broken (ML-DSA) | SLH-DSA depends only on SHA-256; chain survives on SLH-DSA alone |
| Implementation bug in either provider freezes chain | Independent ACVP validation; canary testing on testnet; governance upgrade path (section 14) |
| 23 KB per input increases block propagation | Benchmark with realistic network topology; adjust block rate or size |
| Verification throughput bottleneck | Parallel verify across inputs; native provider |
| Java BigInteger side-channel leakage | Signing oracle / cold-key pattern for production; hot signing only for development |
| Hardware wallet cannot store 2.5 KB key | Signing oracle / cold-key pattern for initial deployment |
| Future NIST deprecates or replaces ML-DSA-87 | Algorithm suite ID allows clean replacement; old UTXOs remain spendable under old suite |
| 128-bit entropy from 12-word mnemonic insufficient | Enforce 256-bit minimum entropy (24-word mnemonic) in wallet |
| Cross-algorithm shared digest attack | Domain-separated sighash per algorithm (section 6) |
