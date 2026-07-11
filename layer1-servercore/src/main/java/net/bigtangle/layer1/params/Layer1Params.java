package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

/**
 * Layer 1 {@link net.bigtangle.params.NetworkParameters}: a sub-chain that
 * accepts order-match and contract block types plus the shared
 * transfer/reward/cross-tangle types needed to run an independent chain. A
 * Layer 1 node (layer1-server / layer1-mcmc) uses these parameters so
 * {@code ServiceBaseCheck.checkBlockBeforeSave} rejects Layer 0-only types
 * (token creation). See {@code LAYERING-PLAN.md}.
 *
 * <p><b>Note:</b> this currently models a single L1 that hosts both order and
 * contract logic, matching how {@code layer1-servercore} is structured today.
 * When order-match and contract split into separate chains, each gets its own
 * subclass with a narrower allow-set and a distinct {@code chainId} (and a
 * distinct genesis). The {@code chainId} here is parameterizable via the
 * constructor for that future split.
 */
public class Layer1Params extends MainNetParams {

    public Layer1Params() {
        this("L1");
    }

    public Layer1Params(String chainId) {
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
                // order-match
                BlockType.BLOCKTYPE_ORDER_OPEN,
                BlockType.BLOCKTYPE_ORDER_CANCEL,
                BlockType.BLOCKTYPE_ORDER_EXECUTE,
                // contracts
                BlockType.BLOCKTYPE_CONTRACT_EVENT,
                BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL,
                BlockType.BLOCKTYPE_CONTRACT_EXECUTE);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return true;
    }
}
