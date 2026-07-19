# Post-Quantum Cryptography Integration Plan

## 1. Threat Model & Scope

Quantum computers threaten two cryptographic primitives currently used:

| Primitive | Use | Quantum Threat | Priority |
|-----------|-----|----------------|----------|
| secp256k1 ECDSA | All signatures (tx, block, node identity) | Shor's algorithm breaks ECDLP entirely | **Critical** |
| HASH160 (RIPEMD-160 + SHA-256) | Address derivation from pubkey | Grover halves security (160->80-bit); key is already hashed so preimage required | **Medium** |
| SHA-256 | Hashing, PoW, merkle trees | Grover halves security (256->128-bit) | **Low** |
| AES-256-CBC | Wallet encryption | Grover weakens 256->128-bit | **Low** |

The critical difference is that **ECDS A public keys are exposed on-chain** at first spend. Once a public key is revealed, Shor's algorithm breaks it completely. Hash-based addresses (HASH160) provide a preimage shield before first spend, but that shield disappears once the key is published.

Priority order for protection:
1. Exposed public keys (immediately broken by Shor once revealed)
2. Signatures (forged by Shor once the key is known)
3. Wallet key generation (seed compromise enables future theft)
4. Hash functions (Grover halves security, not catastrophic)

---

## 2. Algorithm Selection

Use **NIST-standardized** algorithms only:

| Algorithm | Standard | Signature Size | Public Key | Role | Maturity |
|-----------|----------|---------------|------------|------|----------|
| **ML-DSA-65** (Dilithium) | FIPS 204 (Aug 2024) | 2.5 KB | 1.3 KB | Primary tx/block signatures | **Final standard** |
| **FN-DSA** (FALCON) | FIPS 206 (draft) | ~700 B | ~900 B | Experimental (smaller sigs) | Draft |
| **SLH-DSA** (SPHINCS+) | FIPS 205 (Aug 2024) | 8 KB (128-bit) | 32 B | Emergency only | Final standard |

**Primary: ML-DSA-65.** Best balance of signature size, verification speed, and NIST finality.

**Experimental: FN-DSA** once standardized. Smaller signatures (~700 B) benefit high-throughput scenarios.

**Emergency: SLH-DSA** only if ML-DSA or FN-DSA are broken. Its 8 KB signatures make it impractical for routine use. Listed here so the architecture accommodates it.

ML-DSA-65 parameter set (the "medium" security level) targets NIST security category 3 (~128-bit symmetric equivalent).

---

## 3. Migration Strategy: Hybrid Signatures

Run **ECDSA + ML-DSA in parallel** during a transition phase:

```
Before activation:    [ECDSA sig]               ->  verify ECDSA
Transition period:    [ECDSA sig][ML-DSA sig]    ->  verify BOTH
Post-quantum:         [ML-DSA sig]               ->  verify ML-DSA
```

This avoids a hard fork and lets users and validators upgrade wallets gradually.

### Hybrid Signature Wire Format

Not simple concatenation. Versioned serialization for future algorithm additions:

```
HybridSignature:
  version:     uint8      (currently 1)
  algorithms:  uint8      (number of component signatures)
  entries:
    algorithm: uint8      (1=ECDSA, 2=ML-DSA, 3=FN-DSA, etc.)
    length:    uint16     (byte length of this signature)
    signature: bytes[length]
```

Future algorithms (FN-DSA, SLH-DSA, or any post-ECDSA scheme) add entries without changing the serialization format.

### Consensus Validation Rules

```
Feature flag SIGNATURE_V2 disabled (current):
  - ECDSA signatures only
  - Hybrid signatures rejected

Feature flag SIGNATURE_V2 enabled:
  Legacy outputs (pre-activation UTXOs):
    - ECDSA-only accepted
    - Hybrid not required
  New outputs (post-activation UTXOs):
    - Hybrid required (ECDSA + ML-DSA)
    - ECDSA-only rejected
  Future outputs (flag SIGNATURE_V3):
    - ML-DSA-only accepted
    - ECDSA rejected
```

### Transaction Versioning

Transaction format must be explicit, not inferred from signature length:

```
TxVersion 1:  ECDSA signatures only
TxVersion 2:  Hybrid (ECDSA + ML-DSA)
TxVersion 3:  ML-DSA only
```

This avoids heuristic detection (e.g., `if (sig.length > 100)`) in consensus code.

---

## 4. Implementation Phases

### Phase 1: Crypto Provider Layer (weeks 1-2)

Goal: Add ML-DSA key and signature primitives without changing consensus.

