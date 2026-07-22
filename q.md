# Plan: Remove ECDSA/secp256k1 — PQ-only Keys

## Goal

Remove all ECDSA secp256k1 key usage from the codebase. Only ML-DSA-87 + SLH-DSA-SHA2-256s (post-quantum dual signature scheme) remains.

## Current State

Both ECKey (secp256k1 ECDSA) and PQ primitives coexist:
- `ECKey.java` + `NativeSecp256k1` JNI bridge — to be removed
- `PQKey.java`, `KeyBundle`, `SignatureBundle`, `PQAddress`, `PQSignatureProvider` — already implemented
- `PQKey.java` already exists from first migration attempt (needs re-review, may have corruption from aborted sed)
- Script.java CHECKSIG/CHECKMULTISIG already dispatches to `PQScriptUtils.verifyPQ()` when pubkey starts with `0x05`

## Phase 1 — Core crypto (1-2 commits)

### 1a. Create PQKey.java
A replacement for ECKey wrapping ML-DSA-87 + SLH-DSA-SHA2-256s key material.
- `PQKey(byte[] mlDsaPrivateKey, byte[] slhDsaPrivateKey, KeyBundle keyBundle)` — public constructor
- `PQKey.createNew()`, `PQKey.fromSeeds()`, `PQKey.fromPublicOnly()`
- `sign(Sha256Hash)` → returns `SignatureBundle`
- `toAddress(NetworkParameters)` → returns `PQAddress`
- `getPublicKeyBytes()` → returns `0x05`-prefixed KeyBundle bytes (callers must handle prefix correctly)
- `fromPublicOnly(bytes)` expects `0x05`-prefixed bytes; `fromPublicOnlyBytes(bytes)` expects raw KeyBundle bytes
- `getKeyCrypter()`, `getEncryptedPrivateKey()` — needed by wallet layer
- `MissingPrivateKeyException`, `KeyIsEncryptedException` — needed by signers

### 1b. Remove ECKey infrastructure
- Delete `ECKey.java`
- Delete `NativeSecp256k1.java`, `Secp256k1Context.java`, `NativeSecp256k1Util.java`
- Delete `org.bitcoin/` package
- Remove secp256k1 build from `helper/bigtangle/Dockerfile`
- Remove secp256k1 mention from `README.md`
- Remove `Secp256k1Context.getContext()` from `AbstractScheduleInitService.java`

### 1c. Update TransactionSignature
`TransactionSignature` no longer extends `ECKey.ECDSASignature`. Make it a standalone class with `BigInteger r, s` + `sighashFlags`.

### 1d. Update DeterministicKey
No longer extends ECKey (now removed). Keep EC-specific HD key derivation as standalone (not PQ) since it's only used in legacy wallet code. Fix `Comparator` import and `Address.fromP2PKHKey()` → `Address.fromHash160()`.

## Phase 2 — Script & Transaction layer (1 commit)

### 2a. Script.java
- Replace `import net.bigtangle.core.*` with explicit imports; add `Transaction`, `UnsafeByteArrayOutputStream`
- Remove EC legacy paths in `executeCheckSig()` and `executeMultiSig()` (CHECKSIG/CHECKMULTISIG only do PQ)

### 2b. ScriptBuilder.java
- `createOutputScript(ECKey)` → `createOutputScript(PQKey)`
- `createInputScript(TransactionSignature, ECKey)` → `createInputScript(TransactionSignature, byte[])`
- `createMultiSigOutputScript(int, List<ECKey>)` → `createMultiSigOutputScript(int, List<PQKey>)`
- Remove `ECKey.PUBKEY_COMPARATOR` usage

### 2c. SignedData.java
- `Sha256Hash.wrap(data)` → `Sha256Hash.twiceOf(data)` (data is not a hash)
- `fromPublicOnlyBytes()` → `fromPublicOnly()` (handles 0x05 prefix)

## Phase 3 — Wallet & Service layer (2-3 commits)

