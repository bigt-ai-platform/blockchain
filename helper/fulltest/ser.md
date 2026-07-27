# Serialization/Deserialization Bug — BenchmarkRunner

## Symptom

```
WARN  n.b.c.p.PQScriptUtils: PQ signature verification failed: too short
```

Every `payToList()` call in `BenchmarkRunner` fails server-side with
`SignatureBundle.deserialize()` receiving < 2 bytes.  The host produces a
34 427‑byte signature bundle, but the Docker server sees an empty scriptSig.

## Evidence

1. **`BitcoinSerializerTest.testTransactionSerializationRoundtripWithPqSignature`**
   — added this session.  It creates a transaction, sets a real PQ signature
   bundle, calls `bitcoinSerialize()`, feeds the bytes to `makeTransaction()`,
   and **passes**.  Wire format is correct *within the same JVM*.

2. **`OkHttp3Util.post` content type** — changed from
   `application/octet-stream; charset=utf-8` to `application/octet-stream`
   (removed `charset`).  No effect — bug is not charset-related.

3. **Docker image rebuilt** — `deploy.sh -m layer0-server` recreates the
   `bigtangle:test` image from the latest code.  `bigtangle-core` JAR inside
   the Docker container has the same md5sum as the host's Maven repo copy.
   The classes are identical.

## Root Cause Hypothesis

The scriptSig **is** preserved by `bitcoinSerialize()` / `makeTransaction()`
(the round‑trip test proves it).  The bytes **are** received by the Docker
`@RequestBody` (Spring logs `reqCmd : submitTransaction, size : <N>`).
Yet `OP_CHECKSIG` on the server sees an empty sig.

**Likely candidate: the `pqSignatureBundle` field confuses the server-side
input deserialization.**  The transaction wire format writes the three
variable fields **after** the inputs/outputs:

```
version
inputs  (each: txHash, outputIndex, scriptSig, sequence)
outputs (each: value, scriptPubKey)
lockTime
memo
dataSignature
pqKeyBundle       ← only if version ≥ 2
pqSignatureBundle ← only if version ≥ 2
```

If the server's `Transaction.parse()` reads the scriptSig **before**
encountering the `pqSignatureBundle` trailer, the scriptSig should be
correct.  But if the **input count** or **scriptSig length** encoding
(`VarInt`) is mis‑parsed — e.g. because the host wrote a `VarInt` that
the server reads differently — all subsequent fields shift, and the
scriptSig "bytes" that `getScriptSig()` returns are actually garbage
from a different part of the stream.

## Next Steps

### 1. Dump hex of the serialized transaction on the host

Add a temporary `log.info("SERIALIZED: {}", Utils.HEX.encode(tx.bitcoinSerialize()))`
inside `BenchmarkRunner` just before `submitTransaction`.  Capture the
hex string for one failing payment.

### 2. Compare hex on server entry

Add a temporary `log.info("RECEIVED: size={} hex={}", bodyByte.length, Utils.HEX.encode(bodyByte))`
at the top of `DispatcherController.processDo()`.  Restart Docker with this
change and compare the host hex vs server hex.  Any difference points to
HTTP transport mangling.

### 3. If hex matches, add a deserialization sanity check

Right after `makeTransaction(bodyByte)` in `submitTransaction`, log the
version, number of inputs, and length of each input's scriptBytes:

```java
Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(bodyByte);
log.info("version={} inputs={}", tx.getVersion(), tx.getInputs().size());
for (int i = 0; i < tx.getInputs().size(); i++) {
    byte[] sb = tx.getInput(i).getScriptBytes();
    log.info("  input[{}] scriptBytes={}", i, sb != null ? sb.length : -1);
}
```

If scriptBytes is < 2, the `VarInt` length encoding or the input stream
position is wrong.

### 4. If all else fails, render the transaction locally

Instead of trusting the HTTP round‑trip, write the serialized bytes to a
temp file and have the server read it from a shared volume.  If that works,
the bug is definitively in HTTP / Spring `@RequestBody`.
