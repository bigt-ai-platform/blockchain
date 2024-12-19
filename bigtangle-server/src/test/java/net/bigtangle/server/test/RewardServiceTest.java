/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.InsufficientMoneyException;
import net.bigtangle.core.exception.UTXOProviderException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.response.GetBlockListResponse;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class RewardServiceTest extends AbstractIntegrationTest {

	// Test difficulty transition
	// @Test
	public void testDifficultyTransition1() throws Exception {

		long currentTime = networkParameters.getGenesisBlock().getTimeSeconds();

		// Reward exactly on target -> no difficulty change

		Block rollingBlock = addBlocks(1, null);
		for (int i = 0; i < NetworkParameters.INTERVAL - 1; i++) {
			currentTime += NetworkParameters.TARGET_SPACING;
			Block rollingBlock2 = addBlocks(1, null);
			rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock2),
					defaultBlockWrap(rollingBlock2), currentTime, store);
			blockGraph.updateChain();
		}

		currentTime += NetworkParameters.TARGET_SPACING;
		rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
				defaultBlockWrap(rollingBlock), currentTime, store);
		blockGraph.updateChain();
		ServiceBaseConnect servicebase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService,jsonmapper);
		assertEquals( servicebase.getRewardInfo(rollingBlock).getDifficultyTargetAsInteger(),
				 servicebase.getRewardInfo(networkParameters.getGenesisBlock( )).getDifficultyTargetAsInteger());
	}

	// Test difficulty transition
	// @Test
	public void testDifficultyTransition2() throws Exception {
		ECKey genesiskey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));
		long currentTime = networkParameters.getGenesisBlock().getTimeSeconds();
		List<Block> addedBlocks = new ArrayList<>();
		// Rewards way too fast -> maximum difficulty change to higher difficulty
		Block rollingBlock = networkParameters.getGenesisBlock();
		for (int i = 0; i < NetworkParameters.INTERVAL - 1; i++) {
			currentTime += NetworkParameters.TARGET_SPACING / 8;

			Block b = payBigTo(genesiskey, BigInteger.valueOf(500000), addedBlocks);
			rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(b), defaultBlockWrap(b),
					currentTime, store);
			blockGraph.updateChain();
		}

		currentTime += NetworkParameters.TARGET_SPACING / 8;
		rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
				defaultBlockWrap(rollingBlock), currentTime, store);
		blockGraph.updateChain();
		ServiceBaseConnect servicebase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService,jsonmapper);
		assertEquals( servicebase.getRewardInfo(rollingBlock).getDifficultyTargetAsInteger().multiply(BigInteger.valueOf(4)),
				servicebase.getRewardInfo(networkParameters.getGenesisBlock()).getDifficultyTargetAsInteger());
		Block highDifficultyBlock = rollingBlock;

		// Rewards way slower -> maximum difficulty change to lower difficulty
		for (int i = 0; i < NetworkParameters.INTERVAL - 1; i++) {
			currentTime += NetworkParameters.TARGET_SPACING * 8;
			Block b = payBigTo(genesiskey, BigInteger.valueOf(500000), addedBlocks);
			rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(b), defaultBlockWrap(b),
					currentTime, store);
			blockGraph.updateChain();
		}

		currentTime += NetworkParameters.TARGET_SPACING * 8;
		Block b = payBigTo(genesiskey, BigInteger.valueOf(500000), addedBlocks);
		rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(b), defaultBlockWrap(b),
				currentTime, store);
		blockGraph.updateChain();
		assertEquals(servicebase.getRewardInfo(rollingBlock).getDifficultyTargetAsInteger().divide(BigInteger.valueOf(4)),
				servicebase.getRewardInfo(highDifficultyBlock).getDifficultyTargetAsInteger());
	}

	// @Test
	public void testDifficultyTransitionPruned() throws Exception {
		serverConfiguration.setServermode("fullpruned");
		testDifficultyTransition2();
	}

	// @Test
	public void testDifficultyTransitionPruned3() throws Exception {
		serverConfiguration.setServermode("fullpruned");
		testDifficultyTransition3();
	}

	// Test difficulty transition
	// @Test
	public void testDifficultyTransition3() throws Exception {

		long currentTime = networkParameters.getGenesisBlock().getTimeSeconds();

		// Rewards way too fast -> maximum difficulty change to higher difficulty
		Block rollingBlock = networkParameters.getGenesisBlock();
		for (int i = 0; i < NetworkParameters.INTERVAL - 1; i++) {
			currentTime += NetworkParameters.TARGET_SPACING / 2;
			rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
					defaultBlockWrap(rollingBlock), currentTime, store);
			blockGraph.updateChain();
		}

		currentTime += NetworkParameters.TARGET_SPACING / 2;
		rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
				defaultBlockWrap(rollingBlock), currentTime, store);
		blockGraph.updateChain();
		ServiceBaseConnect servicebase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService,jsonmapper);
		assertTrue(servicebase.getRewardInfo(rollingBlock).getDifficultyTargetAsInteger()
				.compareTo(servicebase.getRewardInfo(networkParameters.getGenesisBlock()).getDifficultyTargetAsInteger()) < 0);
		Block highDifficultyBlock = rollingBlock;

		// Rewards way too fast -> maximum difficulty change to higher difficulty
		for (int i = 0; i < NetworkParameters.INTERVAL - 1; i++) {
			currentTime += NetworkParameters.TARGET_SPACING * 2;
			rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
					defaultBlockWrap(rollingBlock), currentTime, store);
			blockGraph.updateChain();
		}

		currentTime += NetworkParameters.TARGET_SPACING * 2;
		rollingBlock = rewardService.createReward(rollingBlock.getHash(), defaultBlockWrap(rollingBlock),
				defaultBlockWrap(rollingBlock), currentTime, store);
		blockGraph.updateChain();
		assertTrue(servicebase.getRewardInfo(rollingBlock).getDifficultyTargetAsInteger()
				.compareTo(servicebase.getRewardInfo(highDifficultyBlock).getDifficultyTargetAsInteger()) > 0);
	}

	public Block createReward(List<Block> blocksAddedAll) throws Exception {

		Block rollingBlock1 = addBlocks(5, blocksAddedAll);

		// Generate mining reward block
		Block rewardBlock1 = makeRewardBlock(networkParameters.getGenesisBlock().getHash());
		blockGraph.updateChain();
		blocksAddedAll.add(rewardBlock1);

		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getMilestone() == 1);

		// Generate more mining reward blocks
		blocksAddedAll.add(  makeRewardBlock(rewardBlock1));
		blockGraph.updateChain();
		return rewardBlock1;
	}

	public Block createReward2(List<Block> blocksAddedAll) throws Exception {
		addBlocks(5, blocksAddedAll);
		// Generate mining reward blocks
		Block rewardBlock2 = makeRewardBlock(networkParameters.getGenesisBlock().getHash());
		blockGraph.updateChain();
		blocksAddedAll.add(rewardBlock2); 
		Block rewardBlock3 = makeRewardBlock(rewardBlock2.getHash());
		blocksAddedAll.add(rewardBlock3);
		  rewardBlock3= makeRewardBlock(rewardBlock3.getHash());
		blocksAddedAll.add(rewardBlock3);
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).getMilestone() == 1);
		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).getMilestone() == 3);
		return rewardBlock3;
	}

	@Test
	// the switch to longest chain
	public void testReorgMiningReward() throws Exception {
		List<Block> a1 = new ArrayList<Block>();
		List<Block> a2 = new ArrayList<Block>();
		// first chains
		Block rewardBlock1 = createReward(a1);
		resetStore();
		// second chain
		Block rewardBlock3 = createReward2(a2);
		resetStore();
		// replay first chain
		for (Block b : a1)
			blockGraph.add(b, true, true, store);
		// add second chain
		for (Block b : a2)
			blockGraph.add(b, true, true, store);

		// assertFalse(getBlockEvaluation(rewardBlock1.getHash()).isConfirmed());
		assertTrue(!getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed()  );

		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).getMilestone() == 3);
		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).isConfirmed());

	}

	@Test
	// out of order added blocks will have the same results
	public void testReorgMiningRewardShuffle() throws Exception {
		List<Block> blocksAddedAll = new ArrayList<Block>();
		List<Block> a1 = new ArrayList<Block>();
		List<Block> a2 = new ArrayList<Block>();

		Block rewardBlock1 = createReward(a1);
		resetStore();
		Block rewardBlock3 = createReward2(a2);
		store.resetStore();
		blocksAddedAll.addAll(a1);
		blocksAddedAll.addAll(a2);

		for (int i = 0; i < 5; i++) {

			// Check add in random order
			Collections.shuffle(blocksAddedAll);

			resetStore();
			// add many times to get chain out of order
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				blockGraph.add(b, true, true, store);
			syncBlockService.connectingOrphans(store);

			assertFalse(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
	//TODO		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getMilestone() == -1);

			assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).getMilestone() == 3);
			assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).isConfirmed());

			// mcmc can not change the status of chain
			mcmcServiceUpdate();

			assertFalse(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
			assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).isConfirmed());
		}
	}

	// test wrong chain with fixed graph and required blocks
	@Test
	public void testReorgMiningRewardWrong() throws Exception {
		// reset to start on node 2
		store.resetStore();
		List<Block> blocksAddedAll = new ArrayList<Block>();
		Block rewardBlock1 = createReward(blocksAddedAll);
		blockGraph.updateChain();
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getMilestone() == 1);

		// Block rollingBlock1 = addFixedBlocks(5, networkParameters.getGenesisBlock(),
		// blocksAddedAll);

		// Generate more mining reward blocks
		Block rewardBlock2 = rewardService.createReward(rewardBlock1.getHash(), defaultBlockWrap(blocksAddedAll.get(0)),
				defaultBlockWrap(blocksAddedAll.get(0)), store);
		blockGraph.updateChain();
		blocksAddedAll.add(rewardBlock2);

		// assertTrue(getBlockEvaluation(rewardBlock1.getHash()).isConfirmed());
		assertTrue(!getBlockEvaluation(rewardBlock2.getHash(), store).isConfirmed());

	}

	// test cutoff chains, reward should not take blocks behind the cutoff chain
	/*
	 * the last block of the chain should not have referenced block behind the the
	 * cutoff height
	 * Stop at the check and select of cutoff height, no exception, this is not a real attack to rewrite the chain
	 */
	//@Test
	public void testReorgMiningRewardCutoff() throws Exception {

		List<Block> blocksAddedAll = new ArrayList<Block>();

		Block rewardBlock2 = makeRewardBlock(blocksAddedAll);

		for (int i = 0; i < NetworkParameters.MILESTONE_CUTOFF + 5; i++) {
			rewardBlock2 = makeRewardBlock(rewardBlock2.getHash());
		}
	
		Block rollingBlock2 = addFixedBlocks(1, networkParameters.getGenesisBlock(), blocksAddedAll,
				wallet.feeTransaction(null));
	 
		// rewardBlock3 takes the long block graph behind cutoff
		try {
			rewardService.createReward(rewardBlock2.getHash(), defaultBlockWrap(rollingBlock2),
					defaultBlockWrap(rollingBlock2), store);
			blockGraph.updateChain();
			fail();
		} catch (VerificationException e) {
			// TODO: handle exception
		}

		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).getMilestone() >= 0);

	}

	// generate a list of block using mcmc and return the last block
	private Block addBlocks(int num, List<Block> blocksAddedAll) throws BlockStoreException, JsonProcessingException,
			IOException, UTXOProviderException, InsufficientMoneyException, InterruptedException, ExecutionException {
		// add more blocks using mcmc
		Block rollingBlock1 = null;
		for (int i = 0; i < num; i++) {
			// rollingBlock1 = rollingBlock1.createNextBlock(rollingBlock1);
			mcmcService.update(store); 
			blockGraph.confirmDo(store);
			HashMap<String, String> requestParam = new HashMap<String, String>();
			byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
					Json.jsonmapper().writeValueAsString(requestParam));
			rollingBlock1 = networkParameters.getDefaultSerializer().makeBlock(data);
			rollingBlock1.addTransaction(wallet.feeTransaction(null));
			rollingBlock1.solve();
			blockGraph.add(rollingBlock1, true, store);
			blocksAddedAll.add(rollingBlock1);
		}
		return rollingBlock1;
	}

	// @Test
	public void blocksFromChainlength() throws Exception {
		// create some blocks
		// testReorgMiningReward();

		HashMap<String, Object> request = new HashMap<String, Object>();
		request.put("start", "0");
		request.put("end", "0");
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.blocksFromChainLength.name(),
				Json.jsonmapper().writeValueAsString(request).getBytes());

		GetBlockListResponse blockListResponse = Json.jsonmapper().readValue(response, GetBlockListResponse.class);

		// log.info("searchBlock resp : " + response);
		assertTrue(blockListResponse.getBlockbytelist().size() > 0);

		for (byte[] data : blockListResponse.getBlockbytelist()) {
			blockService.addConnected(data, false);
		}
	}

}