package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

/**
 * L1-ordermatch {@link net.bigtangle.params.NetworkParameters}: accepts only
 * order-match block types plus the shared transfer/reward/cross-tangle types.
 * chainId = {@code "ordermatch"}.
 *
 * @see ContractL1Params (the L1-contract counterpart)
 */
public class OrderMatchL1Params extends MainNetParams {

    public OrderMatchL1Params() {
        this("ordermatch");
    }

    public OrderMatchL1Params(String chainId) {
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
                BlockType.BLOCKTYPE_ORDER_EXECUTE);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return true;
    }
}