### 3a. Wallet layer (Wallet, BasicKeyChain, WalletBase, KeyChainGroup, etc.)
- Replace all `ECKey` types with `PQKey`
- `new ECKey()` → `PQKey.createNew()`
- `ECKey.fromPrivate(bytes)` → `PQKey.fromSeeds()`
- `ECKey.fromPublicOnly(bytes)` → `PQKey.fromPublicOnlyBytes()` or `fromPublicOnly()`
- `.sign(hash, null)` → `.sign(hash)` (PQKey.sign takes only Sha256Hash)
- `.sign(hash, aesKey)` → `.decrypt(aesKey).sign(hash)`
- `.toAddress(params).toBase58()` → `.toAddress(params).toHex()`
- `.encodeToDER()` → `.serialize()` (SignatureBundle)

### 3b. DeterministicKeyChain handling
DeterministicKey (EC-based HD keys) and PQKey are separate types. Fix type mismatches in:
- `DeterministicKeyChain.getKeys()` → wrap as `List<PQKey>` via `toPQKey()` helper
- `KeyChainGroup.freshKeys()` → return `List<PQKey>`
- `KeyChainGroup.findKeyFromPubHash()` → handle both types separately

### 3c. Service layer
- `ServiceBaseCheck`, `ServiceBaseConfirmation` — `ECKey.verify()` → `PQScriptUtils.verifyPQ()`
- `ServiceBase`, `ServiceBaseOrder`, `ServiceBaseConnect` — `ECKey` type → `PQKey`
- `AnchorService`, `BridgeService` — use PQKey/PQScriptUtils
- All `DispatcherController.java` (layer0, l1-order, l1-pai, l1-contract) — `ECKey.fromPublicOnly()` → `PQKey.fromPublicOnlyBytes()`
- `StakeService` — `depositKey.toAddress()` → `Address.fromHash160(params, Utils.sha256hash160(depositKey.getPubKey()))`
- `ValidatorDutyService` — `ECKey.fromPrivate()` → `PQKey.fromSeeds()`; `getPubKey()` → `getPublicKeyBytes()`
- `PayMultiSignService` — add missing `Sha256Hash` import (pre-existing bug)
- `AccessPermissionedService` — remove `ECIESCoder` usage (EC-specific encryption)
- `AccessGrantService`, `SubtanglePermissionService` — `ECKey.fromPublicOnly()` → `PQKey.fromPublicOnlyBytes()`
- `MultiSignServiceCreate`, `TokenDomainnameService` — `ECKey` → `PQKey`
- `DatabaseFullBlockStoreBase` — `ECKey ecKey` → `PQKey pqKey` in multisig loop
- `MarketOrderItem`, `WalletUtil` — `ECKey` type → `PQKey`
- `OrderMatchingEngine` — `ECKey.fromPublicOnly()` → `PQKey.fromPublicOnlyBytes()`

### 3d. Remove ECIES from SerializationTest
`ECIESCoder.encrypt(ECPoint, byte[])` is EC-specific. Remove ECIES portion from test.

## Phase 4 — Test fixes (2-3 commits)

### 4a. Core test compilation fixes
- `CoinSerializationTest`, `TransactionInputSerializationTest`, `TransactionOutputSerializationTest` — `key.toAddress()` → `Address.fromHash160(params, Utils.sha256hash160(key.getPubKey()))`
- `FakeTxBuilder` — same pattern; also `PKKey` typo → `PQKey`
- `TransactionTest` — `assertThrows(ScriptException)` → `assertThrows(VerificationException)` (PQ path throws different exception)

### 4b. Integration test fixes
All `AbstractIntegrationTest.java` (layer0, l1-order, l1-contract, l1-pai):
- `ECKey.fromPrivate()` → `PQKey.createNew()`
- `.toAddress().toBase58()` → `.toAddress().toHex()`
- `TransactionOutput.fromAddress(..., key.toAddress(params))` → `TransactionOutput.fromCoinKey(..., key)` or `Address.fromHash160(...)`
- `.sign(sighash, null)` → `.sign(sighash)`
- `ScriptBuilder.createInputScript(sig, key)` → `ScriptBuilder.createInputScript(sig, key.getPublicKeyBytes())`

