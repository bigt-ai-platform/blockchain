package net.bigtangle.layer1.params;

import java.util.EnumSet;

import net.bigtangle.core.BlockType;
import net.bigtangle.params.MainNetParams;

/**
 * Layer-1 EVM chain parameters. chainId = {@code "EVM"}. This chain hosts the
 * EVM smart-contract world state only; EVM deploy/call blocks reference a
 * contract token registered via token creation.
 */
public class EVML1Params extends MainNetParams {

	public EVML1Params() {
		this("EVM");
	}

	public EVML1Params(String chainId) {
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
