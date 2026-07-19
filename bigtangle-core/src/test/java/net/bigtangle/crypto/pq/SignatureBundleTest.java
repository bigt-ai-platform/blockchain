package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class SignatureBundleTest {

    @Test
    void serializeRoundTrip() {
        byte[] sig1 = new byte[4627]; Arrays.fill(sig1, (byte) 0x03);
        byte[] sig2 = new byte[16123]; Arrays.fill(sig2, (byte) 0x04);

        SignatureBundle bundle = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, sig1),
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, sig2)));

        byte[] serialized = bundle.serialize();
        SignatureBundle deserialized = SignatureBundle.deserialize(serialized);

        assertEquals(bundle, deserialized);
        assertEquals(2, deserialized.entries().size());
    }

    @Test
    void entriesSortedByAlgorithmId() {
        byte[] sig1 = new byte[100]; Arrays.fill(sig1, (byte) 0x01);
        byte[] sig2 = new byte[200]; Arrays.fill(sig2, (byte) 0x02);

        SignatureBundle bundle = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, sig1),
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, sig2)));

        assertEquals(PQConstants.ALG_ML_DSA_87, bundle.entries().get(0).algorithm());
        assertEquals(PQConstants.ALG_SLH_DSA_SHA2_256S, bundle.entries().get(1).algorithm());
    }

    @Test
    void versionFieldPreserved() {
        SignatureBundle bundle = new SignatureBundle(7, List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, new byte[4627])));

        assertEquals(7, bundle.version());
        assertEquals(7, bundle.serialize()[0] & 0xFF);
    }

    @Test
    void getEntryByAlgorithm() {
        byte[] sig = new byte[4627]; Arrays.fill(sig, (byte) 0x09);
        SignatureBundle bundle = new SignatureBundle(List.of(
                new SignatureBundle.Entry(PQConstants.ALG_ML_DSA_87, sig)));

        assertNotNull(bundle.getEntry(PQConstants.ALG_ML_DSA_87));
        assertNull(bundle.getEntry(PQConstants.ALG_SLH_DSA_SHA2_256S));
    }
}
