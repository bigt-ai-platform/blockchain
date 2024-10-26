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
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.Coin;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.response.GetTXRewardListResponse;
import net.bigtangle.core.response.GetTXRewardResponse;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.core.ConflictCandidate;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.server.data.OrderMatchingResult;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.utils.UtilSort;
import net.bigtangle.store.FullBlockStore;
import net.bigtangle.utils.Json;

public abstract class ServiceBaseConfirmation extends ServiceBaseOrder {

	private static final Logger logger = LoggerFactory.getLogger(ServiceBaseConfirmation.class);

	public ServiceBaseConfirmation(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService) {
		super(serverConfiguration, networkParameters, cacheBlockService);

	}

	/**
	 * Recursively removes the specified block and its approvers from the collection
	 * if this block is contained in the collection.
	 */
	public void removeBlockAndApproversFrom(Collection<BlockWrap> blocks, BlockWrap startingBlock, FullBlockStore store)
			throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<BlockWrap>(
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
			for (Sha256Hash req : store.getSolidApproverBlockHashes(block.getBlockHash())) {
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
	public void addConfirmedApproversTo(Collection<BlockWrap> blocks, BlockWrap startingBlock, FullBlockStore store)
			throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<BlockWrap>(
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
			for (Sha256Hash req : store.getSolidApproverBlockHashes(block.getBlockHash())) {
				if (!blockQueueSet.contains(req)) {
					BlockWrap pred = getBlockWrap(req, store);
					blockQueueSet.add(req);
					blockQueue.add(pred);
				}
			}
		}
	}

	/**
	 * Recursively adds the specified block and its approved and required blocks to
	 * the collection as referenced if the blocks are not in the collection etc, see
	 * continue. if a required as dependency block is missing somewhere, returns
	 * false. throwException will be true, if it required the validation for
	 * consensus. Otherwise, it does ignore the cutoff blocks.
	 *
	 */
	public boolean addReferencedBlockHashesTo(Set<BlockWrap> blocks, BlockWrap startingBlock, long cutoffHeight,
			long prevMilestoneNumber, boolean throwException, List<Block.Type> blocktypes, boolean checkSpentConflict,
			FullBlockStore store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<BlockWrap>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());
		boolean notMissingAnything = true;

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
				addBlockWithCheckReferenced(blocks, block, checkSpentConflict, store);
			} else {
				for (Block.Type type : blocktypes) {
					if (type.equals(block.getBlock().getBlockType())) {
						addBlockWithCheckReferenced(blocks, block, checkSpentConflict, store);
					}
				}
			}

