# Plan: Re-enable dual key support — ECKey + PQKey (token migration path)

## Status: IMPLEMENTED

All phases below are implemented and verified (`mvn clean test-compile` green; core
320 tests green; `helper/testall.sh` 231 layer0 tests green incl. the new
`EcToPqMigrationTest`).

## Goal

Re-introduce legacy `ECKey` (ECDSA/secp256k1) support alongside the current `PQKey`
(ML-DSA-87 + SLH-DSA-SHA2-256s) so that existing users can move funds held under an
**ECKey** address to a new **PQKey** address.

This is the inverse of commit `71c756b62` ("PQ migration: remove ECDSA/secp256k1,
only quantum-secure keys"), which deleted `ECKey` and its native secp256k1 bridge and
rewired every caller to `PQKey`.

## Confirmed design decisions

| Decision | Choice |
|----------|--------|
| EC crypto backend | **BouncyCastle 1.79** (no native secp256k1 JNI). `DeterministicKey.doSign()` already uses `ECDSASigner`. |
| Type architecture | **Clean dual-type refactor**: `ECKey` and `PQKey` become siblings under a common `Key` abstraction; `DeterministicKey` no longer `extends PQKey`. |
| Migration UX | **Normal spend + send**: user imports legacy EC key, wallet shows balance, sends to a PQ address via a standard transfer. No new tx type. |
| Activation | No height gate required for EC (legacy format already on-chain). PQ behavior is unchanged. |

## Current state (from code audit)

Already present and reusable:
- `TransactionSignature` — full ECDSA DER encode/decode + `sighashFlags` (`crypto/TransactionSignature.java`).
- `DeterministicKey` — EC HD keys with `ecSign()`/`doSign()` (BouncyCastle) and EC `getPubKey()` (0x02/0x03/0x04).
- `LazyECPoint`, `HDKeyDerivation`, `DeterministicHierarchy`, `ECIESCoder` — still intact.
- `Script.executeCheckSig`/`executeMultiSig` already dispatch on `PQScriptUtils.isPQPubkey(pubKey)`.

Broken / missing (the actual work):
1. **Script EC verification stub** — the non-PQ "else" branch calls `PQScriptUtils.verifyPQ`
   on EC pubkeys, which always fails. No real ECDSA verify path exists.
2. **Signing dispatch** — `Transaction.calculateSignature()` (both overloads) unconditionally
   calls `key.sign(hash)` (PQ) and returns a dummy `r=s=1` `TransactionSignature`; the EC
   branch in `LocalTransactionSigner` therefore produces invalid EC signatures.
3. **Type hierarchy hack** — `DeterministicKey extends PQKey` but inherits PQ `sign()` and
   `toAddress()`, which are wrong for EC material.
4. **Wallet typed to `PQKey` only** — `KeyChain`/`BasicKeyChain`/`KeyChainGroup`/`Wallet`
   have no EC import path; legacy WIF/`DumpedPrivateKey` import was removed.
5. **Address handling** — `getECKey(aesKey, address)` matches only PQ formats; legacy base58
   hash160 addresses are not produced/parsed on the EC path.

---

## Phase 0 — Baseline

- [ ] `git checkout -b feature/dual-key-eckey` from `eckey` (or create a WIP branch).
- [ ] `mvn -q compile` and `bash helper/testall.sh` on the branch start point; record the green baseline.
- [ ] Commit any branch scaffolding only after baseline is confirmed.

## Phase 1 — Core key abstraction (crypto layer)

**Files:** `bigtangle-core/src/main/java/net/bigtangle/core/`, `net/bigtangle/crypto/`, `net/bigtangle/crypto/pq/`.

1. [ ] Introduce a common `Key` interface (or `KeyType` enum `EC`/`PQ`) with:
   - `KeyType getKeyType()`
   - `byte[] getPubKey()` (EC: SEC1 0x02/0x03/0x04; PQ: 0x05-prefixed KeyBundle)
   - `byte[] getPubKeyHash()`
   - signing + `toAddress()` contracts (EC → legacy `Address`, PQ → `PQAddress`)
2. [ ] Restore `ECKey` as a BouncyCastle-backed implementation (no JNI):
   - `createNew()`, `fromPrivate(byte[])`, `fromPrivate(BigInteger)`, `fromPublicOnly(byte[])`.
   - `sign(Sha256Hash)` → `TransactionSignature` (via `ECDSASigner`).
   - static `verify(byte[] ecPubkey, TransactionSignature sig, Sha256Hash hash)` → boolean.
   - `toAddress(NetworkParameters)` → legacy `Address.fromHash160`.
3. [ ] Refactor `DeterministicKey` to `implements Key` (or `extends ECKey`) instead of
   `extends PQKey`; keep `ecSign()`, chain-code/HD logic, and EC `getPubKey()`.
4. [ ] Make `PQKey` `implements Key`; add `getKeyType() = PQ`; keep everything else.
5. [ ] Add `KeyType`-aware factory/helpers: `Key.fromPublicOnly`, `Key.isPQ(byte[])` etc.

**Verify:** `mvn -q compile -pl bigtangle-core -am`.

## Phase 2 — Script verification (critical broken stub)

**File:** `bigtangle-core/src/main/java/net/bigtangle/script/Script.java`.

1. [ ] In `executeCheckSig` (`~line 1455` else branch), replace the bogus
   `PQScriptUtils.verifyPQ(...)` with real ECDSA verify:
   ```java
   TransactionSignature sig = TransactionSignature.decodeFromBitcoin(sigBytes, requireCanonical, LOW_S);
   Sha256Hash hash = txContainingThis.hashForSignature(index, connectedScript, (byte) sig.sighashFlags);
   sigValid = ECKey.verify(pubKey, sig, hash);
   ```
2. [ ] In `executeMultiSig` (`~line 1538` else branch), same replacement for EC pubkeys.
3. [ ] Keep the PQ branch (`isPQPubkey`) unchanged; dispatch purely on the pubkey prefix.

**Verify:** unit tests re-enabled in Phase 8 prove EC scripts verify again.

## Phase 3 — Signing dispatch

**Files:** `core/Transaction.java`, `signers/LocalTransactionSigner.java`.

1. [ ] Fix `Transaction.calculateSignature(inputIndex, key, ...)` to dispatch on `key.getKeyType()`:
   - EC → `((DeterministicKey) key).ecSign(hash, null)` → real `TransactionSignature`.
   - PQ → current `key.sign(hash)` + `pqSignatureBundle` path.
2. [ ] Fix `addSignedInput`/`signInputs` (`~line 833`/`845`) to dispatch instead of the hard
   `((DeterministicKey) sigKey).ecSign(...)` cast.
3. [ ] Verify `LocalTransactionSigner` (`~line 119`) else-branch now receives a valid EC
   `TransactionSignature` and builds the scriptSig via `createInputScript(sig, key)`.

## Phase 4 — Address handling

**Files:** `core/Address.java`, `core/PQKey.java`, `crypto/DeterministicKey.java`, `script/ScriptBuilder.java`.

1. [ ] EC `toAddress()` → legacy `Address.fromHash160(params, hash160)` (base58). PQ unchanged (`PQAddress`).
2. [ ] `DeterministicKey.toAddress()` → legacy `Address` (override the inherited PQ behavior).
3. [ ] Add `Address.fromKey(NetworkParameters, Key)` helper so callers are key-type agnostic.
4. [ ] Audit `ScriptBuilder.createOutputScript`/`createInputScript` for EC vs PQ `getPubKey()` correctness (they are already prefix-agnostic via `getPubKey()`).

## Phase 5 — Wallet layer (dual-key management)

**Files:** `wallet/*` (`KeyChain`, `KeyChainGroup`, `BasicKeyChain`, `DeterministicKeyChain`, `Wallet`, `WalletBase`).

1. [ ] Generalize key collections to hold both `ECKey` and `PQKey` (use the common `Key` type
   in signatures; keep storage keyed by `getPubKeyHash()` which already works for both).
2. [ ] Restore legacy EC import: re-add `DumpedPrivateKey`/WIF parse (or hex-only
   `fromPrivate` path) and `Wallet.importKey` for EC material.
3. [ ] Make `Wallet.getECKey(aesKey, address)` match both legacy base58 hash160 and PQ hex;
   resolve the duplicated/conflicting matching at `Wallet.java:1137-1147`.
4. [ ] Ensure `getPubKeyHash()`/`getAddress()` from `UTXO` still round-trips for both types.

## Phase 6 — Service / API layer

**Files:** `server/BaseDispatcherController.java`, `server/service/Access*`, `StakeService`,
`ValidatorDutyService`, `layer0/l1` dispatchers, `MultiSignServiceCreate`, `TokenDomainnameService`.

1. [ ] In `BaseDispatcherController` (`~line 693`) and access services, restore the EC branch:
   `PQKey.fromPublicOnly` stays for PQ; add `Address.fromBase58` / `ECKey.fromPublicOnly` for legacy.
2. [ ] For `AccessPermissionedService`/`AccessGrantService`, re-enable `ECIESCoder` where EC
   payloads are expected; keep PQ-only where ECIES has no PQ equivalent.
3. [ ] Revert EC→PQ substitutions in `StakeService`/`ValidatorDutyService` where both key types
   are legitimate, using the key-type dispatch.

## Phase 7 — Migration flow (user-facing)

1. [ ] Confirm "normal spend + send" end-to-end: wallet holds a legacy EC key → `getBalance`
   sees the EC UTXOs → user sends to a PQ address → `LocalTransactionSigner` produces an EC
   signature → chain verifies via `Script` → PQ output created.
2. [ ] Add a documented example + integration test: "migrate from ECKey to PQKey".

## Phase 8 — Tests

- [ ] Restore EC test vectors: `ScriptTest` `tx_valid.json` / `tx_invalid.json`,
  `testCreateMultiSigInputScript`, `ScriptSerializationTest.testScriptWithSignatures`.
- [ ] Restore/rewrite `ECKeyTest` (BouncyCastle variants of sign/verify/pubkey/address).
- [ ] Fix `AddressTest.p2shAddressCreationFromKeys`, `ECKeyEncryptTest`.
- [ ] Add dual-key tests: sign-with-EC + verify, sign-with-PQ + verify, mixed multisig, and
  the Phase 7 migration test.
- [ ] Update genesis/`BlockTest.testSerial`/`GenesisHashTest` if any key format touched genesis (should be unchanged since PQ-only genesis is retained).

## Phase 9 — Build & verification

```
mvn -q compile
mvn test -pl bigtangle-core -am -q
mvn test -pl layer0-mcmc -am -q
bash helper/testall.sh
```

Commit after each phase (do not use broad `sed` — targeted edits per file).

---

## Risks / known pitfalls

| Risk | Mitigation |
|------|------------|
| `DeterministicKey extends PQKey` hack leaks PQ semantics (`sign()`, `toAddress()`) into EC keys | Refactor to sibling types in Phase 1 before touching dispatch. |
| EC `verifyPQ` stub in `Script` silently accepts nothing (always false) | Replace with `ECKey.verify`; add explicit EC test vectors. |
| `calculateSignature` returns dummy `r=s=1` for EC | Dispatch on key type; assert real `TransactionSignature` in tests. |
| Address type confusion (base58 hash160 vs PQ hex, 20 vs 32 bytes) | Single `Address.fromKey()` helper; one lookup path in `Wallet.getECKey`. |
| Native secp256k1 build re-introduction | Not needed — BouncyCastle only. Keep `Dockerfile`/`README` free of native step. |
| EC key import lost (WIF/`DumpedPrivateKey` removed) | Restore in Phase 5; gate behind explicit "legacy import". |
| Genesis/block hashes depend on key encoding | Avoid changing PQ genesis path; EC support must be additive only. |
