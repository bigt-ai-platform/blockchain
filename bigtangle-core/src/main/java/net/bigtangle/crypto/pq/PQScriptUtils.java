package net.bigtangle.crypto.pq;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.bigtangle.core.Sha256Hash;

/**
 * Post-quantum script verification utilities.
 *
 * <p>PQ pubkeys on the script stack are prefixed with {@value #PQ_PUBKEY_PREFIX}
 * (0x05) to distinguish them from legacy EC keys (0x02/0x03/0x04).
 * The prefix byte is followed by the canonical KeyBundle encoding.
 *
 * <p>PQ signatures on the script stack carry the full SignatureBundle.
 */
public final class PQScriptUtils {

    private static final Logger log = LoggerFactory.getLogger(PQScriptUtils.class);

    /** Magic prefix byte for PQ pubkeys on the script stack.  0x05 avoids
     *  conflict with SEC1 EC key prefixes (0x02/0x03/0x04).
     *  Legacy scripts ignore this prefix — the first byte is simply
     *  treated as the beginning of the public key, which will fail
     *  EC verification harmlessly. */
    public static final byte PQ_PUBKEY_PREFIX = (byte) 0x05;

    /** The shared provider used by script verification. */
    private static volatile PQSignatureProvider provider;

    /**
     * Successful PQ verifications memoized by (pubkey, signature, sighash).
     * Mirrors Bitcoin's signature cache: the same input is verified once at
     * mempool ingest and must not pay full ML-DSA/SLH-DSA cost again at block
     * solidity check.  Only successes are cached (failures stay cheap to
     * re-evaluate and cannot be poisoned by timing).
     */
    private static final Cache<Sha256Hash, Boolean> verifyCache = CacheBuilder.newBuilder()
            .maximumSize(100_000).build();

    /** Lazy init to avoid classloading order issues. */
    private static PQSignatureProvider provider() {
        PQSignatureProvider p = provider;
        if (p == null) {
            synchronized (PQScriptUtils.class) {
                p = provider;
                if (p == null) {
                    provider = p = new BcPQSignatureProvider();
                }
            }
        }
        return p;
    }

    /** Set provider (primarily for testing). */
    public static void setProvider(PQSignatureProvider p) { provider = p; }
    public static PQSignatureProvider getProvider() { return provider(); }

    /* ── Detection ─────────────────────────────────────────────────────── */

    /** True if the first byte of the pubkey stack item is the PQ prefix. */
    public static boolean isPQPubkey(byte[] pubkey) {
        return pubkey != null && pubkey.length > 1 && pubkey[0] == PQ_PUBKEY_PREFIX;
    }

    /** Extract the KeyBundle from a prefixed pubkey stack item. */
    public static KeyBundle extractKeyBundle(byte[] prefixedPubkey) {
        return KeyBundle.deserialize(
                java.util.Arrays.copyOfRange(prefixedPubkey, 1, prefixedPubkey.length));
    }

    /** Build a prefixed pubkey for the script stack. */
    public static byte[] prefixedPubkey(KeyBundle keyBundle) {
        byte[] bundleBytes = keyBundle.serialize();
        byte[] result = new byte[1 + bundleBytes.length];
        result[0] = PQ_PUBKEY_PREFIX;
        System.arraycopy(bundleBytes, 0, result, 1, bundleBytes.length);
        return result;
    }

    /* ── Domain-separated sighash ──────────────────────────────────────── */

    /** Compute a domain-separated hash (domain || base). */
    public static byte[] domainSeparatedHash(Sha256Hash baseSighash, String domain) {
        return domainSeparatedHash(baseSighash.getBytes(), domain);
    }

    /** Compute a domain-separated hash from raw bytes (domain || base). */
    public static byte[] domainSeparatedHash(byte[] base, String domain) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[domainBytes.length + base.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        System.arraycopy(base, 0, combined, domainBytes.length, base.length);
        return Sha256Hash.hash(combined);
    }

