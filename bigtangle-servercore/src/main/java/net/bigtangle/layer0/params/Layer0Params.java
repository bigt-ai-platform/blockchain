package net.bigtangle.layer0.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

/**
 * Layer 0 {@link net.bigtangle.params.NetworkParameters}: the settlement chain
 * that accepts token creation, transfer/payment, reward/mining and the
 * cross-tangle anchor. A Layer 0 node (layer0-server) uses these
 * parameters so {@code ServiceBaseCheck.checkBlockBeforeSave} rejects any
 * Layer 1 block type (order / contract). See {@code LAYERING-PLAN.md}.
 *
 * <p>Extends {@link MainNetParams} so genesis, address headers and difficulty
 * are identical to the main chain - only the allowed block-type set is
 * restricted.
 */
public class Layer0Params extends MainNetParams {

    public Layer0Params() {
        super();
        this.chainId = "L0";
    }

    @Override
    public EnumSet<BlockType> getAllowedBlockTypes() {
        return EnumSet.of(
                BlockType.BLOCKTYPE_INITIAL,
                BlockType.BLOCKTYPE_TRANSFER,
                BlockType.BLOCKTYPE_TOKEN_CREATION,
                BlockType.BLOCKTYPE_BEACON,
                BlockType.BLOCKTYPE_CROSSTANGLE,
                BlockType.BLOCKTYPE_USERDATA,
                BlockType.BLOCKTYPE_FILE,
                BlockType.BLOCKTYPE_GOVERNANCE,
                BlockType.BLOCKTYPE_STAKE,
                BlockType.BLOCKTYPE_SLASHING,
                BlockType.BLOCKTYPE_EXIT);
    }
}
