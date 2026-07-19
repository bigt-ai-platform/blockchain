package net.bigtangle.crypto.pq;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PQAddressTest {

    @Test
    void addressFromKeyBundleIsDeterministic() {
        byte[] pk1 = new byte[2560];
        byte[] pk2 = new byte[64];
        for (int i = 0; i < 2560; i++) pk1[i] = (byte) (i & 0xFF);
        for (int i = 0; i < 64; i++) pk2[i] = (byte) ((i + 100) & 0xFF);

        KeyBundle bundle1 = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        KeyBundle bundle2 = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        PQAddress addr1 = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, bundle1);
        PQAddress addr2 = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, bundle2);

        assertEquals(addr1, addr2);
        assertArrayEquals(addr1.hash(), addr2.hash());
    }

    @Test
    void addressMatchesKeyBundle() {
        byte[] pk1 = new byte[2560];
        byte[] pk2 = new byte[64];
        for (int i = 0; i < 2560; i++) pk1[i] = (byte) (i & 0xFF);
        for (int i = 0; i < 64; i++) pk2[i] = (byte) ((i + 200) & 0xFF);

        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        PQAddress addr = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, bundle);

        assertTrue(addr.matches(bundle));
    }

    @Test
    void addressRejectsWrongKeyBundle() {
        byte[] pk1 = new byte[2560];
        byte[] pk2 = new byte[64];
        byte[] pk3 = new byte[64];
        for (int i = 0; i < 64; i++) pk2[i] = (byte) (i & 0xFF);
        for (int i = 0; i < 64; i++) pk3[i] = (byte) ((i + 99) & 0xFF);

        KeyBundle bundle1 = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        KeyBundle bundle2 = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk3)));

        PQAddress addr = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, bundle1);

        assertFalse(addr.matches(bundle2));
    }

    @Test
    void serializeRoundTrip() {
        byte[] pk1 = new byte[2560];
        byte[] pk2 = new byte[64];
        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        PQAddress addr = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_TESTNET, PQConstants.SUITE_CAT5_DUAL_1, bundle);

        byte[] serialized = addr.serialize();
        assertEquals(35, serialized.length);

        PQAddress deserialized = PQAddress.deserialize(serialized);
        assertEquals(addr, deserialized);
        assertEquals(addr.version(), deserialized.version());
        assertEquals(addr.network(), deserialized.network());
        assertEquals(addr.suite(), deserialized.suite());
    }

    @Test
    void hexRoundTrip() {
        byte[] pk1 = new byte[2560];
        byte[] pk2 = new byte[64];
        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk1),
                new KeyBundle.Entry(PQConstants.ALG_SLH_DSA_SHA2_256S, pk2)));

        PQAddress addr = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_MAINNET, PQConstants.SUITE_CAT5_DUAL_1, bundle);

        String hex = addr.toHex();
        PQAddress fromHex = PQAddress.fromHex(hex);
        assertEquals(addr, fromHex);
    }

    @Test
    void networkAndSuitePreserved() {
        byte[] pk = new byte[2560];
        KeyBundle bundle = new KeyBundle(List.of(
                new KeyBundle.Entry(PQConstants.ALG_ML_DSA_87, pk)));

        PQAddress addr = PQAddress.fromKeyBundle(
                PQConstants.NETWORK_MAINNET, PQConstants.SUITE_CAT5_DUAL_1, bundle);

        assertEquals(PQConstants.NETWORK_MAINNET, addr.network());
        assertEquals(PQConstants.SUITE_CAT5_DUAL_1, addr.suite());
        assertEquals(PQConstants.ADDRESS_VERSION, addr.version());
    }
}
