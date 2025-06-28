/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;

public class UtilsTest {

	private static final Logger log = LoggerFactory.getLogger(UtilsTest.class);

 

    public static Block createBlock(NetworkParameters params, Block prevBlock, Block branchBlock) {
        return createNextBlock(prevBlock,branchBlock, NetworkParameters.BLOCK_VERSION_GENESIS,
                Address.fromBase58(params, "1Kbm8rqjcX6j5oLbq9J8FapksdvrfGUA88").getHash160());
    }

    /**
     * Returns a solved, valid empty block that builds on top of this one and
     * the specified other Block.
     */
    public static Block createNextBlock(Block prevBlock, Block branchBlock, final long version, byte[] mineraddress) {
        Block b = new Block(prevBlock.getParams(), version);

        b.setMinerAddress(mineraddress);
        b.setPrevBlockHash(prevBlock.getHash());
        b.setPrevBranchBlockHash(branchBlock.getHash());

        // Set difficulty according to previous consensus
        // only BLOCKTYPE_REWARD and BLOCKTYPE_INITIAL should overwrite this
        b.setLastMiningRewardBlock(Math.max(prevBlock.getLastMiningRewardBlock(), branchBlock.getLastMiningRewardBlock()));
        b.setDifficultyTarget(prevBlock.getLastMiningRewardBlock() >= branchBlock.getLastMiningRewardBlock() ? prevBlock.getDifficultyTarget()
                : branchBlock.getDifficultyTarget());

        b.setHeight(Math.max(prevBlock.getHeight(), branchBlock.getHeight()) + 1);

        // Don't let timestamp go backwards
        long currTime = System.currentTimeMillis() / 1000;
        long minTime = Math.max(currTime, branchBlock.getTimeSeconds());
        if (currTime >= minTime)
            b.setTime(currTime + 1);
        else
            b.setTime(minTime);
        b.solve();
        try {
            b.verifyHeader();
        } catch (VerificationException e) {
            throw new RuntimeException(e); // Cannot happen.
        }
        if (b.getVersion() != version) {
            throw new RuntimeException();
        }
        return b;
    }

	
}
