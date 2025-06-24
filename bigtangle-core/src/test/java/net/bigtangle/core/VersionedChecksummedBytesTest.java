/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static net.bigtangle.core.Utils.HEX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;

/**
 *
 */
public class VersionedChecksummedBytesTest {
    static final NetworkParameters testParams = MainNetParams.get();
    static final NetworkParameters mainParams = MainNetParams.get();

   // @Test
    public void stringification() throws Exception {
        // Test a testnet address.
        VersionedChecksummedBytes a = new VersionedChecksummedBytes(testParams.getAddressHeader(), HEX.decode("fda79a24e50ff70ff42f7d89585da5bd19d9e5cc"));
        assertEquals("n4eA2nbYqErp7H6jebchxAN59DmNpksexv", a.toString());

        VersionedChecksummedBytes b = new VersionedChecksummedBytes(mainParams.getAddressHeader(), HEX.decode("4a22c3c4cbb31e4d03b15550636762bda0baf85a"));
        assertEquals("17kzeh4N8g49GFvdDzSf8PjaPfyoD1MndL", b.toString());
    }

    @Test
    public void cloning() throws Exception {
        VersionedChecksummedBytes a = new VersionedChecksummedBytes(testParams.getAddressHeader(), HEX.decode("fda79a24e50ff70ff42f7d89585da5bd19d9e5cc"));
        VersionedChecksummedBytes b = a.clone();

        assertEquals(a, b);
        assertNotSame(a, b);
    }

    @Test
    public void comparisonCloneEqualTo() throws Exception {
        VersionedChecksummedBytes a = new VersionedChecksummedBytes(testParams.getAddressHeader(), HEX.decode("fda79a24e50ff70ff42f7d89585da5bd19d9e5cc"));
        VersionedChecksummedBytes b = a.clone();

        assertTrue(a.compareTo(b) == 0);
    }
}
