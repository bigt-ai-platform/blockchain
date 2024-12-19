package net.bigtangle.server.service.base;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.core.ConflictCandidate;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;

/*
 * ServiceVerifyReward can accept new reward with referenced blocks with no change of the referenced blocks
 */
public class ServiceVerifyReward extends ServiceBaseConnect {

	public ServiceVerifyReward(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService,jsonmapper);

	} 

	private static final Logger logger = LoggerFactory.getLogger(ServiceVerifyReward.class);

	public void verifyRewardChainConfirmReferenced(Block newMilestoneBlock, BlockStoreInterface store)
			throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newMilestoneBlock.getTransactions().get(0).getData());
		Set<Sha256Hash> referrencedBlocks = currRewardInfo.getBlocks();
		long cutoffHeight = getRewardCutoffHeight(currRewardInfo.getPrevRewardHash(), store);

		// Check all referenced blocks have their requirements
		SolidityState solidityState = checkReferencedBlockRequirements(newMilestoneBlock, cutoffHeight, store);
		if (solidityState.isSuccessState())
			throw new VerificationException(" checkReferencedBlockRequirements is failed: " + solidityState);

		// Solidify referenced blocks
		solidifyBlocks(currRewardInfo, store);

		// Ensure the new difficulty and tx is set correctly
		checkGeneratedReward(newMilestoneBlock, referrencedBlocks, store);

		// Sanity check: No reward blocks are approved
		checkContainsNoRewardBlocks(newMilestoneBlock, store);

		// Check: At this point, predecessors must be solid
		solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.checkSolidity(newMilestoneBlock, false, store, false);

		if (solidityState.isSuccessState())
			throw new VerificationException(
					" .checkSolidity is failed: " + solidityState + "\n with block = " + newMilestoneBlock);

		long milestoneNumber = store.getRewardChainLength(newMilestoneBlock.getHash());

		// Find conflicts in the dependency set
		HashSet<BlockWrap> allApprovedNewBlocks = new HashSet<>();
		for (Sha256Hash hash : referrencedBlocks) {
			BlockWrap blockWrap = getBlockWrap(hash, store);
			allApprovedNewBlocks.add(blockWrap);
		}

		allApprovedNewBlocks.add(getBlockWrap(newMilestoneBlock.getHash(), store));

		// If anything is already spent, no-go
		boolean anySpentInputs = hasSpentInputs(allApprovedNewBlocks, true, store);
		// Optional<ConflictCandidate> spentInput =
		// findFirstSpentInput(allApprovedNewBlocks);

		if (anySpentInputs) {
			solidityState = SolidityState.getFailState();
			throw new VerificationException("there are hasSpentInputs in allApprovedNewBlocks ");
		}
		// If any conflicts exist between the current set of
		// blocks, no-go
		boolean anyCandidateConflicts = allApprovedNewBlocks.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.groupingBy(ConflictCandidate::getConflictPoint))
				.values().stream().anyMatch(l -> l.size() > 1);
	//	if (anyCandidateConflicts)
	//		showConflict(allApprovedNewBlocks);

		// Did we fail? Then we stop now and rerun consensus
		// logic on the new longest chain.
		if (anyCandidateConflicts) {
			solidityState = SolidityState.getFailState();
			throw new VerificationException("conflicts exist between the current set of ");
		}

		// Otherwise, all predecessors exist and were at least
		// solid > 0, so we should be able to confirm everything
		solidifyBlock(newMilestoneBlock, solidityState, true, store);

		confirmBlocksSorted(store, milestoneNumber, allApprovedNewBlocks, new HashSet<>());

	}

	private void checkGeneratedReward(Block newMilestoneBlock, Set<Sha256Hash> referrencedBlocks,
			BlockStoreInterface store) throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newMilestoneBlock.getTransactions().get(0).getData());

		RewardBuilderResult result = getRewardBuilderResult(newMilestoneBlock.getPrevBlockHash(),
				newMilestoneBlock.getPrevBranchBlockHash(), currRewardInfo.getPrevRewardHash(),
				newMilestoneBlock.getTimeSeconds(), enableOrderMatchExecutionChain(newMilestoneBlock),
				referrencedBlocks, store);
		if (currRewardInfo.getDifficultyTargetReward() != result.getDifficulty()) {
			throw new VerificationException("Incorrect difficulty target");
		}
		if (!enableOrderMatchExecutionChain(newMilestoneBlock)) {
			OrderMatchingResult ordermatchresult = generateOrderMatching(newMilestoneBlock, store);

			// Only check the Hash of OrderMatchingResult
			if (currRewardInfo.getOrdermatchingResult() == null
					|| !currRewardInfo.getOrdermatchingResult().equals(ordermatchresult.getOrderMatchingResultHash())) {
				// if(currRewardInfo.getChainlength()!=197096)
				throw new VerificationException("OrderMatchingResult transactions output is   wrong.");
			}
		}
		Transaction miningTx = generateVirtualMiningRewardTX(newMilestoneBlock, store);

		// Only check the Hash of OrderMatchingResult
		if (!currRewardInfo.getMiningResult().equals(miningTx.getHash())) {
			throw new VerificationException("generateVirtualMiningRewardTX transactions output is wrong.");
		}
	}

	/*
	 * check blocks are in not in milestone
	 */
	private SolidityState checkReferencedBlockRequirements(Block newMilestoneBlock, long cutoffHeight,
			BlockStoreInterface store) throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newMilestoneBlock.getTransactions().get(0).getData());

		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			BlockWrap block = getBlockWrap(hash, store);
			if (block == null)
				return SolidityState.fromReferenced(hash, true);
			if (block.getBlock().getHeight() < cutoffHeight)
				throw new VerificationException("Referenced blocks are below cutoff height.");

			Set<Sha256Hash> requiredBlocks = getAllRequiredBlockHashes(block.getBlock(), false);
			for (Sha256Hash reqHash : requiredBlocks) {
				BlockWrap req = getBlockWrap(reqHash, store);
				if (req == null)
					return SolidityState.from(reqHash, true);

				if (req.getBlockEvaluation().getMilestone() < 0 && !currRewardInfo.getBlocks().contains(reqHash)) {
					// FIXME blocks problem with 4046309 throw new
					// VerificationException("Predecessors are not in
					// milestone." + req.toString());
				}
			}
		}

		return SolidityState.getSuccessState();
	}

	private void checkContainsNoRewardBlocks(Block newMilestoneBlock, BlockStoreInterface store)
			throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newMilestoneBlock.getTransactions().get(0).getData());
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			BlockWrap block = getBlockWrap(hash, store);
			if (block.getBlock().getBlockType() == Type.BLOCKTYPE_REWARD)
				throw new VerificationException("Reward block referenced block has other reward blocks" + block);
		}
	}

	/**
	 * Computes RewardBuilderResult here for new reward blocks.
	 * 
	 */
	public RewardBuilderResult getRewardBuilderResult(Sha256Hash prevTrunk, Sha256Hash prevBranch,
			Sha256Hash prevRewardHash, long currentTime, boolean ordermatchexecutionChain, Set<Sha256Hash> referenced,
			BlockStoreInterface store) throws BlockStoreException {

		BlockWrap prevTrunkBlock = getBlockWrap(prevTrunk, store);
		BlockWrap prevBranchBlock = getBlockWrap(prevBranch, store);

		return calcRewardInfo(ordermatchexecutionChain, prevTrunkBlock, prevBranchBlock, prevRewardHash, currentTime,
				referenced, store);

	}
 
	public RewardBuilderResult calcRewardInfo(boolean contractExecute, BlockWrap prevTrunk, BlockWrap prevBranch,
			Sha256Hash prevRewardHash, long currentTime, Set<Sha256Hash> referenced, BlockStoreInterface store)
			throws BlockStoreException {

		// Read previous reward block's data
		long prevChainLength = store.getRewardChainLength(prevRewardHash);
		// Build transaction for block
		Transaction tx = new Transaction(networkParameters);
		long difficultyReward = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.calculateNextChainDifficulty(prevRewardHash, prevChainLength + 1, currentTime, store);
		// Build the type-specific tx data
		RewardInfo rewardInfo = new RewardInfo(prevRewardHash, difficultyReward, referenced, prevChainLength + 1);
		tx.setData(rewardInfo.toByteArray());
		tx.setMemo(new MemoInfo("Reward"));
		return new RewardBuilderResult(tx, difficultyReward);
	}

 
	/**
	 * Called as part of connecting a block when the new block results in a
	 * different chain having higher total work.
	 * 
	 */
	public void handleNewBestChain(Block newChainHead, BlockStoreInterface store)
			throws BlockStoreException, VerificationException {
		// checkState(lock.isHeldByCurrentThread());
		// This chain has overtaken the one we currently believe is best.
		// Reorganize is required.
		//
		// Firstly, calculate the block at which the chain diverged. We only
		// need to examine the
		// chain from beyond this block to find differences.
		Block head = getChainHead(store);
		final Block splitPoint = findSplit(newChainHead, head, store);
		if (splitPoint == null) {
			logger.info(" splitPoint is null, the chain ist not complete: {} ", newChainHead);
			return;
		}

		logger.info("Re-organize after split at height {}", splitPoint.getHeight());
		logger.info("Old chain head: \n {}", head);
		logger.info("New chain head: \n {}", newChainHead);
		logger.info("Split at block: \n {}", splitPoint);
		// Then build a list of all blocks in the old part of the chain and the
		// new part.
		LinkedList<Block> oldBlocks = new LinkedList<>();
		if (!head.getHash().equals(splitPoint.getHash())) {
			oldBlocks = getPartialChain(head, splitPoint, store);
		}
		final LinkedList<Block> newBlocks = getPartialChain(newChainHead, splitPoint, store);
		// Disconnect each block in the previous best chain that is no
		// longer in the new best chain from last to begin
		oldBlocks.sort(new SortbyBlock());
		for (Block oldBlock : oldBlocks) {
			// Sanity check:
			if (!oldBlock.getHash().equals(networkParameters.getGenesisBlock().getHash())) {
				// Unset the milestone (Chain length) of this one
				long milestoneNumber = getRewardInfo(oldBlock).getChainlength();
				List<Sha256Hash> blocksInMilestoneInterval = getBlocksInMilestoneInterval(milestoneNumber,
						milestoneNumber, store);
				// Unconfirm anything not in milestone
				unconfirmBlocks(store,   blocksInMilestoneInterval);
			}
			store.commitDatabaseBatchWrite();
			store.beginDatabaseBatchWrite();
		}
		Block cursor;
		// Walk in ascending chronological order.
		for (Iterator<Block> it = newBlocks.descendingIterator(); it.hasNext();) {
			cursor = it.next();
			verifyRewardChainConfirmReferenced(cursor, store);
			// if we build a chain longer than head, do a commit, even it may be
			// failed after this.
			if (getRewardInfo(cursor).getChainlength() > getRewardInfo(head).getChainlength()) {
				store.commitDatabaseBatchWrite();
				store.beginDatabaseBatchWrite();
			}
		}

		// Update the pointer to the best known block.
		// setChainHead(storedNewHead);
	}

	private void unconfirmBlocks(BlockStoreInterface store ,
			List<Sha256Hash> blocksInMilestoneInterval) throws BlockStoreException {
		HashSet<BlockWrap> blocksToRemoveBlocks = new HashSet<>();
		for (Sha256Hash b : blocksInMilestoneInterval) {
			blocksToRemoveBlocks.add(getBlockWrap(b, store));
		}
		unconfirmBlocksSorted(store,  blocksToRemoveBlocks, new HashSet<>());
	}

	/**
	 * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is
	 * included, lower is not.
	 */
	private LinkedList<Block> getPartialChain(Block higher, Block lower, BlockStoreInterface store)
			throws BlockStoreException {
		checkArgument(higher.getHeight() > lower.getHeight(), "higher and lower are reversed");
		LinkedList<Block> results = new LinkedList<>();
		Block cursor = higher;
		while (true) {
			results.add(cursor);
			cursor = checkNotNull(store.get(getRewardInfo(cursor).getPrevRewardHash()), "Ran off the end of the chain");
			if (cursor.equals(lower))
				break;
		}
		return results;
	}

	private Block getChainHead(BlockStoreInterface store) throws BlockStoreException {
		return store.get(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
	}

}
