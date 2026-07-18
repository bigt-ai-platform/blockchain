package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.BlockType;
import net.bigtangle.layer1.params.PaiL1TestParams;

public class Layer1BlockTypeScopingTest {

    @Test
    public void testPaiL1RejectsTokenCreation() {
        PaiL1TestParams params = new PaiL1TestParams();
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_INITIAL));
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_TRANSFER));
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_BEACON));
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_CROSSTANGLE));
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_CONTRACT_EVENT));
        assertTrue(params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL));

        assertTrue(!params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_TOKEN_CREATION));
        assertTrue(!params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_ORDER_OPEN));
        assertTrue(!params.getAllowedBlockTypes().contains(BlockType.BLOCKTYPE_GOVERNANCE));
    }
}