			// Queue all of its required blocks if not already queued.
			Set<Sha256Hash> allRequiredBlockHashes = getAllRequiredBlockHashes(block.getBlock(), true);
			for (Sha256Hash req : allRequiredBlockHashes) {
				if (!blockQueueSet.contains(req)) {
					BlockWrap pred = getBlockWrap(req, store);
					if (pred == null) {
						notMissingAnything = false;
						continue;
					} else {
						blockQueueSet.add(req);
						blockQueue.add(pred);
					}
				}
			}
		}

		return notMissingAnything;
	}

	public boolean checkExists(Set<BlockWrap> allApproved, BlockWrap newBlock) throws BlockStoreException {
		for (BlockWrap b : allApproved) {
			if (b.getBlockHash().equals(newBlock.getBlockHash())) {
				return true;
			}
		}
		return false;
	}

	/*
	 * add the block and its referenced BlockWrap to allApprovedNewBlocks, if
	 * checkSpentConflict then add this block and its referenced all or nothing
	 */
	public void addBlockWithCheckReferenced(Set<BlockWrap> allApprovedNewBlocks, BlockWrap block,
			boolean checkSpentConflict, FullBlockStore store) throws BlockStoreException {
		boolean check = true;
		if (checkSpentConflict) {
			Set<BlockWrap> allApprovedCheckBlocks = new HashSet<>();
			allApprovedCheckBlocks.add(block);
			// contract execution, then check all referenced blocks with no conflicts
			if (Block.Type.BLOCKTYPE_CONTRACT_EXECUTE.equals(block.getBlock().getBlockType())
					|| Block.Type.BLOCKTYPE_ORDER_EXECUTE.equals(block.getBlock().getBlockType())) {
				allApprovedCheckBlocks.addAll(getReferrencedBlockWrap(block.getBlock(), store));
			}
			check = checkSpentAndConflict(allApprovedNewBlocks, allApprovedCheckBlocks, store);
		}
		if (check) {
			allApprovedNewBlocks.add(block);
			if ((Block.Type.BLOCKTYPE_CONTRACT_EXECUTE.equals(block.getBlock().getBlockType())
					|| Block.Type.BLOCKTYPE_ORDER_EXECUTE.equals(block.getBlock().getBlockType()))) {
				allApprovedNewBlocks.addAll(getReferrencedBlockWrap(block.getBlock(), store));
			}
		}

	}

	public boolean checkSpentAndConflict(Set<BlockWrap> allApproved, Set<BlockWrap> newBlocks, FullBlockStore store)
			throws BlockStoreException {
		Set<BlockWrap> allApprovedNewBlocks = new HashSet<>();

		allApprovedNewBlocks.addAll(allApproved);
		allApprovedNewBlocks.addAll(newBlocks);

		boolean anySpentInputs = hasSpentInputs(allApprovedNewBlocks, store);

		if (anySpentInputs) {
			return false;

		}

		boolean anyCandidateConflicts = allApprovedNewBlocks.stream().map(b -> b.toConflictCandidates())
				.flatMap(i -> i.stream()).collect(Collectors.groupingBy(i -> i.getConflictPoint())).values().stream()
				.anyMatch(l -> l.size() > 1);
		if (anyCandidateConflicts) {
			return false;
		}

		return true;

	}

	public boolean hasSpentInputs(Set<BlockWrap> allApprovedNewBlocks, FullBlockStore store) {
		return allApprovedNewBlocks.stream().map(b -> b.toConflictCandidates()).flatMap(i -> i.stream()).anyMatch(c -> {
			try {
				boolean re = hasSpentDependencies(c, store);
				// if (re)
				// logger.debug("hasSpentInputs " + c.getBlock().getBlock().toString());
				return re;
			} catch (BlockStoreException e) {
				// e.printStackTrace();
				return true;
			}
		});
	}

	public boolean solidifyWaiting(Block block, FullBlockStore store) throws BlockStoreException {

		SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService)
				.checkSolidity(block, false, store, false);
		// allow here unsolid block, as sync may do only the referenced blocks
		if (SolidityState.State.MissingPredecessor.equals(solidityState.getState())) {
			solidifyBlock(block, SolidityState.getSuccessState(), false, store);
		} else {
			solidifyBlock(block, solidityState, false, store);
		}
		return true;
	}

	/**
	 * Get the {@link Script} from the script bytes or return Script of empty byte
	 * array.
	 */
	protected Script getScript(byte[] scriptBytes) {
		try {
			return new Script(scriptBytes);
		} catch (Exception e) {
			return new Script(new byte[0]);
		}
	}

	/**
	 * Get the address from the {@link Script} if it exists otherwise return empty
	 * string "".
	 *
	 * @param script The script.
	 * @return The address.
	 */
	protected String getScriptAddress(@Nullable Script script) {
		String address = "";
		try {
			if (script != null) {
				address = script.getToAddress(networkParameters, true).toString();
			}
		} catch (Exception e) {
			// e.printStackTrace();
		}
		return address;
	}

	public List<BlockWrap> sortedPrevsChain(List<Contractresult> prevs, FullBlockStore store)
			throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		List<BlockWrap> re = new ArrayList<BlockWrap>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Contractresult prevNotMilestone : prevs) {
			re.add(getBlockWrap(prevNotMilestone.getBlockHash(), store));
		}
		new UtilSort().sortBlockWrap(re);
		return re;
	}

	public Set<BlockWrap> collectPrevsChain(List<Contractresult> prevs, Contractresult prevMilestone,
			FullBlockStore store) throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		Set<BlockWrap> re = new HashSet<BlockWrap>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Contractresult prevNotMilestone : prevs) {
			re = new HashSet<BlockWrap>();
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

	public List<BlockWrap> sortedPrevsChainOrderresult(List<Orderresult> prevs, FullBlockStore store)
			throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		List<BlockWrap> re = new ArrayList<BlockWrap>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Orderresult prevNotMilestone : prevs) {
			re.add(getBlockWrap(prevNotMilestone.getBlockHash(), store));
		}
		new UtilSort().sortBlockWrap(re);
		return re;
	}

	public Set<BlockWrap> collectPrevsChain(List<Orderresult> prevs, Orderresult prevMilestone, FullBlockStore store)
			throws BlockStoreException {
		// get all unspents forms a chain, remove others from prevs
		Set<BlockWrap> re = new HashSet<BlockWrap>();
		if (prevs.isEmpty())
			return re;

		// find the longest chained execution connected to last milestone
		for (Orderresult prevNotMilestone : prevs) {
			re = new HashSet<BlockWrap>();
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

	public Set<BlockWrap> collectReferencedChainedOrderExecutions(BlockWrap headContractExecutions,
			FullBlockStore store) throws BlockStoreException {

		Set<BlockWrap> re = new HashSet<BlockWrap>();
		boolean brokenChained = true;
		BlockWrap startingBlock = headContractExecutions;
		while (startingBlock != null) {
			re.add(startingBlock);
			startingBlock = getBlockWrap(new OrderExecutionResult()
					.parseChecked(startingBlock.getBlock().getTransactions().get(0).getData()).getPrevblockhash(),
					store);

			if (startingBlock == null) {
				brokenChained = false;

			}
			if (startingBlock != null && Sha256Hash.ZERO_HASH.equals(startingBlock.getBlock().getHash())) {
				brokenChained = false;
				// finish at origin or
				startingBlock = null;
			}
			if (startingBlock != null && startingBlock.getBlockEvaluation().getMilestone() > 0) {
				brokenChained = false;
				// finish at origin or
				startingBlock = null;
			}
		}
		if (brokenChained) {
			return new HashSet<BlockWrap>();
		} else {
			return re;
		}
	}

	/*
	 * return all Execution Blocks not in milestone and chained from
	 * headContractExecutions to Execution in milestone or begin.
	 */
	public Set<BlockWrap> collectReferencedChainedContractExecutions(BlockWrap headContractExecutions,
			FullBlockStore store) throws BlockStoreException {

		Set<BlockWrap> re = new HashSet<BlockWrap>();

		BlockWrap startingBlock = headContractExecutions;
		boolean brokenChained = true;
		while (startingBlock != null) {
			re.add(startingBlock);
			startingBlock = getBlockWrap(new ContractExecutionResult()
					.parseChecked(startingBlock.getBlock().getTransactions().get(0).getData()).getPrevblockhash(),
					store);

			if (startingBlock == null) {
				brokenChained = false;

			}
			if (startingBlock != null && Sha256Hash.ZERO_HASH.equals(startingBlock.getBlock().getHash())) {
				brokenChained = false;
				// finish at origin or
				startingBlock = null;
			}
			if (startingBlock != null && startingBlock.getBlockEvaluation().getMilestone() > 0) {
				brokenChained = false;
				// finish at origin
				startingBlock = null;
			}
		}
		if (brokenChained) {
			return new HashSet<BlockWrap>();
		} else {
			return re;
		}
	}

	/**
	 * Recursively adds the specified block and its approved blocks to the
	 * collection if the blocks are not in the current milestone and not in the
	 * collection. if a block is missing somewhere, returns false. For check missing
	 * is not allowed, for build missing is not checked
	 *
	 */
	public boolean addRequiredUnconfirmedBlocksTo(Collection<BlockWrap> blocks, BlockWrap startingBlock,
			long cutoffHeight, FullBlockStore store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<BlockWrap>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startingBlock);
		blockQueueSet.add(startingBlock.getBlockHash());
		boolean notMissingAnything = true;

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

			// Queue all of its required blocks if not already queued.
			for (Sha256Hash req : getAllRequiredBlockHashes(block.getBlock(), false)) {
				if (!blockQueueSet.contains(req)) {
					BlockWrap pred = getBlockWrap(req, store);
					if (pred == null) {
						notMissingAnything = false;
						continue;
					} else {
						blockQueueSet.add(req);
						blockQueue.add(pred);
					}
				}
			}
		}

		return notMissingAnything;
	}

	public boolean hasSpentDependencies(ConflictCandidate c, FullBlockStore store) throws BlockStoreException {
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			return getUTXOSpent(c, store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();

			// Initial issuances are allowed iff no other same token issuances
			// are confirmed, i.e. spent iff any token confirmed
			if (connectedToken.getTokenindex() == 0)
				return store.getTokenAnyConfirmed(connectedToken.getTokenid(), connectedToken.getTokenindex());
			else
				return store.getTokenSpent(connectedToken.getPrevblockhash());
		case REWARDISSUANCE:
			return store.getRewardSpent(c.getConflictPoint().getConnectedReward().getPrevRewardHash());
		case DOMAINISSUANCE:
			// exception for the block
			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();
			return store.getDomainIssuingConfirmedBlock(connectedDomainToken.getTokenname(),
					connectedDomainToken.getDomainNameBlockHash(), connectedDomainToken.getTokenindex()) != null;
		case CONTRACTEXECUTE:
			final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContracExecute();
			if (connectedContracExecute.getPrevblockhash().equals(networkParameters.getGenesisBlock().getHash())) {
				return false;
			} else {
				return store.checkContractResultSpent(connectedContracExecute.getPrevblockhash(),
						c.getBlock().getBlockHash());

			}
		case ORDEREXECUTE:
			final OrderExecutionResult connectedOrderExecute = c.getConflictPoint().getConnectedOrderExecute();
			if (connectedOrderExecute.getPrevblockhash().equals(Sha256Hash.ZERO_HASH)) {
				return false;
			} else {
				return store.checkOrderResultSpent(connectedOrderExecute.getPrevblockhash(),
						c.getBlock().getBlockHash());
			}

		default:
			throw new RuntimeException("Not Implemented");
		}
	}

	public boolean hasConfirmedDependencies(ConflictCandidate c, FullBlockStore store) throws BlockStoreException {
		switch (c.getConflictPoint().getType()) {
		case TXOUT:
			return getUTXOConfirmed(c.getConflictPoint().getConnectedOutpoint(), store);
		case TOKENISSUANCE:
			final Token connectedToken = c.getConflictPoint().getConnectedToken();

			// Initial issuances are allowed (although they may be spent
			// already)
			if (connectedToken.getTokenindex() == 0)
				return true;
			else
				return store.getTokenConfirmed(connectedToken.getPrevblockhash());
		case REWARDISSUANCE:
			return store.getRewardConfirmed(c.getConflictPoint().getConnectedReward().getPrevRewardHash());
		case DOMAINISSUANCE:
			final Token connectedDomainToken = c.getConflictPoint().getConnectedDomainToken();
			return store.getTokenConfirmed(Sha256Hash.wrap(connectedDomainToken.getDomainNameBlockHash()));
		case CONTRACTEXECUTE:
			final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContracExecute();
			if (connectedContracExecute.getPrevblockhash().equals(Sha256Hash.ZERO_HASH)) {
				return true;
			} else {
				return store.checkContractResultConfirmed(connectedContracExecute.getPrevblockhash());
			}
		case ORDEREXECUTE:
			final OrderExecutionResult connectedOrderExecute = c.getConflictPoint().getConnectedOrderExecute();
			if (connectedOrderExecute.getPrevblockhash().equals(Sha256Hash.ZERO_HASH)) {
				return true;
			} else {
				return store.checkOrderResultConfirmed(connectedOrderExecute.getPrevblockhash());
			}
		default:
			throw new RuntimeException("not implemented");
		}
	}

	private boolean findBlockWithSpentOrUnconfirmedInputs(HashSet<BlockWrap> blocks, FullBlockStore store) {
		// Get all conflict candidates in blocks
		Stream<ConflictCandidate> candidates = blocks.stream().map(b -> b.toConflictCandidates())
				.flatMap(i -> i.stream());

		// Find conflict candidates whose used outputs are already spent or
		// still unconfirmed
		return candidates.filter((ConflictCandidate c) -> {
			try {
				return hasSpentDependencies(c, store) || !hasConfirmedDependencies(c, store);
			} catch (BlockStoreException e) {
				e.printStackTrace();
			}
			return false;
		}).findFirst().isPresent();
	}

	/**
	 * Resolves all conflicts such that the confirmed set is compatible with all
	 * blocks remaining in the set of blocks.
	 * 
	 * @param blocksToAdd  the set of blocks to add to the current milestone
	 * @param cutoffHeight
	 * @throws BlockStoreException
	 */
	public void resolveAllConflicts(TreeSet<BlockWrap> blocksToAdd, long cutoffHeight, FullBlockStore store)
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
		resolveMilestoneConflicts(blocksToAdd, store);

		// Then resolve conflicts between non-milestone + new candidates
		resolveTemporaryConflicts(blocksToAdd, cutoffHeight, store);

		// Remove blocks and their approvers that have at least one input
		// with its corresponding output no longer confirmed
		removeWhereUsedOutputsUnconfirmed(blocksToAdd, store);
	}

	/**
	 * Remove blocks from blocksToAdd that miss their required predecessors, i.e.
	 * the predecessors are not confirmed or in blocksToAdd.
	 * 
	 * @param blocksToAdd
	 * @throws BlockStoreException
	 */
	private void removeWhereUnconfirmedRequirements(TreeSet<BlockWrap> blocksToAdd, FullBlockStore store)
			throws BlockStoreException {
		Iterator<BlockWrap> iterator = blocksToAdd.iterator();
		while (iterator.hasNext()) {
			BlockWrap b = iterator.next();
			List<BlockWrap> allRequirements = getAllBlocksFromHash(getAllRequiredBlockHashes(b.getBlock(), true),
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
	 * @param blocksToAdd
	 * @throws BlockStoreException
	 */
	public void removeWhereIneligible(Set<BlockWrap> blocksToAdd, FullBlockStore store) {
		findWhereCurrentlyIneligible(blocksToAdd).forEach(b -> {
			try {
				removeBlockAndApproversFrom(blocksToAdd, b, store);
			} catch (BlockStoreException e) {
				// Cannot happen.
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Find blocks from blocksToAdd that are currently locally ineligible.
	 * 
	 * @param blocksToAdd
	 * @throws BlockStoreException
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
	 * @param blocksToAdd
	 * @throws BlockStoreException
	 */
	public void removeWhereUsedOutputsUnconfirmed(Set<BlockWrap> blocksToAdd, FullBlockStore store)
			throws BlockStoreException {
		// Confirmed blocks are always ok
		new HashSet<BlockWrap>(blocksToAdd).stream().filter(b -> !b.getBlockEvaluation().isConfirmed())
				.flatMap(b -> b.toConflictCandidates().stream()).filter(c -> {
					try {
						return !hasConfirmedDependencies(c, store); // Any
																	// candidates
						// where used
						// dependencies unconfirmed
					} catch (BlockStoreException e) {
						// Cannot happen.
						e.printStackTrace();
						throw new RuntimeException(e);
					}
				}).forEach(c -> {
					try {
						removeBlockAndApproversFrom(blocksToAdd, c.getBlock(), store);
					} catch (BlockStoreException e) {
						// Cannot happen.
						e.printStackTrace();
						throw new RuntimeException(e);
					}
				});
	}

	private void resolveMilestoneConflicts(Set<BlockWrap> blocksToAdd, FullBlockStore store)
			throws BlockStoreException {
		// Find all conflict candidates in blocks to add
		List<ConflictCandidate> conflicts = blocksToAdd.stream().map(b -> b.toConflictCandidates())
				.flatMap(i -> i.stream()).collect(Collectors.toList());

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
	 * @param blocksToAdd
	 * @param cutoffHeight
	 * @throws BlockStoreException
	 */
	private void resolveTemporaryConflicts(Set<BlockWrap> blocksToAdd, long cutoffHeight, FullBlockStore store)
			throws BlockStoreException {
		HashSet<ConflictCandidate> conflictingOutPoints = new HashSet<ConflictCandidate>();
		HashSet<BlockWrap> conflictingConfirmedBlocks = new HashSet<BlockWrap>();

		// Find all conflicts in the new blocks + confirmed blocks
		findFixableConflicts(blocksToAdd, conflictingOutPoints, conflictingConfirmedBlocks, store);

		// Resolve all conflicts by grouping by UTXO ordered by descending
		// rating
		HashSet<BlockWrap> losingBlocks = resolveTemporaryConflicts(conflictingOutPoints, blocksToAdd, cutoffHeight,
				store);

		// For confirmed blocks that have been eliminated call disconnect
		// procedure
		HashSet<Sha256Hash> traversedUnconfirms = new HashSet<>();
		for (BlockWrap b : conflictingConfirmedBlocks.stream().filter(b -> losingBlocks.contains(b))
				.collect(Collectors.toList())) {
			unconfirmRecursive(b.getBlockEvaluation().getBlockHash(), traversedUnconfirms, store);
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
	 * @param conflictingOutPoints
	 * @return losingBlocks: blocks that have been removed due to conflict
	 *         resolution
	 * @throws BlockStoreException
	 */
	private HashSet<BlockWrap> resolveTemporaryConflicts(Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> blocksToAdd, long cutoffHeight, FullBlockStore store) throws BlockStoreException {
		// Initialize blocks that will/will not survive the conflict resolution
		HashSet<BlockWrap> initialBlocks = conflictingOutPoints.stream().map(c -> c.getBlock())
				.collect(Collectors.toCollection(HashSet::new));
		HashSet<BlockWrap> winningBlocks = new HashSet<>(blocksToAdd);
		for (BlockWrap winningBlock : initialBlocks) {
			if (!addRequiredUnconfirmedBlocksTo(winningBlocks, winningBlock, cutoffHeight, store))
				throw new RuntimeException("Shouldn't happen: Block is solid but missing predecessors. ");
			addConfirmedApproversTo(winningBlocks, winningBlock, store);
		}
		HashSet<BlockWrap> losingBlocks = new HashSet<>(winningBlocks);

		// Sort conflicts internally by descending rating, then cumulative
		// weight.
		Comparator<ConflictCandidate> byDescendingRating = getConflictComparator()
				.thenComparingLong((ConflictCandidate e) -> e.getBlock().getMcmc().getRating())
				.thenComparingLong((ConflictCandidate e) -> e.getBlock().getMcmc().getCumulativeWeight())
				.thenComparingLong((ConflictCandidate e) -> -e.getBlock().getBlockEvaluation().getInsertTime())
				.thenComparing((ConflictCandidate e) -> e.getBlock().getBlockEvaluation().getBlockHash()).reversed();

		Supplier<TreeSet<ConflictCandidate>> conflictTreeSetSupplier = () -> new TreeSet<ConflictCandidate>(
				byDescendingRating);

		Map<Object, TreeSet<ConflictCandidate>> conflicts = conflictingOutPoints.stream().collect(
				Collectors.groupingBy(i -> i.getConflictPoint(), Collectors.toCollection(conflictTreeSetSupplier)));

		// Sort conflicts among each other by descending max(rating).
		Comparator<TreeSet<ConflictCandidate>> byDescendingSetRating = getConflictSetComparator()
				.thenComparingLong((TreeSet<ConflictCandidate> s) -> s.first().getBlock().getMcmc().getRating())
				.thenComparingLong(
						(TreeSet<ConflictCandidate> s) -> s.first().getBlock().getMcmc().getCumulativeWeight())
				.thenComparingLong(
						(TreeSet<ConflictCandidate> s) -> -s.first().getBlock().getBlockEvaluation().getInsertTime())
				.thenComparing(
						(TreeSet<ConflictCandidate> s) -> s.first().getBlock().getBlockEvaluation().getBlockHash())
				.reversed();

		Supplier<TreeSet<TreeSet<ConflictCandidate>>> conflictsTreeSetSupplier = () -> new TreeSet<TreeSet<ConflictCandidate>>(
				byDescendingSetRating);

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

	private Comparator<TreeSet<ConflictCandidate>> getConflictSetComparator() {
		return new Comparator<TreeSet<ConflictCandidate>>() {
			@Override
			public int compare(TreeSet<ConflictCandidate> o1, TreeSet<ConflictCandidate> o2) {
				return 0;
			}
		};
	}

	private Comparator<ConflictCandidate> getConflictComparator() {
		return new Comparator<ConflictCandidate>() {
			@Override
			public int compare(ConflictCandidate o1, ConflictCandidate o2) {
				return 0;
			}
		};
	}

	/**
	 * Finds conflicts in blocksToAdd itself and with the confirmed blocks.
	 * 
	 * @param blocksToAdd
	 * @param conflictingOutPoints
	 * @throws BlockStoreException
	 */
	private void findFixableConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> conflictingConfirmedBlocks, FullBlockStore store) throws BlockStoreException {

		findUndoableConflicts(blocksToAdd, conflictingOutPoints, conflictingConfirmedBlocks, store);
		findCandidateConflicts(blocksToAdd, conflictingOutPoints);
	}

	/**
	 * Finds conflicts among blocks to add themselves
	 * 
	 * @param blocksToAdd
	 * @param conflictingOutPoints
	 * @throws BlockStoreException
	 */
	private void findCandidateConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints)
			throws BlockStoreException {
		// Get conflicts that are spent more than once in the
		// candidates
		List<ConflictCandidate> candidateCandidateConflicts = blocksToAdd.stream().map(b -> b.toConflictCandidates())
				.flatMap(i -> i.stream()).collect(Collectors.groupingBy(i -> i.getConflictPoint())).values().stream()
				.filter(l -> l.size() > 1).flatMap(l -> l.stream()).collect(Collectors.toList());

		// Add the conflicting candidates
		for (ConflictCandidate c : candidateCandidateConflicts) {
			conflictingOutPoints.add(c);
		}
	}

	/**
	 * Finds conflicts between current confirmed and blocksToAdd
	 * 
	 * @param blocksToAdd
	 * @param conflictingOutPoints
	 * @throws BlockStoreException
	 */
	private void findUndoableConflicts(Set<BlockWrap> blocksToAdd, Set<ConflictCandidate> conflictingOutPoints,
			Set<BlockWrap> conflictingConfirmedBlocks, FullBlockStore store) throws BlockStoreException {
		// Find all conflict candidates in blocks to add
		List<ConflictCandidate> conflicts = blocksToAdd.stream().map(b -> b.toConflictCandidates())
				.flatMap(i -> i.stream()).collect(Collectors.toList());

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
	private BlockWrap getSpendingBlock(ConflictCandidate c, FullBlockStore store) throws BlockStoreException {
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
			final ContractExecutionResult connectedContracExecute = c.getConflictPoint().getConnectedContracExecute();
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

	private void filterSpent(Collection<ConflictCandidate> blockConflicts, FullBlockStore store) {
		blockConflicts.removeIf(c -> {
			try {
				return !hasSpentDependencies(c, store);
			} catch (BlockStoreException e) {
				e.printStackTrace();
				return true;
			}
		});
	}

	public Set<Sha256Hash> getMissingPredecessors(Block block, FullBlockStore store) throws BlockStoreException {
		Set<Sha256Hash> missingPredecessorBlockHashes = new HashSet<>();
		final Set<Sha256Hash> allPredecessorBlockHashes = getAllRequiredBlockHashes(block, false);
		for (Sha256Hash predecessorReq : allPredecessorBlockHashes) {
			Block pred = getBlock(predecessorReq, store);
			if (pred == null)
				missingPredecessorBlockHashes.add(predecessorReq);

		}
		return missingPredecessorBlockHashes;
	}

	public GetTXRewardResponse getMaxConfirmedReward(Map<String, Object> request, FullBlockStore store)
			throws BlockStoreException {

		return GetTXRewardResponse.create(cacheBlockService.getMaxConfirmedReward(store));

	}

	public GetTXRewardListResponse getAllConfirmedReward(Map<String, Object> request, FullBlockStore store)
			throws BlockStoreException {

		return GetTXRewardListResponse.create(store.getAllConfirmedReward());

	}

	protected void showConflict(HashSet<BlockWrap> allApprovedNewBlocks) {
		List<List<ConflictCandidate>> candidateConflicts = allApprovedNewBlocks.stream()
				.map(b -> b.toConflictCandidates()).flatMap(i -> i.stream())
				.collect(Collectors.groupingBy(i -> i.getConflictPoint())).values().stream().filter(l -> l.size() > 1)
				.collect(Collectors.toList());
		for (List<ConflictCandidate> l : candidateConflicts) {
			for (ConflictCandidate c : l) {
				logger.debug(" conflict list: " + c.toString());
			}
		}
	}

	public void solidifyBlocks(RewardInfo currRewardInfo, FullBlockStore store) throws BlockStoreException {
		Comparator<Block> comparator = Comparator.comparingLong((Block b) -> b.getHeight())
				.thenComparing((Block b) -> b.getHash());
		TreeSet<Block> referencedBlocks = new TreeSet<Block>(comparator);
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			Block block = getBlock(hash, store);
			if (block != null)
				referencedBlocks.add(block);
		}
		for (Block block : referencedBlocks) {
			solidifyWaiting(block, store);
		}
	}

	public boolean getUTXOConfirmed(TransactionOutPoint txout, FullBlockStore store) throws BlockStoreException {
		return store.getOutputConfirmation(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
	}

	public BlockEvaluation getUTXOSpender(TransactionOutPoint txout, FullBlockStore store) throws BlockStoreException {
		return store.getTransactionOutputSpender(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
	}

	public UTXO getUTXO(TransactionOutPoint out, FullBlockStore store) throws BlockStoreException {
		return store.getTransactionOutput(out.getBlockHash(), out.getTxHash(), out.getIndex());
	}

	public long getCurrentMaxHeight(TXReward maxConfirmedReward, FullBlockStore store) throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return NetworkParameters.FORWARD_BLOCK_HORIZON;
		return store.get(maxConfirmedReward.getBlockHash()).getHeight() + NetworkParameters.FORWARD_BLOCK_HORIZON;
	}

	public long getCurrentCutoffHeight(TXReward maxConfirmedReward, FullBlockStore store) throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return 0;
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.MILESTONE_CUTOFF);
		TXReward confirmedAtHeightReward = store.getRewardConfirmedAtHeight(chainlength);
		return store.get(confirmedAtHeightReward.getBlockHash()).getHeight();
	}

	public boolean getUTXOSpent(ConflictCandidate c, FullBlockStore store) throws BlockStoreException {
		TransactionOutPoint txout = c.getConflictPoint().getConnectedOutpoint();

		UTXO a = store.getTransactionOutput(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
		// the TransactionOutPoint does not exist, try do the calculation
		if (a == null) {
			solidifyWaiting(getBlock(txout.getBlockHash(), store), store);
			a = store.getTransactionOutput(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
		}
		// the TransactionOutPoint does not exist, the conflict would be ok, but it will
		// not confirmed later.
		if (a == null)
			return false;
		boolean re = a.isSpent() && !c.getBlock().getBlockHash().equals(a.getSpenderBlockHash());
		/*
		 * if (re) { logger.debug("getUTXOSpent true " + a.toString() +
		 * "\n TransactionOutPoint = " + getBlock(txout.getBlockHash(), store) +
		 * " \n spender = " + getBlock(a.getSpenderBlockHash(), store)); }
		 */
		return re;

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
	 * @throws BlockStoreException
	 */
	public boolean isEligibleForApprovalSelection(HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			FullBlockStore store) throws BlockStoreException {
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
		if (!conflictingOutPoints.isEmpty())
			return false;

		// Otherwise, the new approved block set is compatible with current
		// confirmation set
		return true;
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
	 * @throws BlockStoreException
	 */
	public boolean isEligibleForApprovalSelection(BlockWrap block, HashSet<BlockWrap> currentApprovedUnconfirmedBlocks,
			long cutoffHeight, long maxHeight, FullBlockStore store) throws BlockStoreException {
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

		// Get sets of all / all new unconfirmed blocks when approving the
		// specified block in combination with the currently included blocks
		@SuppressWarnings("unchecked")
		HashSet<BlockWrap> allApprovedUnconfirmedBlocks = (HashSet<BlockWrap>) currentApprovedUnconfirmedBlocks.clone();
		try {
			if (!addRequiredUnconfirmedBlocksTo(allApprovedUnconfirmedBlocks, block, cutoffHeight, store))
				throw new RuntimeException("Shouldn't happen: Block is solid but missing predecessors. ");
		} catch (VerificationException e) {
			return false;
		}

		// If this set of blocks is eligible, all is fine
		return isEligibleForApprovalSelection(allApprovedUnconfirmedBlocks, store);
	}

	protected void confirmBlock(BlockWrap block, FullBlockStore blockStore) throws BlockStoreException {

		// Update block's transactions in db
		for (final Transaction tx : block.getBlock().getTransactions()) {
			confirmTransaction(block.getBlock(), tx, blockStore);
		}

		// type-specific updates
		switch (block.getBlock().getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
			// For rewards, update reward to be confirmed now
			confirmReward(block, blockStore);
			if (!enableOrderMatchExecutionChain(block.getBlock())) {
				confirmOrderMatching(block, blockStore);
			}
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			// For token creations, update token db
			confirmToken(block, blockStore);
			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			confirmVOSOrUserData(block, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			confirmContractEvent(block.getBlock(), blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			confirmContractExecute(block.getBlock(), blockStore);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			confirmOrderExecute(block.getBlock(), blockStore);
			break;
		case BLOCKTYPE_ORDER_OPEN:
			confirmOrderOpen(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}
	}

	private void confirmVOSOrUserData(BlockWrap block, FullBlockStore blockStore) {
		Transaction tx = block.getBlock().getTransactions().get(0);
		if (tx.getData() != null && tx.getDataSignature() != null) {
			try {
				@SuppressWarnings("unchecked")
				List<HashMap<String, Object>> multiSignBies = Json.jsonmapper().readValue(tx.getDataSignature(),
						List.class);
				Map<String, Object> multiSignBy = multiSignBies.get(0);
				byte[] pubKey = Utils.HEX.decode((String) multiSignBy.get("publickey"));
				byte[] data = tx.getHash().getBytes();
				byte[] signature = Utils.HEX.decode((String) multiSignBy.get("signature"));
				boolean success = ECKey.verify(data, signature, pubKey);
				if (!success) {
					throw new BlockStoreException("multisign signature error");
				}
				synchronizationUserData(block.getBlock().getHash(), DataClassName.valueOf(tx.getDataClassName()),
						tx.getData(), (String) multiSignBy.get("publickey"), block.getBlock().getBlockType().ordinal(),
						blockStore);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void confirmOrderMatching(BlockWrap block, FullBlockStore blockStore) throws BlockStoreException {
		// Get list of consumed orders, virtual order matching tx and newly
		// generated remaining order book
		// TODO don't calculate again, it should already have been calculated
		// before

		OrderMatchingResult actualCalculationResult = generateOrderMatching(block.getBlock(), blockStore);
		confirmOrderMatching(block.getBlock(), actualCalculationResult, blockStore);

	}

	private void confirmOrderMatching(Block block, OrderMatchingResult actualCalculationResult,
			FullBlockStore blockStore) throws BlockStoreException {

		// All consumed order records are now spent by this block
		for (OrderRecord o : actualCalculationResult.getSpentOrders()) {
			o.setSpent(true);
			o.setSpenderBlockHash(block.getHash());
		}

		blockStore.updateOrderSpent(actualCalculationResult.getSpentOrders());
		// Set virtual outputs confirmed
		confirmVirtualCoinbaseTransaction(block, blockStore);
		// Set new orders confirmed

		blockStore.updateOrderConfirmed(actualCalculationResult.getRemainingOrders(), true);

		// Update the matching history in db
		addMatchingEvents(actualCalculationResult, actualCalculationResult.getOutputTx().getHashAsString(),
				block.getTimeSeconds(), blockStore);
	}

	/*
	 * confirm from the Execution
	 */
	public void confirmOrderExecute(Block block, FullBlockStore blockStore) throws BlockStoreException {

		confirmOrderExecute(block, true, blockStore);

	}

	public void confirmOrderExecute(Block block, boolean confirm, FullBlockStore blockStore)
			throws BlockStoreException {

		try {
			OrderExecutionResult result = new OrderExecutionResult().parse(block.getTransactions().get(0).getData());
			Orderresult prevblockhash = blockStore.getOrderResult(result.getPrevblockhash());
			OrderExecutionResult check = new ServiceOrderExecution(serverConfiguration, networkParameters,
					cacheBlockService).orderMatching(block, prevblockhash, result.getReferencedBlocks(), blockStore);
			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getAllRecords().equals(check.getAllRecords())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {
				debugOrderExecutionResult(block, check, confirm, blockStore);

				if (confirm) {
					for (OrderRecord c : check.getSpentOrderRecord()) {
						c.setSpent(true);
						c.setSpenderBlockHash(block.getHash());
					}
					blockStore.updateOrderSpent(check.getSpentOrderRecord());
					blockStore.updateOrderConfirmed(check.getRemainderRecords(), block.getHash(), confirm);
					blockStore.updateOrderResultConfirmed(block.getHash(), confirm);
					confirmTransaction(block, confirm, check.getOutputTx(), blockStore);

					blockStore.updateOrderCancelSpent(check.getCancelRecords(), block.getHash(), confirm);

					// Update the matching
					addMatchingEventsOrderExecution(check, check.getOutputTx().getHashAsString(),
							block.getTimeSeconds(), blockStore);
					for (BlockWrap dep : getReferrencedBlockWrap(block, blockStore)) {
						confirmBlock(dep, blockStore);
					}
				} else {
					for (OrderRecord c : check.getSpentOrderRecord()) {
						// not revert if the same order is in remainder,
						if (!spentInRemainder(c, check.getRemainderOrderRecord())) {
							c.setSpent(false);
							c.setSpenderBlockHash(null);
						}
					}
					for (OrderRecord c : check.getRemainderOrderRecord()) {
						c.setSpent(false);
						c.setSpenderBlockHash(null);
					}
					blockStore.updateOrderSpent(check.getSpentOrderRecord());
					blockStore.updateOrderSpent(check.getRemainderOrderRecord());
					blockStore.updateOrderConfirmed(check.getRemainderRecords(), block.getHash(), confirm);
					blockStore.updateOrderResultConfirmed(block.getHash(), confirm);
					confirmTransaction(block, confirm, check.getOutputTx(), blockStore);

					blockStore.updateOrderCancelSpent(check.getCancelRecords(), null, confirm);

					blockStore.updateOrderresultMilestone(block.getHash(), -1);
					for (BlockWrap dep : getReferrencedBlockWrap(block, blockStore)) {
						unconfirm(dep, new HashSet<>(), blockStore);
					}
				}

				evictTransactions(block.getHash(), blockStore);

			} else {
				logger.debug("check failed result=" + result.toString() + " check =" + check.toString());
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/*
	 * connect from the contract Execution
	 */
	public void confirmContractExecute(Block block, FullBlockStore blockStore) throws BlockStoreException {

		try {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(block.getTransactions().get(0).getData());
			Contractresult prevblockhash = blockStore.getContractresult(result.getPrevblockhash());
			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
					cacheBlockService).executeContract(block, blockStore, result.getContracttokenid(), prevblockhash,
							result.getReferencedBlocks());
			// Sets.difference(result.getRemainderRecords(), check.getRemainderRecords());
			// Sets.difference(check.getRemainderRecords(), result.getRemainderRecords());
			if (check != null && result.getOutputTxHash().equals(check.getOutputTxHash())
					&& result.getAllRecords().equals(check.getAllRecords())
					&& result.getRemainderRecords().equals(check.getRemainderRecords())
					&& result.getCancelRecords().equals(check.getCancelRecords())) {

				// blockStore.updateContractEventConfirmed(check.getAllRecords(), true);
				blockStore.updateContractEventSpent(check.getAllRecords(), block.getHash(), true);

				blockStore.updateContractResultConfirmed(block.getHash(), true);

				confirmTransaction(block, check.getOutputTx(), blockStore);
				for (BlockWrap dep : getReferrencedBlockWrap(block, blockStore)) {
					confirmBlock(dep, blockStore);
				}
				// reset cache
				evictTransactions(block.getHash(), blockStore);

			}
			// blockStore.updateContractEvent( );
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void unConfirmContractExecute(Block block, FullBlockStore blockStore) throws BlockStoreException {

		try {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(block.getTransactions().get(0).getData());

			blockStore.updateContractResultConfirmed(block.getHash(), false);
			blockStore.updateContractEventSpent(result.getAllRecords(), block.getHash(), false);
			blockStore.updateTransactionOutputConfirmed(block.getHash(), result.getOutputTxHash(), 0, false);

			blockStore.updateContractresultMilestone(block.getHash(), -1);

			evictTransactions(block.getHash(), blockStore);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void unConfirmOrderExecute(Block block, FullBlockStore blockStore) throws BlockStoreException {

		confirmOrderExecute(block, false, blockStore);
	}

	private void confirmOrderOpen(BlockWrap block, FullBlockStore blockStore) throws BlockStoreException {
		// Set own output confirmed
		blockStore.updateOrderConfirmed(block.getBlock().getHash(), Sha256Hash.ZERO_HASH, true);
	}

	private void confirmReward(BlockWrap block, FullBlockStore blockStore) throws BlockStoreException {
		// Set virtual reward tx outputs confirmed
		confirmVirtualCoinbaseTransaction(block.getBlock(), blockStore);

		// Set used other output spent
		blockStore.updateRewardSpent(blockStore.getRewardPrevBlockHash(block.getBlock().getHash()), true,
				block.getBlock().getHash());

		// Set own output confirmed
		blockStore.updateRewardConfirmed(block.getBlock().getHash(), true);
		cacheBlockService.evictMaxConfirmedReward();
	}

	protected void insertVirtualUTXOs(Block block, Transaction virtualTx, FullBlockStore blockStore) {
		try {
			ArrayList<Transaction> txs = new ArrayList<Transaction>();
			txs.add(virtualTx);
			connectUTXOs(block, txs, blockStore);
		} catch (BlockStoreException e) {
			// Expected after reorgs
			logger.warn("Probably reinserting reward: ", e);
		}
	}

	private void confirmToken(BlockWrap block, FullBlockStore blockStore) throws BlockStoreException {
		// Set used other output spent
		if (blockStore.getTokenPrevblockhash(block.getBlock().getHash()) != null)
			blockStore.updateTokenSpent(blockStore.getTokenPrevblockhash(block.getBlock().getHash()), true,
					block.getBlock().getHash());

		// Set own output confirmed
		blockStore.updateTokenConfirmed(block.getBlock().getHash(), true);
	}

	private void confirmContractEvent(Block block, FullBlockStore blockStore) throws BlockStoreException {

		// Set own output confirmed
		List<Sha256Hash> bs = new ArrayList<>();
		bs.add(block.getHash());
		blockStore.updateContractEventConfirmed(bs, true);
	}

	private void unConfirmContractEvent(Block block, FullBlockStore blockStore) throws BlockStoreException {

		// Set own output confirmed
		List<Sha256Hash> bs = new ArrayList<>();
		bs.add(block.getHash());
		blockStore.updateContractEventConfirmed(bs, false);
		// unconfirm the Contractexecution
		Sha256Hash result = blockStore.getContractEventSpent(block.getHash());
		if (result != null) {
			// unconfirm the contract result
			unConfirmContractExecute(getBlock(result, blockStore), blockStore);
		}
	}

	private void confirmTransaction(Block block, Transaction tx, FullBlockStore blockStore) throws BlockStoreException {
		confirmTransaction(block, true, tx, blockStore);
	}

	private void confirmTransaction(Block block, boolean confirm, Transaction tx, FullBlockStore blockStore)
			throws BlockStoreException {

		// Set own outputs confirmed
		for (TransactionOutput out : tx.getOutputs()) {
			blockStore.updateTransactionOutputConfirmed(block.getHash(), tx.getHash(), out.getIndex(), confirm);
		}
		// Set previous outputs as spent
		for (TransactionInput in : tx.getInputs()) {

			if (!tx.isCoinBase()) {
				UTXO prevOut = blockStore.getTransactionOutput(in.getOutpoint().getBlockHash(),
						in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
				// Sanity check
				if (prevOut == null) {
					BlockWrap b = getBlockWrap(in.getOutpoint().getBlockHash(), blockStore);
					throw new RuntimeException("Attempted to spend a non-existent output from block" + b.toString());
				}
				// FIXME transaction check at connected if (prevOut.isSpent())
				// throw new RuntimeException("Attempted to spend an already spent output!");
				if (confirm)
					blockStore.updateTransactionOutputSpent(prevOut.getBlockHash(), prevOut.getTxHash(),
							prevOut.getIndex(), confirm, block.getHash());
				else {
					blockStore.updateTransactionOutputSpent(prevOut.getBlockHash(), prevOut.getTxHash(),
							prevOut.getIndex(), confirm, null);
				}
			}
		}

	}

	public void evictTransactions(Block block, FullBlockStore blockStore) throws BlockStoreException {

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

	public void evictTransactions(Sha256Hash blockHash, FullBlockStore blockStore) {

		try {
			List<UTXO> utxos = blockStore.getOpenOutputsByBlockhash(blockHash);
			for (UTXO u : utxos) {
				cacheBlockService.evictOutputs(u.getAddress(), blockStore);
				cacheBlockService.evictOutputs(u.getFromaddress(), blockStore);
			}
		} catch (Exception e) {

		}
	}

	private void confirmVirtualCoinbaseTransaction(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Set own outputs confirmed
		blockStore.updateAllTransactionOutputsConfirmed(block.getHash(), true);
	}

	public void unconfirm(Sha256Hash blockHash, HashSet<Sha256Hash> traversedBlockHashes, FullBlockStore blockStore)
			throws BlockStoreException {
		BlockWrap blockWrap = getBlockWrap(blockHash, blockStore);
		unconfirm(blockWrap, traversedBlockHashes, blockStore);
	}

	public void unconfirm(BlockWrap blockWrap, HashSet<Sha256Hash> traversedBlockHashes, FullBlockStore blockStore)
			throws BlockStoreException {
		// If already unconfirmed, return
		if (traversedBlockHashes.contains(blockWrap.getBlockHash()))
			return;

		BlockEvaluation blockEvaluation = blockWrap.getBlockEvaluation();
		Block block = blockWrap.getBlock();

		// If already unconfirmed, return
		if (!blockEvaluation.isConfirmed())
			return;

		// Then unconfirm the block outputs
		unconfirmBlockOutputs(block, blockStore);

		// Set unconfirmed
		blockStore.updateBlockEvaluationConfirmed(blockEvaluation.getBlockHash(), false);
		blockStore.updateBlockEvaluationMilestone(blockEvaluation.getBlockHash(), -1);

		evictTransactions(block, blockStore);

		// Keep track of unconfirmed blocks
		traversedBlockHashes.add(blockWrap.getBlockHash());
	}

	public void unconfirmRecursive(Sha256Hash blockHash, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {
		// If already confirmed, return
		if (traversedBlockHashes.contains(blockHash))
			return;

		BlockWrap blockWrap = getBlockWrap(blockHash, blockStore);
		BlockEvaluation blockEvaluation = blockWrap.getBlockEvaluation();
		Block block = blockWrap.getBlock();

		// If already unconfirmed, return
		if (!blockEvaluation.isConfirmed())
			return;

		// Unconfirm all dependents
		unconfirmDependents(block, traversedBlockHashes, blockStore);

		// Then unconfirm the block itself
		unconfirmBlockOutputs(block, blockStore);

		evictTransactions(block, blockStore);
		// Set unconfirmed
		blockStore.updateBlockEvaluationConfirmed(blockEvaluation.getBlockHash(), false);
		blockStore.updateBlockEvaluationMilestone(blockEvaluation.getBlockHash(), -1);
		evictTransactions(block, blockStore);
		// Keep track of unconfirmed blocks
		traversedBlockHashes.add(blockHash);
	}

	private void unconfirmDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes, FullBlockStore blockStore)
			throws BlockStoreException {
		// Unconfirm all approver blocks first
		for (Sha256Hash approver : blockStore.getSolidApproverBlockHashes(block.getHash())) {
			unconfirmRecursive(approver, traversedBlockHashes, blockStore);
		}

		// Disconnect all transaction output dependents
		for (Transaction tx : block.getTransactions()) {
			for (TransactionOutput txout : tx.getOutputs()) {
				UTXO utxo = blockStore.getTransactionOutput(block.getHash(), tx.getHash(), txout.getIndex());
				if (utxo != null && utxo.isSpent()) {
					unconfirmRecursive(
							blockStore.getTransactionOutputSpender(block.getHash(), tx.getHash(), txout.getIndex())
									.getBlockHash(),
							traversedBlockHashes, blockStore);
				}
			}
		}

		// Disconnect all type-specific dependents
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
			// Unconfirm dependents
			unconfirmRewardDependents(block, traversedBlockHashes, blockStore);
			unconfirmOrderMatchingDependents(block, traversedBlockHashes, blockStore);
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			// Unconfirm dependents
			unconfirmTokenDependents(block, traversedBlockHashes, blockStore);
			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			unconfirmContractEventDependents(block, traversedBlockHashes, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_OPEN:
			unconfirmOrderOpenDependents(block, traversedBlockHashes, blockStore);
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}
	}

	private void unconfirmOrderMatchingDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {
		// Get list of consumed orders, virtual order matching tx and newly
		// generated remaining order book
		if (!enableOrderMatchExecutionChain(block)) {
			OrderMatchingResult matchingResult = generateOrderMatching(block, blockStore);

			// Disconnect all virtual transaction output dependents
			Transaction tx = matchingResult.getOutputTx();
			for (TransactionOutput txout : tx.getOutputs()) {
				UTXO utxo = blockStore.getTransactionOutput(block.getHash(), tx.getHash(), txout.getIndex());
				if (utxo != null && utxo.isSpent()) {
					unconfirmRecursive(
							blockStore.getTransactionOutputSpender(block.getHash(), tx.getHash(), txout.getIndex())
									.getBlockHash(),
							traversedBlockHashes, blockStore);
				}
			}
		}
	}

	private void unconfirmOrderOpenDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {

		// Disconnect order record spender
		if (blockStore.getOrderSpent(block.getHash(), Sha256Hash.ZERO_HASH)) {
			unconfirmRecursive(blockStore.getOrderSpender(block.getHash(), Sha256Hash.ZERO_HASH), traversedBlockHashes,
					blockStore);
		}
	}

	private void unconfirmContractEventDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {
		Sha256Hash contractEventSpent = blockStore.getContractEventSpent(block.getHash());
		if (contractEventSpent != null) {
			unconfirmRecursive(contractEventSpent, traversedBlockHashes, blockStore);
		}
	}

	private void unconfirmTokenDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {

		// Disconnect token record spender
		if (blockStore.getTokenSpent(block.getHash())) {
			unconfirmRecursive(blockStore.getTokenSpender(block.getHashAsString()), traversedBlockHashes, blockStore);
		}

		// If applicable: Disconnect all domain definitions that were based on
		// this domain
		Token token = blockStore.getTokenByBlockHash(block.getHash());

		List<String> dependents = blockStore.getDomainDescendantConfirmedBlocks(token.getBlockHashHex());
		for (String b : dependents) {
			unconfirmRecursive(Sha256Hash.wrap(b), traversedBlockHashes, blockStore);

		}
	}

	private void unconfirmRewardDependents(Block block, HashSet<Sha256Hash> traversedBlockHashes,
			FullBlockStore blockStore) throws BlockStoreException {

		// Disconnect reward record spender
		if (blockStore.getRewardSpent(block.getHash())) {
			unconfirmRecursive(blockStore.getRewardSpender(block.getHash()), traversedBlockHashes, blockStore);
		}

		// Disconnect all virtual transaction output dependents
		Transaction tx = generateVirtualMiningRewardTX(block, blockStore);
		for (TransactionOutput txout : tx.getOutputs()) {
			UTXO utxo = blockStore.getTransactionOutput(block.getHash(), tx.getHash(), txout.getIndex());
			if (utxo != null && utxo.isSpent()) {
				unconfirmRecursive(blockStore
						.getTransactionOutputSpender(block.getHash(), tx.getHash(), txout.getIndex()).getBlockHash(),
						traversedBlockHashes, blockStore);
			}
		}
	}

	/**
	 * Disconnect the block, unconfirm all outputs of order, UTXOs and UTXO-like etc
	 * constructs.
	 * 
	 * @throws BlockStoreException if the block store had an underlying error or
	 *                             block does not exist in the block store at all.
	 */
	private void unconfirmBlockOutputs(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Unconfirm all transactions of the block
		for (Transaction tx : block.getTransactions()) {
			unconfirmTransaction(tx, block, blockStore);
		}

		// Then unconfirm type-specific stuff
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
			unconfirmReward(block, blockStore);
			if (!enableOrderMatchExecutionChain(block)) {
				unconfirmOrderMatching(block, blockStore);
			}
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			unconfirmToken(block, blockStore);
			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			unConfirmContractEvent(block, blockStore);
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			unConfirmContractExecute(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			unConfirmOrderExecute(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_OPEN:
			unconfirmOrderOpen(block, blockStore);
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}
	}

	private void unconfirmOrderMatching(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Get list of consumed orders, virtual order matching tx and newly
		// generated remaining order book
		if (!enableOrderMatchExecutionChain(block)) {
			OrderMatchingResult matchingResult = generateOrderMatching(block, blockStore);
			unconfirmOrderMatching(block, matchingResult, blockStore);
		}
	}

	private void unconfirmOrderMatching(Block block, OrderMatchingResult matchingResult, FullBlockStore blockStore)
			throws BlockStoreException {

		// All consumed order records are now unspent by this block
		Set<OrderRecord> updateOrder = new HashSet<OrderRecord>(matchingResult.getSpentOrders());
		for (OrderRecord o : updateOrder) {
			o.setSpent(false);
			o.setSpenderBlockHash(null);
		}
		blockStore.updateOrderSpent(updateOrder);

		// Set virtual outputs unconfirmed
		unconfirmVirtualCoinbaseTransaction(block.getHash(), blockStore);

		blockStore.updateOrderConfirmed(matchingResult.getRemainingOrders(), false);

		// Update the matching history in db
		removeMatchingEvents(matchingResult.getOutputTx().getHash(), blockStore);
	}

	public void removeMatchingEvents(Sha256Hash h, FullBlockStore store) throws BlockStoreException {
		store.deleteMatchingEvents(h.toString());
	}

	private void unconfirmOrderOpen(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Set own output unconfirmed

		blockStore.updateOrderConfirmed(block.getHash(), Sha256Hash.ZERO_HASH, false);
	}

	private void unconfirmReward(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Unconfirm virtual tx
		unconfirmVirtualCoinbaseTransaction(block.getHash(), blockStore);

		// Set used other output unspent
		blockStore.updateRewardSpent(blockStore.getRewardPrevBlockHash(block.getHash()), false, null);

		// Set own output unconfirmed
		blockStore.updateRewardConfirmed(block.getHash(), false);
		cacheBlockService.evictMaxConfirmedReward();
	}

	private void unconfirmToken(Block block, FullBlockStore blockStore) throws BlockStoreException {
		// Set used other output unspent
		if (blockStore.getTokenPrevblockhash(block.getHash()) != null)
			blockStore.updateTokenSpent(blockStore.getTokenPrevblockhash(block.getHash()), false, null);

		// Set own output unconfirmed
		blockStore.updateTokenConfirmed(block.getHash(), false);
	}

	/**
	 * Disconnects the UTXOs of the transaction
	 * 
	 * @param tx
	 * @param parentBlock
	 * @throws BlockStoreException
	 */
	private void unconfirmTransaction(Transaction tx, Block parentBlock, FullBlockStore blockStore)
			throws BlockStoreException {
		// Set used outputs as unspent
		if (!tx.isCoinBase()) {
			for (TransactionInput txin : tx.getInputs()) {
				blockStore.updateTransactionOutputSpent(txin.getOutpoint().getBlockHash(),
						txin.getOutpoint().getTxHash(), txin.getOutpoint().getIndex(), false, null);
			}
		}

		// Set own outputs unconfirmed
		for (TransactionOutput txout : tx.getOutputs()) {
			blockStore.updateTransactionOutputConfirmed(parentBlock.getHash(), tx.getHash(), txout.getIndex(), false);
		}
	}

	private void unconfirmVirtualCoinbaseTransaction(Sha256Hash hash, FullBlockStore blockStore)
			throws BlockStoreException {
		// Set own outputs unconfirmed
		blockStore.updateAllTransactionOutputsConfirmed(hash, false);
	}

	public void solidifyBlock(Block block, SolidityState solidityState, boolean setMilestoneSuccess,
			FullBlockStore blockStore) throws BlockStoreException {
//		if (block.getBlockType() == Type.BLOCKTYPE_ORDER_EXECUTE) {
//			logger.debug(block.toString());
//		}

		switch (solidityState.getState()) {
		case MissingCalculation:
			blockStore.updateBlockEvaluationSolid(block.getHash(), 1);

			// Reward blocks follow different logic: If this is new, run
			// consensus logic
			if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
				solidifyReward(block, blockStore);
				return;
			}
			// Insert other blocks into waiting list
			// insertUnsolidBlock(block, solidityState, blockStore);
			break;
		case MissingPredecessor:
			if (block.getBlockType() == Type.BLOCKTYPE_INITIAL
					&& getBlockWrap(block.getHash(), blockStore).getBlockEvaluation().getSolid() > 0) {
				throw new RuntimeException("Should not happen");
			}

			blockStore.updateBlockEvaluationSolid(block.getHash(), 0);

			// Insert into waiting list
			// insertUnsolidBlock(block, solidityState, blockStore);
			break;
		case Success:
			// If already set, nothing to do here...
			if (getBlockWrap(block.getHash(), blockStore).getBlockEvaluation().getSolid() == 2)
				return;

			// TODO don't calculate again, it may already have been calculated
			// before
			connectUTXOs(block, blockStore);
			connectTypeSpecificUTXOs(block, blockStore);
			calculateBlockOrderMatchingResult(block, blockStore);

			if (block.getBlockType() == Type.BLOCKTYPE_REWARD && !setMilestoneSuccess) {
				// If we don't want to set the milestone success, initialize as
				// missing calc
				blockStore.updateBlockEvaluationSolid(block.getHash(), 1);
			} else {
				// Else normal update
				blockStore.updateBlockEvaluationSolid(block.getHash(), 2);
			}
			if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
				solidifyReward(block, blockStore);
				return;
			}

			break;
		case Invalid:

			blockStore.updateBlockEvaluationSolid(block.getHash(), -1);
			break;
		}
		cacheBlockService.evictBlockEvaluation(block.getHash());
	}

	/**
	 * Calculates and inserts any virtual transaction outputs so dependees can
	 * become solid
	 * 
	 * @param block
	 * @return
	 * @throws BlockStoreException
	 */
	public Optional<OrderMatchingResult> calculateBlockOrderMatchingResult(Block block, FullBlockStore blockStore)
			throws BlockStoreException {

		Transaction tx = null;
		OrderMatchingResult matchingResult = null;

		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE:
			break;
		case BLOCKTYPE_FILE:
			break;
		case BLOCKTYPE_GOVERNANCE:
			break;
		case BLOCKTYPE_INITIAL:
			break;
		case BLOCKTYPE_REWARD:
			tx = generateVirtualMiningRewardTX(block, blockStore);
			insertVirtualUTXOs(block, tx, blockStore);

			// Get list of consumed orders, virtual order matching tx and newly
			// generated remaining order book
			if (!enableOrderMatchExecutionChain(block)) {
				matchingResult = generateOrderMatching(block, blockStore);
				tx = matchingResult.getOutputTx();
				insertVirtualUTXOs(block, tx, blockStore);
				insertVirtualOrderRecords(block, matchingResult.getRemainingOrders(), blockStore);
			}
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			break;
		case BLOCKTYPE_TRANSFER:
			break;
		case BLOCKTYPE_USERDATA:
			break;
		case BLOCKTYPE_CONTRACT_EVENT:
			break;
		case BLOCKTYPE_CONTRACT_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			break;
		case BLOCKTYPE_ORDER_OPEN:
			break;
		case BLOCKTYPE_ORDER_CANCEL:
			break;
		case BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		default:
			throw new RuntimeException("Not Implemented");

		}

		// Return the computation result
		return Optional.ofNullable(matchingResult);
	}

	@SuppressWarnings("unused")
	private Optional<ConflictCandidate> findFirstSpentInput(HashSet<BlockWrap> allApprovedNewBlocks,
			FullBlockStore store) {
		return allApprovedNewBlocks.stream().map(b -> b.toConflictCandidates()).flatMap(i -> i.stream()).filter(c -> {
			try {
				return hasSpentDependencies(c, store);
			} catch (BlockStoreException e) {
				e.printStackTrace();
				return true;
			}
		}).findFirst();
	}

	/**
	 * Adds the specified block and all approved blocks to the confirmed set. This
	 * will connect all transactions of the block by marking used UTXOs spent and
	 * adding new UTXOs to the db.
	 * 
	 * @param blockHash
	 * @param milestoneNumber
	 * @throws BlockStoreException
	 */

	public void confirm(Sha256Hash blockHash, HashSet<Sha256Hash> traversedBlockHashes, long milestoneNumber,
			FullBlockStore store) throws BlockStoreException {
		// If already confirmed, return
		if (traversedBlockHashes.contains(blockHash))
			return;

		BlockWrap blockWrap = getBlockWrap(blockHash, store);
		BlockEvaluation blockEvaluation = blockWrap.getBlockEvaluation();

		// If already confirmed, return
		if (blockEvaluation.isConfirmed())
			return;

		// Set confirmed, only if it is not confirmed
		if (!blockEvaluation.isConfirmed()) {
			store.updateBlockEvaluationConfirmed(blockEvaluation.getBlockHash(), true);
		}
		store.updateBlockEvaluationMilestone(blockEvaluation.getBlockHash(), milestoneNumber);

		// Confirm the block
		confirmBlock(blockWrap, store);

		// special Conrfirm with update

		switch (blockWrap.getBlock().getBlockType()) {

		case BLOCKTYPE_CONTRACT_EXECUTE:
			store.updateContractresultMilestone(blockEvaluation.getBlockHash(), milestoneNumber);
			break;
		case BLOCKTYPE_ORDER_EXECUTE:
			store.updateOrderresultMilestone(blockEvaluation.getBlockHash(), milestoneNumber);
			break;
		default:
			break;

		}

		evictTransactions(blockWrap.getBlock(), store);

		// Keep track of confirmed blocks
		traversedBlockHashes.add(blockHash);
	}

	//
	public Transaction generateVirtualMiningRewardTX(Block block, FullBlockStore blockStore)
			throws BlockStoreException {
		if (enableOrderMatchExecutionChain(block)) {
			return generateVirtualMiningRewardTXFeeBased(block, blockStore);
		} else {
			return generateVirtualMiningRewardTX1(block, blockStore);
		}
	}

	public Transaction generateVirtualMiningRewardTXFeeBased(Block block, FullBlockStore blockStore)
			throws BlockStoreException {
		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Set<Sha256Hash> candidateBlocks = rewardInfo.getBlocks();

		// Build transaction outputs sorted by addresses
		Transaction tx = new Transaction(networkParameters);

		// Reward the consensus block with the static reward
		tx.addOutput(Coin.FEE_DEFAULT.times(candidateBlocks.size()),
				new Address(networkParameters, block.getMinerAddress()));

		return tx;
	}

	/**
	 * Deterministically creates a mining reward transaction based on the previous
	 * blocks and previous reward transaction. DOES NOT CHECK FOR SOLIDITY. You have
	 * to ensure that the approved blocks result in an eligible reward block.
	 * 
	 * @return mining reward transaction
	 * @throws BlockStoreException
	 */
	public Transaction generateVirtualMiningRewardTX1(Block block, FullBlockStore blockStore)
			throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Set<Sha256Hash> candidateBlocks = rewardInfo.getBlocks();

		// Count how many blocks from miners in the reward interval are approved
		// and build rewards
		Queue<BlockWrap> blockQueue = new PriorityQueue<BlockWrap>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		for (Sha256Hash bHash : candidateBlocks) {
			blockQueue.add(getBlockWrap(bHash, blockStore));
		}

		// Initialize
		Set<BlockWrap> currentHeightBlocks = new HashSet<>();
		Map<BlockWrap, Set<Sha256Hash>> snapshotWeights = new HashMap<>();
		Map<Address, Long> finalRewardCount = new HashMap<>();
		BlockWrap currentBlock = null, approvedBlock = null;
		Address consensusBlockMiner = new Address(networkParameters, block.getMinerAddress());
		long currentHeight = Long.MAX_VALUE;
		long totalRewardCount = 0;

		for (BlockWrap tip : blockQueue) {
			snapshotWeights.put(tip, new HashSet<>());
		}

		// Go backwards by height
		while ((currentBlock = blockQueue.poll()) != null) {

			// If we have reached a new height level, trigger payout
			// calculation
			if (currentHeight > currentBlock.getBlockEvaluation().getHeight()) {

				// Calculate rewards
				totalRewardCount = calculateHeightRewards(currentHeightBlocks, snapshotWeights, finalRewardCount,
						totalRewardCount);

				// Finished with this height level, go to next level
				currentHeightBlocks.clear();
				long currentHeight_ = currentHeight;
				snapshotWeights.entrySet().removeIf(e -> e.getKey().getBlockEvaluation().getHeight() == currentHeight_);
				currentHeight = currentBlock.getBlockEvaluation().getHeight();
			}

			// Stop criterion: Block not in candidate list
			if (!candidateBlocks.contains(currentBlock.getBlockHash()))
				continue;

			// Add your own hash to approver hashes of current approver hashes
			snapshotWeights.get(currentBlock).add(currentBlock.getBlockHash());

			// Count the blocks of current height
			currentHeightBlocks.add(currentBlock);

			// Continue with both approved blocks
			approvedBlock = getBlockWrap(currentBlock.getBlock().getPrevBlockHash(), blockStore);
			if (!blockQueue.contains(approvedBlock)) {
				if (approvedBlock != null) {
					blockQueue.add(approvedBlock);
					snapshotWeights.put(approvedBlock, new HashSet<>(snapshotWeights.get(currentBlock)));
				}
			} else {
				snapshotWeights.get(approvedBlock).add(currentBlock.getBlockHash());
			}
			approvedBlock = getBlockWrap(currentBlock.getBlock().getPrevBranchBlockHash(), blockStore);
			if (!blockQueue.contains(approvedBlock)) {
				if (approvedBlock != null) {
					blockQueue.add(approvedBlock);
					snapshotWeights.put(approvedBlock, new HashSet<>(snapshotWeights.get(currentBlock)));
				}
			} else {
				snapshotWeights.get(approvedBlock).add(currentBlock.getBlockHash());
			}
		}

		// Exception for height 0 (genesis): since prevblock does not exist,
		// finish payout
		// calculation
		if (currentHeight == 0) {
			totalRewardCount = calculateHeightRewards(currentHeightBlocks, snapshotWeights, finalRewardCount,
					totalRewardCount);
		}

		// Build transaction outputs sorted by addresses
		Transaction tx = new Transaction(networkParameters);

		// Reward the consensus block with the static reward
		tx.addOutput(Coin.SATOSHI.times(NetworkParameters.REWARD_AMOUNT_BLOCK_REWARD), consensusBlockMiner);

		// Reward twice: once the consensus block, once the normal block maker
		// of good quantiles
		for (Entry<Address, Long> entry : finalRewardCount.entrySet().stream()
				.sorted(Comparator.comparing((Entry<Address, Long> e) -> e.getKey())).collect(Collectors.toList())) {
			tx.addOutput(Coin.SATOSHI.times(entry.getValue() * NetworkParameters.PER_BLOCK_REWARD),
					consensusBlockMiner);
			tx.addOutput(Coin.SATOSHI.times(entry.getValue() * NetworkParameters.PER_BLOCK_REWARD), entry.getKey());
		}

		// The input does not really need to be a valid signature, as long
		// as it has the right general form and is slightly different for
		// different tx
		TransactionInput input = new TransactionInput(networkParameters, tx, Script
				.createInputScript(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes()));
		tx.addInput(input);
		tx.setMemo(new MemoInfo("MiningRewardTX"));
		return tx;
	}

	// For each height, throw away anything below the 99-percentile
	// in terms of reduced weight
	private long calculateHeightRewards(Set<BlockWrap> currentHeightBlocks,
			Map<BlockWrap, Set<Sha256Hash>> snapshotWeights, Map<Address, Long> finalRewardCount,
			long totalRewardCount) {
		long heightRewardCount = (long) Math.ceil(0.95d * currentHeightBlocks.size());
		totalRewardCount += heightRewardCount;

		long rewarded = 0;
		for (BlockWrap rewardedBlock : currentHeightBlocks.stream()
				.sorted(Comparator.comparingLong(b -> snapshotWeights.get(b).size()).reversed())
				.collect(Collectors.toList())) {
			if (rewarded >= heightRewardCount)
				break;

			Address miner = new Address(networkParameters, rewardedBlock.getBlock().getMinerAddress());
			if (!finalRewardCount.containsKey(miner))
				finalRewardCount.put(miner, 1L);
			else
				finalRewardCount.put(miner, finalRewardCount.get(miner) + 1);
			rewarded++;
		}
		return totalRewardCount;
	}

	private void solidifyReward(Block block, FullBlockStore blockStore) throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
		long currChainLength = blockStore.getRewardChainLength(prevRewardHash) + 1;
		long difficulty = rewardInfo.getDifficultyTargetReward();

		blockStore.insertReward(block.getHash(), prevRewardHash, difficulty, currChainLength);

	}

}