    /* ── Verification ──────────────────────────────────────────────────── */

    /**
     * True when transaction-level signatures must carry SLH-DSA in addition to
     * ML-DSA-87.  Follows the same governance as the proposer path: the dual
     * suite is required only once {@value PQConstants#DUAL_ACTIVATION_PROPERTY}
     * activates it; by default (unset) the chain runs the ML-DSA-only phase and
     * dual keys are verified with whatever signatures they actually carry.
     */
    public static boolean slhDsaRequiredForTx() {
        return PQConstants.dualActivationHeightFromProperty() >= 0;
    }

    /**
     * Verify a PQ signature against a pubkey.
     *
     * <p>Applies TX-level domain separator for replay protection, then
     * per-algorithm domain separators to each signature.
     *
     * @param prefixedPubkey pubkey from script stack (with 0x05 prefix)
     * @param sigBytes       raw SignatureBundle from script stack
     * @param baseSighash    the base transaction sighash (before domain separation)
     * @return true iff ML-DSA verifies and SLH-DSA is satisfied per suite policy
     */
    public static boolean verifyPQ(byte[] prefixedPubkey, byte[] sigBytes, Sha256Hash baseSighash) {
        return verifyPQ(prefixedPubkey, sigBytes, baseSighash, slhDsaRequiredForTx());
    }

    /**
     * Verify a PQ signature with an explicit dual-suite requirement
     * (same semantics as {@link #verifyProposerSignature}).
     *
     * <p>ML-DSA-87 is always required.  When the key bundle carries an SLH-DSA
     * entry, a provided SLH-DSA signature is always verified; a missing one is
     * only tolerated while {@code requireSlhDsa} is false.  Successful results
     * are memoized — repeat verification of the same input (mempool pass, then
     * block solidity pass) is a cache hit instead of a second ~2 ms verify.
     *
     * @param requireSlhDsa true iff the dual suite is active for tx signatures
     * @return true iff the required signatures verify
     */
    public static boolean verifyPQ(byte[] prefixedPubkey, byte[] sigBytes, Sha256Hash baseSighash,
            boolean requireSlhDsa) {
        // Key on a digest, not the raw arrays: an ML-DSA-87 pubkey+sig pair is
        // ~7 KB, so 100k raw-array entries would pin ~700 MB of heap.
        byte[] keyMaterial = new byte[1 + prefixedPubkey.length + sigBytes.length + baseSighash.getBytes().length];
        keyMaterial[0] = requireSlhDsa ? (byte) 1 : (byte) 0;
        int off = 1;
        System.arraycopy(prefixedPubkey, 0, keyMaterial, off, prefixedPubkey.length);
        off += prefixedPubkey.length;
        System.arraycopy(sigBytes, 0, keyMaterial, off, sigBytes.length);
        off += sigBytes.length;
        System.arraycopy(baseSighash.getBytes(), 0, keyMaterial, off, baseSighash.getBytes().length);
        Sha256Hash digestKey = Sha256Hash.of(keyMaterial);

        Boolean cached = verifyCache.getIfPresent(digestKey);
        if (cached != null) return cached;
        boolean ok = doVerifyPQ(prefixedPubkey, sigBytes, baseSighash, requireSlhDsa);
        if (ok) {
            try {
                // Concurrent duplicate computes are fine: result is deterministic.
                verifyCache.get(digestKey, () -> true);
            } catch (ExecutionException ignored) {
                // Never fails for this loader; fall through.
            }
        }
        return ok;
    }

