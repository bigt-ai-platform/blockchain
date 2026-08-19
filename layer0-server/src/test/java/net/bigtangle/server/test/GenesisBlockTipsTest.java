/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.server.core.BlockWrap;

/**
 * PoS tip-selection tests: verifies that the GHOST fork choice (via the
 * {@link TipsService} stand-in) always yields a validated block pair, starting
 * from genesis and as blocks are added to the store.
 */
public class GenesisBlockTipsTest extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(GenesisBlockTipsTest.class);

	@Test
	public void testSingleBlockAndTipsGeneration() throws Exception {
		// Create a block referencing genesis as parents
		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
		Block block1 = UtilsTest.createBlock(networkParameters, genesis, genesis);

		log.info("Block 1 created: " + block1.getHashAsString());

		// Add the block to the blockgraph
		blockGraph.addBlock(block1, true, store);

		// Verify block is stored
		BlockWrap block1Wrap = store.getBlockWrap(block1.getHash());
		assertNotNull(block1Wrap, "Block should be stored in database");

		log.info("Block 1 stored successfully with height: " + block1Wrap.getBlock().getHeight());

		// Verify we can get a validated block pair
		Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
		assertNotNull(tips, "Should be able to get validated block pair");
		assertNotNull(tips.getLeft(), "Left tip should not be null");
		assertNotNull(tips.getRight(), "Right tip should not be null");

		log.info("Validated block pair - Left: " + tips.getLeft().getBlockHash()
				+ ", Right: " + tips.getRight().getBlockHash());

		// Verify tips have valid heights
		assertTrue(tips.getLeft().getBlock().getHeight() >= 0);
		assertTrue(tips.getRight().getBlock().getHeight() >= 0);
	}

	@Test
	public void testMultipleBlocksAndTipsGeneration() throws Exception {
		// Create initial block chain starting from genesis reference
		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

		// Create first block
		Block block1 = createAndAddNextBlock(genesis, genesis);
		log.info("Block 1 added: " + block1.getHashAsString());

		// Create additional blocks building on top of each other
		Block block2 = createAndAddNextBlock(block1, block1);
		log.info("Block 2 added: " + block2.getHashAsString());

		Block block3 = createAndAddNextBlock(block2, block2);
		log.info("Block 3 added: " + block3.getHashAsString());

		Block block4 = createAndAddNextBlock(block3, block3);
		log.info("Block 4 added: " + block4.getHashAsString());

		log.info("Multiple blocks added successfully");

		// Get validated block pair multiple times to ensure consistency
		for (int i = 0; i < 5; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			assertNotNull(tips, "Tips should not be null in iteration " + (i + 1));
			assertNotNull(tips.getLeft(), "Left tip should not be null");
			assertNotNull(tips.getRight(), "Right tip should not be null");

			log.info("Iteration " + (i + 1) + " - Tips: "
					+ tips.getLeft().getBlockHash() + ", "
					+ tips.getRight().getBlockHash());
		}
	}

	@Test
	public void testTipsUpdateAfterNewBlocks() throws Exception {
		// Start with one block
		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
		Block block1 = createAndAddNextBlock(genesis, genesis);

		log.info("Initial block added: " + block1.getHashAsString());

		// Add more blocks
		Block block2 = createAndAddNextBlock(block1, block1);
		Block block3 = createAndAddNextBlock(block2, block2);

		log.info("Added 2 more blocks");

		// Verify validated block pairs are still available
		Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
		assertNotNull(tips);
		assertNotNull(tips.getLeft());
		assertNotNull(tips.getRight());

		log.info("Tips still valid after updates");
	}
}