### 4c. Remove legacy script test vectors
- `ScriptTest.dataDrivenValidTransactions` — tx_valid.json contains EC-key data
- `ScriptTest.dataDrivenInvalidTransactions` — tx_invalid.json contains EC-key data
- `ScriptTest.testCreateMultiSigInputScript` — hardcoded EC transaction bytes
- `ScriptSerializationTest.testScriptWithSignatures` — serialization mismatch

### 4d. Genesis block & hash
- `UtilGeneseBlock.add()` — legacy EC pubkeys (0x02/0x03/0x04 prefix) must be wrapped in a `KeyBundle` before passing to `PQKey.fromPublicOnly()`, otherwise `KeyBundle.deserialize()` throws "truncated key bytes".
- `BlockTest.testSerial` — expected genesis hash changes (pubkey format changed)
- `GenesisHashTest` — also needs updated expected hash

### 4e. PQScriptTest
`PQKey.createNew().getPubKey()` now returns PQ-prefixed bytes (0x05), so `isPQPubkey` returns true. Update assertion.

### 4f. AddressTest
`p2shAddressCreationFromKeys` — hardcoded expected P2SH address won't match random PQKey addresses. Use deterministic seeds (`Arrays.fill(seed, byte)`) or assert non-null only.

### 4g. ECKeyEncryptTest
Full of ECIESCoder/ECKey-specific tests. May need full removal or heavy rewriting.

### 4h. Other test compilation fixes
- `UtilsTest.testSolve` — uses `UtilGeneseBlock.createGenesis()` which needs the genesis fix
- `FeePoolRewardTest`, `DoubleSpentAttack`, `PaymentBenchmark`, benchmark tests — use `ECKey.fromPrivate()` pattern → `PQKey.createNew()`
- Various tests with `PQKey.createNew(.toAddress(networkParameters))` typo (missing `)` before `.`) — fix syntax

## Execution order

```
Phase 1a + 1b + 1c + 1d   (PQKey + remove ECKey)
       ↓
Phase 2a + 2b + 2c         (Script layer)
       ↓
Phase 3a + 3b + 3c + 3d   (Wallet + Service)
       ↓
Phase 4a + 4b + 4c + 4d + 4e + 4f + 4g + 4h (Tests)
       ↓
Full build & test suite
```

## Verification

After each phase:
```
mvn compile -q                                    # must pass
mvn test -pl bigtangle-core -am -q                # 269 core tests pass
mvn test -pl layer0-mcmc ...                      # 169 L0 tests pass
bash helper/testall.sh                            # full pipeline passes
```

## Known pitfalls (from first attempt)

| Pitfall | Mitigation |
|---------|-----------|
| `git checkout -- .` undid everything | **Commit after each phase** |
| Broad `sed -i` across 30+ files corrupted code | Use targeted edits per file, never regex replace |
| `PQAddress` vs `Address` type mismatch | Bridge: `Address.fromHash160(params, Utils.sha256hash160(key.getPubKey()))` |
| `Sha256Hash.wrap(data)` crashes (data ≠ 32 bytes) | Use `Sha256Hash.twiceOf(data)` or `Sha256Hash.of(data)` |
| `DeterministicKey`/`PQKey` type conflicts | Keep DeterministicKey standalone, `toPQKey()` wrapper |
| `.toAddress().toBase58()` → PQAddress has only `toHex()` | Replace all `.toBase58()` with `.toHex()` on PQ addresses |
| `.encodeToDER()` → SignatureBundle uses `serialize()` | Replace all `.encodeToDER()` with `.serialize()` |
| `ECIESCoder` uses EC-specific `ECPoint`/`BigInteger` | Remove ECIES; cannot be migrated to PQ |
| `PQKey.createNew(.toAddress(…))` typo (extra `.` before `(`) | Check all `createNew()` calls for correct syntax |
| Spring `ApplicationContext` failures in integration tests | Kill stale Docker containers first: `docker stop $(docker ps -aq) && docker rm $(docker ps -aq)` |
