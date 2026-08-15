package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.script.Script.VerifyFlag;
import net.bigtangle.params.NetworkParameters;

public class ECKeyTest {

    private final NetworkParameters params = net.bigtangle.params.TestParams.get();

    @Test
    public void testKeyType() {
        ECKey key = ECKey.createNew();
        assertEquals(KeyType.EC, key.getKeyType());
        assertEquals(KeyType.PQ, PQKey.createNew().getKeyType());
    }

    @Test
    public void testSignAndVerify() {
        ECKey key = ECKey.createNew();
        Sha256Hash hash = Sha256Hash.of(new byte[] { 1, 2, 3, 4 });
        TransactionSignature sig = key.sign(hash);
        assertNotNull(sig);
        assertTrue(ECKey.verify(hash.getBytes(), sig, key.getPubKey()));
        // Tampered hash must not verify
        Sha256Hash other = Sha256Hash.of(new byte[] { 5, 6, 7, 8 });
        assertFalse(ECKey.verify(other.getBytes(), sig, key.getPubKey()));
    }

    @Test
    public void testAddressIsLegacyBase58() {
        ECKey key = ECKey.createNew();
        Address addr = key.toAddress(params);
        assertNotNull(addr);
        assertEquals(20, addr.getHash160().length);
        assertEquals(addr, Address.fromHash160(params, key.getPubKeyHash()));
        assertEquals(addr.toBase58(), key.toAddressString(params));
    }

    @Test
    public void testFromPrivateRoundTrip() {
        ECKey key = ECKey.createNew();
        ECKey restored = ECKey.fromPrivate(key.getPrivKeyBytes());
        assertEquals(key.getPublicKeyAsHex(), restored.getPublicKeyAsHex());
        assertArrayEquals(key.getPubKeyHash(), restored.getPubKeyHash());
    }

    @Test
    public void testEcScriptVerify() throws Exception {
        ECKey key = ECKey.createNew();
        Script scriptPubKey = ScriptBuilder.createOutputScript(key);

        Transaction tx = new Transaction(params);
        tx.addOutput(new TransactionOutput(params, tx, Coin.COIN, scriptPubKey.getProgram()));
        TransactionInput input = tx.addSignedInput(
                TransactionOutPoint.fromTransactionOutPoint4(params, 0, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH),
                scriptPubKey, key);
        assertNotNull(input);
        input.getScriptSig().correctlySpends(tx, 0, scriptPubKey, EnumSet.noneOf(VerifyFlag.class));
    }
}
