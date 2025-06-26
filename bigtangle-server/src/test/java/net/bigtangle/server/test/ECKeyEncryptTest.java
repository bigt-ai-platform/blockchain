package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.spongycastle.util.encoders.Hex;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.crypto.ECIESCoder;

 
public class ECKeyEncryptTest extends AbstractIntegrationTest {

    private byte[] payload = Hex.decode("1122334455");

    @Test
    public void importECKeyDecrypt() {
        BigInteger privKey = new BigInteger("5e173f6ac3c669587538e7727cf19b782a4f2fda07c1eaa662c593e5e85e3051", 16);
        ECKey ecKey = ECKey.fromPrivate(privKey);
        try {
            byte[] cipher = ECIESCoder.encrypt(ecKey.getPubKeyPoint(), payload);
            byte[] decryptedPayload = ECIESCoder.decrypt(ecKey.getPrivKey(), cipher);
            assertArrayEquals(decryptedPayload, payload);
        } catch (Throwable e) {
            e.printStackTrace();
        }

    }

    @Test
    public void newECKeyDecrypt() {
        ECKey ecKey = new ECKey();
        try {
            byte[] cipher = ECIESCoder.encrypt(ecKey.getPubKeyPoint(), payload);
            byte[] decryptedPayload = ECIESCoder.decrypt(ecKey.getPrivKey(), cipher);
            assertArrayEquals(decryptedPayload, payload);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

      
    @Test
    public void accessTokenSignatureVerify() {
        String message = UUID.randomUUID().toString();
        byte[] buf = message.getBytes();
        Sha256Hash hash = Sha256Hash.wrapReversed(Sha256Hash.hashTwice(buf, 0, buf.length));
        
        ECKey ecKey = new ECKey();
        ECKey.ECDSASignature party1Signature = ecKey.sign(hash);
        byte[] signature = party1Signature.encodeToDER();
        
        boolean success = ecKey.verify(hash.getBytes(), signature);
        assertTrue(success);
    }
    
   
    
}
