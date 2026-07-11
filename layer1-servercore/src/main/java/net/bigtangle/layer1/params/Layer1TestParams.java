package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

public class Layer1TestParams extends TestParams {

    public Layer1TestParams() {
        this("L1");
    }

    public Layer1TestParams(String chainId) {
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
                BlockType.BLOCKTYPE_ORDER_CANCEL,
                BlockType.BLOCKTYPE_ORDER_EXECUTE,
                BlockType.BLOCKTYPE_CONTRACT_EVENT,
                BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL,
                BlockType.BLOCKTYPE_CONTRACT_EXECUTE);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return true;
    }
}
