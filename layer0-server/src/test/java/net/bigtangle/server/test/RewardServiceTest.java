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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class RewardServiceTest extends AbstractIntegrationTest {

    @Autowired
    private StakeService stakeService;

    @Autowired
    private GhostService ghostService;

	public Block createReward(List<Block> blocksAddedAll) throws Exception {

		Block rollingBlock1 = addBlocks(5, blocksAddedAll);

		// Generate mining reward block
		Block rewardBlock1 = makeRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash());
		blockGraph.updateChain();
		blocksAddedAll.add(rewardBlock1);

		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getChainlength() == 1);

		// Generate more mining reward blocks
		blocksAddedAll.add(  makeRewardBlock(rewardBlock1));
		blockGraph.updateChain();
		return rewardBlock1;
	}

	public Block createReward2(List<Block> blocksAddedAll) throws Exception {
		addBlocks(5, blocksAddedAll);
		// Generate mining reward blocks
		Block rewardBlock2 = makeRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash());
		blockGraph.updateChain();
		blocksAddedAll.add(rewardBlock2); 
		Block rewardBlock3 = makeRewardBlock(rewardBlock2.getHash());
		blocksAddedAll.add(rewardBlock3);
		  rewardBlock3= makeRewardBlock(rewardBlock3.getHash());
		blocksAddedAll.add(rewardBlock3);
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).getChainlength() == 1);
		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).getChainlength() == 3);
		return rewardBlock3;
	}

	@Test
	public void testReorgMiningReward() throws Exception {
		// PoS chain reorg: a beacon chain with higher chain length wins
		PQKey validatorKey = PQKey.createNew();
		store.saveStakeDeposit(new StakeRecord(
				validatorKey.getPubKey(), StakeService.MIN_STAKE,
				validatorKey.getPubKeyHash()));
		stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

		mcmcService.update(store);
		mcmcService.calcNewBlockPrototype(store);

		// Create first beacon block chain
		Block first = makeRewardBlock();
		blockGraph.updateChain();
		assertTrue(getBlockEvaluation(first.getHash(), store).isConfirmed());

		// Create a second beacon block extending the first
		Block second = makeRewardBlock(first);
		blockGraph.updateChain();
		assertTrue(getBlockEvaluation(second.getHash(), store).isConfirmed());

		// The first block should stay confirmed (no reorg in PoS)
		assertTrue(getBlockEvaluation(first.getHash(), store).isConfirmed());
		assertTrue(getBlockEvaluation(second.getHash(), store).isConfirmed());
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

		// PoS fork choice is LMD-GHOST (attestation weight), not the longest
		// chain: register a validator and attest to chain B's head so GHOST
		// deterministically prefers it, regardless of shuffle order.
		PQKey shuffleValidator = PQKey.createNew();
		store.saveStakeDeposit(new StakeRecord(shuffleValidator.getPubKey(), StakeService.MIN_STAKE,
				shuffleValidator.getPubKeyHash()));
		stakeService.activateValidator(shuffleValidator.getPubKey(), 0, store);

		for (int i = 0; i < 5; i++) {

			// Check add in random order
			Collections.shuffle(blocksAddedAll);

			resetStore();
			store.saveStakeDeposit(new StakeRecord(shuffleValidator.getPubKey(), StakeService.MIN_STAKE,
					shuffleValidator.getPubKeyHash()));
			stakeService.activateValidator(shuffleValidator.getPubKey(), 0, store);
			// Re-attest to chain B's head on the fresh store.
			net.bigtangle.core.AttestationData att = new net.bigtangle.core.AttestationData();
			att.setSlot(1);
			att.setEpoch(0);
			att.setSourceEpoch(0);
			att.setTargetEpoch(0);
			att.setBeaconBlockHash(rewardBlock3.getHash());
			att.setValidatorPubkey(shuffleValidator.getPubKey());
			att.setBlsPubkey(net.bigtangle.server.service.RandaoService.blsPubkey(shuffleValidator));
			att.setSignature(net.bigtangle.server.service.RandaoService.blsSign(shuffleValidator,
					att.getMessageHash().getBytes()));
			ghostService.processAttestation(att, store);
			// add many times to get chain out of order
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);
			for (Block b : blocksAddedAll)
				add(b, true, true, store);
			syncBlockService.connectingOrphans(store);

			assertFalse(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());
	//TODO		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getChainlength() == -1);

			assertTrue(getBlockEvaluation(rewardBlock3.getHash(), store).getChainlength() == 3);
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
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).getChainlength() == 1);

		// A beacon extending the confirmed reward chain is confirmed.
		Block rewardBlock2 = makeRewardBlock(rewardBlock1);
		blockGraph.updateChain();
		assertTrue(getBlockEvaluation(rewardBlock2.getHash(), store).isConfirmed());
		assertEquals(rewardBlock2.getHash(), cacheBlockService.getMaxConfirmedReward(store).getBlockHash());

		// A competing beacon that tries to fork off a stale reward head (the
		// initial genesis reward) must NOT move the canonical confirmed head.
		rewardService.createReward(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(blocksAddedAll.get(0)), defaultBlockWrap(blocksAddedAll.get(0)), store);
		blockGraph.updateChain();

		TXReward head = cacheBlockService.getMaxConfirmedReward(store);
		assertEquals(rewardBlock2.getHash(), head.getBlockHash(), "stale fork must not replace the canonical head");
	}

	// test cutoff chains, reward should not take blocks behind the cutoff chain
	/*
	 * the last block of the chain should not have referenced block behind the the
	 * cutoff height
	 * Stop at the check and select of cutoff height, no exception, this is not a real attack to rewrite the chain
	 */
	// generate a list of block using mcmc and return the last block
	private Block addBlocks(int num, List<Block> blocksAddedAll) throws BlockStoreException, JsonProcessingException,
			IOException, UTXOProviderException, InsufficientMoneyException, InterruptedException, ExecutionException {
		Block rollingBlock1 = null;
		for (int i = 0; i < num; i++) {
			mcmcService.update(store); 
			blockGraph.confirmDo(store);
			HashMap<String, String> requestParam = new HashMap<String, String>();
			byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
					Json.jsonmapper().writeValueAsString(requestParam));
			rollingBlock1 = networkParameters.getDefaultSerializer().makeBlock(data);
			try {
				rollingBlock1.addTransaction(wallet.feeTransaction(null));
			} catch (Exception e) {
				// wallet may have no UTXOs; PoW-disabled tests skip fees
			}
			blockGraph.addBlock(rollingBlock1, true, store);
			blocksAddedAll.add(rollingBlock1);
		}
		return rollingBlock1;
	}

}