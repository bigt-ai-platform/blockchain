package net.bigtangle.server.service.base;

import java.util.Collection;
import java.util.Comparator;
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
import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.core.ConflictCandidate;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.store.BlockStoreInterface;

/*
 * ServiceVerifyReward can accept new reward with referenced blocks with no change of the referenced blocks
 */
public class ServiceVerifyReward extends ServiceBaseConnect {

	private final MempoolService mempoolService;

	public ServiceVerifyReward(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		this(serverConfiguration, networkParameters, cacheBlockService, jsonmapper, null);
	}

	public ServiceVerifyReward(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper, MempoolService mempoolService) {
		super(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		this.mempoolService = mempoolService;
	}

	private static final Logger logger = LoggerFactory.getLogger(ServiceVerifyReward.class);

	public void verifyRewardChainConfirmReferenced(Block newChainlengthBlock, BlockStoreInterface store)
			throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newChainlengthBlock.getTransactions().get(0).getData());
		Set<Sha256Hash> referrencedBlocks = currRewardInfo.getBlocks();
		long cutoffHeight = getRewardCutoffHeight(currRewardInfo.getPrevRewardHash(), store);

		// Check all referenced blocks have their requirements
		SolidityState solidityState = checkReferencedBlockRequirements(newChainlengthBlock, cutoffHeight, store);
		if (solidityState.notSuccessState())
			throw new VerificationException(" checkReferencedBlockRequirements is failed: " + solidityState);

		// Solidify referenced blocks
		solidifyBlocks(currRewardInfo, store);

		// Sanity check: No reward blocks are approved
		checkContainsNoRewardBlocks(newChainlengthBlock, store);

		// Check: At this point, predecessors must be solid
		solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.checkSolidity(newChainlengthBlock, false, store, false);

		if (solidityState.notSuccessState()) {
			// A missing predecessor means the beacon's DAG parents (prevBlockHash
			// / prevBranchBlockHash) or a referenced block have not been synced to
			// this node yet (multi-node gossip lag). The beacon is VALID — it will
			// connect once the missing blocks arrive — so signal a retryable
			// MissingDependencyException: the queue wrapper keeps the beacon queued
			// (and requests the missing parent) instead of deleting it forever.
			// Dropping it would fork the chain: the single slot proposer builds on
			// the confirmed head, but nodes that dropped its beacons could never
			// confirm them and would diverge permanently.
			if (solidityState.isDirectlyMissing()) {
				throw new net.bigtangle.exception.VerificationException.MissingDependencyException(
						"beacon DAG parent not synced yet: " + solidityState.getMissingDependency());
			}
			throw new VerificationException(
					" .checkSolidity is failed: " + solidityState + "\n with block = " + newChainlengthBlock);
		}

		long chainlength = store.getRewardChainLength(newChainlengthBlock.getHash());

		// Find conflicts in the dependency set
		HashSet<BlockWrap> allApprovedNewBlocks = new HashSet<>();
		for (Sha256Hash hash : referrencedBlocks) {
			BlockWrap blockWrap = getBlockWrap(hash, store);
			allApprovedNewBlocks.add(blockWrap);
		}

		allApprovedNewBlocks.add(getBlockWrap(newChainlengthBlock.getHash(), store));

