package net.bigtangle.crypto.pq;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Lazy init to avoid classloading order issues. */
    private static PQSignatureProvider provider() {
        if (provider == null) {
            provider = new BcPQSignatureProvider();
        }
        return provider;
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

    /** Compute a domain-separated sighash for a given algorithm. */
    public static byte[] domainSeparatedHash(Sha256Hash baseSighash, String domain) {
        byte[] base = baseSighash.getBytes();
        byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[domainBytes.length + base.length];
        System.arraycopy(domainBytes, 0, combined, 0, domainBytes.length);
        System.arraycopy(base, 0, combined, domainBytes.length, base.length);
        return Sha256Hash.hash(combined);
    }

    /* ── Verification ──────────────────────────────────────────────────── */

    /**
     * Verify a PQ signature against a pubkey.
     *
     * @param prefixedPubkey pubkey from script stack (with 0x05 prefix)
     * @param sigBytes       raw SignatureBundle from script stack
     * @param baseSighash    the base transaction sighash (before domain separation)
     * @return true iff both ML-DSA and SLH-DSA verify independently
     */
    public static boolean verifyPQ(byte[] prefixedPubkey, byte[] sigBytes, Sha256Hash baseSighash) {
        try {
            KeyBundle keyBundle = extractKeyBundle(prefixedPubkey);
            SignatureBundle sigBundle = SignatureBundle.deserialize(sigBytes);

            PQSignatureProvider p = provider();

            // ML-DSA-87
            byte[] mlMsg = domainSeparatedHash(baseSighash, PQConstants.MLDSA_SIG_DOMAIN);
            KeyBundle.Entry mlKey = keyBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            SignatureBundle.Entry mlSig = sigBundle.getEntry(PQConstants.ALG_ML_DSA_87);
            if (mlKey == null || mlSig == null) return false;
            if (!p.verify(PQConstants.ALG_ML_DSA_87, mlKey.publicKey(), mlMsg, mlSig.signature()))
                return false;

            // SLH-DSA-SHA2-256s
            byte[] slhMsg = domainSeparatedHash(baseSighash, PQConstants.SLHDSA_SIG_DOMAIN);
            KeyBundle.Entry slhKey = keyBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
            SignatureBundle.Entry slhSig = sigBundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S);
            if (slhKey == null || slhSig == null) return false;
            if (!p.verify(PQConstants.ALG_SLH_DSA_SHA2_256S, slhKey.publicKey(), slhMsg, slhSig.signature()))
                return false;

            return true;
        } catch (Exception e) {
            log.warn("PQ signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}
