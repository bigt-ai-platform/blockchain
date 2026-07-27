# P2PKH-Only Implementation Plan

## Goal

All outputs use P2PKH (`OP_DUP OP_HASH160 <hash> OP_EQUALVERIFY OP_CHECKSIG`).
No P2PK outputs are created. UTXO set stores 25 bytes per output instead of
~2632 bytes. Inputs always include the pubkey (`<sig> <pubkey>`).

---

## Step 1: Core output creation — `createOutputScript(PQKey)` → P2PKH

**File:** `ScriptBuilder.java:246-248`

```java
// Before (P2PK):
public static Script createOutputScript(PQKey key) {
    return new ScriptBuilder().data(key.getPubKey()).op(OP_CHECKSIG).build();
}

// After (P2PKH):
public static Script createOutputScript(PQKey key) {
    return new ScriptBuilder()
        .op(OP_DUP).op(OP_HASH160)
        .data(key.getPubKeyHash())
        .op(OP_EQUALVERIFY).op(OP_CHECKSIG).build();
}
```

This is the single most impactful change — `fromCoinKey()`, `addOutput(Coin, PQKey)`,
`Block.coinbase`, `OrderMatchingEngine`, `Wallet` change outputs all use this
method and automatically become P2PKH.

---

## Step 2: Core output creation — `createOutputScript(KeyBundle)` → P2PKH

**File:** `ScriptBuilder.java:255-257`

```java
// Before (P2PK):
public static Script createOutputScript(KeyBundle keyBundle) {
    return new ScriptBuilder().data(PQScriptUtils.prefixedPubkey(keyBundle)).op(OP_CHECKSIG).build();
}

// After (P2PKH):
public static Script createOutputScript(KeyBundle keyBundle) {
    byte[] pubkey = PQScriptUtils.prefixedPubkey(keyBundle);
    return new ScriptBuilder()
        .op(OP_DUP).op(OP_HASH160)
        .data(Utils.sha256hash160(pubkey))
        .op(OP_EQUALVERIFY).op(OP_CHECKSIG).build();
}
```

---

## Step 3: Spending always includes pubkey in input

All spending paths must include `<pubkey>` in the input script since P2PKH
requires it for `OP_HASH160 ... OP_EQUALVERIFY`.

### 3a. `Transaction.addSignedInput()` — always P2PKH path

**File:** `Transaction.java:834-837`

```java
// Before:
if (scriptPubKey.isSentToRawPubKey() || scriptPubKey.isSentToMultiSig())
    input.setScriptSig(ScriptBuilder.createInputScript(txSig));
else if (scriptPubKey.isSentToAddress())
    input.setScriptSig(ScriptBuilder.createInputScript(txSig, sigKey));

// After (always include pubkey):
if (scriptPubKey.isSentToAddress() || scriptPubKey.isSentToRawPubKey()) {
    // P2PKH (and P2PK backward compat): include pubkey in input
    input.setScriptSig(ScriptBuilder.createInputScript(txSig, sigKey));
}
```

### 3b. `Transaction.signInputs()` — same

**File:** `Transaction.java:850-853`

Same pattern as 3a.

### 3c. `LocalTransactionSigner` — always PQ P2PKH path

**File:** `LocalTransactionSigner.java:117-136`

```java
// Before (branches on script type):
byte[] pkFromScript = null;
boolean isP2PKH = false;
if (scriptPubKey.isSentToRawPubKey()) {
    pkFromScript = scriptPubKey.getPubKey();
} else if (scriptPubKey.isSentToAddress() && key != null && PQScriptUtils.isPQPubkey(key.getPubKey())) {
    pkFromScript = key.getPubKey();
    isP2PKH = true;
}
if (pkFromScript != null && PQScriptUtils.isPQPubkey(pkFromScript)) {
    ...
    if (isP2PKH)
        txIn.setScriptSig(ScriptBuilder.createInputScriptForPQ(sigBundle, key));
    else
        txIn.setScriptSig(ScriptBuilder.createInputScriptForPQ(sigBundle));
}

// After (unified: always use key from keybag):
if (key != null && PQScriptUtils.isPQPubkey(key.getPubKey())) {
    ...
    // P2PKH input: <sigBundle> <pubkey>
    txIn.setScriptSig(ScriptBuilder.createInputScriptForPQ(
        SignatureBundle.deserialize(storedBundle), key));
}
```

