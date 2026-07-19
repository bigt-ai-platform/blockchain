/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.params;

import java.math.BigInteger; 

import com.google.common.collect.ImmutableList;
 

/**
 * Parameters for the main production network on which people trade goods and
 * services.
 */
public class TestParams extends NetworkParameters {

    public TestParams() {
        super();

        id = ID_UNITTESTNET;

        difficultyLimit = new BigInteger("578960377169117509212217050695880916496095398817113098493422368414323410000");
        rewardDifficultyLimit = difficultyLimit.subtract(new BigInteger("100"));

        dumpedPrivateKeyHeader = 128;
        addressHeader = 111;
        p2shHeader = 196;
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };

        packetMagic = 0xf9beb4d9L;
        bip32HeaderPub = 0x0488B21E; // The 4 byte header that serializes in
                                     // base58 to "xpub".
        bip32HeaderPriv = 0x0488ADE4; // The 4 byte header that serializes in
                                      // base58 to "xprv"
        genesisPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
        permissionDomainname = ImmutableList.of(genesisPub);
 

 
    }

    public String[] serverSeeds() {
        return new String[] {};

    }

    private static TestParams instance;

    public static synchronized TestParams get() {
        if (instance == null) {
            instance = new TestParams();
        }
        return instance;
    }

}
