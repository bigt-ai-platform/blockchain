package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

public class ContractL1TestParams extends TestParams {

    public ContractL1TestParams() {
        this("L1-contract");
    }

    public ContractL1TestParams(String chainId) {
        super();
        this.chainId = chainId;
    }

    @Override
    public boolean genesisMintsBIG() { return true; }

    @Override
    public EnumSet<BlockType> getAllowedBlockTypes() {
        return EnumSet.of(
                BlockType.BLOCKTYPE_INITIAL,
                BlockType.BLOCKTYPE_TRANSFER,
                BlockType.BLOCKTYPE_BEACON,
                BlockType.BLOCKTYPE_CROSSTANGLE,
                BlockType.BLOCKTYPE_CONTRACT_EVENT,
                BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
    }

    @Override
    public boolean isOrderMatchExecutionChainEnabled() {
        return false;
    }
}
