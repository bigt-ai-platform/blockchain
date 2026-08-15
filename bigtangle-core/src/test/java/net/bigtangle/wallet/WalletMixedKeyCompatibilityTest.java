package net.bigtangle.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Key;
import net.bigtangle.core.KeyType;
import net.bigtangle.core.PQKey;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;

/**
 * Compatibility tests for the mixed (EC + PQ) wallet and the legacy WIF
 * ("wallet key file") import/export path.
 */
public class WalletMixedKeyCompatibilityTest {

    private final NetworkParameters params = TestParams.get();

    @Test
    public void testWifRoundTrip() throws Exception {
        ECKey key = ECKey.createNew();
        String wif = key.getPrivateKeyAsWiF(params);
        ECKey restored = ECKey.fromWIF(params, wif);
        assertEquals(key.getPublicKeyAsHex(), restored.getPublicKeyAsHex());
        assertEquals(key.toAddressString(params), restored.toAddressString(params));
    }

    @Test
    public void testWifUncompressedRoundTrip() throws Exception {
        ECKey key = ECKey.fromPrivate(ECKey.createNew().getPrivKeyBytes(), false);
        String wif = key.getPrivateKeyAsWiF(params);
        ECKey restored = ECKey.fromWIF(params, wif);
        assertTrue(Arrays.equals(key.getPrivKeyBytes(), restored.getPrivKeyBytes()));
        assertEquals(key.getPublicKeyAsHex(), restored.getPublicKeyAsHex());
    }

    @Test
    public void testMixedWalletKeyManagement() {
        ECKey ecKey = ECKey.createNew();
        PQKey pqKey = PQKey.createNew();

        // Old (EC-only) wallet
        Wallet wallet = Wallet.fromKeys(params, ecKey);
        // Migration: import a PQ key into the existing wallet
        wallet.importKey(pqKey);

        // Both keys are present and addressable
        assertEquals(2, wallet.walletKeysAll(null).size());

        Key ec = wallet.getECKey(null, ecKey.toAddressString(params));
        assertNotNull(ec);
        assertEquals(KeyType.EC, ec.getKeyType());

        Key pq = wallet.getECKey(null, pqKey.toAddressString(params));
        assertNotNull(pq);
        assertEquals(KeyType.PQ, pq.getKeyType());

        // Key lookup by pubkey hash works for both types
        assertNotNull(wallet.findKeyFromPubHash(ecKey.getPubKeyHash()));
        assertNotNull(wallet.findKeyFromPubHash(pqKey.getPubKeyHash()));
    }

    @Test
    public void testMigrateLegacyWifWalletToMixed() throws Exception {
        // Legacy EC wallet: the private key is stored as WIF (the old "wallet key file")
        ECKey legacyKey = ECKey.createNew();
        String wif = legacyKey.getPrivateKeyAsWiF(params);

        // Restore the legacy EC key from WIF into a fresh wallet, then add a PQ key
        ECKey restored = ECKey.fromWIF(params, wif);
        Wallet wallet = Wallet.fromKeys(params, restored);
        PQKey pqKey = PQKey.createNew();
        wallet.importKey(pqKey);

        assertEquals(2, wallet.walletKeysAll(null).size());
        assertNotNull(wallet.findKeyFromPubHash(restored.getPubKeyHash()));
        assertNotNull(wallet.findKeyFromPubHash(pqKey.getPubKeyHash()));
        assertNotNull(wallet.getECKey(null, restored.toAddressString(params)));
        assertNotNull(wallet.getECKey(null, pqKey.toAddressString(params)));
    }

    @Test
    public void testPqKeySeedRoundTrip() {
        PQKey key = PQKey.createNew();
        String seedHex = key.getPrivateKeySeedAsHex();
        assertNotNull(seedHex);
        assertEquals(64, seedHex.length()); // 32-byte ML-DSA seed

        PQKey restored = PQKey.fromPrivateKeyHex(seedHex);
        assertEquals(key.getKeyBundle(), restored.getKeyBundle());
        assertEquals(key.getPublicKeyAsHex(), restored.getPublicKeyAsHex());
    }

    @Test
    public void testPqDualKeySeedRoundTrip() {
        byte[] mlSeed = new byte[32];
        mlSeed[0] = 1;
        byte[] slhSeed = new byte[32];
        slhSeed[0] = 2;
        PQKey key = PQKey.fromSeeds(mlSeed, slhSeed);
        String seedHex = key.getPrivateKeySeedAsHex();
        assertNotNull(seedHex);
        assertEquals(128, seedHex.length()); // 64-byte dual seed

        PQKey restored = PQKey.fromPrivateKeyHex(seedHex);
        assertEquals(key.getKeyBundle(), restored.getKeyBundle());
        assertEquals(key.getPublicKeyAsHex(), restored.getPublicKeyAsHex());
    }
}
