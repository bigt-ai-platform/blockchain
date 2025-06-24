/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.wallet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.google.protobuf.ByteString;

import net.bigtangle.core.ECKey;
import net.bigtangle.crypto.KeyCrypterScrypt;
import net.bigtangle.kits.WalletUtil;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.TestParams;
 

public class WalletUtilTest {

    private static final Logger log = LoggerFactory.getLogger(WalletUtilTest.class);

    @Test
    public void walletCreateLoadTest() throws Exception {

        byte[] a = WalletUtil.createWallet(TestParams.get());
        Wallet wallet = WalletUtil.loadWallet(false, new ByteArrayInputStream(a), MainNetParams.get());

        List<ECKey> issuedKeys = wallet.walletKeys(null);
        assertTrue(issuedKeys.size() == 1);
        for (ECKey ecKey : issuedKeys) {
            log.debug(ecKey.getPublicKeyAsHex());
            log.debug(ecKey.getPrivateKeyAsHex());
            log.debug(ecKey.toAddress(MainNetParams.get()).toString());
        }

    }

    @Test
    public void walletCreateEncryptTest() throws Exception {

        ECKey key = new ECKey();
        byte[] a = WalletUtil.createWallet(MainNetParams.get());

        byte[] b = WalletUtil.encrypt(key, a);
        Wallet wallet = WalletUtil.loadWallet(false, new ByteArrayInputStream(WalletUtil.decrypt(key, b)),
                MainNetParams.get());

        List<ECKey> issuedKeys = wallet.walletKeys(null);
        assertTrue(issuedKeys.size() == 1);
        for (ECKey ecKey : issuedKeys) {
            log.debug(ecKey.getPublicKeyAsHex());
            log.debug(ecKey.getPrivateKeyAsHex());
            log.debug(ecKey.toAddress(MainNetParams.get()).toString());
        }

    }

    @Test
    public void setPassword() throws Exception {

        byte[] a = WalletUtil.createWallet(MainNetParams.get());
        Wallet wallet = WalletUtil.loadWallet(false, new ByteArrayInputStream(a), MainNetParams.get());

        List<ECKey> issuedKeys = wallet.walletKeys(null);
        assertTrue(issuedKeys.size() > 0);

        String oldPassword = "test";
        oldPassword += new Random().nextLong();

        for (int i = 0; i < 10; i++) {
            KeyCrypterScrypt scrypt = new KeyCrypterScrypt(SCRYPT_PARAMETERS);

            KeyParameter aesKey = scrypt.deriveKey(oldPassword);

            if (wallet.isEncrypted()) {
                wallet.decrypt(oldPassword);
            }
            wallet.encrypt(scrypt, aesKey);
            List<ECKey> k2 = wallet.walletKeys(aesKey);

            assertTrue(wallet.isEncrypted());
            // assertEquals(issuedKeys, k2);
            issuedKeys.stream().allMatch(num -> k2.contains(num));
            String password = "test";
            password += new Random().nextLong();
            wallet.changePassword(password, oldPassword);

            oldPassword = password;
        }

    }

    public static final Protos.ScryptParameters SCRYPT_PARAMETERS = Protos.ScryptParameters.newBuilder().setP(6).setR(8)
            .setN(32768).setSalt(ByteString.copyFrom(KeyCrypterScrypt.randomSalt())).build();

  
   // @Test
    public void walletCreateLoadSize() throws Exception {

        int size = 10;
        byte[] a = WalletUtil.createWallet(MainNetParams.get(), size);
      
        Wallet wallet = WalletUtil.loadWallet(false, new ByteArrayInputStream(a), MainNetParams.get());

        List<ECKey> issuedKeys = wallet.walletKeys(null);
        log.debug(issuedKeys.size()+""); 
        assertTrue(issuedKeys.size() >= size); 
        
        FileUtils.writeByteArrayToFile(new File("logs/testsize.wallet"), a);
    }

}
