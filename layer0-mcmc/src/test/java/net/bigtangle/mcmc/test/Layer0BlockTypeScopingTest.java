package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.exception.VerificationException;

/**
 * Verifies that Layer 0 rejects block types that belong exclusively to
 * Layer 1 (order-match and contract types). The allow-set gate in
 * {@code ServiceBaseCheck.checkBlockBeforeSave} enforces this; these
 * tests confirm it works for every L1-only block type.
 *
 * @see Layer1BlockTypeScopingTest (mirror test for Layer 1 rejecting L0 types)
 */
public class Layer0BlockTypeScopingTest extends AbstractIntegrationTest {

    @Test
    public void l0RejectsOrderOpen() {
        assertRejected(BlockType.BLOCKTYPE_ORDER_OPEN);
    }

    @Test
    public void l0RejectsOrderCancel() {
        assertRejected(BlockType.BLOCKTYPE_ORDER_CANCEL);
    }

    @Test
    public void l0RejectsOrderExecute() {
        assertRejected(BlockType.BLOCKTYPE_ORDER_EXECUTE);
    }

    @Test
    public void l0RejectsContractEvent() {
        assertRejected(BlockType.BLOCKTYPE_CONTRACT_EVENT);
    }

    @Test
    public void l0RejectsContractEventCancel() {
        assertRejected(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
    }

    @Test
    public void l0RejectsContractExecute() {
        assertRejected(BlockType.BLOCKTYPE_CONTRACT_EXECUTE);
    }

    private void assertRejected(BlockType rejectedType) {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block badBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        badBlock.setBlockType(rejectedType);
        badBlock.solve();
        // checkBlockBeforeSave is the allow-set gate; saveBlock expects it has
        // already been called, so we test the gate directly.
        VerificationException ex = assertThrows(VerificationException.class,
                () -> blockService.checkBlockBeforeSave(badBlock, store));
        assertTrue(ex.getMessage().contains("not allowed"),
                "Error must mention 'not allowed'; got: " + ex.getMessage());
    }
}
