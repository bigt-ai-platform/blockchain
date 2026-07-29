package net.bigtangle.mcmc.test;

import org.junit.jupiter.api.Test;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.KeyBundle;
import net.bigtangle.params.TestParams;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PQKeyCrossPlatformCompareTest {

    @Test
    public void dumpJavaKeyForComparison() throws Exception {
        byte[] mlDsaSeed = new byte[32];
        byte[] slhDsaSeed = new byte[32];
        java.util.Arrays.fill(mlDsaSeed, (byte) 0x42);
        java.util.Arrays.fill(slhDsaSeed, (byte) 0x24);

        PQKey javaKey = PQKey.fromSeeds(mlDsaSeed, slhDsaSeed);
        assertNotNull(javaKey);

        System.out.println("");
        System.out.println("=== JAVA output (same seed as TS: 0x42*32, 0x24*32) ===");
        System.out.println("privateKeyMlHex: " + javaKey.getPrivateKeyMlAsHex());
        System.out.println("privateKeySlhHex: " + javaKey.getPrivateKeySlhAsHex());

        byte[] rawPubKey = javaKey.getKeyBundleBytes();
        byte[] prefixedPubKey = javaKey.getPublicKeyBytes();

        System.out.println("rawPubKey (getKeyBundleBytes) length: " + rawPubKey.length);
        System.out.println("rawPubKey hex: " + Utils.HEX.encode(rawPubKey));
        System.out.println("");
        System.out.println("prefixedPubKey (getPublicKeyBytes) length: " + prefixedPubKey.length);
        System.out.println("prefixedPubKey hex: " + Utils.HEX.encode(prefixedPubKey));
        System.out.println("");
        System.out.println("getPubKeyHash: " + Utils.HEX.encode(javaKey.getPubKeyHash()));
        System.out.println("toAddress base58: " + javaKey.toAddress(TestParams.get()).toBase58());

        KeyBundle bundle = javaKey.getKeyBundle();
        System.out.println("");
        System.out.println("=== KeyBundle entries ===");
        System.out.println("version: " + bundle.version());
        System.out.println("entries count: " + bundle.entries().size());
        int idx = 0;
        for (KeyBundle.Entry e : bundle.entries()) {
            byte[] pk = e.publicKey();
            String first32 = Utils.HEX.encode(
                pk.length >= 32 ? java.util.Arrays.copyOf(pk, 32) : pk);
            System.out.println("  Entry " + idx + ": algorithm=" + e.algorithm()
                + " key length=" + pk.length + " keyHex(first 32)=" + first32);
            idx++;
        }

        byte[] reSerialized = bundle.serialize();
        System.out.println("");
        System.out.println("Re-serialized bundle length: " + reSerialized.length
            + " (original: " + rawPubKey.length + ")");
        System.out.println("Serialization round-trip: " + java.util.Arrays.equals(rawPubKey, reSerialized));
    }
}