1. **Add ML-DSA library dependency**

   ```xml
   <dependency>
     <groupId>org.bouncycastle</groupId>
     <artifactId>bcprov-jdk18on</artifactId>
     <version>1.78</version>
   </dependency>
   ```

   Bouncy Castle 1.78+ supports ML-DSA (Dilithium) via `NISTObjectIdentifiers`.

2. **Create `SignatureKey` interface**

   New file: `bigtangle-core/.../crypto/SignatureKey.java`

   ```java
   interface SignatureKey {
       Algorithm algorithm();
       byte[] publicKey();
       byte[] sign(byte[] msg);
       boolean verify(byte[] msg, byte[] sig);
       int securityLevel();
   }
   ```

   Implementations: `ECDSAKey` (wraps existing ECKey logic), `MLDSAKey`, `HybridKey`.

   The interface name avoids a `PQ` prefix — the architecture should be algorithm-agnostic. Ed25519, FN-DSA, or any future scheme all implement the same contract.

3. **Create `Signature` class hierarchy**

   - `ECDSASignature` — wraps existing DER-encoded signature
   - `MLDSASignature` — wraps raw ML-DSA signature bytes
   - `HybridSignature` — versioned container with component signatures

4. **Library abstraction: SignatureProvider**

   Consensus code must never import Bouncy Castle directly:

   ```
   SignatureProvider (interface)
       -> BcSignatureProvider (Bouncy Castle implementation)
       -> MLDSAEngine (stateless verify, may use native)
   ```

   This makes future library swaps (e.g., native libsodium, pure Java fallback) transparent.

### Phase 2: Address Format (weeks 2-3)

Goal: Define how PQ public keys map to addresses.

1. **Full SHA-256 hash, not truncated HASH160**

   Current: `HASH160(pubkey) = RIPEMD160(SHA256(pubkey))` — 20 bytes
   New:    `SHA256(pubkey)` — 32 bytes

   32 bytes provides full collision resistance. There is no meaningful storage or UX benefit to truncating to 20 bytes in a post-quantum context.

2. **Versioned address payload**

   Not a single `addressHeader` byte. Instead:

   ```
   Address:
     version:  uint8     (currently 1)
     network:  uint8     (0=mainnet, 1=testnet)
     algorithm: uint8    (1=ECDSA, 2=ML-DSA, 3=Hybrid, etc.)
     hash:     bytes[32] (SHA256 of public key)
   ```

   Base58-encoded (or Bech32 for future use). Adding future algorithms never requires changing the encoding format.

3. **Update NetworkParameters**

   ```java
   // Address version bytes for each algorithm+network combination
   Map<Algorithm, Map<NetworkType, Integer>> addressPrefixes;
   ```

### Phase 3: Transaction & Block Verification (weeks 3-5)

Goal: Verify hybrid signatures in transactions and blocks.

1. **No new opcodes**

   OP_CHECKSIG_PQ, OP_CHECKMULTISIG_PQ are **not** needed. The existing `OP_CHECKSIG` opcode dispatches to the correct verifier based on the key type in the script:

   ```
   executeCheckSig():
     pubkey = stack.pop()
     signature = stack.pop()

     switch pubkey.algorithm:
       ECDSA  -> verifyECDSA(signature, pubkey)
       MLDSA  -> verifyMLDSA(signature, pubkey)
       Hybrid -> verifyECDSA(sig.ecdsa, pubkey) && verifyMLDSA(sig.pq, pubkey)
       unknown -> fail
   ```

   This approach is cleaner than versioning opcodes (Bitcoin learned this with Taproot). Key type determines verification.

2. **Extend LocalTransactionSigner**

   - If wallet has both ECDSA and ML-DSA keys, produce `HybridSignature`
   - Script format: `[hybrid_sig] [pubkey_with_algorithm_tag]`

3. **Extend ScriptBuilder**

   - `createOutputScript(SignatureKey) -> Script` — embeds algorithm tag + hash or full key
   - `createInputScript(Signature[]) -> Script` — embeds signatures

4. **Extend Transaction.hashForSignature()**

   - Signature hash algorithm remains SHA-256 (only the signing primitive changes)
   - Inputs referencing ML-DSA outputs use the same sighash procedure as ECDSA

### Phase 4: Key Management & Wallet (weeks 4-6)

Goal: Wallets can generate, store, and spend from PQ keys.

1. **Extend WalletBase**

   - Parallel key chain: `KeyChainGroup` for EC keys, `PQKeyChainGroup` for PQ keys
   - `walletKeys()` returns both
   - `findKeyFromPubHash()` searches both chains

2. **Wallet serialization (protobuf)**

   - Add `SignatureKey` messages to `Protos.java`
   - Update `Wallet` protobuf serialization to include PQ key chains

