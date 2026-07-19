package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

class PQKeyDerivationTest {

    @Test
    void hkdfExtractExpandRoundTrip() {
        byte[] ikm = "this is a test BIP39 seed material 123456".getBytes(StandardCharsets.UTF_8);
        byte[] salt = PQConstants.HKDF_SALT.getBytes(StandardCharsets.UTF_8);

        byte[] prk = PQKeyDerivation.hkdfExtract(salt, ikm);
        assertNotNull(prk);
        assertEquals(32, prk.length);

        byte[] okm = PQKeyDerivation.hkdfExpand(prk, "test".getBytes(StandardCharsets.UTF_8), 64);
        assertNotNull(okm);
        assertEquals(64, okm.length);
    }

    @Test
    void deterministicKeyDerivation() {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) seed[i] = (byte) (i * 7 + 1);

        byte[] km1 = PQKeyDerivation.deriveRootKeyMaterial(seed);
        byte[] km2 = PQKeyDerivation.deriveRootKeyMaterial(seed);

        assertArrayEquals(km1, km2);
    }

    @Test
    void differentSeedsProduceDifferentKeys() {
        byte[] seed1 = new byte[64];
        byte[] seed2 = new byte[64];
        new SecureRandom().nextBytes(seed1);
        new SecureRandom().nextBytes(seed2);

        byte[] km1 = PQKeyDerivation.deriveRootKeyMaterial(seed1);
        byte[] km2 = PQKeyDerivation.deriveRootKeyMaterial(seed2);

        assertFalse(java.util.Arrays.equals(km1, km2));
    }

    @Test
    void deriveChildKeys() {
        byte[] seed = new byte[64];
        for (int i = 0; i < 64; i++) seed[i] = (byte) (i + 1);

        byte[] prk = PQKeyDerivation.hkdfExtract(
                PQConstants.HKDF_SALT.getBytes(StandardCharsets.UTF_8), seed);

        byte[] child1 = PQKeyDerivation.deriveChildKey(prk, 0, PQConstants.SUITE_CAT5_DUAL_1);
        byte[] child2 = PQKeyDerivation.deriveChildKey(prk, 0, PQConstants.SUITE_CAT5_DUAL_1);
        byte[] child3 = PQKeyDerivation.deriveChildKey(prk, 1, PQConstants.SUITE_CAT5_DUAL_1);

        assertEquals(64, child1.length);
        assertArrayEquals(child1, child2);        // same index -> same key
        assertFalse(java.util.Arrays.equals(child1, child3)); // different index -> different key
    }

    @Test
    void rejectShortSeed() {
        byte[] shortSeed = new byte[16];
        assertThrows(IllegalArgumentException.class, () ->
                PQKeyDerivation.deriveRootKeyMaterial(shortSeed));
    }
}
