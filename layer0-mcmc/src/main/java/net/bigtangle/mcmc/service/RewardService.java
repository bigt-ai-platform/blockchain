/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.service;

import java.math.BigInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.VerificationException.CutoffException;
import net.bigtangle.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.service.base.ServiceBaseConnect.RewardBuilderResult;
import net.bigtangle.server.service.base.ServiceBaseReward;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.BlockServiceCreate;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.mcmc.service.TipsService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/**
 * <p>
 * A RewardService provides service for create and validate the reward chain.
 * </p>
 */
@Service
public class RewardService {

	@Autowired
	protected BlockStoreService blockGraph;
	@Autowired
	private BlockService blockService;
	@Autowired
	private BlockServiceCreate blockServiceCreate;
	@Autowired
	protected TipsService tipService;
	@Autowired
	protected ServerConfiguration serverConfiguration;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	private StoreService storeService;
	@Autowired
	private ScheduleConfiguration scheduleConfiguration;

	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;

	@Autowired
	private BlockSaveService blockSaveService;
	@Autowired
	protected ObjectMapper jsonmapper;

	@Value("${service.schedule.rewardonlywithreferenced:true}")
	private boolean onlyWithreferenced;

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final String LOCKID = this.getClass().getName();

	/**
	 * Scheduled update function that updates the Tangle
	 *
	 */

