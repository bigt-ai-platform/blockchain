package net.bigtangle.crypto.pq;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * HKDF-based post-quantum key derivation from a BIP39 seed.
 *
 * <p>Following the PQ integration plan: HKDF (RFC 5869) with SHA-256,
 * explicit salt ({@code BIGTANGLE-PQ-v1}) and info ({@code wallet root}).
 * Each 32-byte key seed is domain-separated.
 */
public final class PQKeyDerivation {

    private PQKeyDerivation() {}

    private static final int HASH_LEN = 32;   // SHA-256 output
    private static final int KEY_SEED_LEN = 32;

    /**
     * Derive root key material from a BIP39 seed.
     *
     * @param seed BIP39 seed bytes (must be at least 32 bytes / 256-bit entropy)
     * @return 64 bytes: first 32 for ML-DSA, next 32 for SLH-DSA
     */
    public static byte[] deriveRootKeyMaterial(byte[] seed) {
        if (seed.length < 32)
            throw new IllegalArgumentException("seed must be >= 32 bytes (256-bit entropy)");

        byte[] prk = hkdfExtract(
                PQConstants.HKDF_SALT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                seed);
        return hkdfExpand(prk, PQConstants.HKDF_INFO_WALLET.getBytes(
                java.nio.charset.StandardCharsets.UTF_8), 64);
    }

    /**
     * Derive a child key from the PRK.
     *
     * @param prk   HKDF pseudorandom key from deriveRootKeyMaterial extraction
     * @param index child index (for deterministic wallet chains)
     * @param suite algorithm suite ID
     * @return 64 bytes of child key material
     */
    public static byte[] deriveChildKey(byte[] prk, int index, int suite) {
        byte[] info = ("child-" + index + "-" + suite).getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        return hkdfExpand(prk, info, 64);
    }

    /**
     * HKDF-Extract (RFC 5869, section 2.2).
     * PRK = HMAC-SHA256(salt, IKM)
     */
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec spec = new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256");
            mac.init(spec);
            return mac.doFinal(ikm);
        } catch (Exception e) {
            throw new RuntimeException("HKDF extract failed", e);
        }
    }

    /**
     * HKDF-Expand (RFC 5869, section 2.3).
     * OKM = first L bytes from iterative HMAC-SHA256(PRK, previous || info || counter)
     */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec spec = new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256");
            mac.init(spec);

            byte[] result = new byte[length];
            byte[] t = new byte[0];
            int offset = 0;

            for (int i = 1; offset < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();

                int copyLen = Math.min(t.length, length - offset);
                System.arraycopy(t, 0, result, offset, copyLen);
                offset += copyLen;
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("HKDF expand failed", e);
        }
    }

    /**
     * Split key material into ML-DSA and SLH-DSA seeds.
     */
    public static byte[] getMLDSASeed(byte[] keyMaterial) {
        return Arrays.copyOfRange(keyMaterial, 0, KEY_SEED_LEN);
    }

    public static byte[] getSLHDSASeed(byte[] keyMaterial) {
        return Arrays.copyOfRange(keyMaterial, KEY_SEED_LEN, KEY_SEED_LEN * 2);
    }
}
