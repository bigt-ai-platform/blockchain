/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.params;

import java.math.BigInteger;
import java.util.Arrays;

import com.google.common.collect.ImmutableList;

import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.pool.server.ServerPool;

/**
 * Parameters for the main production network on which people trade goods and
 * services.
 */
public class TestParams extends AbstractBitcoinNetParams {

  
    public TestParams() {
        super();

        id = ID_UNITTESTNET;

        maxTarget = new BigInteger("578960377169117509212217050695880916496095398817113098493422368414323410000");
        maxTargetReward = maxTarget.subtract(new BigInteger("100"));

        dumpedPrivateKeyHeader = 128;
        addressHeader = 0;
        p2shHeader = 5;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };

        packetMagic = 0xf9beb4d9L;
        bip32HeaderPub = 0x0488B21E; // The 4 byte header that serializes in
                                     // base58 to "xpub".
        bip32HeaderPriv = 0x0488ADE4; // The 4 byte header that serializes in
                                      // base58 to "xprv"
        genesisPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
        permissionDomainname = ImmutableList.of(genesisPub);
  
        // Equihash Settings
        equihashN = 100;
        equihashK = 4;

        genesisBlock = createGenesis(this);

    }

    public String[] serverSeeds() {
        return new String[] {   };

    }

    private static TestParams instance;

    public static synchronized TestParams get() {
        if (instance == null) {
            instance = new TestParams();
        }
        return instance;
    }

  

}
