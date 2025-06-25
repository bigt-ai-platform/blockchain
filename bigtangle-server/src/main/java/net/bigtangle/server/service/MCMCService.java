/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
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
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.Rating;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/*
 *  This service offers maintenance functions to update the local mcmc state of the Tangle
 */
@Service
public class MCMCService {
	private final String LOCKID = this.getClass().getName();

	private static final Logger log = LoggerFactory.getLogger(MCMCService.class);

	@Autowired
	protected BlockStoreService blockGraph;

	@Autowired
	private TipsService tipsService;

	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	private ServerConfiguration serverConfiguration;

	@Autowired
	protected NetworkParameters networkParameters;

	@Autowired
	private StoreService storeService;
	@Autowired
	private ScheduleConfiguration scheduleConfiguration;
	@Autowired
	protected ObjectMapper jsonmapper;

	public void startSingleProcess() throws BlockStoreException {
		// ExecutorService executor = Executors.newSingleThreadExecutor();

		ExecutorService executor = Executors.newSingleThreadExecutor();

		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit((Callable) () -> {
			startSingleProcessDo();
			return "finish";
		});
		try {
			handler.get(scheduleConfiguration.getMcmcrate() * 5, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			// log.debug(" mcmcService Timeout ");
			handler.cancel(true);
		} catch (InterruptedException e) {
			// log.debug(" mcmcService ", e);
		} catch (Exception e) {
			log.debug(" mcmcService     ", e);
		} finally {
			executor.shutdownNow();
		}

	}

	public void startSingleProcessDo() throws BlockStoreException {
		BlockStoreInterface store = storeService.getStore();
		try {
			// log.info("mcmcService started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else if (lock.getLocktime() < System.currentTimeMillis() - scheduleConfiguration.getMcmcrate() * 100) {
				log.info("mcmcService   out date delete and insert: {}", Utils.dateTimeFormat(lock.getLocktime()));
				store.deleteLockobject(LOCKID);
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else {
				// log.info("mcmcService running at start = " +
				// Utils.dateTimeFormat(lock.getLocktime()));
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				update(store);
				store.deleteLockobject(LOCKID);
				// if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000)
				// log.info("mcmcService time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
				watch.stop();
			}
		} catch (Exception e) {
			log.error("mcmcService ", e);
			if (!e.getLocalizedMessage().contains("java.sql.SQLIntegrityConstraintViolationException")) {
				store.deleteLockobject(LOCKID);
			}
		} finally {
			store.close();
		}

	}

	public void update(BlockStoreInterface store) throws InterruptedException, ExecutionException, BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
			long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, store);
			long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, store);
			updateWeightAndDepth(cutoffHeight, maxHeight, store);
			updateRating(maxConfirmedReward, cutoffHeight, maxHeight, store);
			deleteMCMC(maxConfirmedReward, store);
			cacheBlockService.evictBlockMCMC();
			cacheBlockPrototypeService.evictBlockPrototypeByte();
			// generate new
			cacheBlockPrototypeService.getBlockPrototype(store);
		} catch (Exception e) {
			log.debug("update  ", e);
		}

	}

	private void deleteMCMC(TXReward maxConfirmedReward, BlockStoreInterface store) throws BlockStoreException {
		store.deleteMCMC(maxConfirmedReward.getChainLength() - NetworkParameters.MILESTONE_CUTOFF);
	}

	/**
	 * Update cumulative weight: the amount of blocks a block is approved by. Update
	 * depth: the longest chain of blocks to a tip. Allows unsolid blocks too.
	 *
	 */
	private void updateWeightAndDepth(long cutoffHeight, long maxHeight, BlockStoreInterface store)
			throws BlockStoreException {
		// Begin from the highest maintained height blocks and go backwards from
		// there
		PriorityQueue<BlockWrap> blockQueue = store.getSolidBlocksInIntervalDescending(cutoffHeight, maxHeight);
		HashMap<Sha256Hash, HashSet<Sha256Hash>> approvers = new HashMap<>();
		HashMap<Sha256Hash, Long> depths = new HashMap<>();

		// Initialize weight and depth of blocks
		for (BlockWrap block : blockQueue) {
			approvers.put(block.getBlockHash(), new HashSet<>());
			depths.put(block.getBlockHash(), 0L);
		}

		BlockWrap currentBlock;
		List<DepthAndWeight> depthAndWeight = new ArrayList<>();
		while ((currentBlock = blockQueue.poll()) != null) {
			Sha256Hash currentBlockHash = currentBlock.getBlockHash();

			// Abort if unmaintained, since it will be irrelevant for any tip
			// selections
			if (currentBlock.getBlockEvaluation().getHeight() <= cutoffHeight)
				continue;

			// Add your own hash to approver hashes of current approver hashes
			approvers.get(currentBlockHash).add(currentBlockHash);

			// Add all current references to both approved blocks
			Sha256Hash prevTrunk = currentBlock.getBlock().getPrevBlockHash();
			subUpdateWeightAndDepth(blockQueue, approvers, depths, currentBlockHash, prevTrunk, store);

			Sha256Hash prevBranch = currentBlock.getBlock().getPrevBranchBlockHash();
			subUpdateWeightAndDepth(blockQueue, approvers, depths, currentBlockHash, prevBranch, store);

			// Update and dereference
			// TODO reduce the update
			// if (currentBlock.getBlockEvaluation().getMilestone() < 0) {
			depthAndWeight.add(new DepthAndWeight(currentBlock.getBlockHash(), approvers.get(currentBlockHash).size(),
					depths.get(currentBlockHash)));

			approvers.remove(currentBlockHash);
			depths.remove(currentBlockHash);
		}
		store.updateBlockEvaluationWeightAndDepth(depthAndWeight);
	}

	private void subUpdateWeightAndDepth(PriorityQueue<BlockWrap> blockQueue,
			HashMap<Sha256Hash, HashSet<Sha256Hash>> approvers, HashMap<Sha256Hash, Long> depths,
			Sha256Hash currentBlockHash, Sha256Hash approvedBlockHash, BlockStoreInterface store)
			throws BlockStoreException {
		Long currentDepth = depths.get(currentBlockHash);
		HashSet<Sha256Hash> currentApprovers = approvers.get(currentBlockHash);
		if (!approvers.containsKey(approvedBlockHash)) {
			BlockWrap prevBlock = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,
					jsonmapper).getBlockWrap(approvedBlockHash, store);
			if (prevBlock != null) {
				blockQueue.add(prevBlock);
				approvers.put(approvedBlockHash, new HashSet<>(currentApprovers));
				depths.put(approvedBlockHash, currentDepth + 1);
			}
		} else {
			approvers.get(approvedBlockHash).addAll(currentApprovers);
			if (currentDepth + 1 > depths.get(approvedBlockHash))
				depths.put(approvedBlockHash, currentDepth + 1);
		}
	}

	/**
	 * Update rating: the percentage of times that tips selected by MCMC approve a
	 * block. Allows unsolid blocks too.
	 *
	 */
	private void updateRating(TXReward maxConfirmedReward, long cutoffHeight, long maxHeight, BlockStoreInterface store)
			throws BlockStoreException {
		// Select #tipCount solid tips via MCMC
		HashMap<Sha256Hash, HashSet<UUID>> selectedTipApprovers = new HashMap<>(NetworkParameters.NUMBER_RATING_TIPS);

		Collection<BlockWrap> selectedTips = tipsService.getRatingTips(maxConfirmedReward,
				NetworkParameters.NUMBER_RATING_TIPS, maxHeight, store);

		// Initialize all approvers as UUID
		for (BlockWrap selectedTip : selectedTips) {
			UUID randomUUID = UUID.randomUUID();
			if (selectedTipApprovers.containsKey(selectedTip.getBlockHash())) {
				HashSet<UUID> result = selectedTipApprovers.get(selectedTip.getBlockHash());
				result.add(randomUUID);
			} else {
				HashSet<UUID> result = new HashSet<>();
				result.add(randomUUID);
				selectedTipApprovers.put(selectedTip.getBlockHash(), result);
			}
		}

		// Begin from the highest solid height tips plus selected tips and go
		// backwards from there
		PriorityQueue<BlockWrap> blockQueue = store.getSolidBlocksInIntervalDescending(cutoffHeight, maxHeight);
		HashSet<BlockWrap> selectedTipSet = new HashSet<>(selectedTips);
		selectedTipSet.removeAll(blockQueue);
		blockQueue.addAll(selectedTipSet);
		HashMap<Sha256Hash, HashSet<UUID>> approvers = new HashMap<>();
		for (BlockWrap tip : blockQueue) {
			approvers.put(tip.getBlock().getHash(), new HashSet<>());
		}

		BlockWrap currentBlock;
		List<Rating> ratings = new ArrayList<>();
		while ((currentBlock = blockQueue.poll()) != null) {
			// Abort if unmaintained
			if (currentBlock.getBlockEvaluation().getHeight() <= cutoffHeight)
				continue;

			// Add your own hashes as reference if current block is one of the
			// selected tips
			if (selectedTipApprovers.containsKey(currentBlock.getBlockHash()))
				approvers.get(currentBlock.getBlockHash())
						.addAll(selectedTipApprovers.get(currentBlock.getBlockHash()));

			// Add all current references to both approved blocks (initialize if
			// not yet initialized)
			Sha256Hash prevTrunk = currentBlock.getBlock().getPrevBlockHash();
			subUpdateRating(blockQueue, approvers, currentBlock, prevTrunk, store);

			Sha256Hash prevBranch = currentBlock.getBlock().getPrevBranchBlockHash();
			subUpdateRating(blockQueue, approvers, currentBlock, prevBranch, store);

			// Update your rating if solid
			if (currentBlock.getBlockEvaluation().getSolid() == 2)
			// && currentBlock.getBlockEvaluation().getMilestone() < 0 )
			{
				ratings.add(new Rating(currentBlock.getBlockHash(), approvers.get(currentBlock.getBlockHash()).size()));
			}
			approvers.remove(currentBlock.getBlockHash());
		}
		store.updateBlockEvaluationRating(ratings);

	}

	private void subUpdateRating(PriorityQueue<BlockWrap> blockQueue, HashMap<Sha256Hash, HashSet<UUID>> approvers,
			BlockWrap currentBlock, Sha256Hash prevTrunk, BlockStoreInterface store) throws BlockStoreException {
		if (!approvers.containsKey(prevTrunk)) {
			BlockWrap prevBlock = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,
					jsonmapper).getBlockWrap(prevTrunk, store);
			if (prevBlock != null) {
				blockQueue.add(prevBlock);
				approvers.put(prevBlock.getBlockHash(), new HashSet<>(approvers.get(currentBlock.getBlockHash())));
			}
		} else {
			approvers.get(prevTrunk).addAll(approvers.get(currentBlock.getBlockHash()));
		}
	}

}
