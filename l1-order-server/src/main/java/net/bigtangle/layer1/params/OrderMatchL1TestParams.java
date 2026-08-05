package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

public class OrderMatchL1TestParams extends TestParams {

    public OrderMatchL1TestParams() {
        this("ordermatch");
    }

    public OrderMatchL1TestParams(String chainId) {
        super();
        this.chainId = chainId;
    }

    @Override
    public boolean genesisMintsBIG() { return false; }

    @Override
    public EnumSet<BlockType> getAllowedBlockTypes() {
        return EnumSet.of(
                BlockType.BLOCKTYPE_INITIAL,
                BlockType.BLOCKTYPE_TRANSFER,
                BlockType.BLOCKTYPE_BEACON,
                BlockType.BLOCKTYPE_CROSSTANGLE,
                BlockType.BLOCKTYPE_TOKEN_CREATION,
                BlockType.BLOCKTYPE_ORDER_OPEN,
                BlockType.BLOCKTYPE_ORDER_CANCEL,
                BlockType.BLOCKTYPE_STAKE,
                BlockType.BLOCKTYPE_SLASHING,
                BlockType.BLOCKTYPE_EXIT);
    }

}
