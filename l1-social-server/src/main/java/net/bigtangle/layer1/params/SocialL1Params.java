package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

public class SocialL1Params extends MainNetParams {

    public SocialL1Params() {
        this("SOCIAL");
    }

    public SocialL1Params(String chainId) {
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
                BlockType.BLOCKTYPE_STAKE,
                BlockType.BLOCKTYPE_SLASHING,
                BlockType.BLOCKTYPE_EXIT);
    }
}
