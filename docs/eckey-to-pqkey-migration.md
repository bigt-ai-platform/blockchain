# User Guide: Migrating Funds from ECKey to PQKey

This guide explains how to move tokens from a legacy **ECKey** (ECDSA/secp256k1)
address to a new post-quantum **PQKey** (ML-DSA-87 + SLH-DSA-SHA2-256s) address.

> The migration is a normal **spend-and-send**: the wallet signs the EC input with
> ECDSA and creates an output owned by the PQ key. No special transaction type is
> required.

---

## 1. Background

| Key type | Signature scheme | Address format | Example |
|----------|------------------|----------------|---------|
| `ECKey` | ECDSA secp256k1 (BouncyCastle) | legacy Base58 (20-byte hash160) | `n4eA2nbYqErp7H6jebchxAN59DmNpksexv` |
| `PQKey` | ML-DSA-87 (+ SLH-DSA after activation) | hex `PQAddress` (35 bytes) | `010101...` (70 hex chars) |

Both key types use the same P2PKH script structure, so they interoperate in the
same UTXO set. The public-key prefix (`0x02/0x03/0x04` = EC, `0x05` = PQ) lets the
script engine dispatch to the correct verifier.

---

## 2. Key APIs

### 2.1 Create or import a legacy EC key

```java
import net.bigtangle.core.ECKey;

// From an existing private key (hex, 32 bytes)
ECKey ecKey = ECKey.fromPrivateString("8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55");

// Or from raw bytes
ECKey ecKey2 = ECKey.fromPrivate(Utils.HEX.decode("8db6...55"));

// From a legacy WIF "wallet key file" string (base58, Bitcoin dumpprivkey format)
ECKey ecKey3 = ECKey.fromWIF(params, "KxFC1jmwwCoACiCAWZ3eXa96mBM6tb3TYzGmf6YwgdGWZgawvrtJ");

// Export back to WIF (for backup / interop with legacy tooling)
String wif = ecKey.getPrivateKeyAsWiF(params);

// Generate a brand new EC key (not usually needed for migration)
ECKey newKey = ECKey.createNew();
```

### 2.2 Create a PQ destination key

```java
import net.bigtangle.core.PQKey;

PQKey pqKey = PQKey.createNew();                 // ML-DSA-87 only (default)
```

### 2.3 Create a wallet for the EC key

```java
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

NetworkParameters params = ...;                  // TestParams.get() / MainNetParams.get()

// Wallet whose only key is the legacy EC key, connected to the layer-0 server
Wallet ecWallet = Wallet.fromKeys(params, ecKey, serverUrl);
```

---

## 3. Migration steps

### Step 1 — Get the legacy address and confirm the balance

```java
String ecAddress = ecKey.toAddress(params).toBase58();
System.out.println("Legacy EC address: " + ecAddress);

// Spendable outputs (UTXOs) controlled by the wallet's keys
List<FreeStandingTransactionOutput> candidates =
        ecWallet.calculateAllSpendCandidates(null, false);
```

> `calculateAllSpendCandidates` queries the layer-0 server using the wallet's key
> hashes, so EC and PQ keys are both included (`walletKeysAll`).

### Step 2 — Import the EC key if the wallet already exists

```java
Wallet wallet = Wallet.fromKeys(params, someOtherKey, serverUrl);
wallet.importKey(ecKey);                          // now holds both EC and PQ keys
```

### Step 3 — Send the funds to the PQ key

Use the key-based `pay` API (works for EC **and** PQ destinations):

```java
import net.bigtangle.core.Coin;
import net.bigtangle.params.NetworkParameters;

// amount of native BIG token (in smallest units)
Coin amount = Coin.valueOf(amountValue, NetworkParameters.BIGTANGLE_TOKENID);

// EC wallet -> PQ key
List<Transaction> txs = ecWallet.pay(null, pqKey, amount, "migrate to PQ");

// or with a MemoInfo
ecWallet.pay(null, pqKey, amount, new MemoInfo("migrate to PQ"));
```

The `pay(KeyParameter, Key, Coin, MemoInfo)` overload:
- creates the output for the PQ key,
- selects EC-controlled inputs,
- signs them with ECDSA (`LocalTransactionSigner` dispatches on key type),
- submits the transaction.

> If you pass an encrypted wallet's AES key, replace `null` with `aesKey`.

### Step 4 — Verify the PQ balance

```java
PQKey pqWalletKey = pqKey;
// Check via the PQ address
String pqAddress = pqKey.toAddress(params).toHex();
```

---

## 4. Complete example

```java
public void migrateEcToPq(NetworkParameters params, String serverUrl, String ecPrivKeyHex) throws Exception {
    // 1. Legacy key + wallet
    ECKey ecKey = ECKey.fromPrivateString(ecPrivKeyHex);
    Wallet ecWallet = Wallet.fromKeys(params, ecKey, serverUrl);

    // 2. Destination PQ key
    PQKey pqKey = PQKey.createNew();

    // 3. Migrate a specific amount of BIG token
    Coin amount = Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID);
    ecWallet.pay(null, pqKey, amount, "migrate to PQ");

    System.out.println("Migrated " + amount + " to " + pqKey.toAddress(params).toHex());
}
```

---

## 5. Address and key lookup reference

| Operation | API |
|-----------|-----|
| EC address (Base58) | `ecKey.toAddress(params).toBase58()` or `ecKey.toAddressString(params)` |
| PQ address (hex) | `pqKey.toAddress(params).toHex()` or `pqKey.toAddressString(params)` |
| All keys in wallet (EC + PQ) | `wallet.walletKeysAll(aesKey)` |
| PQ keys only (legacy accessor) | `wallet.walletKeys(aesKey)` |
| Find key by address | `wallet.getECKey(aesKey, address)` (returns `Key`) |
| Import a key | `wallet.importKey(key)` (accepts `ECKey` or `PQKey`) |

---

## 6. Notes

- **EC signing** uses BouncyCastle (no native secp256k1 JNI is required).
- **Verification** is automatic: `Script.executeCheckSig`/`executeMultiSig` dispatch
  to ECDSA for `0x02/0x03/0x04` pubkeys and to `PQScriptUtils.verifyPQ` for `0x05`.
- The migration is **additive** — existing PQ-only flows are unchanged.
- Legacy EC keys can be re-imported at any time; there is no height gate on EC
  spending (the legacy address format is already part of the chain).
