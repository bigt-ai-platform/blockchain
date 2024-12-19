/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class TipsService {

	private final Logger log = LoggerFactory.getLogger(TipsService.class);

	@Autowired
	private ServerConfiguration serverConfiguration;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected ObjectMapper jsonmapper;

	private static final Random seed = new Random();

	private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	/**
	 * A job submitted to the executor which finds a rating tip.
	 */
	private class RatingTipWalker implements Callable<BlockWrap> {
		final BlockWrap entryPoint;
		long maxHeight;
		final BlockStoreInterface store;

		public RatingTipWalker(final BlockWrap entryPoint, long maxHeight, BlockStoreInterface store) {
			this.entryPoint = entryPoint;
			this.maxHeight = maxHeight;
			this.store = store;
		}

		@Override
		public BlockWrap call() throws Exception {
			return getRatingTip(entryPoint, Long.MAX_VALUE, maxHeight, store);
		}
	}

	/**
	 * Performs MCMC without walker restrictions. Note: We cannot disallow blocks
	 * conflicting with the milestone, since reorgs must be allowed to happen. We
	 * cannot check if given blocks are eligible without the milestone since that is
	 * not efficiently computable. Hence allows unsolid blocks.
	 * 
	 * @param count The number of rating tips.
	 * @return A list of rating tips.
	 */
	public Collection<BlockWrap> getRatingTips(TXReward maxConfirmedReward, int count, long maxHeight,
			BlockStoreInterface store) throws BlockStoreException {
		Stopwatch watch = Stopwatch.createStarted();

		List<BlockWrap> entryPoints = getEntryPoints(count, maxConfirmedReward.getChainLength(), store);
		List<Future<BlockWrap>> ratingTipFutures = new ArrayList<>(count);
		List<BlockWrap> ratingTips = new ArrayList<>(count);

		for (BlockWrap entryPoint : entryPoints) {
			FutureTask<BlockWrap> future = new FutureTask<>(new RatingTipWalker(entryPoint, maxHeight, store));
			executor.execute(future);
			ratingTipFutures.add(future);
		}

		for (Future<BlockWrap> future : ratingTipFutures) {
			try {
				ratingTips.add(future.get());
			} catch (InterruptedException thrownE) {
				// cancel with timeout
				// throw new RuntimeException(thrownE); // Shouldn't happen
			} catch (ExecutionException thrownE) {
				throw new BlockStoreException(thrownE); // Shouldn't happen
			}
		}

		watch.stop();
		log.trace("getRatingTips with count {} time {} ms.", count, watch.elapsed(TimeUnit.MILLISECONDS));

		return ratingTips;
	}

	/**
	 * Selects two blocks to approve via MCMC. Disallows unsolid blocks.
	 * 
	 * @return Two blockhashes selected via MCMC
	 */
	public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(BlockStoreInterface store) throws BlockStoreException {

		return getValidatedBlockPair(cacheBlockService.getMaxConfirmedReward(store), new HashSet<>(), store);
	}


	/**
	 * Selects two blocks to approve via MCMC. Disallows unsolid blocks.
	 * 
	 * @return Two blockhashes selected via MCMC
	 */
	public Pair<BlockWrap, BlockWrap> getValidatedRewardBlockPair(Sha256Hash prevRewardHash, BlockStoreInterface store)
			throws BlockStoreException {
		return getValidatedRewardBlockPair(cacheBlockService.getMaxConfirmedReward(store), new HashSet<>(),
				prevRewardHash, store);
	}

	private Pair<BlockWrap, BlockWrap> getValidatedRewardBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, Sha256Hash prevRewardHash, BlockStoreInterface store)
			throws BlockStoreException {

		Pair<BlockWrap, BlockWrap> candidate = getValidatedRewardBlockPairDo(maxConfirmedReward,
				currentApprovedNonMilestoneBlocks, prevRewardHash, store);
		if (!candidate.getLeft().equals(candidate.getRight())) {
			return candidate;
		}
		for (int i = 0; i < 5; i++) {
			Pair<BlockWrap, BlockWrap> paar = getValidatedRewardBlockPairDo(maxConfirmedReward, new HashSet<>(),
					prevRewardHash, store);
			if (!paar.getLeft().equals(paar.getRight())) {
				return paar;
			}
		}
		return candidate;
	}

	private Pair<BlockWrap, BlockWrap> getValidatedRewardBlockPairDo(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, Sha256Hash prevRewardHash, BlockStoreInterface store)
			throws BlockStoreException {
		List<BlockWrap> entryPoints = getEntryPoints(2, maxConfirmedReward.getChainLength(), store);
		BlockWrap left = entryPoints.get(0);
		BlockWrap right = entryPoints.get(1);
		return getValidatedRewardBlockPair(currentApprovedNonMilestoneBlocks, left, right, prevRewardHash, store);
	}

	private Pair<BlockWrap, BlockWrap> getValidatedRewardBlockPair(HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			BlockWrap left, BlockWrap right, Sha256Hash prevRewardHash, BlockStoreInterface store)
			throws BlockStoreException {
		Stopwatch watch = Stopwatch.createStarted();
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper);
		long cutoffHeight = serviceBase.getRewardCutoffHeight(prevRewardHash, store);
		long maxHeight = serviceBase.getRewardMaxHeight();
		long prevMilestoneNumber = store.getRewardChainLength(prevRewardHash);
		HashSet<BlockWrap> currentNewMilestoneBlocks = new HashSet<>();

		// Initialize approved blocks
		if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");
		if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");
		if (!serviceBase.addReferencedBlockHashesTo(currentNewMilestoneBlocks, left, cutoffHeight, prevMilestoneNumber,
				null, false, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");
		if (!serviceBase.addReferencedBlockHashesTo(currentNewMilestoneBlocks, right, cutoffHeight, prevMilestoneNumber,
				null, false, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");

		// Necessary: Initial test if the prototype's
		// currentApprovedNonMilestoneBlocks are actually valid
		if (!serviceBase.isEligibleForApprovalSelection(currentApprovedUnconfirmedBlocks, store))
			throw new InfeasiblePrototypeException("The given prototype is invalid under the current milestone");

		return getValidatedBlockPair(currentApprovedUnconfirmedBlocks, left, right, store, watch, serviceBase,
				cutoffHeight, maxHeight);
	}

	public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, BlockStoreInterface store)
			throws BlockStoreException {
		List<BlockWrap> entryPoints = getEntryPoints(2, maxConfirmedReward.getChainLength(), store);
		BlockWrap left = entryPoints.get(0);
		BlockWrap right = entryPoints.get(1);
		return getValidatedBlockPair(maxConfirmedReward, currentApprovedNonMilestoneBlocks, left, right, store);
	}

	private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedUnconfirmedBlocks, BlockWrap left, BlockWrap right,
			BlockStoreInterface store) throws BlockStoreException {
		Stopwatch watch = Stopwatch.createStarted();
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper);
		long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, store);
		long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, store);

		// Initialize approved blocks
		if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");
		if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store))
			throw new InfeasiblePrototypeException("The given starting points are insolid");

		// Necessary: Initial test if the prototype's
		// currentApprovedNonMilestoneBlocks are actually valid

		if (!serviceBase.isEligibleForApprovalSelection(currentApprovedUnconfirmedBlocks, store))
			throw new InfeasiblePrototypeException("The given prototype is invalid under the current milestone");

		return getValidatedBlockPair(currentApprovedUnconfirmedBlocks, left, right, store, watch, serviceBase,
				cutoffHeight, maxHeight);
	}

	private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			BlockWrap left, BlockWrap right, BlockStoreInterface store, Stopwatch watch, ServiceBaseConnect serviceBase,
			long cutoffHeight, long maxHeight) throws BlockStoreException {
		// Perform next steps
		BlockWrap nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight,
				store);
		BlockWrap nextRight = performValidatedStep(right, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight,
				store);

		// Repeat: Proceed on path to be included first (highest rating else
		// random)
		while (nextLeft != left && nextRight != right) {
			if (nextLeft.getMcmc().getRating() > nextRight.getMcmc().getRating()) {
				// Go left
				left = nextLeft;
				if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight,
						store))
					throw new InfeasiblePrototypeException(
							"Shouldn't happen: block is missing predecessors but was approved.");

				// Perform next steps
				nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
				nextRight = validateOrPerformValidatedStep(right, currentApprovedUnconfirmedBlocks, nextRight,
						cutoffHeight, maxHeight, store);
			} else {
				// Go right
				right = nextRight;
				if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight,
						store))
					throw new InfeasiblePrototypeException(
							"Shouldn't happen: block is missing predecessors but was approved.");

				// Perform next steps
				nextRight = performValidatedStep(right, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight,
						store);
				nextLeft = validateOrPerformValidatedStep(left, currentApprovedUnconfirmedBlocks, nextLeft,
						cutoffHeight, maxHeight, store);
			}
		}

		// Go forward on the remaining paths
		while (nextLeft != left) {
			left = nextLeft;
			if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight,
					store))
				throw new InfeasiblePrototypeException(
						"Shouldn't happen: block is missing predecessors but was approved.");
			nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
		}
		while (nextRight != right) {
			right = nextRight;
			if (!serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight,
					store))
				throw new InfeasiblePrototypeException(
						"Shouldn't happen: block is missing predecessors but was approved.");
			nextRight = performValidatedStep(right, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
		}

		watch.stop();
		log.trace("getValidatedBlockPair iteration time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));

		return Pair.of(left, right);
	}

	// Does not redo finding next step if next step was still valid
	private BlockWrap validateOrPerformValidatedStep(BlockWrap fromBlock,
			HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, BlockWrap potentialNextBlock, long cutoffHeight,
			long maxHeight, BlockStoreInterface store) throws BlockStoreException {
		if (new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.isEligibleForApprovalSelection(potentialNextBlock, currentApprovedNonMilestoneBlocks, cutoffHeight,
						maxHeight, store))
			return potentialNextBlock;
		else
			return performValidatedStep(fromBlock, currentApprovedNonMilestoneBlocks, cutoffHeight, maxHeight, store);
	}

	// Finds a potential approver block to include given the currently approved
	// blocks
	private BlockWrap performValidatedStep(BlockWrap fromBlock, HashSet<BlockWrap> currentApprovedNonMilestoneBlocks,
			long cutoffHeight, long maxHeight, BlockStoreInterface store) throws BlockStoreException {
		List<BlockWrap> candidates = new ArrayList<>();
//		if( fromBlock.getBlock().getHeight()==9)
//		{
//		 log.debug(fromBlock.toString());
//		}
		for (Sha256Hash req : store.getApproverBlockHashes(fromBlock.getBlockHash())) {
			candidates.add(new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
					.getBlockWrap(req, store));
		}

		BlockWrap result;
		do {
			// Find results until one is valid/eligible
			result = performTransition(fromBlock, candidates);
			candidates.remove(result);
		} while (!new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.isEligibleForApprovalSelection(result, currentApprovedNonMilestoneBlocks, cutoffHeight, maxHeight,
						store));
		return result;
	}

	private BlockWrap getRatingTip(BlockWrap currentBlock, long maxTime, long maxHeight, BlockStoreInterface store)
			throws BlockStoreException {
		// Repeatedly perform transitions until the final tip is found
		List<BlockWrap> approvers = store.getNotInvalidApproverBlocks(currentBlock.getBlock().getHash());
		approvers.removeIf(b -> b.getBlockEvaluation().getInsertTime() > maxTime);
		BlockWrap nextBlock = performTransition(currentBlock, approvers);

		while (currentBlock != nextBlock && nextBlock.getBlockEvaluation().getHeight() <= maxHeight) {
			currentBlock = nextBlock;
			approvers = store.getNotInvalidApproverBlocks(currentBlock.getBlock().getHash());
			approvers.removeIf(b -> b.getBlockEvaluation().getInsertTime() > maxTime);
			nextBlock = performTransition(currentBlock, approvers);
		}
		return currentBlock;
	}

	/**
	 * Performs one step of MCMC random walk by cumulative weight.
	 * 
	 * @param currentBlock the block to take a step from
	 * @param candidates   all blocks approving the block that are allowed to go to
	 * @return currentBlock if no further steps possible, else a new block from
	 *         approvers
	 */
	public BlockWrap performTransition(BlockWrap currentBlock, List<BlockWrap> candidates) {
		if (candidates.isEmpty()) {
			return currentBlock;
		} else if (candidates.size() == 1) {
			return candidates.get(0);
		} else {
			double[] transitionWeights = new double[candidates.size()];
			double transitionWeightSum = 0;
			long currentCumulativeWeight = currentBlock.getMcmc().getCumulativeWeight();

			// Calculate the unnormalized transition weights
			for (int i = 0; i < candidates.size(); i++) {
				// Calculate transition weights
				transitionWeights[i] = Math.exp(serverConfiguration.getAlphaMCMC()
						* (currentCumulativeWeight - candidates.get(i).getMcmc().getCumulativeWeight()));
				transitionWeightSum += transitionWeights[i];
			}

			// Randomly select one of the approvers by transition probabilities
			double transitionRealization = seed.nextDouble() * transitionWeightSum;
			for (int i = 0; i < candidates.size(); i++) {
				transitionRealization -= transitionWeights[i];
				if (transitionRealization <= 0) {
					return candidates.get(i);
				}
			}

			log.warn("MCMC step failed");
			return currentBlock;
		}
	}

	/**
	 * Returns the specified amount of entry points for tip selection.
	 * 
	 * @param count amount of entry points to get
	 * @return hashes of the entry points
	 */
	private List<BlockWrap> getEntryPoints(int count, long currChainLength, BlockStoreInterface store)
			throws BlockStoreException {
		List<BlockWrap> candidates = new ArrayList<>();
		List<Sha256Hash> hashs = getEntryPointCandidates(currChainLength, store);
		if (hashs.isEmpty()) {
			candidates.add(store.getBlockWrap(cacheBlockService.getMaxConfirmedReward(store).getBlockHash()));
		} else {
			ServiceBaseConnect serviceBaseConnect = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper);
			for (Sha256Hash hash : hashs) {
				candidates.add(serviceBaseConnect.getBlockWrap(hash, store));
			}
		}
		return pullRandomlyByCumulativeWeight(candidates, count);
	}

	public List<Sha256Hash> getEntryPointCandidates(long currChainLength, BlockStoreInterface store)
			throws BlockStoreException {

		return new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.getEntryPointCandidates(currChainLength, store);
	}

	/**
	 * Randomly pulls with replacement the specified amount from the specified list.
	 * 
	 * @param candidates List to pull from
	 * @param count      Amount to pull
	 * @return Random pulls from collection
	 */
	private List<BlockWrap> pullRandomlyByCumulativeWeight(List<BlockWrap> candidates, int count) {
		if (candidates.isEmpty())
			throw new IllegalArgumentException("Candidate list is empty.");

		double maxBlockWeight = candidates.stream().mapToLong(e -> e.getMcmc().getCumulativeWeight()).max().orElse(1L);
		double normalizedBlockWeightSum = candidates.stream()
				.mapToDouble(e -> e.getMcmc().getCumulativeWeight() / maxBlockWeight).sum();
		List<BlockWrap> results = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			// Randomly select weighted by cumulative weights
			double selectionRealization = seed.nextDouble() * normalizedBlockWeightSum;
			for (BlockWrap selectedBlock : candidates) {
				selectionRealization -= selectedBlock.getMcmc().getCumulativeWeight() / maxBlockWeight;
				if (selectionRealization <= 0) {
					results.add(selectedBlock);
					break;
				}
			}
		}

		return results;
	}
}