The `key` variable is `redeemData.getFullKey()` which is always available.
No script-type branching needed.

### 3d. `TransactionOutPoint.getConnectedKey()` — always hash lookup

**File:** `TransactionOutPoint.java:172-187`

```java
// Before:
if (connectedScript.isSentToAddress()) {
    byte[] addressBytes = connectedScript.getPubKeyHash();
    return keyBag.findKeyFromPubHash(addressBytes);
} else if (connectedScript.isSentToRawPubKey()) {
    byte[] pubkeyBytes = connectedScript.getPubKey();
    return keyBag.findKeyFromPubKey(pubkeyBytes);
}

// After (unified: always hash lookup):
if (connectedScript.isSentToAddress() || connectedScript.isSentToRawPubKey()) {
    // All outputs are P2PKH; fall back to raw pubkey for any P2PK remnants
    byte[] hash = connectedScript.isSentToAddress()
        ? connectedScript.getPubKeyHash()
        : Utils.sha256hash160(connectedScript.getPubKey());
    return keyBag.findKeyFromPubHash(hash);
}
```

### 3e. `TransactionOutPoint.getConnectedRedeemData()` — same

**File:** `TransactionOutPoint.java:208-229`

Same pattern as 3d.

---

## Step 4: Script.java — simplify P2PKH as the only output type

### 4a. Remove `isSentToRawPubKey()`

**File:** `Script.java:240-243`

Can remove. All outputs are P2PKH. Keep as deprecated or remove entirely.

### 4b. Simplify `getNumberOfBytesRequiredToSpend()`

**File:** `Script.java:668-678`

```java
// Before:
} else if (isSentToRawPubKey()) {
    return SIG_SIZE;  // P2PK
} else if (isSentToAddress()) {
    return SIG_SIZE + (pubKey != null ? pubKey.getPubKey().length : uncompressedPubKeySize);  // P2PKH
}

// After (always P2PKH):
} else if (isSentToAddress() || isSentToRawPubKey()) {
    return SIG_SIZE + (pubKey != null ? pubKey.getPubKey().length : uncompressedPubKeySize);
}
```

### 4c. `ScriptType` enum

**File:** `Script.java:71`

```java
// Before:
enum ScriptType { NO_TYPE, P2PKH, PUB_KEY, P2SH }

// After (P2PKH is the only key-to-script type):
enum ScriptType { NO_TYPE, P2PKH, P2SH }
```

**Note:** Removing `PUB_KEY` (ordinal 2) shifts `P2SH` from 3 → 2 in the DB.
Option: keep `PUB_KEY` slot as unused to avoid DB migration, or migrate.

---

## Step 5: Undo Phase 0 P2PK-specific changes

### 5a. `DispatcherController.fundAddresses` — restore address-only path

**File:** `DispatcherController.java:816-823`

```java
// Current (P2PK, requires pubkey):
String pubkeyHex = (String) entry.get("pubkey");
if (pubkeyHex == null) {
    throw new IllegalArgumentException("pubkey is required for fundAddresses");
}
...
utxo.setScript(ScriptBuilder.createOutputScript(key));  // P2PK

// Restore (P2PKH, address-only is fine):
String pubkeyHex = (String) entry.get("pubkey");
if (pubkeyHex != null) {
    byte[] pubkeyBytes = Utils.HEX.decode(pubkeyHex);
    PQKey key = PQKey.fromPublicOnly(pubkeyBytes);
    utxo.setScript(ScriptBuilder.createOutputScript(key));  // now P2PKH!
} else {
    utxo.setScript(ScriptBuilder
            .createOutputScript(Address.fromBase58(networkParameters, addrStr)));  // P2PKH
}
```

