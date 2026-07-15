package net.bigtangle.layer0.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

/**
 * Test-network Layer 0 parameters: use the historical unit-test genesis and
 * address headers while keeping Layer 0's block-type boundary.
 */
public class Layer0TestParams extends TestParams {

    public Layer0TestParams() {
        super();
        this.chainId = "L0";
    }

    @Override
    public EnumSet<BlockType> getAllowedBlockTypes() {
        return EnumSet.of(
                BlockType.BLOCKTYPE_INITIAL,
                BlockType.BLOCKTYPE_TRANSFER,
                BlockType.BLOCKTYPE_TOKEN_CREATION,
                BlockType.BLOCKTYPE_REWARD,
                BlockType.BLOCKTYPE_CROSSTANGLE,
                BlockType.BLOCKTYPE_USERDATA,
                BlockType.BLOCKTYPE_FILE,
                BlockType.BLOCKTYPE_GOVERNANCE,
                BlockType.BLOCKTYPE_STAKE,
                BlockType.BLOCKTYPE_SLASHING);
    }
}