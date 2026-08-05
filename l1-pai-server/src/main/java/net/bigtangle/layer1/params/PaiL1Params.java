package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

public class PaiL1Params extends MainNetParams {

    public PaiL1Params() {
        this("PAI");
    }

    public PaiL1Params(String chainId) {
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
                BlockType.BLOCKTYPE_CONTRACT_EVENT,
                BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL,
                BlockType.BLOCKTYPE_STAKE,
                BlockType.BLOCKTYPE_SLASHING,
                BlockType.BLOCKTYPE_EXIT);
    }
}
