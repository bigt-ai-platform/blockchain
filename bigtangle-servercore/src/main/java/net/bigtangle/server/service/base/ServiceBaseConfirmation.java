/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
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
import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.exception.VerificationException.MissingDependencyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlock;
import net.bigtangle.core.SpentBlockData;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.core.ConflictCandidate;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.handler.ContractConnectSupport;
import net.bigtangle.server.service.base.handler.ContractExecutorRegistry;
import net.bigtangle.server.service.base.handler.OrderExecutorRegistry;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.service.CacheBlockService;
 
 
import net.bigtangle.store.BlockStoreInterface;

public abstract class ServiceBaseConfirmation extends ServiceBaseOrder {

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseConfirmation.class);

	private static final long NoConflict = -10;
	private static final long ConflictWithConfirmed = -5;

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
	 * Recursively adds block from the headBlock as DAG to the collection
	 * Set<BlockWrap> blocks with predecessors and required blocks. Set<BlockWrap>
	 * blocks will be solid and conflict free set. This is used for build the reward
	 * chain and execution chains. From a head block to all predecessors and
	 * required blocks
	 *
	 */
	public void dagBlockHashesFrom(Set<BlockWrap> blocks, BlockWrap headBlock, long cutoffHeight,
			long prevMilestoneNumber, List<BlockType> blocktypes, boolean checkSpentConflict, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(headBlock);
		blockQueueSet.add(headBlock.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// Nothing added if already in set
			if (checkExists(blocks, block))
				continue;
			// Nothing added if already in milestone
			if (block.getBlockEvaluation().getMilestone() >= 0)
				continue;
			// Check if the block is in cutoff and not in chain
			if (block.getBlock().getHeight() <= cutoffHeight && block.getBlockEvaluation().getMilestone() < 0) {
				continue;
			}
			// Check if the block is solid,
			if (block.getBlockEvaluation().getSolid() < 0) {
				continue;
			}
			// Add this block for matched.

			if (matchType(block, blocktypes)) {
				//
				boolean checked = checkSpentAndConflict(blocks, block, checkMilestone, store);
				if (checkMilestone)
					checked = checked && checkBestExecutionChain(block.getBlock(), store);
				if (checked) {
					blocks.add(block);
				} else {
					// logger.debug("checkSpentAndConflict true, not add" );
					// skip conflict
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

	private boolean matchType(BlockWrap block, List<BlockType> blocktypes) {
		if (blocktypes == null)
			return true;
		for (BlockType type : blocktypes) {
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

	protected boolean checkSpentAndConflict(Set<BlockWrap> allApproved, BlockWrap newBlock, boolean checkMilestone,
			BlockStoreInterface store) {
		Set<BlockWrap> allApprovedNewBlocks = new HashSet<>();

		allApprovedNewBlocks.addAll(allApproved);
		allApprovedNewBlocks.add(newBlock);

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
						long m = hasConflictDependencyMilestone(c, checkMilestone, store);
						boolean re = hasConflictDependency(m, checkMilestone );
						if (re){
							logger.debug("hasSpentInputs {}", c.getBlock().getBlock().toString());
						store.updateBlockEvaluationMilestone(c.getBlock().getBlock().getHash(), -1*m);
						}
							return re;
					} catch (BlockStoreException e) {
						return true;
					}
				});
	}

	public Set<BlockWrap> collectPrevsChain(Contractresult prevNotMilestone, Contractresult lastMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		Set<BlockWrap> re = new HashSet<>();
		Contractresult startingBlock = prevNotMilestone;
		// prevNotMilestone = lastMilestone
		if (startingBlock.getBlockHash().equals(lastMilestone.getBlockHash())) {
			return re;
		}
		while (startingBlock != null
				&& !Contractresult.firstContractresult().getBlockHash().equals(startingBlock.getBlockHash())) {
			re.add(getBlockWrap(startingBlock.getBlockHash(), store));
			if (startingBlock.getPrevblockhash().equals(lastMilestone.getBlockHash())) {
				return re;
			} else {
				startingBlock = store.getContractresult(startingBlock.getPrevblockhash());
			}
		}
		// not chained to lastMilestone
		return new HashSet<>();
	}

	public Set<BlockWrap> collectPrevsOrderChain(Orderresult prevNotMilestone, Orderresult lastMilestone,
			BlockStoreInterface store) throws BlockStoreException {

		Set<BlockWrap> re = new HashSet<>();
		Orderresult startingBlock = prevNotMilestone;
		if (startingBlock.getBlockHash().equals(lastMilestone.getBlockHash())) {
			return re;
		}
		while (startingBlock != null
				&& !Orderresult.zeroOrderresult().getBlockHash().equals(startingBlock.getBlockHash())) {
			re.add(getBlockWrap(startingBlock.getBlockHash(), store));
			if (startingBlock.getPrevblockhash().equals(lastMilestone.getBlockHash())) {
				return re;
			} else {
				startingBlock = store.getOrderResult(startingBlock.getPrevblockhash());
			}
		}

		return re;
	}

	/**
	 * Recursively adds the specified block and its approved blocks to the
	 * collection. startingBlock is an approval of a block from blocks, it will add
	 * other DAG block to the given blocks. The conflict is not check here, will be
	 * check later. blocks <-- startingBlock --> add predecessors + required inputs
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
			// Check if the block is solid,
			if (block.getBlockEvaluation().getSolid() < 0) {
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

	public boolean hasConflictDependency(long re, boolean checkMilestone )
			throws BlockStoreException {
	 
		if (re == NoConflict)
			return false;
		else {
			if (checkMilestone)
				return re > ConflictWithConfirmed;
			else
				return re >= ConflictWithConfirmed;
		}

	}

	/*
	 * check, if the inputs/prev is spent by other
	 * 
	 * Input/previous = spent by myself: return true if Input/previous is confirmed,
	 * return true for unconfirmed Input/previous = spent by other = true if
	 * Input/previous is confirmed or milestone
	 * 
	 * checkMilestone is the check inputs/prev spent by other in milestone, ignore
	 * only confirmed
	 * 
	 * 
	 */
	public long hasConflictDependencyMilestone(ConflictCandidate c, boolean checkMilestone, BlockStoreInterface store)
			throws BlockStoreException {
		SpentBlockData s;
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			return checkUTXOSpent(c, checkMilestone, store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();
			if (connectedToken.getTokenindex() == 0) {
				if (checkMilestone)
					return NoConflict;
				else { if (store.getTokenAnyConfirmed(connectedToken.getTokenid(), connectedToken.getTokenindex()))
					return ConflictWithConfirmed;
				 else
					return NoConflict;
				}
			}
			s = store.getTokenSpent(connectedToken.getPrevblockhash());
			if (s == null)
				return NoConflict;
			else
				return checkSpentByOther(c, checkMilestone, s, store);
		case REWARDISSUANCE:
			if (store.getRewardSpent(c.getConflictPoint().getConnectedReward().getPrevRewardHash()))
				return ConflictWithConfirmed;
			else
				return NoConflict;

		case DOMAINISSUANCE:
			// exception for the block
			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();
			if (store.getDomainIssuingConfirmedBlock(connectedDomainToken.getTokenname(),
					connectedDomainToken.getDomainNameBlockHash(), connectedDomainToken.getTokenindex()) != null)
				return ConflictWithConfirmed;
			else
				return NoConflict;
		case CONTRACTEXECUTE:
			return checkContractSpentByOther(c, checkMilestone, store);
		case ORDEREXECUTE:
			return checkOrderSpent(c, checkMilestone, store);
		default:
			throw new RuntimeException("Not Implemented");
		}
	}

	private long checkSpentByOther(ConflictCandidate c, boolean checkMilestone, SpentBlock prev,
			BlockStoreInterface store) throws BlockStoreException {
		if (c.getBlock().getBlockHash().equals(prev.getSpenderBlockHash())) {
			if (prev.isConfirmed())
				return NoConflict;
		} else {
			// other with conflict
			if (prev.getSpenderBlockHash() != null) {
				BlockWrap conflictBlock = getBlockWrap(prev.getSpenderBlockHash(), store);
				if (conflictBlock == null)
					return NoConflict;
				if (checkMilestone) {
					if (conflictBlock.getBlockEvaluation().getMilestone() > 0) {
						return conflictBlock.getBlockEvaluation().getMilestone();
					}
				} else {
					if (conflictBlock.getBlockEvaluation().getMilestone() > 0)
						return conflictBlock.getBlockEvaluation().getMilestone();
					if (conflictBlock.getBlockEvaluation().isConfirmed())
						return ConflictWithConfirmed;
				}
			}
		}
		return NoConflict;
	}

	private long checkContractSpentByOther(ConflictCandidate c, boolean checkMilestone, BlockStoreInterface store)
			throws BlockStoreException {
		final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContractExecute();
		// check spent by other milestone or confirm
		List<Contractresult> allWithPrev = store.getContractresultWithPrev(connectedContracExecute.getPrevblockhash());
		for (Contractresult s : allWithPrev) {
			if (!c.getBlock().getBlockHash().equals(s.getBlockHash())) {
				if (!c.getBlock().getBlockHash().equals(s.getSpenderBlockHash())) {
					if (checkMilestone) {
						if (s.getMilestone() > 0) {
							return s.getMilestone();
						}
					} else {
						if (s.getMilestone() > 0)
							return s.getMilestone();
						if (s.isConfirmed())
							return ConflictWithConfirmed;
					}
				}
			}
		}
		// check myself
		for (Contractresult s : allWithPrev) {
			if (c.getBlock().getBlockHash().equals(s.getBlockHash())) {
				return NoConflict;
			}
		}
		// the referenced check
		return checkReferencedBlocksConflictDependency(
				c.getConflictPoint().getConnectedContractExecute().getReferencedBlocks(), checkMilestone, store);
	}

	private long checkReferencedBlocksConflictDependency(Set<Sha256Hash> referencedBlocks, boolean checkMilestone,
			BlockStoreInterface store) throws BlockStoreException {
		for (Sha256Hash ref : referencedBlocks) {
			BlockWrap refBlock = getBlockWrap(ref, store);
			if (refBlock == null)
				return NoConflict;
			for (ConflictCandidate r : refBlock.toConflictCandidates()) {
				long check = hasConflictDependencyMilestone(r, checkMilestone, store);
				if (check > NoConflict)
					return check;
			}
		}
		return NoConflict;
	}

	private long checkOrderSpent(ConflictCandidate c, boolean checkMilestone, BlockStoreInterface store)
			throws BlockStoreException {
		final OrderExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedOrderExecute();
		List<Orderresult> allWithPrev = store.getOrderresultWithPrev(connectedContracExecute.getPrevblockhash());
		for (Orderresult s : allWithPrev) {
			if (!c.getBlock().getBlockHash().equals(s.getBlockHash())) {
				if (!c.getBlock().getBlockHash().equals(s.getSpenderBlockHash())) {
					if (checkMilestone) {
						if (s.getMilestone() > 0) {
							return s.getMilestone();
						}
					} else {
						if (s.getMilestone() > 0)
							return s.getMilestone();
						if (s.isConfirmed())
							return ConflictWithConfirmed;
					}
				}
			}
		}
		for (Orderresult s : allWithPrev) {
			if (c.getBlock().getBlockHash().equals(s.getBlockHash())) {
				return NoConflict;
			}
		}
		// the referenced check
		return checkReferencedBlocksConflictDependency(connectedContracExecute.getReferencedBlocks(), checkMilestone,
				store);
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

		// Find conflict candidates whose used outputs are already spent and confirmed
		return candidates.anyMatch((ConflictCandidate c) -> {
			try {
				long m = hasConflictDependencyMilestone(c, false, store);  
				return hasConflictDependency(m, false );
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
		return blocksToAdd.stream().filter(b -> b.getBlock().getBlockType() == BlockType.BLOCKTYPE_REWARD)
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

	/*
	 * all blocks with conflict from milestone will be set as solid= -milestone. At
	 * revert of the milestone, will be reset solid = 0 as initial all blocks with
	 * conflicts with milestone block will be not added to DAG process
	 */
	public void updateMilestoneConflicts(Set<BlockWrap> blocksToAdd, BlockStoreInterface store)
			throws BlockStoreException {
		// Find all conflict candidates in blocks to add
		List<ConflictCandidate> conflicts = blocksToAdd.stream().map(BlockWrap::toConflictCandidates)
				.flatMap(Collection::stream).collect(Collectors.toList());

		// Drop any spent by milestone
		for (ConflictCandidate c : conflicts) {
			// Find the spending block we are competing with
			long m = hasConflictDependencyMilestone(c, true, store);
			if (m > ConflictWithConfirmed)
				store.updateBlockEvaluationSolid(c.getBlock().getBlockHash(), -1 * m);
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

	/*
	 * filter all blocks with hasConflictDependency
	 */
	private void filterSpent(Collection<ConflictCandidate> blockConflicts, BlockStoreInterface store) {
		blockConflicts.removeIf(c -> {
			try {
				long m = hasConflictDependencyMilestone(c, true, store); 
				return !hasConflictDependency(m, true );
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
	private long checkUTXOSpent(ConflictCandidate c, boolean checkMilestone, BlockStoreInterface store)
			throws BlockStoreException {
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
			return NoConflict;
		return checkSpentByOther(c, checkMilestone, a, store);

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

		blockStore.updateAllTransactionOutputsConfirmed(block.getBlock().getHash(), confirmation);
		if (confirmation) {
			confirmBlockTransactionSpentBatch(block.getBlock(), blockStore);
		} else {
			for (final Transaction tx : block.getBlock().getTransactions()) {
				confirmTransactionSpent(block.getBlock(), false, tx, blockStore);
			}
		}
		// Layer strategy: delegate to a registered handler if present.
		if (handlerFor(block.getBlock().getBlockType()).isPresent()) {
			SolidityContext ctx = SolidityContext.builder().block(block.getBlock()).store(blockStore)
					.milestoneNumber(milestoneNumber).confirmation(confirmation)
					.blockHash(block.getBlockHash()).base(this).build();
			handlerFor(block.getBlock().getBlockType()).get().confirm(ctx);
			return;
		}
		// type-specific updates (fallback when no handler registered)
		switch (block.getBlock().getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE, BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER,
				BLOCKTYPE_CONTRACT_EVENT,  BLOCKTYPE_ORDER_CANCEL, BLOCKTYPE_CONTRACTEVENT_CANCEL, BLOCKTYPE_STAKE,
				BLOCKTYPE_SLASHING:
			updateBlockConfirmOnly(block.getBlockHash(), milestoneNumber, confirmation, blockStore);
			break;
		case BLOCKTYPE_REWARD:
			confirmReward(block, confirmation, blockStore);
			if (!enableOrderMatchExecutionChain(block.getBlock())) {
				confirmOrderMatching(block, confirmation, blockStore);
				confirmContractExecution(block, confirmation, blockStore);
			}
			updateBlockConfirmOnly(block.getBlockHash(), milestoneNumber, confirmation, blockStore);
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			updateBlockConfirmOnly(block.getBlockHash(), milestoneNumber, confirmation, blockStore);
			confirmToken(block, confirmation, blockStore);
			break;
		case BLOCKTYPE_USERDATA:
			updateBlockConfirmOnly(block.getBlockHash(), milestoneNumber, confirmation, blockStore);
			confirmVOSOrUserData(block, confirmation, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			if (confirmation)
				handleNewBestExecutionChain(block.getBlock(), milestoneNumber, blockStore);
			else {
				confirmContractExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			}
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			if (confirmation)
				handleNewBestExecutionChain(block.getBlock(), milestoneNumber, blockStore);
			else {
				confirmOrderExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			}
			break;
		case BLOCKTYPE_ORDER_OPEN:
			updateBlockConfirmOnly(block.getBlockHash(), milestoneNumber, confirmation, blockStore);
			blockStore.updateOrderBlockhash(block.getBlockHash(), Sha256Hash.ZERO_HASH, confirmation, false, null);
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}
	}

	private void confirmExecution(BlockWrap block, long milestoneNumber, boolean confirmation,
			BlockStoreInterface blockStore) throws BlockStoreException {

		// type-specific updates
		switch (block.getBlock().getBlockType()) {

		case BLOCKTYPE_CONTRACT_EXECUTE:
			confirmContractExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			confirmOrderExecute(block.getBlock(), milestoneNumber, confirmation, blockStore);
			break;
		default:
			break;

		}
	}

	public void confirmVOSOrUserData(BlockWrap block, boolean confirmation, BlockStoreInterface blockStore)
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

	public void confirmOrderMatching(BlockWrap block, boolean confirmation, BlockStoreInterface blockStore)
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

	/**
	 * Applies inline contract execution state changes during reward confirmation
	 * when the execution chain is disabled. The contract results were already
	 * computed during reward creation; this method applies the state transitions
	 * (mark events spent, confirm outputs, update contract results).
	 */
	private void confirmContractExecution(BlockWrap blockWrap, boolean confirmation,
			BlockStoreInterface blockStore) throws BlockStoreException {
		try {
			if (!confirmation) return;
			RewardInfo rewardInfo = new RewardInfo()
					.parseChecked(blockWrap.getBlock().getTransactions().get(0).getData());
			if (rewardInfo.getContractResult() == null
					|| rewardInfo.getContractResult().equals(Sha256Hash.ZERO_HASH)) {
				return;
			}
			ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper);
			long cutoff = serviceBase.getCurrentCutoffHeight(
					cacheBlockService.getMaxConfirmedReward(blockStore), blockStore);
			Set<BlockWrap> refs = new HashSet<>();
			List<BlockType> types = List.of(BlockType.BLOCKTYPE_CONTRACT_EVENT,
					BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
			serviceBase.dagBlockHashesFrom(refs,
					serviceBase.getBlockWrap(blockWrap.getBlock().getPrevBlockHash(), blockStore),
					cutoff, rewardInfo.getChainlength(), types, true, false, blockStore);
			serviceBase.dagBlockHashesFrom(refs,
					serviceBase.getBlockWrap(blockWrap.getBlock().getPrevBranchBlockHash(), blockStore),
					cutoff, rewardInfo.getChainlength(), types, true, false, blockStore);

			List<Token> openContracts = blockStore.getTokenTypeList(TokenType.contract.ordinal());
			for (Token contract : openContracts) {
				Contractresult lastConfirmed = blockStore.getMaxConfirmedContractresult(contract.getTokenid());
				if (lastConfirmed == null) continue;

				ContractExecutionResult result = ContractExecutorRegistry.get()
						.map(exec -> {
							try {
								return exec.executeContract(
										(ContractConnectSupport) serviceBase, networkParameters,
										blockWrap.getBlock(), blockStore, contract.getTokenid(),
										lastConfirmed, serviceBase.getHashSet(refs));
							} catch (BlockStoreException e) {
								throw new RuntimeException(e);
							}
						})
						.orElse(null);
				if (result == null || result.getOutputTx().getOutputs().isEmpty()) continue;

				for (Sha256Hash ref : result.getReferencedBlocks()) {
					blockStore.updateContractEventBlockhash(ref, Sha256Hash.ZERO_HASH, true, true,
							blockWrap.getBlockHash());
					confirmContractEventTransaction(getBlock(ref, blockStore), true,
							blockWrap.getBlockEvaluation().getMilestone(), blockStore);
				}
				blockStore.updateContractEventPrevhash(result.getPrevblockhash(), true, true,
						blockWrap.getBlockHash());
				for (ContractEventRecord c : result.getRemainderContractEventRecord()) {
					c.setConfirmed(true);
					c.setSpent(false);
					c.setSpenderBlockHash(null);
					c.setCollectinghash(blockWrap.getBlockHash());
				}
				blockStore.updateContractEventSpent(result.getRemainderContractEventRecord());
				blockStore.updateContractresultMilestone(blockWrap.getBlockHash(),
						blockWrap.getBlockEvaluation().getMilestone());
				blockStore.updateContractResultConfirmed(blockWrap.getBlockHash(), true);
				confirmTransaction(blockWrap.getBlock(), true, result.getOutputTx(), blockStore);
				blockStore.updateContractResultSpent(result.getPrevblockhash(), blockWrap.getBlockHash(), true);
				blockStore.updateContractEventCancelSpent(result.getCancelRecords(), blockWrap.getBlockHash(), true);
			}
		} catch (Exception e) {
			logger.error("Failed to verify inline contract execution", e);
		}
	}

	public void confirmOrderExecute(Block block, long milestoneNumber, boolean confirm, BlockStoreInterface blockStore)
			throws BlockStoreException {
		updateBlockConfirmOnly(block.getHash(), milestoneNumber, confirm, blockStore);
		try {
			OrderExecutionResult result = new OrderExecutionResult().parse(block.getTransactions().get(0).getData());
			Orderresult prevblockhash = blockStore.getOrderResult(result.getPrevblockhash());
			OrderExecutionResult check = OrderExecutorRegistry.get().map(exec -> {
						try {
							return exec.executeOrderMatching((net.bigtangle.server.service.base.handler.OrderMatchingSupport) this,
									networkParameters,
									block, prevblockhash, result.getReferencedBlocks(), blockStore);
						} catch (BlockStoreException e) {
							throw new RuntimeException(e);
						}
					})
					.orElse(null);

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())

					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {
				// debugOrderExecutionResult(block, check, confirm, blockStore);

				if (confirm) {

					for (Sha256Hash ref : check.getReferencedBlocks()) {
						blockStore.updateOrderBlockhash(ref, Sha256Hash.ZERO_HASH, true, true, block.getHash());
						confirmOrderAndTransaction(getBlock(ref, blockStore), true, milestoneNumber, blockStore);
					}
					blockStore.updateOrderPrevhash(check.getPrevblockhash(), true, true, block.getHash());

					blockStore.updateOrderConfirmed(check.getRemainderOrderRecord(), true);

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

					blockStore.updateOrderPrevhash(check.getPrevblockhash(), false, false, null);
					mayRestoreprevOrderExecution(blockStore, result, prevblockhash, block);

					blockStore.updateOrderConfirmed(check.getRemainderOrderRecord(), false);
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
				//	logger.debug(a.getValue().toString());
				//	logger.debug("checkSum failed");
				}

			}
		} catch (Exception e) {
			// ignore as check only
		}

	}

	/*
	 * confirmation of the contract Execution
	 */
	public void confirmContractExecute(Block block, long milestoneNumber, boolean confirm,
			BlockStoreInterface blockStore) throws BlockStoreException {

		try {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(block.getTransactions().get(0).getData());
			Contractresult prevblockhash = blockStore.getContractresult(result.getPrevblockhash());
			// Invoke the contract engine via the Layer-1 SPI instead of
			// `new ServiceContract(...)`. See ContractConnectSupport.
			// At runtime `this` is always a ServiceBaseConnect subclass
			// (ServiceBaseCheck / ServiceVerifyReward / ServiceBaseReward),
			// hence a ContractConnectSupport.
			ContractExecutionResult check = ContractExecutorRegistry.get()
					.map(exec -> {
						try {
							return exec.executeContract((net.bigtangle.server.service.base.handler.ContractConnectSupport) this,
									networkParameters, block, blockStore,
									result.getContracttokenid(), prevblockhash, result.getReferencedBlocks());
						} catch (BlockStoreException e) {
							throw new RuntimeException(e);
						}
					})
					.orElse(null);

			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {

				confirmContractExecuteDo(block, milestoneNumber, confirm, blockStore, prevblockhash, check);
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void confirmContractExecuteDo(Block block, long milestoneNumber, boolean confirm,
			BlockStoreInterface blockStore, Contractresult prevblockhash, ContractExecutionResult check)
			throws BlockStoreException {
		if (confirm) {
			for (Sha256Hash ref : check.getReferencedBlocks()) {
				blockStore.updateContractEventBlockhash(ref, Sha256Hash.ZERO_HASH, true, true, block.getHash());
				confirmContractEventTransaction(getBlock(ref, blockStore), true, milestoneNumber, blockStore);
			}
			blockStore.updateContractEventPrevhash(prevblockhash.getBlockHash(), true, true, block.getHash());

			for (ContractEventRecord c : check.getRemainderContractEventRecord()) {
				c.setConfirmed(true);
				c.setSpent(false);
				c.setSpenderBlockHash(null);
				// remainder set the collection hash using block hash, as the calculation of
				// execution the block hash is done only at solve block.
				c.setCollectinghash(block.getHash());
			}
			blockStore.updateContractEventSpent(check.getRemainderContractEventRecord());

			// update ContractResult
			blockStore.updateContractresultMilestone(block.getHash(), milestoneNumber);
			blockStore.updateContractResultConfirmed(block.getHash(), true);
			confirmTransaction(block, true, check.getOutputTx(), blockStore);
			blockStore.updateContractResultSpent(check.getPrevblockhash(), block.getHash(), true);

			// update cancel
			blockStore.updateContractEventCancelSpent(check.getCancelRecords(), block.getHash(), true);

		} else {
			Sha256Hash spenderBlockHash = prevblockhash.getSpenderBlockHash();
			// referenced block can be referenced by other conflict block, set to
			// unconfirmed only the block is the spender
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

			blockStore.updateContractEventPrevhash(prevblockhash.getBlockHash(), false, false, null);
			// may restore the prev state,
			mayRestorePrevContractExecution(block, blockStore, prevblockhash);

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

		}

		updateBlockConfirmOnly(block.getHash(), milestoneNumber, confirm, blockStore);
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

	public void confirmReward(BlockWrap block, boolean confirm, BlockStoreInterface blockStore)
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

	public void confirmToken(BlockWrap block, boolean confirm, BlockStoreInterface blockStore)
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
				if (prevOut == null && confirm) {
					BlockWrap b = getBlockWrap(in.getOutpoint().getBlockHash(), blockStore);
					throw new RuntimeException("Attempted to spend a non-existent output from block" + b.toString());
				}
				if (confirm) {
					if (prevOut != null)
						blockStore.updateTransactionOutputSpent(prevOut.getBlockHash(), prevOut.getTxHash(),
								prevOut.getIndex(), true, block.getHash());
				} else {
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

	private void confirmTransactionSpent(Block block, boolean confirm, Transaction tx, BlockStoreInterface blockStore)
			throws BlockStoreException {

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

	private void confirmBlockTransactionSpentBatch(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {
		List<Sha256Hash> prevBlockHashes = new ArrayList<>();
		List<Sha256Hash> prevTxHashes = new ArrayList<>();
		List<Long> indexes = new ArrayList<>();
		for (Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase()) continue;
			for (TransactionInput in : tx.getInputs()) {
				Sha256Hash b = in.getOutpoint().getBlockHash();
				Sha256Hash h = in.getOutpoint().getTxHash();
				long idx = in.getOutpoint().getIndex();
				prevBlockHashes.add(b);
				prevTxHashes.add(h);
				indexes.add(idx);
			}
		}
		if (!prevBlockHashes.isEmpty()) {
			blockStore.updateTransactionOutputSpentBatch(prevBlockHashes, prevTxHashes, indexes, block.getHash());
			for (int i = 0; i < prevBlockHashes.size(); i++) {
					UTXO prevOut = blockStore.getTransactionOutput(prevBlockHashes.get(i),
							prevTxHashes.get(i), indexes.get(i));
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

		if(enableOrderMatchExecutionChain(blockWrap.getBlock())) {
		if (blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_CONTRACT_EVENT)
				|| blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_ORDER_OPEN)) {
			return;
		}
		}
		updateBlockConfirm(blockWrap, milestoneNumber, confirmation, store);

		// Keep track of confirmed blocks
		traversedBlockHashes.add(blockWrap.getBlockHash());

	}

	private void updateBlockConfirm(BlockWrap blockWrap, long milestoneNumber, boolean confirmation,
			BlockStoreInterface store) throws BlockStoreException {

		confirmBlockTransactionWithType(blockWrap, milestoneNumber, confirmation, store);

		evictTransactionsAndBlockEva(blockWrap.getBlock(), store);
	}

	private void updateBlockConfirmOnly(Sha256Hash blockhash, long milestoneNumber, boolean confirmation,
			BlockStoreInterface store) throws BlockStoreException {
		store.updateBlockEvaluationConfirmed(blockhash, confirmation);
		store.updateBlockEvaluationMilestone(blockhash, milestoneNumber);
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
		arrayList.sort(Comparator.comparingLong((BlockWrap w) -> w.getBlock().getHeight()) );
		for (BlockWrap block : arrayList) {
			unconfirm(block, traversedConfirms, -1, store);
			// if (checksum)

		}
		checkSum(store);
	}

	/*
	 * unconfirm chained execution for contract and order, it will add all follow of
	 * the unconfirmed blocks. Execution will be added, only if it is longest than
	 * the last confirmed execution
	 */
	public Set<BlockWrap> addUnconfirmBlocksChainedFollow(BlockStoreInterface store, Set<BlockWrap> blocks)
			throws BlockStoreException {
		Set<BlockWrap> re = new HashSet<>();
		for (BlockWrap blockWrap : blocks) {
			if (blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_CONTRACT_EXECUTE)) {
				ContractExecutionResult c = new ContractExecutionResult()
						.parseChecked(blockWrap.getBlock().getTransactions().get(0).getData());
				Contractresult last = store.getMaxConfirmedContractresult(c.getContracttokenid());
				if (last.getChainlength() < c.getChainlength()) {
					addFollowerContract(store, blockWrap, re);
				} else {
					// skip this block
					continue;
				}
			}
			if (blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_ORDER_EXECUTE)) {
				addFollowerOrderexecution(store, blockWrap, re);
			}
			re.add(blockWrap);
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

			if (blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_CONTRACT_EXECUTE)) {
				ContractExecutionResult c = new ContractExecutionResult()
						.parseChecked(blockWrap.getBlock().getTransactions().get(0).getData());
				Contractresult last = store.getMaxConfirmedContractresult(c.getContracttokenid());
				if (last.getChainlength() < c.getChainlength()) {
					addExecutionPrev(store, blockWrap, re);
				} else {
					// skip this block
					continue;
				}
			}
			if (blockWrap.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_ORDER_EXECUTE)) {
				addExecutionPrev(store, blockWrap, re);
			}
			// at the end may skip
			re.add(blockWrap);
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
		arrayList.sort(Comparator.comparingLong((BlockWrap w) -> w.getBlock().getHeight()));
		for (BlockWrap approvedBlock : arrayList) {
			confirm(approvedBlock, traversedConfirms, milestoneNumber, true, store);
	 
		}
		checkSum(store);
	}

	public Set<BlockWrap> collectNotAreadyCollected(Set<BlockWrap> collectedBlocks, Set<BlockWrap> prevs)
			throws IOException {
		Set<BlockWrap> collectNews = new HashSet<>();
		Set<Sha256Hash> alreadyCollected = collectNotSpentFrom(prevs);
		for (BlockWrap b : collectedBlocks) {
			if (!b.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_CONTRACT_EXECUTE)) {
				if (!alreadyCollected.contains(b.getBlockHash())) {
					collectNews.add(b);
				}
			}
		}
		return collectNews;
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

	/**
	 * Called as part of connecting a block when the new block results in a
	 * different chain having higher total work as longest reward chain.
	 * 
	 */
	public void handleNewBestExecutionChain(Block newChainHead, long milestoneNumber, BlockStoreInterface store)
			throws BlockStoreException, VerificationException {
		// checkState(lock.isHeldByCurrentThread());
		// This chain has overtaken the one we currently believe is best.
		// Reorganize is required.
		//
		// Firstly, calculate the block at which the chain diverged. We only
		// need to examine the
		// chain from beyond this block to find differences.
		Block head = getChainHeadExecution(newChainHead, store);

		if (head == null) {
			confirmExecution(getBlockWrap(newChainHead.getHash(), store), milestoneNumber, true, store);
			return;
		}

		if (head != null && getExecuteChainlength(newChainHead) <= getExecuteChainlength(head)) {
			// best execution chain nothing to dod
			return;
		}

		if (head != null && getExecutionPrev(newChainHead).equals(head.getHash())) {
			// ok follow the head
			confirmExecution(getBlockWrap(newChainHead.getHash(), store), milestoneNumber, true, store);
			return;
		}
		// there is new best execution chain
		executionBestChained(newChainHead, milestoneNumber, store, head);

	}

	private void executionBestChained(Block newChainHead, long milestoneNumber, BlockStoreInterface store, Block head)
			throws BlockStoreException {
		final Block splitPoint = findSplit(newChainHead, head, store);
		if (splitPoint == null) {
			logger.info(" splitPoint is null, the chain ist not complete: {} ", newChainHead);
			return;
		}

		logger.info("Re-organize after split at height {}", splitPoint.getHeight());
		logger.info("Old Execution chain head: \n {}", head);
		logger.info("New Execution chain head: \n {}", newChainHead);
		logger.info("Split at Execution block: \n {}", splitPoint);
		// Then build a list of all blocks in the old part of the chain and the
		// new part.
		LinkedList<Block> oldBlocks = new LinkedList<>();
		if (!head.getHash().equals(splitPoint.getHash())) {
			oldBlocks = getPartialChain(head, splitPoint, store);
		}
		// add other confirmed and not milestone execution block lower than the
		// newChainHead
		for (Sha256Hash h : getLowerExecuteChainlength(newChainHead, store)) {
			if (findBlockFromHash(oldBlocks, h) == null) {
				oldBlocks.add(getBlock(h, store));
			}
		}
		final LinkedList<Block> newBlocks = getPartialChain(newChainHead, splitPoint, store);
		// Disconnect each block in the previous best chain that is no
		// longer in the new best chain from last to begin
		oldBlocks.sort(Comparator.comparingLong((Block w) -> w.getHeight()));
		for (Block oldBlock : oldBlocks) {
			// Sanity check:
			if (!oldBlock.getHash().equals(UtilGeneseBlock.createGenesis(networkParameters) .getHash())) {
				// Unconfirm
				confirmExecution(getBlockWrap(oldBlock.getHash(), store), -1, false, store);
			}
		}
		Block cursor;
		// Walk in ascending chronological order.
		for (Iterator<Block> it = newBlocks.descendingIterator(); it.hasNext();) {
			cursor = it.next();
			confirmExecution(getBlockWrap(cursor.getHash(), store), milestoneNumber, true, store);
			// if we build a chain longer than head, do a commit, even it may be
			// failed after this.
			if (getExecuteChainlength(cursor) > getExecuteChainlength(head)) {
				store.commitDatabaseBatchWrite();
				store.beginDatabaseBatchWrite();
			}
		}
	}

	private Block findBlockFromHash(List<Block> collectList, Sha256Hash blockhash) throws BlockStoreException {
		for (Block b : collectList) {
			if (b.getHash().equals(blockhash))
				return b;
		}
		return null;
	}

	public boolean checkBestExecutionChain(Block newChainHead, BlockStoreInterface store)
			throws BlockStoreException, VerificationException {
		Block head = null;
		switch (newChainHead.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			ContractExecutionResult c = new ContractExecutionResult()
					.parseChecked(newChainHead.getTransactions().get(0).getData());
			head = getBlock(store.getMaxConfirmedContractresult(c.getContracttokenid()).getBlockHash(), store);
		case BLOCKTYPE_ORDER_EXECUTE:
			// head = getBlock(store.getMaxConfirmedOrderresult().getBlockHash(), store);
		default:
		}
		if (head == null) {
			return true;
		} else {
			boolean re = getExecuteChainlength(newChainHead) > getExecuteChainlength(head);
			if (re) {
				logger.debug(newChainHead.toString());
			}
			return re;
		}
	}

	public Block getChainHeadExecution(Block block, BlockStoreInterface store) throws BlockStoreException {

		switch (block.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			ContractExecutionResult c = new ContractExecutionResult()
					.parseChecked(block.getTransactions().get(0).getData());
			return getBlock(store.getMaxConfirmedContractresult(c.getContracttokenid()).getBlockHash(), store);
		case BLOCKTYPE_ORDER_EXECUTE:
			return getBlock(store.getMaxConfirmedOrderresult().getBlockHash(), store);
		case BLOCKTYPE_REWARD:
			return getBlock(store.getMaxConfirmedReward().getBlockHash(), store);
		default:
			throw new RuntimeException("block.getBlockType() is wrong " + block.getBlockType());
		}

	}

	/**
	 * Locates the point in the chain at which newBlock and chainHead diverge.
	 * Returns null if no split point was found (ie they are not part of the same
	 * chain). Returns newChainHead or chainHead if they don't actually diverge but
	 * are part of the same chain. return null, if the newChainHead is not complete
	 * locally.
	 */
	public Block findSplit(Block newChainHead, Block oldChainHead, BlockStoreInterface store)
			throws BlockStoreException {
		Block currentChainCursor = oldChainHead;
		Block newChainCursor = newChainHead;
		// Loop until we find the reward block both chains have in common.
		// Example:
		//
		// A -> B -> C -> D
		// *****\--> E -> F -> G
		//
		// findSplit will return block B. oldChainHead = D and newChainHead = G.
		while (!currentChainCursor.equals(newChainCursor)) {
			if (getExecuteChainlength(currentChainCursor) > getExecuteChainlength(newChainCursor)) {
				currentChainCursor = store.get(getExecutionPrev(currentChainCursor));
				checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");

			} else {
				Sha256Hash executionPrev = getExecutionPrev(newChainCursor);
				if (!Sha256Hash.ZERO_HASH.equals(executionPrev)) {
					newChainCursor = store.get(executionPrev);
					checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
				} else {
					newChainCursor = currentChainCursor;
				}

			}
		}
		return currentChainCursor;
	}

	public long getExecuteChainlength(Block block) {
		if (block == null)
			return 0;
		switch (block.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			return new ContractExecutionResult().parseChecked(block.getTransactions().get(0).getData())
					.getChainlength();
		case BLOCKTYPE_ORDER_EXECUTE:
			return new OrderExecutionResult().parseChecked(block.getTransactions().get(0).getData()).getChainlength();
		case BLOCKTYPE_REWARD:
			return new RewardInfo().parseChecked(block.getTransactions().get(0).getData()).getChainlength();
		case BLOCKTYPE_INITIAL:
			return 0;

		default:
			throw new RuntimeException("block.getBlockType() is wrong " + block.getBlockType());
		}

	}

	public List<Sha256Hash> getLowerExecuteChainlength(Block newChainHead, BlockStoreInterface store)
			throws BlockStoreException {
		List<Sha256Hash> re = new ArrayList<>();
		switch (newChainHead.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			ContractExecutionResult c = new ContractExecutionResult()
					.parseChecked(newChainHead.getTransactions().get(0).getData());
			for (Contractresult s : store.getLowerConfirmedContractresult(c.getContracttokenid(),
					getExecuteChainlength(newChainHead))) {
				re.add(s.getBlockHash());
			}
		case BLOCKTYPE_ORDER_EXECUTE:
			for (Orderresult s : store.getLowerConfirmedOrderresult(getExecuteChainlength(newChainHead))) {
				re.add(s.getBlockHash());
			}
		default:
		}
		return re;
	}

	/**
	 * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is
	 * included, lower is not.
	 */
	protected LinkedList<Block> getPartialChain(Block higher, Block lower, BlockStoreInterface store)
			throws BlockStoreException {
		checkArgument(getExecuteChainlength(higher) > getExecuteChainlength(lower), "higher and lower are reversed");
		LinkedList<Block> results = new LinkedList<>();
		Block cursor = higher;
		while (true) {
			results.add(cursor);
			cursor = checkNotNull(store.get(getExecutionPrev(cursor)), "Ran off the end of the chain");
			if (cursor.equals(lower))
				break;
		}
		return results;
	}

}
