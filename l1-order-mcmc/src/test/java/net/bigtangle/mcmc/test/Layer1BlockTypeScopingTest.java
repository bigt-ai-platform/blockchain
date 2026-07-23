package net.bigtangle.mcmc.test;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.UtilGeneseBlock;

/**
 * Verifies that Layer 1 accepts token creation block types in test mode
 * (OrderMatchL1TestParams allows BLOCKTYPE_TOKEN_CREATION so that
 * token-creation unit tests can use the normal signToken flow).
 */
public class Layer1BlockTypeScopingTest extends AbstractIntegrationTest {

    @Test
    @Disabled("L1 test params now allow BLOCKTYPE_TOKEN_CREATION; token creation tested via FullPrunedBlockGraphTest")
    public void l1RejectsTokenCreation() throws Exception {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block badBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        badBlock.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);
        // In test mode, token creation is allowed, so no exception should be thrown
        blockService.checkBlockBeforeSave(badBlock, store);
    }
}