Note: both branches now produce P2PKH (since `createOutputScript(PQKey)` was changed
in Step 1). The else branch always produced P2PKH. Unified code path.

### 5b. `FundAddressesIT` — remove pubkey addition

Revert to original address-only test.

### 5c. Tests — change assertions to P2PKH

| File | Before | After |
|------|--------|-------|
| `AbstractIntegrationTest.java:1016` | `assert isSentToRawPubKey()` | `assert isSentToAddress()` |
| `ScriptTest.java:142` | `assertTrue(s.isSentToRawPubKey())` | `assertTrue(s.isSentToAddress())` |
| `ScriptSerializationTest.java:77` | `assertTrue(isSentToRawPubKey())` | `assertTrue(isSentToAddress())` |

---

## Step 6: Update P2PK callers to P2PKH

These files create outputs via methods we changed in Steps 1-2. No code change
needed — they automatically produce P2PKH now:

| File | Method | Auto-changed? |
|------|--------|---------------|
| `TransactionOutput.fromCoinKey()` | Uses `createOutputScript(PQKey)` | Yes (Step 1) |
| `Transaction.addOutput(Coin, PQKey)` | Uses `fromCoinKey` | Yes |
| `Block.java:850-870` coinbase | Uses `createOutputScript(PQKey)` | Yes |
| `UtilGeneseBlock.java:60` | Uses `createOutputScript(PQKey)` | Yes |
| `OrderMatchingEngine.java:193` | Uses `addOutput(Coin, PQKey)` | Yes |
| `ServiceBaseOrder.java:348` | Same | Yes |
| Wallet change outputs | Uses `addOutput(Coin, PQKey)` | Yes |

These need **manual** change because they pass a raw pubkey, not a PQKey:

| File | Line | Current | New |
|------|------|---------|-----|
| `Block.java:863` | `ScriptBuilder.createOutputScript(PQKey.fromPublicOnly(pubKey))` | Auto-fixed by Step 1 |
| `DispatcherController.java:819` | `utxo.setScript(ScriptBuilder.createOutputScript(key))` | Auto-fixed by Step 1 |

---

## Testing

### Unit tests to verify

| Test | What to verify |
|------|----------------|
| `ScriptSerializationTest` | `createOutputScript(PQKey)` produces P2PKH, not P2PK |
| `ScriptTest` | `isSentToAddress()` returns true for new outputs |
| `TransactionTest` | Input scripts include pubkey |
| `PQScriptTest` | PQ dual-sig verification works with P2PKH scripts |

### Integration tests

| Test | What to verify |
|------|----------------|
| `OrderMatchTest` | Orders work with P2PKH UTXOs |
| `FundAddressesIT` | Funding with address-only creates P2PKH |
| `SubmitTransactionsIT` | PQ signing works with P2PKH inputs |
| All `AbstractIntegrationTest` subclasses | `createTestTransaction` works with P2PKH |

---

## File change summary

| File | Change type |
|------|-------------|
| `ScriptBuilder.java` | 2 output methods change to P2PKH; `createInputScript(Signature)` may be removed |
| `Transaction.java` | 2 signing methods simplified to always include pubkey |
| `TransactionOutPoint.java` | 2 key lookup methods unified to hash lookup |
| `LocalTransactionSigner.java` | Simplified to always use key from keybag |
| `Script.java` | Remove `isSentToRawPubKey()`; simplify sizing |
| `DispatcherController.java` | Restore address-only path |
| `FundAddressesIT.java` | Revert to address-only |
| `AbstractIntegrationTest.java` | Assert P2PKH |
| `ScriptTest.java` | Assert P2PKH |
| `ScriptSerializationTest.java` | Assert P2PKH |
