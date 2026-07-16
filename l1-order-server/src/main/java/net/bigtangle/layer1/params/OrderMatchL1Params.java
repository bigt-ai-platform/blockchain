package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

public class OrderMatchL1Params extends MainNetParams {

    public OrderMatchL1Params() {
        this("ordermatch");
    }

    public OrderMatchL1Params(String chainId) {
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
                BlockType.BLOCKTYPE_ORDER_OPEN,
                BlockType.BLOCKTYPE_ORDER_CANCEL);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return false;
    }
}
