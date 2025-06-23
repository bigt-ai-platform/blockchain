/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Utils;
import net.bigtangle.server.service.base.MinioService;

public class MinioTests extends AbstractIntegrationTest {

    

    @Test
    public void testSaveBlock() throws Exception {

        ECKey to = ECKey
                .fromPrivate(Utils.HEX.decode("34c4fc283cd9ac303deb6617b8dcd4c033b007782fd15bd168d7fc0e1819f3f8"));

        Coin aCoin = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);

        List<Block> rollingBlock = wallet.pay(null, to.toAddress(networkParameters).toString(), aCoin,
                "");

        if (!rollingBlock.isEmpty()) {
            Block blockToSave = rollingBlock.get(0);
        	new MinioService(minioConfig, networkParameters).put(blockToSave);

            // Read the object back from Minio
            Block retrieved  = 	new MinioService(minioConfig, networkParameters).get(blockToSave.getHash());

            byte[] originalBytes = blockToSave.bitcoinSerialize();

            assertTrue(java.util.Arrays.equals(originalBytes, retrieved.bitcoinSerialize()));
        } else {
            System.out.println("No block was created to save.");
        }
    }
}
