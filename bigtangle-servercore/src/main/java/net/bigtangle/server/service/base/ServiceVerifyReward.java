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
import net.bigtangle.core.TXReward;
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

	/** Log the per-beacon connect breakdown when a beacon references this many blocks. */
	private static final int PERF_CONNECT_LOG_MIN_REFS = Integer.getInteger("perf.connectLogMinRefs", 20);

	public void verifyRewardChainConfirmReferenced(Block newChainlengthBlock, BlockStoreInterface store)
			throws BlockStoreException {

		long perfStart = System.currentTimeMillis();
		long perfRequirementsMs = 0, perfSolidifyMs = 0, perfConflictMs = 0, perfSolidifyBlockMs = 0;

		RewardInfo currRewardInfo = new RewardInfo().parseChecked(newChainlengthBlock.getTransactions().get(0).getData());
		Set<Sha256Hash> referrencedBlocks = currRewardInfo.getBlocks();
		long cutoffHeight = getRewardCutoffHeight(currRewardInfo.getPrevRewardHash(), store);

		// Check all referenced blocks have their requirements
		long t = System.currentTimeMillis();
		SolidityState solidityState = checkReferencedBlockRequirements(newChainlengthBlock, cutoffHeight, store);
		if (solidityState.notSuccessState())
			throw new VerificationException(" checkReferencedBlockRequirements is failed: " + solidityState);
		perfRequirementsMs = System.currentTimeMillis() - t;

		// Solidify referenced blocks
		t = System.currentTimeMillis();
		solidifyBlocks(currRewardInfo, store);

		// Sanity check: No reward blocks are approved
		checkContainsNoRewardBlocks(newChainlengthBlock, store);

		// Check: At this point, predecessors must be solid
		solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.checkSolidity(newChainlengthBlock, false, store, false);
		perfSolidifyMs = System.currentTimeMillis() - t;

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

		// Reuse the proposal's conflict resolutions when the confirmed head is
		// unchanged (same token) so the batched prefetch does not re-read the
		// ~50k UTXOs; a moved confirmed head yields a different token and a
		// fresh resolution.
		TXReward confirmed = cacheBlockService.getMaxConfirmedReward(store);
		String conflictCacheToken = confirmed.getBlockHash() + ":" + confirmed.getChainLength();
		// Base state from the token-keyed cache, then overlay the proposal
		// sweep's resolutions (head-independent hand-off): on a healthy chain
		// the head moves between propose and connect, so the token-keyed cache
		// misses every slot and a full UTXO re-resolution dominated beacon
		// connect (300-900ms).
		loadConflictCache(conflictCacheToken);
		net.bigtangle.server.service.base.ServiceBaseConfirmation.mergeConflictCache(
				net.bigtangle.server.service.base.ServiceBaseConfirmation.loadLatestSweep());

		// Find conflicts in the dependency set.
		// Bounded per cycle: parse/confirm at most MAX_CONFIRM_PER_CYCLE
		// referenced blocks (oldest first). Under sustained overload the
		// unconfirmed backlog — and therefore each beacon's reference list —
		// grows without limit; materializing ALL referenced blocks as parsed
		// objects here made peak memory O(lag) and OOM'd the node. Skipped
		// blocks are simply confirmed by a LATER beacon: every new proposal
		// sweeps all still-unconfirmed blocks into its own reference set
		// (SlotService.addAllUnconfirmedBlocks), so nothing is orphaned.
		HashSet<BlockWrap> allApprovedNewBlocks = new HashSet<>();
		for (Sha256Hash hash : oldestReferencedBlocks(referrencedBlocks, MAX_CONFIRM_PER_CYCLE, store)) {
			BlockWrap blockWrap = getBlockWrap(hash, store);
			allApprovedNewBlocks.add(blockWrap);
		}

		allApprovedNewBlocks.add(getBlockWrap(newChainlengthBlock.getHash(), store));

		// If anything is already spent, remove those blocks and continue
		// with the rest.  This handles the case where reward blocks are
		// created faster than UpdateChain processes them — the second reward
		// block may reference blocks already confirmed by the first.
		t = System.currentTimeMillis();
		if (hasSpentInputs(allApprovedNewBlocks, true, store)) {
			allApprovedNewBlocks.removeIf(bw -> bw.getBlockEvaluation().getChainlength() > 0);
			if (allApprovedNewBlocks.size() <= 1) return;
		}
		perfConflictMs = System.currentTimeMillis() - t;
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
		t = System.currentTimeMillis();
		solidifyBlock(newChainlengthBlock, solidityState, true, store);
		perfSolidifyBlockMs = System.currentTimeMillis() - t;

		confirmBlocksSorted(store, chainlength, allApprovedNewBlocks, new HashSet<>());

		int referenced = currRewardInfo.getBlocks() == null ? 0 : currRewardInfo.getBlocks().size();
		long totalMs = System.currentTimeMillis() - perfStart;
		if (referenced >= PERF_CONNECT_LOG_MIN_REFS || totalMs > 1000) {
			logger.info(
					"beacon connect: refs={} chainlength={} requirements={}ms solidify={}ms conflict={}ms "
							+ "solidifyBlock={}ms total={}ms",
					referenced, chainlength, perfRequirementsMs, perfSolidifyMs, perfConflictMs, perfSolidifyBlockMs,
					totalMs);
		}

	}

	/** Max referenced blocks parsed+confirmed per beacon-connect cycle. */
	private static final int MAX_CONFIRM_PER_CYCLE = 40;

	/**
	 * Oldest-first subset of the referenced blocks, bounded to
	 * {@code limit} entries. Ordering uses only evaluation rows (cheap);
	 * blocks with unknown height sort last. Bounds confirm-path memory to
	 * O(limit) parsed blocks regardless of how far confirmation lags.
	 */
	private java.util.List<Sha256Hash> oldestReferencedBlocks(java.util.Set<Sha256Hash> refs, int limit,
			BlockStoreInterface store) throws BlockStoreException {
		if (refs.size() <= limit) {
			return new java.util.ArrayList<>(refs);
		}
		java.util.List<Sha256Hash> ordered = new java.util.ArrayList<>(refs);
		ordered.sort(java.util.Comparator.comparingLong((Sha256Hash h) -> {
			try {
				net.bigtangle.core.BlockEvaluation e = store.getBlockEvaluationsByhashs(h);
				return e == null ? Long.MAX_VALUE : e.getInsertTime();
			} catch (net.bigtangle.exception.BlockStoreException ex) {
				return Long.MAX_VALUE;
			}
		}));
		return ordered.subList(0, limit);
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

			if (cacheBlockService.isTxValidated(hash)) {
				// Every input of this block was resolved against a local UTXO
				// when the tx was verified on this node, so its source blocks
				// existed here and are never pruned while unconfirmed. Skip
				// the per-required-hash walk — it used to deserialize every
				// ancestor block and dominated beacon connect under load.
				continue;
			}

			Set<Sha256Hash> requiredBlocks = getAllRequiredBlockHashes(block.getBlock());
			for (Sha256Hash reqHash : requiredBlocks) {
				// Existence check only: loading the full block here
				// deserialized megabytes per required hash for no benefit.
				if (store.getBlockEvaluationsByhashs(reqHash) == null)
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
