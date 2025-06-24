/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.utils.DumpedPrivateKey;

public class DumpedPrivateKeyTest {

    private static final MainNetParams MAINNET = MainNetParams.get();
  //  private static final TestParams TESTNET = TestParams.get();

    @Test
    public void checkNetwork() throws Exception {
        DumpedPrivateKey.fromBase58(MAINNET, "5HtUCLMFWNueqN9unpgX2DzjMg6SDNZyKRb8s3LJgpFg5ubuMrk");
    }

//    @Test(expected = WrongNetworkException.class)
//    public void checkNetworkWrong() throws Exception {
//        DumpedPrivateKey.fromBase58(TESTNET, "5HtUCLMFWNueqN9unpgX2DzjMg6SDNZyKRb8s3LJgpFg5ubuMrk");
//    }

    @Test
    public void testJavaSerialization() throws Exception {

        DumpedPrivateKey key = new DumpedPrivateKey(MAINNET, new ECKey().getPrivKeyBytes(), true);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        new ObjectOutputStream(os).writeObject(key);
        DumpedPrivateKey keyCopy = (DumpedPrivateKey) new ObjectInputStream(new ByteArrayInputStream(os.toByteArray()))
                .readObject();
        assertEquals(key, keyCopy);
    }

    @Test
    public void cloning() throws Exception {
        DumpedPrivateKey a = new DumpedPrivateKey(MAINNET, new ECKey().getPrivKeyBytes(), true);
        // TODO: Consider overriding clone() in DumpedPrivateKey to narrow the type
        DumpedPrivateKey b = (DumpedPrivateKey) a.clone();

        assertEquals(a, b);
        assertNotSame(a, b);
    }

    @Test
    public void roundtripBase58() throws Exception {
        String base58 = "5HtUCLMFWNueqN9unpgX2DzjMg6SDNZyKRb8s3LJgpFg5ubuMrk";
        assertEquals(base58, DumpedPrivateKey.fromBase58(null, base58).toBase58());
    }
}
