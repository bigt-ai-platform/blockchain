/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlockData;
import net.bigtangle.core.Token;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.core.exception.VerificationException.MissingDependencyException;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.core.ConflictCandidate;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseConnect.SortbyBlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConnect.SortbyBlockWrapAsc;
import net.bigtangle.store.BlockStoreInterface;

public abstract class ServiceBaseConfirmation extends ServiceBaseOrder {

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseConfirmation.class);

	public ServiceBaseConfirmation(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);

	}

	/**
	 * Recursively removes the specified block and its approvers from the collection
	 * if this block is contained in the collection.
	 */
	public void removeBlockAndApproversFrom(Collection<BlockWrap> blocks, BlockWrap startingBlock,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()));
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// Nothing to remove further if not in set
			if (!blocks.contains(block))
				continue;

			// Remove this block.
			blocks.remove(block);

			// Queue all of its approver blocks if not already queued.
			for (Sha256Hash req : store.getApproverBlockHashes(block.getBlockHash())) {
				if (!blockQueueSet.contains(req)) {
					BlockWrap pred = getBlockWrap(req, store);
					blockQueueSet.add(req);
					blockQueue.add(pred);
				}
			}
		}
	}

	/**
	 * Recursively adds the specified block and its approvers to the collection if
	 * the blocks are in the current milestone and not in the collection.
	 */
	public void addConfirmedApproversTo(Collection<BlockWrap> blocks, BlockWrap startingBlock,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()));
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// Nothing added if already in set or not confirmed
			if (!block.getBlockEvaluation().isConfirmed() || blocks.contains(block))
				continue;

			// Add this block.
			blocks.add(block);

			// Queue all of its confirmed approver blocks if not already queued.
			for (Sha256Hash req : store.getApproverBlockHashes(block.getBlockHash())) {
				if (!blockQueueSet.contains(req)) {
					BlockWrap pred = getBlockWrap(req, store);
					blockQueueSet.add(req);
					blockQueue.add(pred);
				}
			}
		}
	}

	/**
	 * Recursively adds the specified block and its approved as DAG to the
	 * collection as referenced if the blocks are not in the collection etc. if a
	 * required block as dependency (not DAG) is missing, raise exception .
	 *
	 */
	public void addReferencedBlockHashesTo(Set<BlockWrap> blocks, BlockWrap startingBlock, long cutoffHeight,
			long prevMilestoneNumber, List<Type> blocktypes, boolean checkSpentConflict, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// Nothing added if already in set
			if (checkExists(blocks, block))
				continue;

			// Nothing added if already in milestone
			if (block.getBlockEvaluation().getMilestone() >= 0
					&& block.getBlockEvaluation().getMilestone() <= prevMilestoneNumber)
				continue;

			// Check if the block is in cutoff and not in chain
			if (block.getBlock().getHeight() <= cutoffHeight && block.getBlockEvaluation().getMilestone() < 0) {
				continue;

			}

			// Add this block and its referenced.
			if (blocktypes == null) {
				if (!addBlockWithCheckReferenced(blocks, block, checkSpentConflict, checkMilestone, store)) {
					continue;
				}
			} else {
				boolean added = false;
				if (matchType(block, blocktypes))
					added = addBlockWithCheckReferenced(blocks, block, checkSpentConflict, checkMilestone, store);
				else {
					// not match the typ, but if the block is as referenced block, then set
					// added=true, for next step
					added = checkBlockReferenced(block, blocks);
				}
				if (!added) {
					// skip to the next
					continue;
				}

			}

			addPredecessors(store, blockQueue, blockQueueSet, block);
			addReferenced(store, blockQueue, blockQueueSet, block);
			checkRequiredBlock(store, blockQueueSet, block);
		}

	}

	private boolean checkBlockReferenced(BlockWrap block, Set<BlockWrap> blocks) {
		for (BlockWrap b : blocks) {
			if (getReferencedBlockHashes(b.getBlock()).stream().anyMatch(p -> p.equals(block.getBlockHash()))) {
				return true;
			}
		}
		return false;
	}

	private boolean matchType(BlockWrap block, List<Type> blocktypes) {

		for (Type type : blocktypes) {
			if (type.equals(block.getBlock().getBlockType())) {
				return true;
			}
		}
		return false;
	}

	private void addReferenced(BlockStoreInterface store, PriorityQueue<BlockWrap> blockQueue,
			Set<Sha256Hash> blockQueueSet, BlockWrap block) throws BlockStoreException {
		for (Sha256Hash req : getReferencedBlockHashes(block.getBlock())) {
			if (!blockQueueSet.contains(req)) {
				BlockWrap pred = getBlockWrap(req, store);
				if (pred != null) {
					addPredecessors(store, blockQueue, blockQueueSet, block);
				}
			}
		}
	}

	private void addPredecessors(BlockStoreInterface store, PriorityQueue<BlockWrap> blockQueue,
			Set<Sha256Hash> blockQueueSet, BlockWrap block) throws BlockStoreException {
		for (Sha256Hash req : getPredecessors(block.getBlock())) {
			if (!blockQueueSet.contains(req)) {
				BlockWrap pred = getBlockWrap(req, store);
				if (pred != null) {
					blockQueueSet.add(req);
					blockQueue.add(pred);
				}
			}
		}
	}

	/*
	 * add the block and its referenced BlockWrap to allApprovedNewBlocks, if
	 * checkSpentConflict then add this block and its referenced all or nothing
	 */
	public boolean addBlockWithCheckReferenced(Set<BlockWrap> allApprovedNewBlocks, BlockWrap block,
			boolean checkSpentConflict, boolean checkMilestone, BlockStoreInterface store) throws BlockStoreException {
		boolean check = true;
		if (checkSpentConflict) {
			Set<BlockWrap> checkBlocks = new HashSet<>();
			checkBlocks.add(block);
			// contract execution, then check all referenced blocks with no conflicts
			if (Type.BLOCKTYPE_CONTRACT_EXECUTE.equals(block.getBlock().getBlockType())
					|| Type.BLOCKTYPE_ORDER_EXECUTE.equals(block.getBlock().getBlockType())) {
				checkBlocks.addAll(getReferrencedBlockWrap(block.getBlock(), store));
			}
			check = checkSpentAndConflict(allApprovedNewBlocks, checkBlocks, checkMilestone, store);
		}
		if (check) {
			allApprovedNewBlocks.add(block);
			return true;
		} else {
			logger.debug("block is not added as there is spent conflict: " + block.toString());
		}
		return false;
	}

	private boolean checkSpentAndConflict(Set<BlockWrap> allApproved, Set<BlockWrap> newBlocks, boolean checkMilestone,
			BlockStoreInterface store) {
		Set<BlockWrap> allApprovedNewBlocks = new HashSet<>();

		allApprovedNewBlocks.addAll(allApproved);
		allApprovedNewBlocks.addAll(newBlocks);

		boolean anySpentInputs = hasSpentInputs(allApprovedNewBlocks, checkMilestone, store);

		if (anySpentInputs) {
			return false;

		}

		boolean anyCandidateConflicts = allApprovedNewBlocks.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.groupingBy(ConflictCandidate::getConflictPoint))
				.values().stream().anyMatch(l -> l.size() > 1);
		return !anyCandidateConflicts;

	}

	public boolean hasSpentInputs(Set<BlockWrap> allApprovedNewBlocks, boolean checkMilestone,
			BlockStoreInterface store) {
		return allApprovedNewBlocks.stream().map(BlockWrap::toConflictCandidates).flatMap(Collection::stream)
				.anyMatch(c -> {
					try {
						boolean re = hasConflictDependency(c, false, checkMilestone, store);
						if (re)
							logger.debug("hasSpentInputs {}", c.getBlock().getBlock().toString());
						return re;
					} catch (BlockStoreException e) {
						return true;
					}
				});
	}

	public Set<BlockWrap> collectPrevsChain(List<Contractresult> prevs, Contractresult prevMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		Set<BlockWrap> re = new HashSet<>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Contractresult prevNotMilestone : prevs) {
			re = new HashSet<>();
			Contractresult startingBlock = prevNotMilestone;
			while (startingBlock != null) {
				re.add(getBlockWrap(startingBlock.getBlockHash(), store));
				if (startingBlock.getPrevblockhash().equals(prevMilestone.getBlockHash())) {
					return re;
				} else {
					startingBlock = findPrev(prevs, startingBlock);
				}
			}
		}
		return re;
	}

	protected Orderresult findPrev(List<Orderresult> prevs, Orderresult result) {

		for (Orderresult b : prevs) {
			if (result.getPrevblockhash().equals(b.getBlockHash())) {
				return b;
			}
		}
		return null;

	}

	public Set<BlockWrap> collectPrevsChain(List<Orderresult> prevs, Orderresult prevMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		Set<BlockWrap> re = new HashSet<>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Orderresult prevNotMilestone : prevs) {
			re = new HashSet<>();
			Orderresult startingBlock = prevNotMilestone;
			while (startingBlock != null) {
				re.add(getBlockWrap(startingBlock.getBlockHash(), store));
				if (startingBlock.getPrevblockhash().equals(prevMilestone.getBlockHash())) {
					return re;
				} else {
					startingBlock = findPrev(prevs, startingBlock);
				}
			}
		}
		return re;
	}

	protected Contractresult findPrev(List<Contractresult> prevs, Contractresult result) {

		for (Contractresult b : prevs) {
			if (result.getPrevblockhash().equals(b.getBlockHash())) {
				return b;
			}
		}
		return null;

	}

	/**
	 * Recursively adds the specified block and its approved blocks to the
	 * collection if the blocks are not in the current milestone and not in the
	 * collection. if a block is missing somewhere, returns false. For check missing
	 * is not allowed, for build missing is not checked
	 *
	 */
	public void addRequiredUnconfirmedBlocksTo(Collection<BlockWrap> blocks, BlockWrap startingBlock, long cutoffHeight,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());
		// continue will skip this block as start

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// Nothing added if already in set or confirmed
			if (block.getBlockEvaluation().getMilestone() >= 0 || block.getBlockEvaluation().isConfirmed()
					|| blocks.contains(block))
				continue;

			// Check if the block is in cutoff and not in chain
			if (block.getBlock().getHeight() <= cutoffHeight && block.getBlockEvaluation().getMilestone() < 0) {
				continue;
			}

			// Add this block.
			blocks.add(block);

			addPredecessors(store, blockQueue, blockQueueSet, block);
			addReferenced(store, blockQueue, blockQueueSet, block);
			checkRequiredBlock(store, blockQueueSet, block);
		}

	}

	/*
	 * if a required Block is missing raise InfeasiblePrototypeException
	 */
	private void checkRequiredBlock(BlockStoreInterface store, Set<Sha256Hash> blockQueueSet, BlockWrap block)
			throws BlockStoreException {
		for (Sha256Hash req : getAllRequiredBlockHashes(block.getBlock())) {
			if (!blockQueueSet.contains(req)) {
				BlockWrap pred = getBlockWrap(req, store);
				if (pred == null) {
					throw new InfeasiblePrototypeException("missing required Block with hash= " + req.toString());
				}
			}
		}
	}

	public boolean hasSpentDependency(ConflictCandidate c, boolean checkNoConfirm, BlockStoreInterface store)
			throws BlockStoreException {
		return hasConflictDependency(c, checkNoConfirm, false, store);
	}

	/*
	 * check, if the inputs/prev is spent by other
	 * 
	 * Input/previous <--me -> false \ <-- other -> true
	 * 
	 * checkNoConfirm is for the check of unconfirmed inputs/prev checkMilestone is
	 * the check other spent in milestone as used check for reward
	 * 
	 * check only spent for chain
	 */
	public boolean hasConflictDependency(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		SpentBlockData s;
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			return checkUTXOSpent(c, checkNoConfirm, checkMilestone, store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();
			if (connectedToken.getTokenindex() == 0) {
				return store.getTokenAnyConfirmed(connectedToken.getTokenid(), connectedToken.getTokenindex());
			}
			s = store.getTokenSpent(connectedToken.getPrevblockhash());
			if (s == null)
				return false;
			else
				return checkSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, s, store);

		case REWARDISSUANCE:
			return store.getRewardSpent(c.getConflictPoint().getConnectedReward().getPrevRewardHash());
		case DOMAINISSUANCE:
			// exception for the block
			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();
			return store.getDomainIssuingConfirmedBlock(connectedDomainToken.getTokenname(),
					connectedDomainToken.getDomainNameBlockHash(), connectedDomainToken.getTokenindex()) != null;
		case CONTRACTEXECUTE:
			return checkContractSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, store);

		case ORDEREXECUTE:
			return checkOrderSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, store);

		default:
			throw new RuntimeException("Not Implemented");
		}
	}

	private boolean checkSpentOrNoConfirm(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			SpentBlockData prev, BlockStoreInterface store) throws BlockStoreException {
		return checkSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, prev.getBlockHash(), prev.getSpenderBlockHash(),
				prev.isConfirmed(), store);
	}

	private boolean checkSpentOrNoConfirm(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			Sha256Hash blockHash, Sha256Hash spenderBlockHash, boolean confirmed, BlockStoreInterface store)
			throws BlockStoreException {
		if (c.getBlock().getBlockHash().equals(spenderBlockHash)) {
			// ConflictCandidate is the SpenderBlockHash
			if (checkNoConfirm) {
				if (!confirmed)
					return true;
			}
			return false;
		} else {
			// other with conflict
			if (spenderBlockHash != null) {
				BlockWrap conflictBlock = getBlockWrap(spenderBlockHash, store);
				if (conflictBlock == null)
					return false;
				if (checkMilestone) {
					if (conflictBlock.getBlockEvaluation().getMilestone() > 0) {
						return true;
					}
				} else {
					if (conflictBlock.getBlockEvaluation().getMilestone() > 0
							|| conflictBlock.getBlockEvaluation().isConfirmed())
						return true;
				}
			}
		}
		return false;
	}

	private boolean checkSpentOrNoConfirm(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			UTXO prev, BlockStoreInterface store) throws BlockStoreException {
		return checkSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, prev.getBlockHash(), prev.getSpenderBlockHash(),
				prev.isConfirmed(), store);

	}

	private boolean checkContractSpentOrNoConfirm(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContractExecute();

		List<Contractresult> allWithPrev = store.getContractresultWithPrev(connectedContracExecute.getPrevblockhash());
		for (Contractresult s : allWithPrev) {
			if (c.getBlock().getBlockHash().equals(s.getBlockHash()))
				continue;

			if (!c.getBlock().getBlockHash().equals(s.getSpenderBlockHash())) {
				if (checkMilestone) {
					if (s.getMilestone() > 0) {
						return true;
					}
				} else {
					if (s.getMilestone() > 0 || s.isConfirmed())
						return true;
				}
			} else {
				// itself is the SpenderBlock.check only if the Confirm
				if (checkNoConfirm)
					if (!s.isConfirmed())
						return true;
			}
		}
		// the referenced check
		for (Sha256Hash ref : connectedContracExecute.getReferencedBlocks()) {
			for (ConflictCandidate r : getBlockWrap(ref, store).toConflictCandidates()) {
				if (hasConflictDependency(r, checkNoConfirm, checkMilestone, store))
					return true;
			}
		}
		return false;
	}

	private boolean checkOrderSpentOrNoConfirm(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		final OrderExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedOrderExecute();
		List<Orderresult> allWithPrev = store.getOrderresultWithPrev(connectedContracExecute.getPrevblockhash());
		for (Orderresult s : allWithPrev) {
			if (c.getBlock().getBlockHash().equals(s.getBlockHash()))
				continue;

			if (!c.getBlock().getBlockHash().equals(s.getSpenderBlockHash())) {
				if (checkMilestone) {
					if (s.getMilestone() > 0) {
						return true;
					}
				} else {
					if (s.getMilestone() > 0 || s.isConfirmed())
						return true;
				}
			} else {
				// itself is the SpenderBlock.check only if the Confirm
				if (checkNoConfirm)
					if (!s.isConfirmed())
						return true;
			}
		}
		// the referenced check
		for (Sha256Hash ref : connectedContracExecute.getReferencedBlocks()) {
			BlockWrap blockWrap = getBlockWrap(ref, store);
			if (blockWrap != null) {
				for (ConflictCandidate r : blockWrap.toConflictCandidates()) {
					if (hasConflictDependency(r, checkNoConfirm, checkMilestone, store))
						return true;
				}
			}
		}
		return false;
	}

	/*
	 * check, if the inputs/prev is confirmed
	 */
	private boolean checkUsedConfirmed(ConflictCandidate c, BlockStoreInterface store) throws BlockStoreException {
		SpentBlockData s;
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			return getUTXOConfirmed(c.getConflictPoint().getConnectedOutpoint(), store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();
			if ((connectedToken != null ? connectedToken.getTokenindex() : 0) == 0)
				return true;
			if (connectedToken == null)
				return false;
			s = store.getTokenSpent(connectedToken.getPrevblockhash());
			if (s == null)
				return false;
			else
				return s.isConfirmed();

		case REWARDISSUANCE:
			return store.getRewardConfirmed(c.getConflictPoint().getConnectedReward().getPrevRewardHash());
		case DOMAINISSUANCE:

			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();
			s = store.getTokenSpent(Sha256Hash.wrap(connectedDomainToken.getDomainNameBlockHash()));
			if (s == null)
				return false;
			else
				return s.isConfirmed();
		case CONTRACTEXECUTE:
			final ContractExecutionResult connectedContractExecute = c.getConflictPoint().getConnectedContractExecute();
			if (Sha256Hash.ZERO_HASH.equals(connectedContractExecute.getPrevblockhash()))
				return true;
			Contractresult b = store.getContractresult(connectedContractExecute.getPrevblockhash());
			if (b != null)
				return b.isConfirmed();
			return false;
		case ORDEREXECUTE:
			final OrderExecutionResult connectedOrderExecute = c.getConflictPoint().getConnectedOrderExecute();
			if (Sha256Hash.ZERO_HASH.equals(connectedOrderExecute.getPrevblockhash()))
				return true;
			Orderresult a = store
					.getOrderResult(connectedOrderExecute != null ? connectedOrderExecute.getPrevblockhash() : null);
			if (a != null)
				return a.isConfirmed();
			return false;

		default:
			throw new RuntimeException("not implemented");
		}
	}

	/*
	 * toConflictCandidates() takes all the inputs from transaction and read the
	 * input.getOutPoint() or the prev of chained data as token and Execution The
	 * conflitcs will be if there is other transaction spent my input or my prev
	 * chained execution or the input/prev is not confirmed.
	 */
	public boolean findBlockWithSpentOrUnconfirmedInputs(Set<BlockWrap> blocks, BlockStoreInterface store) {
		// Get all conflict candidates in blocks
		Stream<ConflictCandidate> candidates = blocks.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream);

		// Find conflict candidates whose used outputs are already spent or
		// still unconfirmed
		return candidates.anyMatch((ConflictCandidate c) -> {
			try {
				return hasSpentDependency(c, true, store);
			} catch (BlockStoreException e) {
				// e.printStackTrace();
			}
			return false;
		});
	}

	/**
	 * Resolves all conflicts such that the confirmed set is compatible with all
	 * blocks remaining in the set of blocks.
	 * 
	 * @param blocksToAdd the set of blocks to add to the current milestone
	 */
	public void resolveAllConflicts(Set<BlockWrap> blocksToAdd, long cutoffHeight, BlockStoreInterface store)
			throws BlockStoreException {
		// Cutoff: Remove if predecessors neither in milestone nor to be
		// confirmed
		removeWhereUnconfirmedRequirements(blocksToAdd, store);

		// Remove ineligible blocks, i.e. only reward blocks
		// since they follow a different logic
		removeWhereIneligible(blocksToAdd, store);

		// Remove blocks and their approvers that have at least one input
		// with its corresponding output not confirmed yet
		removeWhereUsedOutputsUnconfirmed(blocksToAdd, store);

		// Resolve conflicting block combinations:
		// Disallow conflicts with milestone blocks,
		// i.e. remove those whose input is already spent by such blocks
		removeMilestoneConflicts(blocksToAdd, store);

		// Then resolve conflicts between non-milestone + new candidates
		removeTemporaryConflicts(blocksToAdd, cutoffHeight, store);

	}

	/**
	 * Remove blocks from blocksToAdd that miss their required predecessors, i.e.
	 * the predecessors are not confirmed or in blocksToAdd.
	 *
	 */
	private void removeWhereUnconfirmedRequirements(Set<BlockWrap> blocksToAdd, BlockStoreInterface store)
			throws BlockStoreException {
		Iterator<BlockWrap> iterator = blocksToAdd.iterator();
		while (iterator.hasNext()) {
			BlockWrap b = iterator.next();
			List<BlockWrap> allRequirements = getAllBlocksFromHash(getAllRequiredBlockHashes(b.getBlock(), false),
					store);
			for (BlockWrap req : allRequirements) {
				if (!req.getBlockEvaluation().isConfirmed() && !blocksToAdd.contains(req)) {
					iterator.remove();
					break;
				}
			}
		}
	}

	/**
	 * Remove blocks from blocksToAdd that are currently locally ineligible.
	 *
	 */
	public void removeWhereIneligible(Set<BlockWrap> blocksToAdd, BlockStoreInterface store) {
		findWhereCurrentlyIneligible(blocksToAdd).forEach(b -> {
			try {
				removeBlockAndApproversFrom(blocksToAdd, b, store);
			} catch (BlockStoreException e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Find blocks from blocksToAdd that are currently locally ineligible.
	 *
	 */
	private Set<BlockWrap> findWhereCurrentlyIneligible(Set<BlockWrap> blocksToAdd) {
		return blocksToAdd.stream().filter(b -> b.getBlock().getBlockType() == Type.BLOCKTYPE_REWARD)
				.collect(Collectors.toSet());
	}

	/**
	 * Remove blocks from blocksToAdd that have at least one used output not
	 * confirmed yet. They may however be spent already, since this leads to
	 * conflicts.
	 *
	 */
	public void removeWhereUsedOutputsUnconfirmed(Set<BlockWrap> blocksToAdd, BlockStoreInterface store) {
		// Confirmed blocks are always ok
		new HashSet<>(blocksToAdd).stream().filter(b -> !b.getBlockEvaluation().isConfirmed())
				.flatMap(b -> b.toConflictCandidates().stream()).filter(c -> {
					try {
						boolean re = !checkUsedConfirmed(c, store);
						// if(re) logger.debug( "checkUsedConfirmed"+c.toString());
						return re;
					} catch (BlockStoreException e) {
						throw new RuntimeException(e);
					}
				}).forEach(c -> {
					try {
						removeBlockAndApproversFrom(blocksToAdd, c.getBlock(), store);
					} catch (BlockStoreException e) {
						throw new RuntimeException(e);
					}
				});
	}

	public void removeMilestoneConflicts(Set<BlockWrap> blocksToAdd, BlockStoreInterface store)
			throws BlockStoreException {
		// Find all conflict candidates in blocks to add
		List<ConflictCandidate> conflicts = blocksToAdd.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.toList());

		// Find only those that are spent
		filterSpent(conflicts, store);

		// Drop any spent by milestone
		for (ConflictCandidate c : conflicts) {
			// Find the spending block we are competing with
			BlockWrap milestoneBlock = getSpendingBlock(c, store);

			// If it is pruned or a milestone, we drop the blocks
			if (milestoneBlock == null || milestoneBlock.getBlockEvaluation().getMilestone() != -1) {
				removeBlockAndApproversFrom(blocksToAdd, c.getBlock(), store);
			}
		}
	}

	/**
	 * Resolves conflicts between non-milestone blocks and candidates
	 *
	 */
	private void removeTemporaryConflicts(Set<BlockWrap> blocksToAdd, long cutoffHeight, BlockStoreInterface store)
			throws BlockStoreException {
		HashSet<ConflictCandidate> conflictingOutPoints = new HashSet<>();
		HashSet<BlockWrap> conflictingConfirmedBlocks = new HashSet<>();

		// Find all conflicts in the new blocks + confirmed blocks
		findFixableConflicts(blocksToAdd, conflictingOutPoints, conflictingConfirmedBlocks, store);

		// Resolve all conflicts by grouping by UTXO ordered by descending
		// rating
		HashSet<BlockWrap> losingBlocks = resolveTemporaryConflicts(conflictingOutPoints, blocksToAdd, cutoffHeight,
				store);

		// For confirmed blocks that have been eliminated call disconnect
		// procedure
		HashSet<Sha256Hash> traversedUnconfirms = new HashSet<>();
		for (BlockWrap b : conflictingConfirmedBlocks.stream().filter(losingBlocks::contains).toList()) {
			unconfirm(b, traversedUnconfirms, -1, store);
		}

		// For candidates that have been eliminated (conflictingOutPoints in
		// blocksToAdd \ winningBlocks) remove them from blocksToAdd
		for (BlockWrap b : losingBlocks) {
			removeBlockAndApproversFrom(blocksToAdd, b, store);
		}
	}

	/**
	 * Resolve all conflicts by grouping by UTXO ordered by descending rating.
	 * 
	 * @return losingBlocks: blocks that have been removed due to conflict
	 *         resolution
	 */
	private HashSet<BlockWrap> resolveTemporaryConflicts(Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> blocksToAdd, long cutoffHeight, BlockStoreInterface store) throws BlockStoreException {
		// Initialize blocks that will/will not survive the conflict resolution
		HashSet<BlockWrap> initialBlocks = conflictingOutPoints.stream().map(ConflictCandidate::getBlock)
				.collect(Collectors.toCollection(HashSet::new));
		HashSet<BlockWrap> winningBlocks = new HashSet<>(blocksToAdd);
		for (BlockWrap winningBlock : initialBlocks) {
			addRequiredUnconfirmedBlocksTo(winningBlocks, winningBlock, cutoffHeight, store);
			addConfirmedApproversTo(winningBlocks, winningBlock, store);
		}
		HashSet<BlockWrap> losingBlocks = new HashSet<>(winningBlocks);

		// Sort conflicts internally by descending rating, then cumulative
		// weight.
		Supplier<TreeSet<ConflictCandidate>> conflictTreeSetSupplier = getTreeSetSupplier();

		Map<Object, TreeSet<ConflictCandidate>> conflicts = conflictingOutPoints.stream().collect(Collectors
				.groupingBy(ConflictCandidate::getConflictPoint, Collectors.toCollection(conflictTreeSetSupplier)));

		// Sort conflicts among each other by descending max(rating).
		Supplier<TreeSet<TreeSet<ConflictCandidate>>> conflictsTreeSetSupplier = getSetSupplier();

		TreeSet<TreeSet<ConflictCandidate>> sortedConflicts = conflicts.values().stream()
				.collect(Collectors.toCollection(conflictsTreeSetSupplier));

		// Now handle conflicts by descending max(rating)
		for (TreeSet<ConflictCandidate> conflict : sortedConflicts) {
			// Take the block with the maximum rating in this conflict that is
			// still in winning Blocks
			ConflictCandidate maxRatingPair = null;
			for (ConflictCandidate c : conflict) {
				if (winningBlocks.contains(c.getBlock())) {
					maxRatingPair = c;
					break;
				}
			}

			// If such a block exists, this conflict is resolved by eliminating
			// all other blocks in this conflict from winning blocks
			if (maxRatingPair != null) {
				for (ConflictCandidate c : conflict) {
					if (c != maxRatingPair) {
						removeBlockAndApproversFrom(winningBlocks, c.getBlock(), store);
					}
				}
			}
		}

		losingBlocks.removeAll(winningBlocks);

		return losingBlocks;
	}

	@NotNull
	private Supplier<TreeSet<TreeSet<ConflictCandidate>>> getSetSupplier() {
		Comparator<TreeSet<ConflictCandidate>> byDescendingSetRating = getConflictSetComparator()
				.thenComparingLong((TreeSet<ConflictCandidate> s) -> s.first().getBlock().getMcmc().getRating())
				.thenComparingLong(
						(TreeSet<ConflictCandidate> s) -> s.first().getBlock().getMcmc().getCumulativeWeight())
				.thenComparingLong(
						(TreeSet<ConflictCandidate> s) -> -s.first().getBlock().getBlockEvaluation().getInsertTime())
				.thenComparing(
						(TreeSet<ConflictCandidate> s) -> s.first().getBlock().getBlockEvaluation().getBlockHash())
				.reversed();

		return () -> new TreeSet<>(byDescendingSetRating);
	}

	@NotNull
	private Supplier<TreeSet<ConflictCandidate>> getTreeSetSupplier() {
		Comparator<ConflictCandidate> byDescendingRating = getConflictComparator()
				.thenComparingLong((ConflictCandidate e) -> e.getBlock().getMcmc().getRating())
				.thenComparingLong((ConflictCandidate e) -> e.getBlock().getMcmc().getCumulativeWeight())
				.thenComparingLong((ConflictCandidate e) -> -e.getBlock().getBlockEvaluation().getInsertTime())
				.thenComparing((ConflictCandidate e) -> e.getBlock().getBlockEvaluation().getBlockHash()).reversed();

		return () -> new TreeSet<>(byDescendingRating);
	}

	private Comparator<TreeSet<ConflictCandidate>> getConflictSetComparator() {
		return (o1, o2) -> 0;
	}

	private Comparator<ConflictCandidate> getConflictComparator() {
		return (o1, o2) -> 0;
	}

	/**
	 * Finds conflicts in blocksToAdd itself and with the confirmed blocks.
	 *
	 */
	private void findFixableConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> conflictingConfirmedBlocks, BlockStoreInterface store) throws BlockStoreException {

		findUndoableConflicts(blocksToAdd, conflictingOutPoints, conflictingConfirmedBlocks, store);
		findCandidateConflicts(blocksToAdd, conflictingOutPoints);
	}

	/**
	 * Finds conflicts among blocks to add themselves
	 *
	 */
	private void findCandidateConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints) {
		// Get conflicts that are spent more than once in the
		// candidates
		List<ConflictCandidate> candidateCandidateConflicts = blocksToAdd.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.groupingBy(ConflictCandidate::getConflictPoint))
				.values().stream().filter(l -> l.size() > 1).flatMap(Collection::stream).toList();

		// Add the conflicting candidates
		conflictingOutPoints.addAll(candidateCandidateConflicts);
	}

	/**
	 * Finds conflicts between current confirmed and blocksToAdd
	 *
	 */
	private void findUndoableConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> conflictingConfirmedBlocks, BlockStoreInterface store) throws BlockStoreException {
		// Find all conflict candidates in blocks to add
		List<ConflictCandidate> conflicts = blocksToAdd.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.toList());

		// Find only those that are spent in confirmed
		filterSpent(conflicts, store);

		// Add the conflicting candidates and confirmed blocks to given set
		for (ConflictCandidate c : conflicts) {
			// Find the spending block we are competing with
			BlockWrap confirmedBlock = getSpendingBlock(c, store);

			// Only go through if the block is undoable, i.e. not milestone
			if (confirmedBlock == null || confirmedBlock.getBlockEvaluation().getMilestone() != -1)
				continue;

			// Add confirmed block
			conflictingOutPoints.add(ConflictCandidate.fromConflictPoint(confirmedBlock, c.getConflictPoint()));
			conflictingConfirmedBlocks.add(confirmedBlock);

			// Then add corresponding new block
			conflictingOutPoints.add(c);
		}
	}

	// Returns null if no spending block found
	private BlockWrap getSpendingBlock(ConflictCandidate c, BlockStoreInterface store) throws BlockStoreException {
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			final BlockEvaluation utxoSpender = getUTXOSpender(c.getConflictPoint().getConnectedOutpoint(), store);
			if (utxoSpender == null)
				return null;
			return getBlockWrap(utxoSpender.getBlockHash(), store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();

			// The spender is always the one block with the same tokenid and
			// index that is confirmed
			return store.getTokenIssuingConfirmedBlock(connectedToken.getTokenid(), connectedToken.getTokenindex());
		case REWARDISSUANCE:
			final Sha256Hash txRewardSpender = store
					.getRewardSpender(c.getConflictPoint().getConnectedReward().getPrevRewardHash());
			if (txRewardSpender == null)
				return null;
			return getBlockWrap(txRewardSpender, store);
		case DOMAINISSUANCE:
			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();

			// The spender is always the one block with the same domainname and
			// predecessing domain tokenid that is confirmed
			return store.getDomainIssuingConfirmedBlock(connectedDomainToken.getTokenname(),
					connectedDomainToken.getDomainNameBlockHash(), connectedDomainToken.getTokenindex());
		case CONTRACTEXECUTE:
			final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContractExecute();
			Sha256Hash t = connectedContracExecute.getSpenderBlockHash();
			if (t == null)
				return null;
			return getBlockWrap(t, store);
		case ORDEREXECUTE:
			final OrderExecutionResult connectedOrderExecute = c.getConflictPoint().getConnectedOrderExecute();
			Sha256Hash spent = connectedOrderExecute.getSpenderBlockHash();
			if (spent == null)
				return null;
			return getBlockWrap(spent, store);
		default:
			throw new RuntimeException("No Implementation");
		}
	}

	private void filterSpent(Collection<ConflictCandidate> blockConflicts, BlockStoreInterface store) {
		blockConflicts.removeIf(c -> {
			try {
				return !hasSpentDependency(c, false, store);
			} catch (BlockStoreException e) {
				// e.printStackTrace();
				return true;
			}
		});
	}

	protected void showConflict(Set<BlockWrap> allApprovedNewBlocks) {
		List<List<ConflictCandidate>> candidateConflicts = allApprovedNewBlocks.stream()
				.map(BlockWrap::toConflictCandidates).flatMap(Collection::stream)
				.collect(Collectors.groupingBy(ConflictCandidate::getConflictPoint)).values().stream()
				.filter(l -> l.size() > 1).toList();
		for (List<ConflictCandidate> l : candidateConflicts) {
			for (ConflictCandidate c : l) {
				logger.debug(" conflict list: {}", c.toString());
			}
		}
	}

	/*
	 * return if TransactionOutPoint of ConflictCandidate c is spent by other
	 */
	private boolean checkUTXOSpent(ConflictCandidate c, boolean checkNoConfirm, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		TransactionOutPoint txout = c.getConflictPoint().getConnectedOutpoint();
		UTXO a = null;
		try {
			byte[] utxobyte = cacheBlockService.getTransactionOutput(utxoKey(txout), store);
			if (utxobyte != null)
				a = jsonmapper.readValue(utxobyte, UTXO.class);
		} catch (Exception e) {
			logger.debug(" ", e);
		}
		// the TransactionOutPoint does not exist, try do the calculation
		// blocks can be out of order and ignore the exception
		if (a == null) {
			try {
				Block block = getBlock(txout.getBlockHash(), store);
				if (block != null) {
					solidifyWaiting(block, store);
					a = store.getTransactionOutput(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
				}
			} catch (Exception e) {
				logger.debug(" ", e);
			}
		}
		// the TransactionOutPoint does not exist, assume no conflict
		if (a == null)
			return false;
		boolean re = checkSpentOrNoConfirm(c, checkNoConfirm, checkMilestone, a, store);

//		if (re) {
//			try {
//				logger.debug("getUTXOSpent true {}\n TransactionOutPoint = {}  ", a, txout);
//			} catch (Exception e) {
//				// logger.debug("", e);
//			}
//		}

		return re;

	}

	private UTXO utxoKey(TransactionOutPoint txout) {
		UTXO u = new UTXO();
		u.setBlockHash(txout.getBlockHash());
		u.setHash(txout.getTxHash());
		u.setIndex(txout.getIndex());
		return u;
	}

	/**
	 * Checks if the given set is eligible to be walked to during local approval tip
	 * selection given the currentcheckBur set of non-confirmed blocks to include.
	 * This is the case if the set is compatible with the current milestone. It must
	 * disallow spent prev UTXOs / unconfirmed prev UTXOs
	 * 
	 * @param currentApprovedUnconfirmedBlocks The set of all currently approved
	 *                                         unconfirmed blocks.
	 * @return true if the given set is eligible
	 */
	public boolean isEligibleForApprovalSelection(HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			BlockStoreInterface store) {
		// Currently ineligible blocks are not ineligible. If we find one, we
		// must stop
		if (!findWhereCurrentlyIneligible(currentApprovedUnconfirmedBlocks).isEmpty())
			return false;

		// If there exists a new block whose dependency is already spent
		// or not confirmed yet, we fail to approve this block since the
		// current set of confirmed blocks takes precedence
		if (findBlockWithSpentOrUnconfirmedInputs(currentApprovedUnconfirmedBlocks, store))
			return false;

		// If conflicts among the approved blocks exist, cannot approve
		HashSet<ConflictCandidate> conflictingOutPoints = new HashSet<>();
		findCandidateConflicts(currentApprovedUnconfirmedBlocks, conflictingOutPoints);
		return conflictingOutPoints.isEmpty();

		// Otherwise, the new approved block set is compatible with current
		// confirmation set
	}

	/**
	 * Checks if the given block is eligible to be walked to during local approval
	 * tip selection given the current set of unconfirmed blocks to include. This is
	 * the case if the block + the set is compatible with the current confirmeds. It
	 * must disallow spent prev UTXOs / unconfirmed prev UTXOs or unsolid blocks.
	 * 
	 * @param block                            The block to check for eligibility.
	 * @param currentApprovedUnconfirmedBlocks The set of all currently approved
	 *                                         unconfirmed blocks.
	 * @return true if the given block is eligible to be walked to during approval
	 *         tip selection.
	 */
	public boolean isEligibleForApprovalSelection(BlockWrap block, HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			long cutoffHeight, long maxHeight, BlockStoreInterface store) throws BlockStoreException {
		// Any confirmed blocks are always compatible with the current
		// confirmeds
		if (block.getBlockEvaluation().isConfirmed())
			return true;

		// Unchecked blocks are not allowed
		if (block.getBlockEvaluation().getSolid() < 2)
			return false;

		// Above maxHeight is not allowed
		if (block.getBlockEvaluation().getHeight() > maxHeight)
			return false;

		// below cutoffHeight is not allowed
		if (block.getBlockEvaluation().getHeight() < cutoffHeight)
			return false;

		// Get sets of all / all new unconfirmed blocks when approving the
		// specified block in combination with the currently included blocks
		@SuppressWarnings("unchecked")
		HashSet<BlockWrap> allApprovedUnconfirmedBlocks = (HashSet<BlockWrap>) currentApprovedUnconfirmedBlocks.clone();
		try {
			addRequiredUnconfirmedBlocksTo(allApprovedUnconfirmedBlocks, block, cutoffHeight, store);
		} catch (VerificationException e) {
			return false;
		}

		// If this set of blocks is eligible, all is fine
		return isEligibleForApprovalSelection(allApprovedUnconfirmedBlocks, store);
	}

	/*
	 * The blocks is confirmed and do the confirm of its transaction and BlockType
	 * special data. Confirmation of order and contract event are controlled by its
	 * execution via referenced only.
	 */
	private void confirmBlockTransactionWithType(BlockWrap block, long milestoneNumber, boolean confirmation,
			BlockStoreInterface blockStore) throws BlockStoreException {

		// confirm transactions
		for (final Transaction tx : block.getBlock().getTransactions()) {
			confirmTransaction(block.getBlock(), confirmation, tx, blockStore);
		}
		// type-specific updates
		switch (block.getBlock().getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE, BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER,
				BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL, BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		case BLOCKTYPE_REWARD:
			confirmReward(block, confirmation, blockStore);
			// For history, OrderMatching is part of rewards
			if (!enableOrderMatchExecutionChain(block.getBlock())) {
				confirmOrderMatching(block, confirmation, blockStore);
			}
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			confirmToken(block, confirmation, blockStore);
			break;
		case BLOCKTYPE_USERDATA:
			confirmVOSOrUserData(block, confirmation, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			confirmContractExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			confirmOrderExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}
	}

	private void confirmVOSOrUserData(BlockWrap block, boolean confirmation, BlockStoreInterface blockStore)
			throws BlockStoreException {
		Transaction tx = block.getBlock().getTransactions().get(0);
		if (tx.getData() != null && tx.getDataSignature() != null) {

			try {
				@SuppressWarnings("unchecked")
				List<HashMap<String, Object>>

				multiSignBies = jsonmapper.readValue(tx.getDataSignature(), List.class);

				Map<String, Object> multiSignBy = multiSignBies.get(0);
				byte[] pubKey = Utils.HEX.decode((String) multiSignBy.get("publickey"));
				byte[] data = tx.getHash().getBytes();
				byte[] signature = Utils.HEX.decode((String) multiSignBy.get("signature"));
				boolean success = ECKey.verify(data, signature, pubKey);
				if (!success) {
					throw new BlockStoreException("multisign signature error");
				}
				if (confirmation)
					synchronizationUserData(block.getBlock().getHash(), DataClassName.valueOf(tx.getDataClassName()),
							tx.getData(), (String) multiSignBy.get("publickey"),
							block.getBlock().getBlockType().ordinal(), blockStore);

			} catch (Exception e) {
				throw new BlockStoreException("multisign signature error");
			}
		}
	}

	private void confirmOrderMatching(BlockWrap block, boolean confirmation, BlockStoreInterface blockStore)
			throws BlockStoreException {
		// Get list of consumed orders, virtual order matching tx and newly
		// generated remaining order book

		OrderMatchingResult actualCalculationResult = generateOrderMatching(block.getBlock(), blockStore);
		confirmOrderMatching(block.getBlock(), actualCalculationResult, confirmation, blockStore);

	}

	private void confirmOrderMatching(Block block, OrderMatchingResult actualCalculationResult, boolean confirmation,
			BlockStoreInterface blockStore) throws BlockStoreException {
		if (confirmation) {
			confirmOrderMatching(block, actualCalculationResult, blockStore);
		} else {
			unconfirmOrderMatching(block, actualCalculationResult, blockStore);
		}

	}

	private void confirmOrderMatching(Block block, OrderMatchingResult actualCalculationResult,
			BlockStoreInterface blockStore) throws BlockStoreException {

		// All consumed order records are now spent by this block
		for (OrderRecord o : actualCalculationResult.getSpentOrders()) {
			o.setSpent(true);
			o.setSpenderBlockHash(block.getHash());
		}

		blockStore.updateOrderSpent(actualCalculationResult.getSpentOrders());
		// Set virtual outputs confirmed
		confirmVirtualCoinbaseTransaction(block, true, blockStore);
		// Set new orders confirmed

		blockStore.updateOrderConfirmed(actualCalculationResult.getRemainingOrders(), true);

		// Update the matching history in db
		addMatchingEvents(actualCalculationResult, actualCalculationResult.getOutputTx().getHashAsString(),
				block.getTimeSeconds(), blockStore);
	}

	private void unconfirmOrderMatching(Block block, OrderMatchingResult matchingResult, BlockStoreInterface blockStore)
			throws BlockStoreException {

		// All consumed order records are now unspent by this block
		Set<OrderRecord> updateOrder = new HashSet<>(matchingResult.getSpentOrders());
		for (OrderRecord o : updateOrder) {
			o.setSpent(false);
			o.setSpenderBlockHash(null);
		}
		blockStore.updateOrderSpent(updateOrder);

		// Set virtual outputs unconfirmed
		confirmVirtualCoinbaseTransaction(block, false, blockStore);

		blockStore.updateOrderConfirmed(matchingResult.getRemainingOrders(), false);

		// Update the matching history in db
		removeMatchingEvents(matchingResult.getOutputTx().getHash(), blockStore);
	}

	private void confirmOrderExecute(Block block, long milestoneNumber, boolean confirm, BlockStoreInterface blockStore)
			throws BlockStoreException {

		try {
			OrderExecutionResult result = new OrderExecutionResult().parse(block.getTransactions().get(0).getData());
			Orderresult prevblockhash = blockStore.getOrderResult(result.getPrevblockhash());
			OrderExecutionResult check = new ServiceOrderExecution(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper)
					.orderMatching(block, prevblockhash, result.getReferencedBlocks(), blockStore);

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getToBeSpent().equals(check.getToBeSpent())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {
				// debugOrderExecutionResult(block, check, confirm, blockStore);

				if (confirm) {

					for (Sha256Hash dep : check.getToBeSpent()) {
						confirmOrderAndTransaction(getBlock(dep, blockStore), true, milestoneNumber, blockStore);
					}
					for (OrderRecord c : check.getToBeSpentRecord()) {
						c.setConfirmed(true);
						c.setSpent(true);
						c.setSpenderBlockHash(block.getHash());
					}
					blockStore.updateOrderSpent(check.getToBeSpentRecord());

					blockStore.updateOrderConfirmed(check.getRemainderOrderRecord(), true);

					for (Sha256Hash ref : check.getReferencedBlocks()) {
						blockStore.updateOrderBlockhash(ref, Sha256Hash.ZERO_HASH, true, true, block.getHash());
					}
					// update cancel
					blockStore.updateOrderCancelSpent(check.getCancelRecords(), block.getHash(), true);

					// update order result
					confirmTransaction(block, true, check.getOutputTx(), blockStore);
					blockStore.updateOrderresultMilestone(block.getHash(), milestoneNumber);
					blockStore.updateOrderResultConfirmed(block.getHash(), true);
					blockStore.updateOrderResultSpent(check.getPrevblockhash(), block.getHash(), true);

					// Update the matching
					addMatchingEventsOrderExecution(check, check.getOutputTx().getHashAsString(),
							block.getTimeSeconds(), blockStore);
				} else {

					// referenced block can be referenced by conflict block
					for (Sha256Hash ref : check.getReferencedBlocks()) {
						OrderRecord event = blockStore.getOrder(ref, Sha256Hash.ZERO_HASH);
						BlockWrap refBlock = getBlockWrap(ref, blockStore);
						if (event != null && block.getHash().equals(event.getSpenderBlockHash())) {
							blockStore.updateOrderBlockhash(ref, Sha256Hash.ZERO_HASH, false, false, null);
							confirmOrderAndTransaction(refBlock.getBlock(), false, -1, blockStore);
						} else {
							if (event != null && event.getSpenderBlockHash() != null)
								logger.debug("referenced block can be referenced by other block:" + refBlock.toString()
										+ "\n spenderBlockHash: " + event.getSpenderBlockHash());
						}
					}

					for (OrderRecord c : check.getToBeSpentRecord()) {
						c.setSpent(false);
						c.setSpenderBlockHash(null);
					}
					blockStore.updateOrderSpent(check.getToBeSpentRecord());

					blockStore.updateOrderConfirmed(check.getRemainderOrderRecord(), false);

					mayRestoreprevOrderExecution(blockStore, result, prevblockhash, block);
					// update cancel
					blockStore.updateOrderCancelSpent(check.getCancelRecords(), null, false);

					// update order result
					confirmTransaction(block, false, check.getOutputTx(), blockStore);
					blockStore.updateOrderResultConfirmed(block.getHash(), false);
					blockStore.updateOrderresultMilestone(block.getHash(), -1);
					blockStore.updateOrderResultSpent(check.getPrevblockhash(), null, false);
				}

				evictTransactionsAndBlockEva(block, blockStore);

			} else {
				logger.debug("check failed result={} check ={}", result, check != null ? check.toString() : null);
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void mayRestoreprevOrderExecution(BlockStoreInterface blockStore, OrderExecutionResult result,
			Orderresult prevblockhash, Block block) throws BlockStoreException {
		if (prevblockhash.getOrderExecutionResult() != null) {
			if (!checkOrderresultOtherSpent(prevblockhash, block, blockStore)) {
				BlockWrap prevBlock = getBlockWrap(result.getPrevblockhash(), blockStore);
				if (prevBlock.getBlockEvaluation().isConfirmed()
						|| prevBlock.getBlockEvaluation().getMilestone() > -1) {
					// recalculate the prev confirmed
					confirmOrderExecute(prevBlock.getBlock(), prevBlock.getBlockEvaluation().getMilestone(), true,
							blockStore);
					evictTransactionsAndBlockEva(prevBlock.getBlock(), blockStore);
				}
			}
		}
	}

	public void checkSum(BlockStoreInterface blockStore) {

		try {
			TokensumsMap map = checkToken(blockStore);
			Map<String, Tokensums> r11 = map.getTokensumsMap();

			for (Entry<String, Tokensums> a : r11.entrySet()) {

				if (!a.getValue().check()) {
					logger.debug(a.getValue().toString());
					logger.debug("checkSum failed");
				}

			}
		} catch (Exception e) {
			// ignore as check only
		}

	}

	/*
	 * connect from the contract Execution
	 */
	private void confirmContractExecute(Block block, long milestoneNumber, boolean confirm,
			BlockStoreInterface blockStore) throws BlockStoreException {

		try {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(block.getTransactions().get(0).getData());
			Contractresult prevblockhash = blockStore.getContractresult(result.getPrevblockhash());
			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper).executeContract(block, blockStore, result.getContracttokenid(),
							prevblockhash, result.getReferencedBlocks());

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getToBeSpent().equals(check.getToBeSpent())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {

				if (confirm) {
					for (ContractEventRecord c : check.getToBeSpentContractEventRecord()) {
						c.setConfirmed(true);
						c.setSpent(true);
						c.setSpenderBlockHash(block.getHash());
					}
					blockStore.updateContractEventSpent(check.getToBeSpentContractEventRecord());

					for (ContractEventRecord c : check.getRemainderContractEventRecord()) {
						c.setConfirmed(true);
						c.setSpent(false);
						c.setSpenderBlockHash(null);
						// remainder set the collection hash using block hash, as the calculation of
						// execution the block hash is done only at solve block.
						c.setCollectinghash(block.getHash());
					}
					blockStore.updateContractEventSpent(check.getRemainderContractEventRecord());

					for (Sha256Hash ref : check.getReferencedBlocks()) {
						blockStore.updateContractEventBlockhash(ref, Sha256Hash.ZERO_HASH, true, true, block.getHash());
						confirmContractEventTransaction(getBlock(ref, blockStore), true, milestoneNumber, blockStore);
					}

					// update ContractResult
					blockStore.updateContractresultMilestone(block.getHash(), milestoneNumber);
					blockStore.updateContractResultConfirmed(block.getHash(), true);
					confirmTransaction(block, true, check.getOutputTx(), blockStore);
					blockStore.updateContractResultSpent(check.getPrevblockhash(), block.getHash(), true);

					// update cancel
					blockStore.updateContractEventCancelSpent(check.getCancelRecords(), block.getHash(), true);

				} else {
					Sha256Hash spenderBlockHash = prevblockhash.getSpenderBlockHash();
					// referenced block can be referenced by conflict block
					for (Sha256Hash ref : check.getReferencedBlocks()) {
						ContractEventRecord event = blockStore.getContractEvent(ref, Sha256Hash.ZERO_HASH);
						BlockWrap refBlock = getBlockWrap(ref, blockStore);
						if (block.getHash().equals(event.getSpenderBlockHash())) {
							blockStore.updateContractEventBlockhash(ref, Sha256Hash.ZERO_HASH, false, false, null);
							confirmContractEventTransaction(refBlock.getBlock(), false, -1, blockStore);
						} else {
							if (event.getSpenderBlockHash() != null)
								logger.debug("referenced block can be referenced by other block:" + refBlock.toString()
										+ "\n spenderBlockHash: " + event.getSpenderBlockHash());
						}
					}

					for (ContractEventRecord c : check.getToBeSpentContractEventRecord()) {
						c.setConfirmed(false);
						c.setSpent(false);
						c.setSpenderBlockHash(null);
					}
					blockStore.updateContractEventSpent(check.getToBeSpentContractEventRecord());

					for (ContractEventRecord c : check.getRemainderContractEventRecord()) {
						c.setConfirmed(false);
						c.setSpent(false);
						c.setSpenderBlockHash(null);
						// remainder set the collection hash using block hash, as the calculation of
						// execution the block hash is not fixed.
						c.setCollectinghash(block.getHash());
					}
					blockStore.updateContractEventSpent(check.getRemainderContractEventRecord());

					// update ContractResult
					blockStore.updateContractresultMilestone(block.getHash(), -1);
					blockStore.updateContractResultConfirmed(block.getHash(), false);
					confirmTransaction(block, false, check.getOutputTx(), blockStore);

					if (block.getHash().equals(spenderBlockHash)) {
						blockStore.updateContractResultSpent(check.getPrevblockhash(), null, false);
					}
					// update cancel
					blockStore.updateContractEventCancelSpent(check.getCancelRecords(), block.getHash(), false);
					// restore the prev state, if this block is spender of the prev
					mayRestorePrevContractExecution(block, blockStore, prevblockhash);
				}
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void mayRestorePrevContractExecution(Block block, BlockStoreInterface blockStore,
			Contractresult prevblockhash) throws BlockStoreException {
		if (prevblockhash.getContractExecutionResult() != null) {
			if (!checkContractresultOtherSpent(prevblockhash, block, blockStore)) {
				BlockWrap prevBlock = getBlockWrap(prevblockhash.getBlockHash(), blockStore);
				if (prevBlock.getBlockEvaluation().isConfirmed() || prevBlock.getBlockEvaluation().getMilestone() > 0) {
					confirmContractExecute(prevBlock.getBlock(), prevBlock.getBlockEvaluation().getMilestone(), true,
							blockStore);
					evictTransactionsAndBlockEva(prevBlock.getBlock(), blockStore);
				}
			}
		}
	}

	private boolean checkContractresultOtherSpent(Contractresult prev, Block c, BlockStoreInterface store)
			throws BlockStoreException {
		List<Contractresult> allWithPrev = store.getContractresultWithPrev(prev.getBlockHash());
		for (Contractresult s : allWithPrev) {
			if (c.getHash().equals(s.getBlockHash()))
				continue;

			if (!c.getHash().equals(s.getSpenderBlockHash())) {
				if (s.getMilestone() > 0) {
					return true;
				}
			} else {
				if (s.isConfirmed())
					return true;
			}
		}
		return false;
	}

	private boolean checkOrderresultOtherSpent(Orderresult prev, Block c, BlockStoreInterface store)
			throws BlockStoreException {
		List<Orderresult> allWithPrev = store.getOrderresultWithPrev(prev.getBlockHash());
		for (Orderresult s : allWithPrev) {
			if (c.getHash().equals(s.getBlockHash()))
				continue;

			if (!c.getHash().equals(s.getSpenderBlockHash())) {
				if (s.getMilestone() > 0) {
					return true;
				}
			} else {
				if (s.isConfirmed())
					return true;
			}
		}
		return false;
	}

	private void confirmReward(BlockWrap block, boolean confirm, BlockStoreInterface blockStore)
			throws BlockStoreException {
		// Set virtual reward tx outputs confirmed
		confirmVirtualCoinbaseTransaction(block.getBlock(), confirm, blockStore);

		if (confirm)
			blockStore.updateRewardSpent(blockStore.getRewardPrevBlockHash(block.getBlock().getHash()), true,
					block.getBlock().getHash());
		else {
			blockStore.updateRewardSpent(blockStore.getRewardPrevBlockHash(block.getBlock().getHash()), false, null);
		}

		// Set own output confirmed
		blockStore.updateRewardConfirmed(block.getBlock().getHash(), confirm);
		cacheBlockService.evictMaxConfirmedReward();
	}

	private void confirmToken(BlockWrap block, boolean confirm, BlockStoreInterface blockStore)
			throws BlockStoreException {
		// Set used other output spent
		if (confirm) {
			Sha256Hash tokenPrevblockhash = blockStore.getTokenPrevblockhash(block.getBlock().getHash());
			if (tokenPrevblockhash != null) {
				blockStore.updateTokenSpent(tokenPrevblockhash, true, block.getBlock().getHash());
			}
		} else {
			Sha256Hash tokenPrevblockhash3 = blockStore.getTokenPrevblockhash(block.getBlock().getHash());
			if (tokenPrevblockhash3 != null) {
				SpentBlockData s = blockStore.getTokenSpent(tokenPrevblockhash3);
				if (block.getBlock().getHash().equals(s.getSpenderBlockHash()))
					blockStore.updateTokenSpent(tokenPrevblockhash3, false, null);
			}

		}
		// Set own output confirmed
		blockStore.updateTokenConfirmed(block.getBlock().getHash(), confirm);
	}

	private void confirmContractEventTransaction(Block block, boolean confirm, long milestoneNumber,
			BlockStoreInterface blockStore) throws BlockStoreException {

		blockStore.updateBlockEvaluationConfirmed(block.getHash(), confirm);
		blockStore.updateBlockEvaluationMilestone(block.getHash(), milestoneNumber);
		confirmTransaction(block, confirm, blockStore);
		evictTransactionsAndBlockEva(block, blockStore);
	}

	private void confirmOrderAndTransaction(Block block, boolean confirm, long milestoneNumber,
			BlockStoreInterface blockStore) throws BlockStoreException {

		blockStore.updateBlockEvaluationConfirmed(block.getHash(), confirm);
		blockStore.updateBlockEvaluationMilestone(block.getHash(), milestoneNumber);

		confirmTransaction(block, confirm, blockStore);
		evictTransactionsAndBlockEva(block, blockStore);
	}

	private void confirmTransaction(Block block, boolean confirm, BlockStoreInterface blockStore)
			throws BlockStoreException {

		// un/confirm transactions
		for (final Transaction tx : block.getTransactions()) {
			confirmTransaction(block, confirm, tx, blockStore);
		}
	}

	private void confirmTransaction(Block block, boolean confirm, Transaction tx, BlockStoreInterface blockStore)
			throws BlockStoreException {

		// Set own outputs confirmation
		for (TransactionOutput out : tx.getOutputs()) {
			blockStore.updateTransactionOutputConfirmed(block.getHash(), tx.getHash(), out.getIndex(), confirm);
		}
		// Set previous outputs as spent
		for (TransactionInput in : tx.getInputs()) {

			if (!tx.isCoinBase()) {
				UTXO prevOut = blockStore.getTransactionOutput(in.getOutpoint().getBlockHash(),
						in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
				// Sanity check, unconfirm block no prev is ok
				if (prevOut == null && confirm) {
					BlockWrap b = getBlockWrap(in.getOutpoint().getBlockHash(), blockStore);
					throw new RuntimeException("Attempted to spend a non-existent output from block" + b.toString());
				}
				if (confirm) {
					if (prevOut != null)
						blockStore.updateTransactionOutputSpent(prevOut.getBlockHash(), prevOut.getTxHash(),
								prevOut.getIndex(), true, block.getHash());
				} else {
					// prevOut can be confirmed by other milestone
					if (prevOut != null && !checkSpentMilestone(prevOut, block, blockStore)) {
						blockStore.updateTransactionOutputSpent(prevOut.getBlockHash(), prevOut.getTxHash(),
								prevOut.getIndex(), false, null);
					}
				}
				if (prevOut != null)
					cacheBlockService.evictTransactionOutput(prevOut, blockStore);
			}
		}

	}

	private boolean checkSpentMilestone(UTXO prevOut, Block block, BlockStoreInterface store)
			throws BlockStoreException {
		if (block.getHash().equals(prevOut.getSpenderBlockHash())) {
			return false;
		} else {
			// other with conflict
			if (prevOut.getSpenderBlockHash() != null) {
				BlockWrap conflictBlock = getBlockWrap(prevOut.getSpenderBlockHash(), store);
				if (conflictBlock == null)
					return false;
				if (conflictBlock.getBlockEvaluation().getMilestone() > -1) {
					return true;
				}

			}
		}
		return false;
	}

	public void evictTransactionsAndBlockEva(Block block, BlockStoreInterface blockStore) throws BlockStoreException {

		for (final Transaction tx : block.getTransactions()) {
			boolean isCoinBase = tx.isCoinBase();
			for (TransactionOutput out : tx.getOutputs()) {
				Script script = getScript(out.getScriptBytes());
				String fromAddress = fromAddress(tx, isCoinBase);
				cacheBlockService.evictAccountBalance(getScriptAddress(script), blockStore);
				cacheBlockService.evictAccountBalance(fromAddress, blockStore);
				cacheBlockService.evictOutputs(getScriptAddress(script), blockStore);
				cacheBlockService.evictOutputs(fromAddress, blockStore);
			}

		}
		cacheBlockService.evictBlockEvaluation(block.getHash());
	}

	private void confirmVirtualCoinbaseTransaction(Block block, boolean confirmation, BlockStoreInterface blockStore)
			throws BlockStoreException {

		blockStore.updateAllTransactionOutputsConfirmed(block.getHash(), confirmation);
	}

	/**
	 * Adds the specified block and all approved blocks to the confirmed set. This
	 * will confirm and unconfirm all transaction data of the block and make the
	 * used transaction data spent.
	 * 
	 * @param traversedBlockHashes: all block hash is called in this process
	 * @param confirmation:         confirm and revoke confirm
	 */
	protected void confirm(BlockWrap blockWrap, HashSet<Sha256Hash> traversedBlockHashes, long milestoneNumber,
			boolean confirmation, BlockStoreInterface store) throws BlockStoreException {
		// If already confirmed, return
		if (traversedBlockHashes.contains(blockWrap.getBlockHash()))
			return;

		// order and contract event are controlled by execution only.
		if (blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_CONTRACT_EVENT)
				|| blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_ORDER_OPEN)) {
			return;
		}

		updateBlockConfirm(blockWrap, milestoneNumber, confirmation, store);

		// Keep track of confirmed blocks
		traversedBlockHashes.add(blockWrap.getBlockHash());

	}

	private void updateBlockConfirm(BlockWrap blockWrap, long milestoneNumber, boolean confirmation,
			BlockStoreInterface store) throws BlockStoreException {
		store.updateBlockEvaluationConfirmed(blockWrap.getBlockHash(), confirmation);

		store.updateBlockEvaluationMilestone(blockWrap.getBlockHash(), milestoneNumber);

		confirmBlockTransactionWithType(blockWrap, milestoneNumber, confirmation, store);

		evictTransactionsAndBlockEva(blockWrap.getBlock(), store);
	}

	public void unconfirm(BlockWrap blockWrap, HashSet<Sha256Hash> traversedBlockHashes, long milestoneNumber,
			BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			confirm(blockWrap, traversedBlockHashes, milestoneNumber, false, blockStore);
		} catch (MissingDependencyException e) {
			// block may be out of order
		}
	}

	public void unconfirmBlocksSorted(BlockStoreInterface store, Set<BlockWrap> blocks,
			HashSet<Sha256Hash> traversedConfirms) throws BlockStoreException {
		unconfirmBlocksSorted(store, blocks, traversedConfirms, false);
	}

	public void unconfirmBlocksSorted(BlockStoreInterface store, Set<BlockWrap> blocks,
			HashSet<Sha256Hash> traversedConfirms, boolean checksum) throws BlockStoreException {

		traversedConfirms = new HashSet<>();
		ArrayList<BlockWrap> arrayList = new ArrayList<>(blocks);
		arrayList.sort(new SortbyBlockWrap());
		for (BlockWrap block : arrayList) {
			unconfirm(block, traversedConfirms, -1, store);
			// if (checksum)
			checkSum(store);
		}
	}

	/*
	 * unconfirm chained execution for contract and order, it will add all follow of
	 * the unconfirmed blocks
	 */
	public Set<BlockWrap> addUnconfirmBlocksChainedFollow(BlockStoreInterface store, Set<BlockWrap> blocks)
			throws BlockStoreException {
		Set<BlockWrap> re = new HashSet<>();
		for (BlockWrap blockWrap : blocks) {
			re.add(blockWrap);
			if (blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_CONTRACT_EXECUTE)) {
				addFollowerContract(store, blockWrap, re);
			}
			if (blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_ORDER_EXECUTE)) {
				addFollowerOrderexecution(store, blockWrap, re);
			}
		}
		return re;
	}

	/*
	 * confirm chained execution for contract and order, it will add all previous
	 * unconfirmed blocks until confirmed
	 */
	public Set<BlockWrap> addUnconfirmBlocksChainedPrev(BlockStoreInterface store, Set<BlockWrap> blocks)
			throws BlockStoreException {
		Set<BlockWrap> re = new HashSet<>();
		for (BlockWrap blockWrap : blocks) {
			re.add(blockWrap);
			if (blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_CONTRACT_EXECUTE)) {
				addExecutionPrev(store, blockWrap, re);
			}
			if (blockWrap.getBlock().getBlockType().equals(Type.BLOCKTYPE_ORDER_EXECUTE)) {
				addExecutionPrev(store, blockWrap, re);
			}
		}
		return re;
	}

	private void addExecutionPrev(BlockStoreInterface store, BlockWrap startingBlock, Set<BlockWrap> blocks)
			throws BlockStoreException {

		while (startingBlock != null) {
			BlockWrap b = getBlockWrap(getExecutionPrev(startingBlock.getBlock()), store);
			startingBlock = b;
			if (b != null && !b.getBlockEvaluation().isConfirmed()) {
				blocks.add(b);
			} else {
				startingBlock = null;
			}
		}

	}

	private void addFollowerContract(BlockStoreInterface store, BlockWrap startingBlock, Set<BlockWrap> blocks)
			throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()));
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());
			// Add this block.
			blocks.add(block);

			// Queue all of its confirmed approver blocks if not already queued.
			for (Contractresult req : store.getContractresultWithPrev(block.getBlockHash())) {
				if (!blockQueueSet.contains(req.getBlockHash())) {
					BlockWrap pred = getBlockWrap(req.getBlockHash(), store);
					blockQueueSet.add(req.getBlockHash());
					blockQueue.add(pred);
				}
			}
		}

	}

	private void addFollowerOrderexecution(BlockStoreInterface store, BlockWrap startingBlock, Set<BlockWrap> blocks)
			throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()));
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());
			// Add this block.
			blocks.add(block);

			// Queue all of its confirmed approver blocks if not already queued.
			for (Orderresult req : store.getOrderresultWithPrev(block.getBlockHash())) {
				if (!blockQueueSet.contains(req.getBlockHash())) {
					BlockWrap pred = getBlockWrap(req.getBlockHash(), store);
					blockQueueSet.add(req.getBlockHash());
					blockQueue.add(pred);
				}
			}
		}

	}

	public void confirmBlocksSorted(BlockStoreInterface store, long milestoneNumber, Collection<BlockWrap> blocks,
			HashSet<Sha256Hash> traversedConfirms) throws BlockStoreException {
		confirmBlocksSorted(store, milestoneNumber, false, blocks, traversedConfirms);
	}

	public void confirmBlocksSorted(BlockStoreInterface store, long milestoneNumber, boolean checksum,
			Collection<BlockWrap> blocks, HashSet<Sha256Hash> traversedConfirms) throws BlockStoreException {
		ArrayList<BlockWrap> arrayList = new ArrayList<>(blocks);
		arrayList.sort(new SortbyBlockWrapAsc());
		for (BlockWrap approvedBlock : arrayList) {
			confirm(approvedBlock, traversedConfirms, milestoneNumber, true, store);
//		 	if (checksum)
//		 	checkSum(store);
		}
	}

	/*
	 * Execution from the blocks must be chained original from milestone.
	 */
	public BlockWrap getFromMilestoneChainedExecutions(Set<BlockWrap> blocks, Block.Type blocktype,
			Set<BlockWrap> prevsNotMilestoneChainedBlocks, Token contract, BlockStoreInterface store)
			throws BlockStoreException {
		BlockWrap fromMilestoneChaineExecutions = null;
		for (BlockWrap block : blocks) {
			if (blocktype.equals(block.getBlock().getBlockType())) {
				prevsNotMilestoneChainedBlocks.add(block);
			}
		}

		if (prevsNotMilestoneChainedBlocks.isEmpty()) {
			List<Contractresult> m = store.getMaxMilestoneContractresult(contract.getTokenid());
			if (!m.isEmpty()) {
				fromMilestoneChaineExecutions = getBlockWrap(m.get(0).getBlockHash(), store);
				{
					for (Contractresult c : m) {
						BlockWrap mc = getBlockWrap(c.getBlockHash(), store);
						if (mc.getBlock().getHeight() > fromMilestoneChaineExecutions.getBlock().getHeight())
							fromMilestoneChaineExecutions = mc;
					}
				}
			}
			return fromMilestoneChaineExecutions;
		}
		for (BlockWrap b : prevsNotMilestoneChainedBlocks) {
			if (isFirsExecution(b.getBlock())) {
				return null;
			}
			fromMilestoneChaineExecutions = getFromMilestoneChaineExecutions(b, prevsNotMilestoneChainedBlocks, true,
					store);
			if (fromMilestoneChaineExecutions == null) {
				throw new InfeasiblePrototypeException(
						"Execution from the blocks must be chained original from milestone: " + b.toString());
			}
		}
		return fromMilestoneChaineExecutions;
	}

	public Set<BlockWrap> collectNotAreadyCollected(Set<BlockWrap> collectedBlocks, Set<BlockWrap> prevs)
			throws IOException {
		Set<BlockWrap> collectNews = new HashSet<>();
		Set<Sha256Hash> alreadyCollected = collectNotSpentFrom(prevs);
		for (BlockWrap b : collectedBlocks) {
			if (!b.getBlock().getBlockType().equals(Block.Type.BLOCKTYPE_CONTRACT_EXECUTE)) {
				if (!alreadyCollected.contains(b.getBlockHash())) {
					collectNews.add(b);
				}
			}
		}
		return collectNews;
	}

	public Contractresult getLastContractresult(Set<BlockWrap> prevs, BlockStoreInterface store)
			throws BlockStoreException {
		BlockWrap re = null;
		for (BlockWrap b : prevs) {
			if (re == null)
				re = b;
			else {
				if (b.getBlock().getHeight() > re.getBlock().getHeight())
					re = b;
			}
		}
		if (re == null)
			return null;
		else
			return store.getContractresult(re.getBlock().getHash());
	}

	protected Set<Sha256Hash> collectNotSpentFrom(Set<BlockWrap> prevs) throws IOException {
		Set<Sha256Hash> collectOrdersNoSpents = new HashSet<>();
		for (BlockWrap b : prevs) {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(b.getBlock().getTransactions().get(0).getData());

			collectOrdersNoSpents.addAll(result.getReferencedBlocks());
		}
		return collectOrdersNoSpents;
	}

}
