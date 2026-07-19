# Post-Quantum Cryptography Integration Plan

No backward compatibility, no hybrid mode, no ECDSA legacy. The chain goes pure post-quantum.

---

## 1. Algorithm Selection

Two NIST-standardized signature families. Both are FIPS final. Use both for defense in depth.

| Algorithm | Standard | Security Category | Signature | Public Key | Assumption |
|-----------|----------|-------------------|-----------|------------|------------|
| **ML-DSA-87** (Dilithium) | FIPS 204 | 5 (~256-bit symmetric) | 4.6 KB | 2.5 KB | Lattice (Module-LWE / Module-SIS) |
| **SLH-DSA-SHA2-256s** (SPHINCS+) | FIPS 205 | 5 (~256-bit symmetric) | 16 KB | 64 B | Hash function (SHA-256 only) |

ML-DSA-87 for everyday signatures (faster, smaller). SLH-DSA-256s for the most conservative security (hash-based, minimal cryptographic assumptions). Any future algorithm must also target category 5.

FN-DSA (FALCON) is excluded — its security is bounded by implementation complexity and it offers no advantage over ML-DSA at category 5.

---

## 2. Dual Signatures

Every transaction input carries two independent signatures:

```
TransactionInput:
  ml_dsa_signature:  bytes[~4600]   (ML-DSA-87)
  slh_dsa_signature: bytes[~16000]  (SLH-DSA-SHA2-256s)
  public_key:        bytes[...]     (both public keys concatenated)
```

Both must verify independently. If either scheme is broken, the other still protects the chain. There is no fallback to a single scheme — both are always required.

---

## 3. Block Structure

```
BlockHeader:
  ...
  proposer_ml_dsa_sig:  bytes[~4600]
  proposer_slh_dsa_sig: bytes[~16000]

Block:
  header:   BlockHeader
  txns:     Transaction[]     (each with dual sigs)
```

Every block is signed twice by the proposer. MCMC tip selection validates both signatures.

---

## 4. Address Format

Full 256-bit hash, no truncation, no ECDSA compatibility.

```
Address:
  network:    uint8     (0=mainnet, 1=testnet)
  hash:       bytes[32] (SHA-256 of concatenated ML-DSA + SLH-DSA public keys)
```

The address is a commitment to both public keys simultaneously. Spending requires revealing both keys and providing both valid signatures.

---

## 5. Consensus Rules

- Every input must carry both an ML-DSA-87 signature and an SLH-DSA-SHA2-256s signature
- Both signatures are verified against their respective public key
- The address must match `SHA256(ml_dsa_pubkey || slh_dsa_pubkey)`
- No grace period, no legacy mode, no hybrid transactions
- Block proposers sign blocks with both keys

The chain is post-quantum from genesis (or from the upgrade block). There is no transition.

---

## 6. Key Derivation

From a BIP39 seed:

```
seed = MnemonicCode.toSeed(mnemonic, passphrase)

key_material = HKDF.expand(HKDF.extract(HKDF.sha256, seed), 64,
                           info="BIGTANGLE-PQ-KEY")

ml_dsa_seed  = key_material[0:32]
slh_dsa_seed = key_material[32:64]

ml_dsa_key  = MLDSAKey.fromSeed(ml_dsa_seed)
slh_dsa_key = SLHDSAKey.fromSeed(slh_dsa_seed)
```

---

## 7. Library Abstraction

```
SignatureProvider
  ├── MLDSAProvider    (ML-DSA-87, Bouncy Castle 1.78+)
  ├── SLHDSAProvider   (SLH-DSA-SHA2-256s, Bouncy Castle 1.78+)
  └── NativeProvider   (future JNI acceleration)
```

Consensus code calls `SignatureProvider.verify()` only. Provider choice is a deployment configuration, not consensus logic.

---

## 8. Block Size Budget

| Component | Size |
|-----------|------|
| ML-DSA-87 sig | 4.6 KB |
| SLH-DSA-256s sig | 16 KB |
| Two public keys | ~2.6 KB |
| Dual sigs per input | ~21 KB |
| Dual sigs per block (proposer) | ~21 KB |

At 2,000 tx/block: ~42 MB of signatures. The current 20 MB `MAX_DEFAULT_BLOCK_SIZE` is insufficient. Increase to 100 MB minimum, or reduce per-block transaction count and increase block rate.

---

## 9. OP_CHECKSIG

No new opcodes. The existing `OP_CHECKSIG` dispatches by checking for both signatures in the input:

```
executeCheckSig():
  pubkey_mldsa  = stack.pop()   (ML-DSA-87 public key)
  pubkey_slhddsa = stack.pop()  (SLH-DSA-256s public key)
  sig_mldsa     = stack.pop()   (ML-DSA-87 signature)
  sig_slhddsa   = stack.pop()   (SLH-DSA-256s signature)

  verifyMLDSA(sig_mldsa, pubkey_mldsa)   OR fail
  verifySLHDSA(sig_slhddsa, pubkey_slhddsa)  OR fail
```

Both must pass. There is no single-sig path.

---

## 10. Risk

| Risk | Mitigation |
|------|------------|
| Lattice cryptography broken (ML-DSA) | SLH-DSA is hash-based and independent; chain survives on SLH-DSA alone |
| SLH-DSA signature size (16 KB per input) increases block propagation time | Benchmark propagation; raise block size limit to 100+ MB; parallel broadcast streams |
| Verification throughput (two signatures per input) | Fork-join parallel verification; native provider for JNI acceleration |
| Hardware wallet cannot store 2.5 KB ML-DSA private key | Signing oracle / cold-key pattern for early deployment |
| Bouncy Castle PQC implementation maturity | Abstracted behind `SignatureProvider`; can replace provider without consensus change |

---

## 11. Files to Modify

| File | Change |
|------|--------|
| pom.xml | Add BC PQC dependency |
| crypto/SignatureKey.java | New — MLDSAKey + SLHDSAKey |
| crypto/SignatureProvider.java | New — provider abstraction |
| crypto/BcSignatureProvider.java | New — BC implementation |
| Script.java | Extend executeCheckSig for dual verification |
| Transaction.java | Dual signature fields, address commitment to both keys |
| Block.java | Dual proposer signatures |
| Wallet.java / WalletBase.java | Dual key chain, HKDF derivation |
| Address.java | 32-byte SHA-256 of both public keys |
| NetworkParameters.java | Remove ECDSA params; add PQ-only config |
| Protos.java | Dual key protobuf messages |
