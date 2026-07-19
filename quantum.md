# Post-Quantum Cryptography Integration Plan

## 1. Threat Model & Scope

Quantum computers threaten two cryptographic primitives currently used:

| Primitive | Use | Quantum Threat | Priority |
|-----------|-----|----------------|----------|
| secp256k1 ECDSA | All signatures (tx, block, node identity) | Shor's algorithm breaks ECDLP entirely | **Critical** |
| SHA-256 | Hashing, PoW, address derivation | Grover's algorithm halves security (256->128-bit) | **Low** |
| RIPEMD-160 | Address (HASH160) | Grover: 160->80-bit -- addresses are just hashes, not signing keys | **Low** |
| AES-256-CBC | Wallet encryption | Grover weakens 256->128-bit -- still safe | **Low** |

The **critical path** is replacing ECDSA with a post-quantum signature scheme.

---

## 2. Algorithm Selection

Use **NIST-standardized** algorithms only:

| Algorithm | Standard | Signature Size | Public Key | Use Case | Maturity |
|-----------|----------|---------------|------------|----------|----------|
| **ML-DSA (FIPS 204)** Dilithium | FIPS 204 (Aug 2024) | 2.5 KB | 1.3 KB | Tx/block signatures | **Final standard** |
| **SLH-DSA (FIPS 205)** SPHINCS+ | FIPS 205 (Aug 2024) | 8 KB (128-bit) | 32 B | Chain-level signing | **Final standard** |
| **FN-DSA (FIPS 206)** FALCON | FIPS 206 (draft) | varies | varies | Future upgrade | Draft |

**Recommendation: ML-DSA (FIPS 204 Dilithium) as primary, SLH-DSA (FIPS 205 SPHINCS+) as fallback.**

ML-DSA offers the best balance of signature size, verification speed, and NIST finality for on-chain use.

---

## 3. Migration Strategy: Hybrid Signatures

Run **ECDSA + ML-DSA in parallel** during a transition phase:

```
Before quantum threat:  [ECDSA sig]             ->  verify ECDSA
Transition period:      [ECDSA sig] [ML-DSA sig] ->  verify BOTH
Post-quantum:           [ML-DSA sig]              ->  verify ML-DSA
```

This avoids a hard fork and lets users/validators upgrade wallets gradually.

---

## 4. Implementation Phases

### Phase 1: Crypto Provider Layer (weeks 1-2)

Goal: Add ML-DSA key & signature primitives without changing consensus.

1. **Add ML-DSA library dependency**
   - Use Bouncy Castle PQC (BC 1.77+ supports ML-DSA/Dilithium via NISTObjectIdentifiers)
   - Or use jdkilmn/dilithium-jni / pqclean JNI wrappers for performance
   - Add to pom.xml:
     ```xml
     <dependency>
       <groupId>org.bouncycastle</groupId>
       <artifactId>bcprov-jdk18on</artifactId>
       <version>1.78</version>
     </dependency>
     ```

2. **Create PQKey interface**
   - New file: `bigtangle-core/.../crypto/PQKey.java`
   - Parallel to ECKey -- holds ML-DSA public/private key bytes
   - `sign(byte[] msg) -> byte[]`
   - `verify(byte[] msg, byte[] sig) -> boolean`
   - `getEncoded() -> byte[]` (X.509/SubjectPublicKeyInfo for pubkey)

3. **Create PQSignature class**
   - New file: `bigtangle-core/.../crypto/PQSignature.java`
   - Wraps raw signature bytes + algorithm ID
   - `encodeToBitcoin() -> byte[]`
   - `decodeFromBitcoin(byte[]) -> PQSignature`

4. **Create HybridSignature class**
   - Holds ECKey.ECDSASignature ecdsaSig + PQSignature pqSig
   - Wire format: `[1-byte flags][ECDSA sig bytes][PQ sig bytes]`
   - Flag bits: `0x01 = has ECDSA`, `0x02 = has PQ`

### Phase 2: Address Format (weeks 2-3)

Goal: Define how PQ public keys map to addresses.

1. **New address prefix for PQ keys**
   - Currently: HASH160(SHA256(pubkey)) for ECDSA (20 bytes)
   - For PQ: SHA256(pubkey) truncated to 20 bytes, or use a new version byte
   - Option A (simpler): Use existing Address format with new addressHeader values
   - Option B (cleaner): New PQAddress class with different encoding (e.g. nano prefix)

2. **Update NetworkParameters**
   ```java
   int addressHeaderPQ           = 42;
   int p2shHeaderPQ              = 43;
   int dumpedPrivateKeyHeaderPQ  = 144;
   ```

3. **Script support for PQ checks**
   - New opcodes: OP_CHECKSIG_PQ, OP_CHECKMULTISIG_PQ (witness version)
   - Or repurpose existing opcodes with version byte in the script

### Phase 3: Transaction & Block Verification (weeks 3-5)

Goal: Verify hybrid signatures in transactions and blocks.