		// If anything is already spent, remove those blocks and continue
		// with the rest.  This handles the case where reward blocks are
		// created faster than UpdateChain processes them — the second reward
		// block may reference blocks already confirmed by the first.
		if (hasSpentInputs(allApprovedNewBlocks, true, store)) {
			allApprovedNewBlocks.removeIf(bw -> bw.getBlockEvaluation().getChainlength() > 0);
			if (allApprovedNewBlocks.size() <= 1) return;
		}
		// If any conflicts exist between the current set of
		// blocks, no-go
		boolean anyCandidateConflicts = allApprovedNewBlocks.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.groupingBy(ConflictCandidate::getConflictPoint))
				.values().stream().anyMatch(l -> l.size() > 1);
		if (anyCandidateConflicts) {
			solidityState = SolidityState.getFailState();
			throw new VerificationException("conflicts exist between the current set of ");
		}

		// Otherwise, all predecessors exist and were at least
		// solid > 0, so we should be able to confirm everything
		solidifyBlock(newChainlengthBlock, solidityState, true, store);

		confirmBlocksSorted(store, chainlength, allApprovedNewBlocks, new HashSet<>());

	}

	/*
	 * check blocks are in not in chainlength
	 */
	private SolidityState checkReferencedBlockRequirements(Block newChainlengthBlock, long cutoffHeight,
			BlockStoreInterface store) throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newChainlengthBlock.getTransactions().get(0).getData());

		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			BlockWrap block = getBlockWrap(hash, store);
			if (block == null)
				return SolidityState.fromReferenced(hash, true);
			if (block.getBlock().getHeight() < cutoffHeight)
				throw new VerificationException("Referenced blocks are below cutoff height.");

			Set<Sha256Hash> requiredBlocks = getAllRequiredBlockHashes(block.getBlock());
			for (Sha256Hash reqHash : requiredBlocks) {
				BlockWrap req = getBlockWrap(reqHash, store);
				if (req == null)
					return SolidityState.from(reqHash, true);
			}
		}

		return SolidityState.getSuccessState();
	}

	private void checkContainsNoRewardBlocks(Block newChainlengthBlock, BlockStoreInterface store)
			throws BlockStoreException {

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newChainlengthBlock.getTransactions().get(0).getData());
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			BlockWrap block = getBlockWrap(hash, store);
			if (block.getBlock().getBlockType() == BlockType.BLOCKTYPE_BEACON)
				throw new VerificationException("Reward block referenced block has other reward blocks" + block);
		}
	}

	/**
	 * Computes RewardBuilderResult here for new reward blocks.
	 * 
	 */
	public RewardBuilderResult calcRewardInfo(boolean contractExecute, BlockWrap prevTrunk, BlockWrap prevBranch,
			Sha256Hash prevRewardHash, long currentTime, Set<Sha256Hash> referenced, BlockStoreInterface store)
			throws BlockStoreException {

		// Read previous reward block's data
		long prevChainLength = store.getRewardChainLength(prevRewardHash);
		// Build transaction for block
		Transaction tx = new Transaction(networkParameters);
		// Build the type-specific tx data
		RewardInfo rewardInfo = new RewardInfo(prevRewardHash, referenced, prevChainLength + 1);
		tx.setData(rewardInfo.toByteArray());
		tx.setMemo(new MemoInfo("Reward"));
		return new RewardBuilderResult(tx);
	}

	/**
	 * Called as part of connecting a block when the new block results in a
	 * different chain having higher total work as longest reward chain.
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
		oldBlocks.sort(Comparator.comparingLong((Block w) -> w.getHeight()));
		for (Block oldBlock : oldBlocks) {
			// Sanity check:
			if (!oldBlock.getHash().equals(UtilGeneseBlock.createGenesis(networkParameters ).getHash())) {
				// Unset the chainlength (Chain length) of this one
				long chainlength = getRewardInfo(oldBlock).getChainlength();
				List<Sha256Hash> blocksInChainlengthInterval = getBlocksInChainlengthInterval(chainlength,
						chainlength, store);
				// all conflicts to this chainlength will reset to initial
				store.resetChainlengthSolid(chainlength);
				// Unconfirm anything in chainlength
				unconfirmBlocks(store, blocksInChainlengthInterval);
			}
			// store.commitDatabaseBatchWrite();
			// store.beginDatabaseBatchWrite();
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

	private void unconfirmBlocks(BlockStoreInterface store, List<Sha256Hash> blocksInChainlengthInterval)
			throws BlockStoreException {
		HashSet<BlockWrap> blocksToRemoveBlocks = new HashSet<>();
		for (Sha256Hash b : blocksInChainlengthInterval) {
			blocksToRemoveBlocks.add(getBlockWrap(b, store));
		}
		unconfirmBlocksSorted(store, blocksToRemoveBlocks, new HashSet<>());
		// Dropped by reorg: mark transactions as DROPPED and put them back into
		// the mempool so they retry on the winner chain. Status writes use the
		// same store/connection as the reorg to avoid cross-connection lock
		// contention with the in-flight batch write.
		for (BlockWrap bw : blocksToRemoveBlocks) {
			try {
				TransactionStatusRecord.markBlock(store, bw.getBlock(), TransactionStatus.DROPPED, null,
						networkParameters);
			} catch (Exception e) {
				logger.debug("Failed to record DROPPED status for block {}: {}", bw.getBlockHash(), e.getMessage());
			}
			reMempool(bw.getBlock(), store);
		}
	}

	private void reMempool(Block block, BlockStoreInterface store) {
		if (mempoolService == null || block.getTransactions() == null) {
			return;
		}
		for (Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase() || tx.getInputs() == null || tx.getInputs().isEmpty()) {
				continue;
			}
			try {
				mempoolService.submitTransaction(tx);
				TransactionStatusRecord.mark(store, tx, TransactionStatus.MEMPOOL, null, null, networkParameters);
			} catch (Exception e) {
				logger.debug("re-mempool failed for tx {}: {}", tx.getHash(), e.getMessage());
			}
		}
	}

	private Block getChainHead(BlockStoreInterface store) throws BlockStoreException {
		return store.get(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
	}

}
