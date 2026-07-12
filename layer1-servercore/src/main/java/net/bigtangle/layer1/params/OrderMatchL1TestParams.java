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
    public EnumSet<BlockType> getAllowedBlockTypes() {
        return EnumSet.of(
                BlockType.BLOCKTYPE_INITIAL,
                BlockType.BLOCKTYPE_TRANSFER,
                BlockType.BLOCKTYPE_REWARD,
                BlockType.BLOCKTYPE_CROSSTANGLE,
                BlockType.BLOCKTYPE_ORDER_OPEN,
                BlockType.BLOCKTYPE_ORDER_CANCEL);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return false;
    }
}
