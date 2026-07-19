package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PQSignatureProviderTest {

    private static PQSignatureProvider provider;
    private static byte[] seed;

    @BeforeAll
    static void setUp() {
        provider = new BcPQSignatureProvider();
        seed = new byte[32];
        new SecureRandom().nextBytes(seed);
    }

    /* ── ML-DSA-87 tests ──────────────────────────────────────────────── */

    @Test
    void generateMLDSAKeyPair() {
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, seed);

        assertNotNull(kp);
        assertEquals(PQConstants.ALG_ML_DSA_87, kp.algorithm());
        assertNotNull(kp.publicKey());
        assertNotNull(kp.privateKey());
        assertTrue(kp.publicKey().length > 0);
        assertTrue(kp.privateKey().length > 0);
    }

    @Test
    void mldsaSignAndVerify() {
        byte[] mlSeed = Arrays.copyOf(seed, 32);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, mlSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_ML_DSA_87,
                kp.privateKey(), message);

        assertNotNull(signature);
        assertTrue(signature.length > 0);

        boolean valid = provider.verify(PQConstants.ALG_ML_DSA_87,
                kp.publicKey(), message, signature);
        assertTrue(valid);
    }

    @Test
    void mldsaBadSignatureRejected() {
        byte[] mlSeed = Arrays.copyOf(seed, 32);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, mlSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_ML_DSA_87,
                kp.privateKey(), message);

        // Flip a byte in the signature
        signature[10] ^= 0x01;

        boolean valid = provider.verify(PQConstants.ALG_ML_DSA_87,
                kp.publicKey(), message, signature);
        assertFalse(valid);
    }

    @Test
    void mldsaBadMessageRejected() {
        byte[] mlSeed = Arrays.copyOf(seed, 32);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, mlSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_ML_DSA_87,
                kp.privateKey(), message);

        // Different message
        message[0] ^= 0x01;

        boolean valid = provider.verify(PQConstants.ALG_ML_DSA_87,
                kp.publicKey(), message, signature);
        assertFalse(valid);
    }

    @Test
    void mldsaBadPublicKeyRejected() {
        byte[] mlSeed = Arrays.copyOf(seed, 32);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, mlSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_ML_DSA_87,
                kp.privateKey(), message);

        // Different key pair
        byte[] otherSeed = new byte[32];
        new SecureRandom().nextBytes(otherSeed);
        PQSignatureProvider.KeyPair otherKp = provider.generateKeyPair(
                PQConstants.ALG_ML_DSA_87, otherSeed);

        boolean valid = provider.verify(PQConstants.ALG_ML_DSA_87,
                otherKp.publicKey(), message, signature);
        assertFalse(valid);
    }

    /* ── SLH-DSA-SHA2-256s tests ──────────────────────────────────────── */

    @Test
    void generateSLHDSAKeyPair() {
        byte[] slhSeed = new byte[32];
        new SecureRandom().nextBytes(slhSeed);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_SLH_DSA_SHA2_256S, slhSeed);

        assertNotNull(kp);
        assertEquals(PQConstants.ALG_SLH_DSA_SHA2_256S, kp.algorithm());
        assertNotNull(kp.publicKey());
        assertNotNull(kp.privateKey());
    }

    @Test
    void slhdsaSignAndVerify() {
        byte[] slhSeed = new byte[32];
        new SecureRandom().nextBytes(slhSeed);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_SLH_DSA_SHA2_256S, slhSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S,
                kp.privateKey(), message);

        assertNotNull(signature);
        assertTrue(signature.length > 0);

        boolean valid = provider.verify(PQConstants.ALG_SLH_DSA_SHA2_256S,
                kp.publicKey(), message, signature);
        assertTrue(valid);
    }

    @Test
    void slhdsaBadSignatureRejected() {
        byte[] slhSeed = new byte[32];
        new SecureRandom().nextBytes(slhSeed);
        PQSignatureProvider.KeyPair kp = provider.generateKeyPair(
                PQConstants.ALG_SLH_DSA_SHA2_256S, slhSeed);

        byte[] message = new byte[64];
        new SecureRandom().nextBytes(message);

        byte[] signature = provider.sign(PQConstants.ALG_SLH_DSA_SHA2_256S,
                kp.privateKey(), message);

        signature[20] ^= 0x01;

        boolean valid = provider.verify(PQConstants.ALG_SLH_DSA_SHA2_256S,
                kp.publicKey(), message, signature);
        assertFalse(valid);
    }

    /* ── Provider metadata ────────────────────────────────────────────── */

    @Test
    void supportedAlgorithmsReported() {
        int[] algs = provider.supportedAlgorithms();
        assertTrue(algs.length >= 1);
        assertTrue(Arrays.stream(algs).anyMatch(a -> a == PQConstants.ALG_ML_DSA_87));
    }

    @Test
    void unknownAlgorithmThrows() {
        assertThrows(UnsupportedOperationException.class, () ->
                provider.sign(99, new byte[32], new byte[1]));
    }
}
