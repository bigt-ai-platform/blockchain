package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.exception.VerificationException;

/**
 * Verifies that Layer 1 rejects block types that belong exclusively to
 * Layer 0 (token creation). The allow-set gate in
 * {@code ServiceBaseCheck.checkBlockBeforeSave} enforces this via the
 * normal {@code saveBlock} REST path.
 *
 * @see Layer0BlockTypeScopingTest (mirror test for Layer 0 rejecting L1 types)
 */
public class Layer1BlockTypeScopingTest extends AbstractIntegrationTest {

    @Test
    public void l1RejectsTokenCreation() {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block badBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        badBlock.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);
        VerificationException ex = assertThrows(VerificationException.class,
                () -> blockService.checkBlockBeforeSave(badBlock, store));
        assertTrue(ex.getMessage().contains("not allowed"),
                "Error must mention 'not allowed'; got: " + ex.getMessage());
    }

    @Test
    public void l1RejectsTokenCreationOnBatchReceivePath() {
        // N4: the allow-set gate must be enforced on the batch receive path
        // (saveBatchBlock -> addNonChain), not just on the explicit
        // checkBlockBeforeSave helper. Without it, an L0-only TOKEN_CREATION
        // block could be smuggled onto an L1 chain via /batchBlock.
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block badBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
        badBlock.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);
        VerificationException ex = assertThrows(VerificationException.class,
                () -> blockSaveService.saveBatchBlock(badBlock, store));
        assertTrue(ex.getMessage().contains("not allowed"),
                "Error must mention 'not allowed'; got: " + ex.getMessage());
    }
}
