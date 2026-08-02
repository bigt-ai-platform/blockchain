package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.TestParams;

public class EVML1TestParams extends TestParams {

	public EVML1TestParams() {
		this("EVM");
	}

	public EVML1TestParams(String chainId) {
		super();
		this.chainId = chainId;
	}

	@Override
	public boolean genesisMintsBIG() {
		return false;
	}

	@Override
	public EnumSet<BlockType> getAllowedBlockTypes() {
		return EnumSet.of(
				BlockType.BLOCKTYPE_INITIAL,
				BlockType.BLOCKTYPE_TRANSFER,
				BlockType.BLOCKTYPE_BEACON,
				BlockType.BLOCKTYPE_CROSSTANGLE,
				BlockType.BLOCKTYPE_TOKEN_CREATION,
				BlockType.BLOCKTYPE_EVM_DEPLOY,
				BlockType.BLOCKTYPE_EVM_CALL);
	}
}