	// createReward is time boxed and can run parallel.
	public void startSingleProcess() throws BlockStoreException {

		BlockStoreInterface store = storeService.getStore();

		try {
			// log.info("create Reward started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else {
				long timeout = 15 * 50000L;
				if (lock.getLocktime() < System.currentTimeMillis() - timeout) {
					log.info(" reward locked is fored delete   {} < {}", lock.getLocktime(),
							System.currentTimeMillis() - timeout);
					store.deleteLockobject(LOCKID);
					store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
					canrun = true;
				} else {
					log.info("reward running return:  {}", Utils.dateTimeFormat(lock.getLocktime()));
				}
			}
			if (canrun) {
				createReward(store);
				store.deleteLockobject(LOCKID);
			}

		} catch (Exception e) {
			log.error("create Reward end  ", e);
			store.deleteLockobject(LOCKID);
		} finally {
			store.close();
		}

	}

	/**
	 * Runs the reward making logic
	 */

	public void createReward(BlockStoreInterface store) throws Exception {

		Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
		Block reward = createReward(prevRewardHash, store);
		if (reward != null) {
			log.debug(" reward block is created: {}", reward);
		}
	}

	public Block createReward(Sha256Hash prevRewardHash, BlockStoreInterface store) throws Exception {
		try {
			Stopwatch watch = Stopwatch.createStarted();
			ServiceBaseReward serviceBase = new ServiceBaseReward(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper); 
			Block prototypeblock = cacheBlockPrototypeService.getBlockPrototype(store); 
			log.debug("  getValidatedRewardBlockPair time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS)); 
			return createReward(prevRewardHash, serviceBase.getBlockWrap(prototypeblock.getPrevBlockHash(), store),
					serviceBase.getBlockWrap(prototypeblock.getPrevBranchBlockHash(), store), store);
		} catch (CutoffException | InfeasiblePrototypeException | NullPointerException e) {
			// fall back to use prev reward as tip
			log.debug(" fall back to use prev reward as tip: ", e);
			BlockWrap prevreward = blockService.getBlockWrap(prevRewardHash, store);
			return createReward(prevRewardHash, prevreward, prevreward, store);
		}
	}

	public Block createReward(Sha256Hash prevRewardHash, BlockWrap prevTrunk, BlockWrap prevBranch,
			BlockStoreInterface store) throws Exception {
		return createReward(prevRewardHash, prevTrunk, prevBranch, null, store);
	}

	public Block createReward(Sha256Hash prevRewardHash, BlockWrap prevTrunk, BlockWrap prevBranch, Long timeOverride,
			BlockStoreInterface store) throws Exception {

		Block block = createMiningRewardBlock(prevRewardHash, prevTrunk, prevBranch, timeOverride, onlyWithreferenced, store);

		if (block != null) {
			// check, if the reward block is too old to avoid conflict.
			TXReward latest = cacheBlockService.getMaxConfirmedReward(store);
			if (latest.getChainLength() > block.getLastMiningRewardBlock()) {
				log.debug("resolved Reward is out of date.");
			} else {
				sendBlockToServer(block,store);
			}
		}
		return block;
	}

	public Block createMiningRewardBlock(Sha256Hash prevRewardHash, BlockWrap prevTrunk, BlockWrap prevBranch,
			boolean onlyWithreferenced, BlockStoreInterface store)
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException {
		return createMiningRewardBlock(prevRewardHash, prevTrunk, prevBranch, null, onlyWithreferenced, store);
	}

	public Block createMiningRewardBlock(Sha256Hash prevRewardHash, BlockWrap prevTrunk, BlockWrap prevBranch,
			Long timeOverride, boolean onlyWithreferenced, BlockStoreInterface store)
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException {
		Stopwatch watch = Stopwatch.createStarted();
		ServiceBaseReward serviceBase = new ServiceBaseReward(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper);

		Block r1 = prevTrunk.getBlock();
		Block r2 = prevBranch.getBlock();
		Block prevRewardBlock = serviceBase.getBlock(prevRewardHash, store);
		long currentTime = Math.max(System.currentTimeMillis() / 1000,
				Math.max(prevRewardBlock.getTimeSeconds(), Math.max(r1.getTimeSeconds(), r2.getTimeSeconds())));
		if (timeOverride != null)
			currentTime = timeOverride;

		Block block = Block.createBlock(networkParameters, r1, r2);

		block.setBlockType(BlockType.BLOCKTYPE_BEACON);
		block.setHeight(Math.max(prevRewardBlock.getHeight(), Math.max(r1.getHeight(), r2.getHeight())) + 1);

		RewardBuilderResult result = serviceBase.calcRewardInfo(prevTrunk, prevBranch, prevRewardHash, currentTime, store);

		Transaction tx = result.getTx();
		RewardInfo currRewardInfo = new RewardInfo().parseChecked(tx.getData());
		block.setLastMiningRewardBlock(currRewardInfo.getChainlength());

		// Enforce timestamp equal to previous max for reward blocktypes
		block.setTime(currentTime);

		// Add all non-BEACON blocks from TipsQueue to collectedBlocks so they get
		// properly confirmed. dagBlockHashesFrom only walks backward (predecessors),
		// so child/sibling blocks (e.g. newly created tokens) are never reached.
		try {
			List<net.bigtangle.server.data.TipsQueue> allTips = store.getAllTipsQueue();
			for (net.bigtangle.server.data.TipsQueue tq : allTips) {
				Block tipBlock = networkParameters.getDefaultSerializer().makeBlock(tq.getBlock());
				if (tipBlock.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
					Sha256Hash tipHash = tipBlock.getHash();
					if (!currRewardInfo.getBlocks().contains(tipHash)) {
						currRewardInfo.getBlocks().add(tipHash);
					}
				}
			}
		} catch (Exception e) {
			log.debug("Could not add TipsQueue blocks to collectedBlocks: {}", e.getMessage());
		}

		block.addTransaction(tx);
		if (currRewardInfo.getBlocks().isEmpty() && onlyWithreferenced) {
			log.debug("   no referenced blocks skip createReward  time {} ms.",
					watch.elapsed(TimeUnit.MILLISECONDS));
			return null;
		}
		// Mining reward removed — epoch-based via EpochRewardService
		// miningTx removed; epoch-based rewards
		tx.setData(currRewardInfo.toByteArray());

		blockServiceCreate.adjustHeightRequiredBlocks(block, store);
		log.debug("prepare Reward time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
		return block;
	}

	public long calculateNextBlockDifficulty(RewardInfo currRewardInfo) {
		return networkParameters.getDifficultyLimitCompact();
	}

	public GetTXRewardResponse getMaxConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		return GetTXRewardResponse.create(cacheBlockService.getMaxConfirmedReward(store));

	}

	public GetTXRewardListResponse getAllConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		return GetTXRewardListResponse.create(store.getAllConfirmedReward());

	}

	private void sendBlockToServer(Block block, BlockStoreInterface store) throws Exception {
		// Get the server URL from configuration (e.g., "http://test-bigtangle-server:8088")
		String serverUrl = serverConfiguration.getServerurl();

		if (serverUrl == null || serverUrl.isEmpty()) {
			log.warn("SERVER_URL not configured, falling back to direct save");
			blockSaveService.saveBlock(block, store);
			return;
		}

		log.debug("Reward block saved locally: {}", block.getHashAsString());
	}

}
