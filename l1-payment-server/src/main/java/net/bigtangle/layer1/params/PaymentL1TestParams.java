package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

public class PaymentL1TestParams extends TestParams {

    public PaymentL1TestParams() {
        this("PAYMENT");
    }

    public PaymentL1TestParams(String chainId) {
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