3. **BIP39 seed to ML-DSA key derivation**

   ```java
   // Use HKDF, not custom HMAC
   byte[] seed = MnemonicCode.toSeed(mnemonic, passphrase);
   byte[] mlDsaSeed = HKDF.extractAndExpand(
       HKDF.sha256(), seed, "ML-DSA-SEED", 32);
   MLDSAKey key = MLDSAKey.fromSeed(mlDsaSeed);
   ```

   HKDF is the standard tool for domain-separated key derivation. The `info` parameter ("ML-DSA-SEED") prevents cross-protocol key reuse.

4. **Key encryption**

   - `KeyCrypterScrypt` already encrypts arbitrary key bytes (AES-256-CBC)
   - PQ private keys are byte arrays — same encryption applies unchanged

### Phase 5: Consensus & P2P (weeks 6-8)

Goal: Blocks and nodes are authenticated with PQ signatures.

1. **Feature flags, not activation height**

   ```
   FeatureFlag SIGNATURE_V2
     - Enables hybrid (ECDSA + ML-DSA) transaction acceptance
     - Nodes advertise supported flags in handshake

   FeatureFlag ADDRESS_V2
     - Enables 32-byte SHA-256 address format

   FeatureFlag SCRIPT_V2
     - Enables key-type-dispatch in OP_CHECKSIG
   ```

   Activation enables one flag at a time. Nodes negotiate. This is more maintainable than hardcoded block heights over years of upgrades.

2. **MCMC peer identity**

   - Nodes identify by ECKey currently
   - Add `HybridKey` to node identity messages
   - Peers verify handshake signatures using whichever algorithm the peer supports

3. **Replay protection**

   - If transaction format changes (TxVersion 1 -> TxVersion 2), include a `version` or `type` field in the sighash to prevent cross-version replay
   - Rule: a transaction signed with TxVersion 1 cannot be replayed as TxVersion 2 on the same chain

### Phase 6: Performance & Validation (weeks 8-10)

Goal: Production-ready performance.

1. **Benchmarking on target JVM**

   Published ML-DSA benchmarks use optimized C. Realistic Java performance will be slower. Plan to measure on the actual deployment hardware and JVM.

   Expected range (JVM, reference impl):
   - ML-DSA-65 sign:   ~200-500 microsec
   - ML-DSA-65 verify: ~50-100 microsec

   This is ~100x slower than ECDSA. Acceptable for most use cases but requires parallel verification for high-throughput nodes.

2. **Block size modeling**

   ECDSA signature per input:  ~70 bytes
   ML-DSA-65 signature:        ~2,500 bytes

   At the current 20 MB `MAX_DEFAULT_BLOCK_SIZE`:

   | Tx count | ECDSA | ML-DSA | Hybrid |
   |----------|-------|--------|--------|
   | 500      | 35 KB | 1.2 MB | 1.3 MB |
   | 2,000    | 140 KB| 4.8 MB | 4.9 MB |
   | 10,000   | 700 KB| 24 MB  | 25 MB  |

   At 2,000 tx/block, hybrid signatures add ~4.9 MB (~25% of block capacity). This is manageable but must be modeled against expected throughput, not hand-waved.

3. **Parallel verification**

   - Use fork-join pool to verify PQ signatures across transactions in a block concurrently
   - ECDSA sigs verify in ~10 microsec each; PQ sigs dominate wall-clock time
   - Target: block verification under 2 seconds

4. **NIST Known Answer Tests (KATs)**

   - Implement verification using NIST KAT vectors from the FIPS 204 package
   - Test vectors must pass identically across BC, native, and any future provider
   - Cover: ML-DSA-65 sign, verify, hybrid encoding, address derivation

---

## 5. Consensus Activation Rules

```
State: SIGNATURE_V2 disabled

  TxVersion 1 (ECDSA):  accepted
  TxVersion 2 (Hybrid): rejected

State: SIGNATURE_V2 enabled, on legacy UTXOs (pre-activation)

  TxVersion 1 (ECDSA):  accepted
  TxVersion 2 (Hybrid): accepted (ECDSA component must match)

State: SIGNATURE_V2 enabled, on new UTXOs (post-activation)

  TxVersion 1 (ECDSA):  rejected
  TxVersion 2 (Hybrid): accepted
  TxVersion 3 (PQ):     accepted
```

---

## 6. Mempool Policy

- Before `SIGNATURE_V2`: reject hybrid transactions at mempool boundary
- After `SIGNATURE_V2`: accept hybrid transactions; relay both versions
- Transaction replacement (RBF) must check signature version compatibility
- Fee estimation must account for ~2.5 KB per PQ signature

