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
import net.bigtangle.params.NetworkParameters;

/**
 *
 */
public class VersionedChecksummedBytesTest {
    static final NetworkParameters testParams = MainNetParams.get();
    static final NetworkParameters mainParams = MainNetParams.get();

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
