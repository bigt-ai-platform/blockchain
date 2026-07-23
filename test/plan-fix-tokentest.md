# Plan: Fix TokenTest

## Current Status

**14 tests**: 4 pass, 10 fail (3 failures, 7 errors)

## Root Cause (Unified)

The `checkFullTokenSolidity` → `checkDomainPermission` correctly requires **2 signatures** for a first-issuance domain token:

1. **Owner signature** — signs `tx.getHash()` (without `dataSignature`) ✓
2. **Domain signer signature** — must be a key from the parent domain's permissioned addresses

The tests only provide signature #1 (the owner's, via `saveToken`). They never provide signature #2 (the domain's), so `checkDomainPermission` throws `InsufficientSignaturesException` → `saveMultiSign` creates pending records for BOTH the owner AND domain signers. But the tests then call `multiSign`/`pullBlockDoMultiSign` with the wrong key (not a domain signer), so those pending domain records are never processed.

Previously, the buggy hash computation in `checkDomainPermission` caused signature verification to always fail before reaching the domain check, so the test flow never exercised this path.

## Key Insight

`saveMultiSign` creates pending records for **all** permissioned addresses:
- The owner key (from `tokenInfo.getMultiSignAddresses()`)
- The domain signer keys (from `queryDomainnameTokenMultiSignAddresses`)

After the first `publishDomainName` → `signTokenAndSaveBlock` call:
- Owner's record → `sign=1` (already signed in `dataSignature`)
- Domain signer's record → `sign=0` (pending)

The test needs to call `multiSign`/`pullBlockDoMultiSign` with the **domain signer's key** (genesis key) to process that pending record. Simply using the owner key (or a random key) doesn't work.

## Correct Flow for Tests

```java
// Step 1: Create token with owner key
wallet.publishDomainName(ownerKey, tokenid, "name", aesKey, "");

// Step 2: Sign with owner key (processes owner's pending record)
Block lastBlock = pullBlockDoMultiSign(tokenid, ownerKey, aesKey);

// Step 3: Sign with domain signer key (processes domain's pending record)
lastBlock = pullBlockDoMultiSign(tokenid, wallet.walletKeys().get(0), aesKey);

// Step 4: Create reward block
makeRewardBlock(lastBlock);

// Step 5: Verify
assertTrue(getToken(tokenid).getTokenname().equals("name"));
```

## Side Issue: Same tokenid reused with different names

Some tests call `publishDomainName` with the same `tokenid` but different domain names (e.g., "com" then "金"). This is a re-issuance, and the server correctly rejects name changes (`PreviousTokenDisallowsException: Cannot change token name`). Each `publishDomainName` needs a unique `tokenid` (derived from a unique `PQKey`).

## Specific Fixes per Test

### 1. `createShopToken` (helper, used by many tests)

Replace the single `pullBlockDoMultiSign` with two calls (owner + domain signer):

```java
private PQKey createShopToken() throws ... {
    PQKey shopKey = PQKey.createNew();
    mcmcService.calcNewBlockPrototype(store);
    wallet.publishDomainName(shopKey, tokenid, "shop", aesKey, "");
    // Sign with owner key
    Block lastBlock = pullBlockDoMultiSign(tokenid, shopKey, aesKey);
    // Sign with domain signer key (genesis key)
    lastBlock = pullBlockDoMultiSign(tokenid, wallet.walletKeys().get(0), aesKey);
    makeRewardBlock(lastBlock);
    assertTrue(getToken(tokenid).getTokenname().equals("shop"));
    return shopKey;
}
```

### 2. `prepareIdentity` (helper, used by 6 tests)

No change needed if `createShopToken` returns a valid `shopKey`. The existing flow:
```java
PQKey shopKey = createShopToken();
PQKey key = PQKey.createNew();
wallet.publishDomainName(key, tokenid, "id.shop", aesKey, "");
Block lastBlock = pullBlockDoMultiSign(tokenid, shopKey, aesKey);
// NEED: add domain signer call
lastBlock = pullBlockDoMultiSign(tokenid, wallet.walletKeys().get(0), aesKey);
makeRewardBlock(lastBlock);
```

### 3. `testCreateDomainToken` (3 blocks, same tokenid → different ids)

```java
// Block 1: create "com" domain with owner key
PQKey key1 = PQKey.createNew();
wallet.publishDomainName(key1, tokenid, "com", aesKey, "");
Block lastBlock = pullBlockDoMultiSign(tokenid, key1, aesKey);
lastBlock = pullBlockDoMultiSign(tokenid, wallet.walletKeys().get(0), aesKey);  // domain signer
makeRewardBlock(lastBlock);
assertTrue(getToken(tokenid).getTokenname().equals("com"));

// Block 2: create "金" domain — NEEDS DIFFERENT tokenid
String tokenid2 = Sha256Hash.hash(wallet.walletKeys().get(0).getPubKey()).toString();
PQKey key2 = PQKey.createNew();
wallet.publishDomainName(key2, tokenid2, "金", aesKey, "金");
lastBlock = pullBlockDoMultiSign(tokenid2, key2, aesKey);
lastBlock = pullBlockDoMultiSign(tokenid2, wallet.walletKeys().get(0), aesKey);
makeRewardBlock(lastBlock);
assertTrue(getToken(tokenid2).getTokenname().equals("金"));
```

### 4. `testGetTokenById`, `testGetTokenConflict`

These call `testCreateToken(wallet.walletKeys().get(0), "test")` from `AbstractIntegrationTest` — but they also call `createShopToken()` first. With `createShopToken` fixed to add the domain signer step, these should pass.

### 5. Complex tests (`testSigneddata`, `testPrescription`, `testCreateCertificate`, `testCreateTokenMulti`)

Flow: `prepareIdentity()` → creates identity token under shop domain. The fix is the same pattern — after each `publishDomainName`, add a second `pullBlockDoMultiSign` with the genesis key.

Example for `testCreateCertificate`:
```java
// After prepareIdentity():
PQKey key = PQKey.createNew();
wallet.publishDomainName(key, tokenid, "cert.shop", aesKey, "");
// Add domain signer step:
pullBlockDoMultiSign(tokenid, wallet.walletKeys().get(0), aesKey);
// ... rest of test
```

**Note**: Some tests use `wallet.multiSign()` instead of `pullBlockDoMultiSign()`. The fix is the same — add a second `multiSign` call with `wallet.walletKeys().get(0)`.

**Note**: `makeRewardBlock(lastBlock)` must be called with the LAST block returned, not an intermediate one.

## Verification

```bash
# Run single test
docker exec test-bigtangle-postgres psql -U root -d postgres \
  -c "DROP DATABASE IF EXISTS info_l0;" 2>/dev/null
docker exec test-bigtangle-postgres psql -U root -d postgres \
  -c "CREATE DATABASE info_l0;" 2>/dev/null
mvn test -pl layer0-mcmc -Dtest="TokenTest#<testMethod>" \
  -DDB_NAME=info_l0 -Dsurefire.forkCount=1 \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DDB_HOSTNAME=localhost -DDB_PORT=5432 \
  -DDB_USERNAME=root -DDB_PASSWORD=test1234 \
  -DargLine="-Xmx512m --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports java.base/java.lang=ALL-UNNAMED"

# Run full TokenTest
mvn test -pl layer0-mcmc -Dtest=TokenTest ...
```