---

## 7. RPC Versioning

| RPC | Change |
|-----|--------|
| `getnewaddress` | New optional arg `algorithm` (ecdsa, mldsa, hybrid) |
| `signrawtransaction` | Accept hybrid signatures; return algorithm info |
| `validateaddress` | Return algorithm type, address version |
| `listunspent` | Include algorithm type per UTXO |
| `createmultisig` | Accept mixed algorithm multisig |

---

## 8. Hardware Wallet Considerations

- ML-DSA keys are ~1.3 KB (vs ~32 B for ECDSA)
- Signatures are ~2.5 KB
- USB transport for 2.5 KB is fine (HID ~64 B/packet -> ~40 packets per sig)
- Smartcard / secure element storage for 1.3 KB private keys may be constrained
- Plan for: signing oracle pattern (hot signer + cold key) for early deployment

---

## 9. Algorithm Identifiers

Every location where a public key or signature appears on-chain must include an algorithm identifier:

| Context | Location | Identifier |
|---------|----------|------------|
| Script pubkey | First byte of script | Algorithm tag |
| Address | Address payload | Algorithm field |
| Transaction input | Signature wrapper | Algorithm in HybridSignature entry |
| Transaction version | Tx header | TxVersion enum |
| Node identity | P2P handshake | Supported flags bitmap |
| Wallet serialization | Key chain protobuf | Algorithm enum |

No format inference from byte length. All algorithm selection is explicit.

---

## 10. Architecture Summary

```
SignatureKey (interface)
  ├── ECDSAKey    (wraps secp256k1)
  ├── MLDSAKey    (wraps ML-DSA-65)
  ├── HybridKey   (ECDSAKey + MLDSAKey)
  └── FutureKey   (FN-DSA, etc.)

Signature (interface)
  ├── ECDSASignature
  ├── MLDSASignature
  ├── HybridSignature
  └── FutureSignature

Script.OP_CHECKSIG
  └── dispatch by key type (no new opcodes)

SignatureProvider (abstraction layer)
  └── BcSignatureProvider  (Bouncy Castle)
  └── NativeSignatureProvider  (JNI, future)

Wallet
  ├── KeyChainGroup  (ECDSA keys)
  ├── PQKeyChainGroup  (PQ keys)
  └── Key derivation (HKDF from BIP39 seed)

Consensus activation
  └── Feature flags: SIGNATURE_V2, ADDRESS_V2, SCRIPT_V2
```

---

## 11. Files to Modify

| File | Change |
|------|--------|
| pom.xml | Add BC PQC dependency |
| crypto/SignatureKey.java | New — algorithm-agnostic key interface |
| crypto/ECDSASignature.java | New — wraps existing ECDSA sig into Signature |
| crypto/MLDSASignature.java | New — ML-DSA signature wrapper |
| crypto/HybridSignature.java | New — versioned hybrid signature container |
| crypto/SignatureProvider.java | New — provider abstraction |
| crypto/BcSignatureProvider.java | New — Bouncy Castle implementation |
| Script.java | Extend executeCheckSig() to dispatch by key type |
| ScriptBuilder.java | Add algorithm-aware output/input script methods |
| ScriptOpCodes.java | Remove OP_CHECKSIG_PQ (not adding it) |
| Transaction.java | Add TxVersion, extend hashForSignature() |
| LocalTransactionSigner.java | Produce HybridSignature when PQ key available |
| Wallet.java / WalletBase.java | Add PQ key chain, HKDF derivation |
| KeyCrypterScrypt.java | No change — byte-based encryption |
| NetworkParameters.java | Add feature flags, address prefixes |
| Address.java | Add versioned address payload, 32-byte SHA-256 hash |
| Protos.java | Add SignatureKey protobuf messages |
| NativeSecp256k1.java | No change — stays as ECDSA fallback |

---

## 12. Risk & Mitigation

| Risk | Mitigation |
|------|------------|
| ML-DSA signature size (2.5 KB) stresses block capacity | Model against expected throughput; adjust MAX_DEFAULT_BLOCK_SIZE if needed |
| Java ML-DSA verification ~100x slower than ECDSA | Parallel fork-join verification; native acceleration path |
| Wallet migration complexity for users | Hybrid mode; legacy EC keys continue working for years |
| Future NIST parameter updates | ML-DSA-65 is FIPS 204 final; abstract interface for provider swap |
| Hardware wallet key storage (1.3 KB pubkey) | Signing oracle pattern for early deployment |
| Cross-version replay attacks | Include TxVersion in sighash; explicit replay protection rule |
| Consensus divergence during transition | Feature flags with precise validation rules (section 5) |