    private static boolean doVerifyPQ(byte[] prefixedPubkey, byte[] sigBytes, Sha256Hash baseSighash,
            boolean requireSlhDsa) {
        try {
            KeyBundle keyBundle = extractKeyBundle(prefixedPubkey);
            SignatureBundle sigBundle = SignatureBundle.deserialize(sigBytes);

            PQSignatureProvider p = provider();

            // Apply TX-level domain separator for replay protection (quantum.md §6)
            byte[] txHash = domainSeparatedHash(baseSighash.getBytes(), PQConstants.TX_DOMAIN);

            // ML-DSA-87 (always required)
            byte[] mlMsg = domainSeparatedHash(txHash, PQConstants.MLDSA_SIG_DOMAIN);
            KeyBundle.Entry mlKey = keyBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            SignatureBundle.Entry mlSig = sigBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            if (mlKey == null || mlSig == null) return false;
            if (!p.verify(PQConstants.ALG_ML_DSA_87, mlKey.publicKey(), mlMsg, mlSig.signature()))
                return false;

            // SLH-DSA-SHA2-256s (required iff the dual suite is active and the
            // key bundle has an SLH-DSA entry).  A present signature is always
            // checked, so providing a bad one never passes in any phase.
            KeyBundle.Entry slhKey = keyBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
            if (slhKey != null) {
                SignatureBundle.Entry slhSig = sigBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
                if (slhSig == null) {
                    if (requireSlhDsa) return false;
                } else {
                    byte[] slhMsg = domainSeparatedHash(txHash, PQConstants.SLHDSA_SIG_DOMAIN);
                    if (!p.verify(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKey.publicKey(), slhMsg, slhSig.signature()))
                        return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verify a block proposer's PQ signatures (suite-gated).
     *
     * <p>ML-DSA-87 is always required.  When {@code requireSlhDsa} is true
     * (dual suite active at the block height), the proposer must also carry and
     * verify an SLH-DSA-SHA2-256s signature — ML-DSA-only proposer keys are
     * rejected (no downgrade).  When false (ML-DSA-only phase), an SLH-DSA
     * signature is only checked if actually present on both sides.
     *
     * @param keyBundle the proposer's public key bundle
     * @param sigBundle the proposer's signature bundle
     * @param signingHash block hash without proposer sig fields (breaks circular dependency)
     * @param requireSlhDsa true iff SUITE_CAT5_DUAL_1 is active at the block height
     * @return true iff the required signatures verify
     */
    public static boolean verifyProposerSignature(KeyBundle keyBundle, SignatureBundle sigBundle, byte[] signingHash,
            boolean requireSlhDsa) {
        try {
            PQSignatureProvider p = provider();

            byte[] mlMsg = domainSeparatedHash(signingHash, PQConstants.MLDSA_SIG_DOMAIN);
            KeyBundle.Entry mlKey = keyBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            SignatureBundle.Entry mlSig = sigBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            if (mlKey == null || mlSig == null) return false;
            if (!p.verify(PQConstants.ALG_ML_DSA_87, mlKey.publicKey(), mlMsg, mlSig.signature()))
                return false;

            byte[] slhMsg = domainSeparatedHash(signingHash, PQConstants.SLHDSA_SIG_DOMAIN);
            KeyBundle.Entry slhKey = keyBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
            SignatureBundle.Entry slhSig = sigBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
            if (requireSlhDsa) {
                if (slhKey == null || slhSig == null) return false;
                if (!p.verify(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKey.publicKey(), slhMsg, slhSig.signature()))
                    return false;
            } else {
                // ML-DSA-only phase: SLH-DSA is checked only when actually present.
                if (slhKey != null && slhSig != null) {
                    if (!p.verify(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKey.publicKey(), slhMsg, slhSig.signature()))
                        return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Proposer PQ signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify a block proposer's PQ signatures requiring the dual set
     * (ML-DSA-87 + SLH-DSA-SHA2-256s).  Backward-compatible wrapper for callers
     * that do not carry a block height.
     *
     * @deprecated use {@link #verifyProposerSignature(KeyBundle, SignatureBundle, byte[], boolean)}
     *             with the suite-gate computed from the block height.
     */
    @Deprecated
    public static boolean verifyProposerSignature(KeyBundle keyBundle, SignatureBundle sigBundle, byte[] signingHash) {
        return verifyProposerSignature(keyBundle, sigBundle, signingHash, true);
    }
}
