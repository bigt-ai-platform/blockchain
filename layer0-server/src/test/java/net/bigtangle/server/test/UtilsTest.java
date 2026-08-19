package net.bigtangle.server.test;

import net.bigtangle.core.Block;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;

public class UtilsTest {

	public static Block createBlock(NetworkParameters params, Block prevBlock, Block branchBlock) {
		return createNextBlock(prevBlock, branchBlock, NetworkParameters.BLOCK_VERSION_GENESIS);
	}

	/**
	 * Returns a solved, valid empty block that builds on top of this one and
	 * the specified other Block.
	 */
	public static Block createNextBlock(Block prevBlock, Block branchBlock, final long version) {
		Block b = Block.setBlock2(prevBlock.getParams(), version);

		b.setPrevBlockHash(prevBlock.getHash());
		b.setPrevBranchBlockHash(branchBlock.getHash());

		// Set difficulty according to previous consensus
		// only BLOCKTYPE_BEACON and BLOCKTYPE_INITIAL should overwrite this
		b.setLastMiningRewardBlock(
				Math.max(prevBlock.getLastMiningRewardBlock(), branchBlock.getLastMiningRewardBlock()));

		b.setHeight(Math.max(prevBlock.getHeight(), branchBlock.getHeight()) + 1);

		// Don't let timestamp go backwards
		long currTime = System.currentTimeMillis() / 1000;
		long minTime = Math.max(currTime, branchBlock.getTimeSeconds());
		if (currTime >= minTime)
			b.setTime(currTime + 1);
		else
			b.setTime(minTime);
		try {
			b.verifyHeader();
		} catch (VerificationException e) {
			throw new RuntimeException(e); // Cannot happen.
		}
		if (b.getVersion() != version) {
			throw new RuntimeException();
		}
		return b;
	}

}