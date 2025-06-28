package net.bigtangle.server.test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Utils;

public class RandomNumberBlock extends AbstractIntegrationTest {

	@Test
	public void testRandomNumber() throws Exception {
		List<Block> a1 = new ArrayList<Block>();
		List<Integer> rnumbers = new ArrayList<Integer>();

		createRandomNumber(a1, rnumbers);

	}

	public void createRandomNumber(List<Block> a1, List<Integer> rnumbers) throws Exception {

		Block r1 = Utils.createGenesis(networkParameters);

		r1 = createReward(r1, a1);

		// Deterministic randomization
		byte[] randomness = Utils.xor(r1.getPrevBlockHash().getBytes(), r1.getPrevBranchBlockHash().getBytes());
		SecureRandom se = new SecureRandom(randomness);
		int l1 = se.nextInt() % 33;

		rnumbers.add(Math.abs(l1));
	}

	public Block createReward(Block rewardBlock1, List<Block> blocksAddedAll) throws Exception {

		// Generate mining reward block
		Block next = makeRewardBlock(rewardBlock1.getHash());
		blocksAddedAll.add(next);

		return next;
	}

}
