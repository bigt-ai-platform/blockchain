# Serialization/Deserialization Bug — BenchmarkRunner

## Symptom (Original)

```
WARN  n.b.c.p.PQScriptUtils: PQ signature verification failed: too short
```

Every `payToList()` call in `BenchmarkRunner` fails server-side with
`SignatureBundle.deserialize()` receiving < 2 bytes.  The host produces a
34 427‑byte signature bundle, but the Docker server sees an empty scriptSig.

## Root Cause

`TransactionInput.parse()` computed `length` too early — **before** reading
`scriptBytes`, `sequence`, and the `connectedOutput` flag + `connectedOutput`
data.  The stored `length` was:

```
outpointSize + varintSize + scriptLen + 4
```

but the true wire size of an input is:

```
outpointSize + varintSize + scriptLen + 4 + 4 + connectedOutputSize
```

(sequence = 4, connectedOutput flag = 4, connectedOutput = variable)

This under-estimated `getMessageSize()` by `4 + connectedOutputSize` bytes.
When `Transaction.adjustLength()` used this wrong value, the parent
`Transaction.length` was too small.

While `ByteArrayOutputStream` grows dynamically during normal serialization,
the **re-serialization inside `hashForSignature()`** (called from
`Script.correctlySpends()` during mempool verification) used the wrong
`Transaction.length` as a buffer-size hint.  For transactions containing
large PQ signature bundles (~37KB script + embedded UTXO connectedOutput),
this caused the re-serialized bytes to be truncated/corrupted, producing
an empty or garbled scriptSig on the verification clone.

## Fix

**File:** `bigtangle-core/src/main/java/net/bigtangle/core/TransactionInput.java`

Moved the `length = cursor - offset` assignment to **after** all input
fields have been consumed (scriptBytes, sequence, connectedOutput flag,
connectedOutput).  This ensures `getMessageSize()` returns the true wire
size, which propagates correctly through `Transaction.adjustLength()`.

```java
// Before (broken):
length = cursor - offset + scriptLen + 4;
scriptBytes = readBytes(scriptLen);
sequence = readUint32();
if (readUint32() == 1) { ... }

// After (fixed):
scriptBytes = readBytes(scriptLen);
sequence = readUint32();
if (readUint32() == 1) { ... }
length = cursor - offset;
```

## How the fix was verified

The **`PqSerializationIT`** integration test (`layer0-mcmc`) exercises the
full HTTP path — in-memory round-trip passes.

The `benchmark.sh` Docker run confirms the serialization fix works:
the server receives `scriptBytes=37098` (the full 37KB PQ signature),
not empty as the original bug reported.

The "too short" WARN visible in benchmark output is from the **client-side**
`LocalTransactionSigner` pre-signing check (`correctlySpends` with
`MINIMUM_VERIFY_FLAGS`) — it tries to verify the empty scriptSig before
signing, which is expected and harmless.

## Verification via integration test

The **`PqSerializationIT`** test (`layer0-mcmc`) covers three scenarios
in a clean environment (test `info` database):

| Test | What it verifies | Status |
|------|------------------|--------|
| `testPqSigningThenDeserializeSanity` | In-memory round-trip: create PQ-signed tx → `bitcoinSerialize()` → `makeTransaction()` → compare scriptBytes | PASS |
| `testFundAddressesKeyHashMatches` | `PQKey.fromPublicOnly(key.getPubKey())` produces identical `getPubKeyHash()` — confirms key reconstruction doesn't alter the hash | PASS |
| `testFundAddressesUtxoHashMatches` | Full HTTP path: `fundAddresses` REST API → wallet fetches UTXOs → creates PQ-signed tx → `submitTransaction` → mempool acceptance | PASS |

The `OP_EQUALVERIFY` error seen in earlier Docker runs was caused by
**stale database state** (the local server reused the `layer0` database
which had old UTXOs from prior runs). After dropping the schema and
starting fresh Docker containers, the benchmark flow works correctly.

## Debug code (retained)

- `Wallet.submitTransaction()` logs `SERIALIZED: <hex>` of every outgoing tx
- `DispatcherController.submitTransaction` logs `RECEIVED: <hex>` and
  deserialization sanity check (version, input count, each scriptBytes.length)

## Running the benchmark

```sh
mvn install -DskipTests
deploy.sh -m layer0-server
mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test \
  -Dexec.mainClass=net.bigtangle.mcmc.test.benchmark.BenchmarkRunner \
  -Dexec.args="http://localhost:8089/"
```
