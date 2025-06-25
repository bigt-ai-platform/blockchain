/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

/**
 * Provides tip selection functionality for the blockchain using Markov Chain Monte Carlo (MCMC) methods.
 * Handles the random walk process for selecting blocks to approve during transaction validation.
 * 
 * <p>Key responsibilities include:
 * <ul>
 *   <li>Executing MCMC walks to find valid rating tips</li>
 *   <li>Managing validated block pairs for consensus approval</li>
 *   <li>Maintaining thread pools for parallel tip selection</li>
 *   <li>Enforcing consensus rules during tip selection</li>
 * </ul>
 *
 * @Service Indicates this is a Spring framework service component
 */

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
import java.util.concurrent.TimeoutException;

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
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException.InfeasiblePrototypeException;
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
		        // Add timeout to prevent indefinite blocking
		        ratingTips.add(future.get(10, TimeUnit.SECONDS));  // Adjust timeout as needed
		    } catch (TimeoutException e) {
		        // Handle timeout
		        future.cancel(true);  // Interrupt the task
		        log.error("Task timed out for entry point:  ",   e);
		    } catch (InterruptedException e) {
		        Thread.currentThread().interrupt();  // Preserve interrupt status
		        log.warn("Processing interrupted", e);
		    } catch (ExecutionException e) {
		        throw new BlockStoreException(e);
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
 
 
	public Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedNonMilestoneBlocks, BlockStoreInterface store)
			throws BlockStoreException {
		List<BlockWrap> entryPoints = getEntryPoints(2, maxConfirmedReward.getChainLength(), store);
		BlockWrap left = entryPoints.get(0);
		BlockWrap right = entryPoints.get(1);
		Pair<BlockWrap, BlockWrap> candidate = getValidatedBlockPair(maxConfirmedReward,
				currentApprovedNonMilestoneBlocks, left, right, store);
		if (!candidate.getLeft().equals(candidate.getRight())) {
			return candidate;
		}
		for (int i = 0; i < 5; i++) {
			Pair<BlockWrap, BlockWrap> paar = getValidatedBlockPair(maxConfirmedReward,
					currentApprovedNonMilestoneBlocks, left, right, store);
			if (!paar.getLeft().equals(paar.getRight())) {
				return paar;
			}
		}
		return candidate;
	}

	private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(TXReward maxConfirmedReward,
			HashSet<BlockWrap> currentApprovedUnconfirmedBlocks, BlockWrap left, BlockWrap right,
			BlockStoreInterface store) throws BlockStoreException {
		Stopwatch watch = Stopwatch.createStarted();
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, store);
		long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, store);

		// Initialize approved blocks
		serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);
		serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store);

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
				serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);

				// Perform next steps
				nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
				nextRight = validateOrPerformValidatedStep(right, currentApprovedUnconfirmedBlocks, nextRight,
						cutoffHeight, maxHeight, store);
			} else {
				// Go right
				right = nextRight;
				serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight,
						store);

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
			serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, left, cutoffHeight, store);
			nextLeft = performValidatedStep(left, currentApprovedUnconfirmedBlocks, cutoffHeight, maxHeight, store);
		}
		while (nextRight != right) {
			right = nextRight;
			serviceBase.addRequiredUnconfirmedBlocksTo(currentApprovedUnconfirmedBlocks, right, cutoffHeight, store);
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
		if (new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
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
			candidates.add(new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.getBlockWrap(req, store));
		}

		BlockWrap result;
		do {
			// Find results until one is valid/eligible
			result = performTransition(fromBlock, candidates);
			candidates.remove(result);
		} while (!new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
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
			ServiceBaseConnect serviceBaseConnect = new ServiceBaseConnect(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper);
			for (Sha256Hash hash : hashs) {
				candidates.add(serviceBaseConnect.getBlockWrap(hash, store));
			}
		}
		return pullRandomlyByCumulativeWeight(candidates, count);
	}

	private List<Sha256Hash> getEntryPointCandidates(long currChainLength, BlockStoreInterface store)
			throws BlockStoreException {

		return new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
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
