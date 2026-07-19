package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class KeyBundleTest {

    @Test
    void serializeRoundTrip() {
        byte[] pk1 = new byte[2560]; Arrays.fill(pk1, (byte) 0x01);
        byte[] pk2 = new byte[64];   Arrays.fill(pk2, (byte) 0x02);

        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        byte[] serialized = bundle.serialize();
        KeyBundle deserialized = KeyBundle.deserialize(serialized);

        assertEquals(bundle, deserialized);
        assertEquals(2, deserialized.entries().size());
    }

    @Test
    void entriesSortedByAlgorithmId() {
        byte[] pk1 = new byte[64]; Arrays.fill(pk1, (byte) 0x02);
        byte[] pk2 = new byte[2560]; Arrays.fill(pk2, (byte) 0x01);

        // Insert SLH-DSA first, ML-DSA second — should sort to ML-DSA first
        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk1),
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk2)));

        assertEquals(PQConstants.ALG_ML_DSA_87, bundle.entries().get(0).algorithm());
        assertEquals(PQConstants.ALG_SLH_DSA_SHA2_256S, bundle.entries().get(1).algorithm());
    }

    @Test
    void canonicalEncodingIsDeterministic() {
        byte[] pk1 = new byte[2560]; Arrays.fill(pk1, (byte) 0xAA);
        byte[] pk2 = new byte[64];   Arrays.fill(pk2, (byte) 0xBB);

        KeyBundle a = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        KeyBundle b = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2),
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1)));

        // Both should produce identical serialized bytes
        assertArrayEquals(a.serialize(), b.serialize());
        assertEquals(a, b);
    }

    @Test
    void versionFieldPreserved() {
        KeyBundle bundle = new KeyBundle(5, List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, new byte[2560])));

        assertEquals(5, bundle.version());
        byte[] serialized = bundle.serialize();
        assertEquals(5, serialized[0] & 0xFF);
    }

    @Test
    void getEntryByAlgorithm() {
        byte[] pk = new byte[2560]; Arrays.fill(pk, (byte) 0x07);
        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk)));

        assertNotNull(bundle.getEntry(PQConstants.ALG_ML_DSA_87));
        assertNull(bundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S));
    }

    @Test
    void deserializeRejectsShortInput() {
        assertThrows(IllegalArgumentException.class, () ->
                KeyBundle.deserialize(new byte[1]));
    }

    @Test
    void deserializeRejectsTruncatedEntry() {
        // version=1, count=1, but no entry bytes
        byte[] data = {1, 1};
        assertThrows(IllegalArgumentException.class, () ->
                KeyBundle.deserialize(data));
    }
}
