package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.BlockType;

public class ConstantTest {

    @Test
    public void blockType() {
        // This data is used to save data in database, so it can be never change
        // Only new add
        assertTrue(BlockType.BLOCKTYPE_INITIAL.ordinal() == 0);
        assertTrue(BlockType.BLOCKTYPE_TRANSFER.ordinal() == 1);
        assertTrue(BlockType.BLOCKTYPE_REWARD.ordinal() == 2);
        assertTrue(BlockType.BLOCKTYPE_TOKEN_CREATION.ordinal() == 3);
        assertTrue(BlockType.BLOCKTYPE_USERDATA.ordinal() == 4);
        assertTrue(BlockType.BLOCKTYPE_CONTRACT_EVENT.ordinal() == 5);
        assertTrue(BlockType.BLOCKTYPE_GOVERNANCE.ordinal() == 6);
        assertTrue(BlockType.BLOCKTYPE_FILE.ordinal() == 7);
        assertTrue(BlockType.BLOCKTYPE_CONTRACT_EXECUTE.ordinal() == 8);
        assertTrue(BlockType.BLOCKTYPE_CROSSTANGLE.ordinal() == 9);
        assertTrue(BlockType.BLOCKTYPE_ORDER_OPEN.ordinal() == 10);
        assertTrue(BlockType.BLOCKTYPE_ORDER_CANCEL.ordinal() == 11);
        assertTrue(BlockType.BLOCKTYPE_ORDER_EXECUTE.ordinal() == 12);
        assertTrue(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL.ordinal() == 13);
    }


   
    
}