1. **Extend LocalTransactionSigner**
   - signInputs(): for each input, if wallet has PQ key, produce HybridSignature
   - Input script format: `[hybrid_sig] [pubkey_or_hash]`

2. **Extend Script.executeCheckSig()**
   - Detect PQ signatures by examining script pubkey length/format
   - Route to ECKey.verify() or PQSignature.verify() accordingly
   - For hybrid: verify BOTH

3. **Extend ScriptBuilder**
   - `createOutputScript(PQKey) -> Script`
   - `createInputScript(HybridSignature) -> Script`

4. **Extend Transaction.hashForSignature()**
   - PQ sigs may need a different sighash algorithm (SHA-256 is fine; only the signing changes)

### Phase 4: Key Management & Wallet (weeks 4-6)

Goal: Wallets can generate, store, and spend from PQ keys.

1. **Extend WalletBase**
   - Parallel key chain for PQ keys: PQKeyChainGroup
   - walletKeys() returns both EC and PQ keys
   - findKeyFromPubHash() searches both key chains

2. **Wallet serialization (protobuf)**
   - Add PQKey messages to Protos.java
   - Update Wallet protobuf serialization to include PQ key chains

3. **BIP39-style seed to PQ key**
   - ML-DSA uses a seed (32 bytes) to deterministically generate keys
   - Same BIP39 seed can derive seed_pq = HMAC-SHA256(seed, "PQ-DILITHIUM")
   - Enables HD-like PQ key derivation from existing mnemonics

4. **Key encryption**
   - KeyCrypterScrypt already encrypts any key bytes (AES-256-CBC)
   - PQ private keys are just byte arrays -- same encryption applies

### Phase 5: Consensus & P2P (weeks 6-8)

Goal: Blocks and nodes are authenticated with PQ signatures.

1. **Block signing**
   - Currently: blocks are PoS-validated, not "signed" by a single entity
   - Validators sign blocks with their node key -- extend to hybrid

2. **MCMC peer identity**
   - Nodes identify by ECKey -- add PQ public key to node identity messages
   - NodeIdentity.pqPublicKey: byte[]

3. **Network upgrade / fork mechanism**
   - Add activation height/epoch in NetworkParameters
   - Before activation: ECDSA-only, ignore PQ sigs
   - After activation: require hybrid sigs for new UTXOs
   - Legacy UTXOs remain spendable with ECDSA only (grace period)

### Phase 6: Performance & Validation (weeks 8-10)

Goal: Production-ready performance.

1. **Benchmarking**
   - ML-DSA: sign ~30 microsec, verify ~10 microsec (reference impl) -- ~10x slower than ECDSA
   - Block verification in MCMC -- parallelize PQ verification across transactions

2. **Signature aggregation (future)**
   - ML-DSA does not natively support signature aggregation
   - Consider batching: verify all PQ sigs in a block in parallel using fork-join pool

3. **Test vector generation**
   - Create deterministic test vectors for PQ signatures
   - Update ScriptTest, TransactionTest, WalletTest

---

## 5. Files to Modify

| File | Change |
|------|--------|
| pom.xml (root) | Add BC PQC dependency |
| ECKey.java | Add HybridSignature wrapper methods |
| Script.java | Extend executeCheckSig() for PQ |
| ScriptBuilder.java | Add PQ script creation methods |
| ScriptOpCodes.java | Add OP_CHECKSIG_PQ (optional) |
| Transaction.java | Extend hashForSignature() |
| TransactionSignature.java | Create parallel PQSignature class |
| LocalTransactionSigner.java | Produce hybrid signatures |
| Wallet.java / WalletBase.java | PQ key chain management |
| KeyCrypterScrypt.java | (no change -- byte-based) |
| NetworkParameters.java | Add PQ address version bytes |
| Address.java | PQ address format |
| Protos.java | PQ key serialization |
| NativeSecp256k1.java | (not touched -- stays as fallback) |

---

## 6. Risk & Mitigation

| Risk | Mitigation |
|------|------------|
| ML-DSA signature size (~2.5 KB vs 70 B ECDSA) increases block size | Account in MAX_DEFAULT_BLOCK_SIZE (currently 20 MB); 2.5 KB per tx is manageable for typical throughput |
| Verification speed (ML-DSA is ~10x slower) | Parallel verification in MCMC; use native/libsodium when available |
| NIST may update ML-DSA parameters | Use the FIPS 204 final parameters; code to abstract interface, not raw params |
| Wallet migration complexity | Hybrid mode lets users upgrade gradually; existing EC keys continue working |
| Bouncy Castle PQC module maturity | BC 1.78+ is production-ready; fallback to pqclean JNI if performance-critical |

---

## 7. Quick Win (Week 1)

For the shortest path to quantum readiness without rearchitecting:

1. Add BC PQC dependency
2. Implement MLDSAKey class (a thin wrapper around BcDilithiumSigner)
3. Add a `pqsign` RPC endpoint that signs with ML-DSA alongside ECDSA
4. Extend Wallet.toAddress() to return both ECDSA and ML-DSA addresses
5. This doesn't change consensus but gives developers early API access
