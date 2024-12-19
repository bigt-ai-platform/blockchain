/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.PermissionDomainname;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserData;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.UTXOProviderException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.response.GetBlockListResponse;
import net.bigtangle.core.response.PermissionedAddressesResponse;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;

public abstract class ServiceBase {
	protected ServerConfiguration serverConfiguration;
	protected NetworkParameters networkParameters;
	protected CacheBlockService cacheBlockService;
	protected ObjectMapper jsonmapper;

	protected abstract void connectTypeSpecificUTXOs(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException, VerificationException;

	protected abstract void connectUTXOs(Block block, BlockStoreInterface blockStore) throws BlockStoreException;

	protected abstract void connectUTXOs(Block block, List<Transaction> transactions, BlockStoreInterface blockStore)
			throws BlockStoreException;

	protected abstract void calculateBlockOrderMatchingResult(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException;

	public ServiceBase(ServerConfiguration serverConfiguration, NetworkParameters networkParameters,
			CacheBlockService cacheBlockService, ObjectMapper jsonmapper) {
		super();
		this.serverConfiguration = serverConfiguration;
		this.networkParameters = networkParameters;
		this.cacheBlockService = cacheBlockService;
		this.jsonmapper= jsonmapper;
	}

	public boolean enableFee(Block block) {
		return (block.getLastMiningRewardBlock() > 1424626
				&& networkParameters.getId().equals(NetworkParameters.ID_MAINNET))
				|| networkParameters.getId().equals(NetworkParameters.ID_UNITTESTNET);
	}

	/*
	 * Enable each order execution in own chain and not part of reward chain
	 */
	public boolean enableOrderMatchExecutionChain(Block block) {
		return enableFee(block);
	}

	/**
	 * get domainname token multi sign address
	 *
	 */
	public List<MultiSignAddress> queryDomainnameTokenMultiSignAddresses(Sha256Hash domainNameBlockHash,
			BlockStoreInterface store) throws BlockStoreException {
		if (domainNameBlockHash.equals(networkParameters.getGenesisBlock().getHash())) {
			List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
			for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
				ECKey ecKey = permissionDomainname.getOutKey();
				multiSignAddresses.add(new MultiSignAddress("", "", ecKey.getPublicKeyAsHex()));
			}
			return multiSignAddresses;
		} else {
			Token token = store.queryDomainnameToken(domainNameBlockHash);
			if (token == null)
				throw new BlockStoreException("token not found");

			final String tokenid = token.getTokenid();
			return store.getMultiSignAddressListByTokenidAndBlockHashHex(tokenid, token.getBlockHash());
		}
	}

	/*
	 * return List<BlockWrap> from the Hash List
	 */
	public List<BlockWrap> getAllBlocksFromHash(Set<Sha256Hash> allBlockHashes, BlockStoreInterface store)
			throws BlockStoreException {
		List<BlockWrap> result = new ArrayList<>();
		for (Sha256Hash pred : allBlockHashes)
			result.add(new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
					.getBlockWrap(pred, store));
		return result;
	}

	/**
	 * Returns all blocks that must be confirmed if this block is confirmed. All
	 * transactions related to this block are include as required includePredecessor
	 * is false = not required the two getPrevBlockHash and getPrevBranchBlockHash
	 * The sync may does not check this two Predecessors
	 */

	public Set<Sha256Hash> getAllRequiredBlockHashes(Block block, boolean includePredecessor) {
		Set<Sha256Hash> allrequireds = new HashSet<>();
		if (includePredecessor) {
			allrequireds.add(block.getPrevBlockHash());
			allrequireds.add(block.getPrevBranchBlockHash());
		}
		// All used transaction outputs

		final List<Transaction> transactions = block.getTransactions();

		for (final Transaction tx : transactions) {
			if (!tx.isCoinBase()) {
				for (int index = 0; index < tx.getInputs().size(); index++) {
					TransactionInput in = tx.getInputs().get(index);
					// due to virtual txs from order/reward
					allrequireds.add(in.getOutpoint().getBlockHash());
				}
			}

		}
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE, BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER,
				BLOCKTYPE_USERDATA, BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_CONTRACT_EXECUTE, BLOCKTYPE_ORDER_EXECUTE,
				BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL, BLOCKTYPE_CONTRACTEVENT_CANCEL:
			break;
		case BLOCKTYPE_REWARD:
			RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());
			allrequireds.add(rewardInfo.getPrevRewardHash());
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			TokenInfo currentToken = new TokenInfo().parseChecked(transactions.get(0).getData());
			allrequireds.add(Sha256Hash.wrap(currentToken.getToken().getDomainNameBlockHash()));
			if (currentToken.getToken().getPrevblockhash() != null)
				allrequireds.add(currentToken.getToken().getPrevblockhash());
			break;
		default:
			throw new RuntimeException("No Implementation");
		}

		return allrequireds;
	}

	public Set<BlockWrap> getReferrencedBlockWrap(Block block, BlockStoreInterface store) throws BlockStoreException {
		Set<BlockWrap> wraps = new HashSet<>();
		for (Sha256Hash hash : getReferrencedBlockHashes(block)) {
			wraps.add(getBlockWrap(hash, store));
		}
		return wraps;
	}

	public Set<Sha256Hash> getReferrencedBlockHashes(Block block) {
		if (block.getBlockType().equals(Block.Type.BLOCKTYPE_CONTRACT_EXECUTE)) {
			return new ContractExecutionResult().parseChecked(block.getTransactions().get(0).getData())
					.getReferencedBlocks();
		}
		if (block.getBlockType().equals(Block.Type.BLOCKTYPE_ORDER_EXECUTE)) {
			return new OrderExecutionResult().parseChecked(block.getTransactions().get(0).getData())
					.getReferencedBlocks();
		}
		return new HashSet<>();
	}

	public Block getBlock(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {

		byte[] re = cacheBlockService.getBlock(blockhash, store);
		if (re == null)
			return null;
		try {
			return networkParameters.getDefaultSerializer().makeZippedBlock(re);
		} catch (Exception e) {

			throw new BlockStoreException(e);
		}
	}

	public BlockWrap getBlockWrap(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {
		try {
			Block block = getBlock(blockhash, store);
			if (block == null)
				return null;
			byte[] be = cacheBlockService.getBlockEvaluation(blockhash, store);
			BlockEvaluation v = BlockEvaluation.buildInitial(block);
			if (be != null)
				v =  jsonmapper.readValue(be, BlockEvaluation.class);
			if (v == null)
				v = BlockEvaluation.buildInitial(block);
			byte[] blockMCMC = cacheBlockService.getBlockMCMC(blockhash, store);

			return new BlockWrap(block, v, jsonmapper.readValue(blockMCMC, BlockMCMC.class), networkParameters);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public RewardInfo getRewardInfo(Block block) {
		return new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
	}

	public Block getExecutionPrev(Block block, BlockStoreInterface store) throws BlockStoreException {
		return getBlock(getExecutionPrev(block), store);
	}

	public Sha256Hash getExecutionPrev(Block block) {
		return switch (block.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE ->
			new ContractExecutionResult().parseChecked(block.getTransactions().get(0).getData()).getPrevblockhash();
		case BLOCKTYPE_ORDER_EXECUTE ->
			new OrderExecutionResult().parseChecked(block.getTransactions().get(0).getData()).getPrevblockhash();
		default -> throw new RuntimeException("Wrong block.getBlockType()");
		};
	}

	public List<Sha256Hash> getEntryPointCandidates(long currChainLength, BlockStoreInterface store)
			throws BlockStoreException {
		long minChainLength = Math.max(0, currChainLength - NetworkParameters.MILESTONE_CUTOFF);
		return getBlocksInMilestoneInterval(minChainLength, currChainLength, store);
	}

	public List<Sha256Hash> getBlocksInMilestoneInterval(long minChainLength, long currChainLength,
			BlockStoreInterface store) throws BlockStoreException {
		return store.getBlocksInMilestoneInterval(minChainLength, currChainLength);

	}

	public void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime, BlockStoreInterface store)
			throws BlockStoreException {

		store.insertMyserverblocks(prevhash, hash, inserttime);
	}

	public boolean existMyserverblocks(Sha256Hash prevhash, BlockStoreInterface store) throws BlockStoreException {

		return store.existMyserverblocks(prevhash);
	}

	public void deleteMyserverblocks(Sha256Hash prevhash, BlockStoreInterface store) throws BlockStoreException {

		store.deleteMyserverblocks(prevhash);
	}

	public GetBlockListResponse blocksFromChainLength(Long start, Long end, BlockStoreInterface store)
			throws BlockStoreException {

		return GetBlockListResponse.create(store.blocksFromChainLength(start, end));
	}

	public long getRewardMaxHeight() {
		return Long.MAX_VALUE;
	}

	public long getRewardCutoffHeight(Sha256Hash prevRewardHash, BlockStoreInterface store) throws BlockStoreException {

		Sha256Hash currPrevRewardHash = prevRewardHash;
		for (int i = 0; i < NetworkParameters.MILESTONE_CUTOFF; i++) {
			Block currRewardBlock;
			currRewardBlock = getBlock(currPrevRewardHash, store);
			RewardInfo currRewardInfo = new RewardInfo()
					.parseChecked(currRewardBlock.getTransactions().get(0).getData());
			if (currPrevRewardHash.equals(networkParameters.getGenesisBlock().getHash()))
				return 0;

			currPrevRewardHash = currRewardInfo.getPrevRewardHash();

		}
		return getBlock(currPrevRewardHash, store).getHeight();
	}

	public SolidityState getMinPredecessorSolidity(Block block, List<BlockWrap> allPredecessors,
			BlockStoreInterface store, boolean predecessorsSolid) throws BlockStoreException {

		for (BlockWrap predecessor : allPredecessors) {
			if (predecessor.getBlockEvaluation().getSolid() == 2) {
			} else if (predecessor.getBlockEvaluation().getSolid() == 1 && predecessorsSolid) {
				SolidityState solidityState = SolidityState.getSuccessState();
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
						.solidifyBlock(predecessor.getBlock(), solidityState, true, store);
				// missingCalculation =
				// SolidityState.fromMissingCalculation(predecessor.getBlockHash());

			} else {
				// TODO check logger.warn("predecessor.getBlockEvaluation().getSolid() = "
				// + predecessor.getBlockEvaluation().getSolid() + " " + block.toString());
				// throw new RuntimeException("not implemented");
			}
		}

		return SolidityState.getSuccessState();

	}

	public Set<Sha256Hash> getHashSet(Set<BlockWrap> alist) {
		Set<Sha256Hash> hashs = new HashSet<>();
		for (BlockWrap o : alist) {
			hashs.add(o.getBlockHash());
		}
		return hashs;
	}

	protected void synchronizationUserData(Sha256Hash blockhash, DataClassName dataClassName, byte[] data,
			String pubKey, long blocktype, BlockStoreInterface blockStore) throws BlockStoreException {
		UserData userData = blockStore.queryUserDataWithPubKeyAndDataclassname(dataClassName.name(), pubKey);
		if (userData == null) {
			userData = new UserData();
			userData.setBlockhash(blockhash);
			userData.setData(data);
			userData.setDataclassname(dataClassName.name());
			userData.setPubKey(pubKey);
			userData.setBlocktype(blocktype);
			blockStore.insertUserData(userData);
			return;
		}
		userData.setBlockhash(blockhash);
		userData.setData(data);
		blockStore.updateUserData(userData);
	}

	public PermissionedAddressesResponse queryDomainnameTokenPermissionedAddresses(String domainNameBlockHash,
			BlockStoreInterface store) throws BlockStoreException {
		if (domainNameBlockHash.equals(networkParameters.getGenesisBlock().getHashAsString())) {
			List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
			for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
				ECKey ecKey = permissionDomainname.getOutKey();
				multiSignAddresses.add(new MultiSignAddress("", "", ecKey.getPublicKeyAsHex()));
			}
			return (PermissionedAddressesResponse) PermissionedAddressesResponse.create("", false, multiSignAddresses);
		} else {
			Token token = store.getTokenByBlockHash(Sha256Hash.wrap(domainNameBlockHash));
			final String domainName = token.getTokenname();

			List<MultiSignAddress> multiSignAddresses = this
					.queryDomainnameTokenMultiSignAddresses(token.getBlockHash(), store);

			return (PermissionedAddressesResponse) PermissionedAddressesResponse.create(domainName, false,
					multiSignAddresses);
		}
	}

	protected String fromAddress(final Transaction tx, boolean isCoinBase) {
		String fromAddress = "";
		if (!isCoinBase) {
			for (TransactionInput t : tx.getInputs()) {
				try {
					if (t.getConnectedOutput().getScriptPubKey().isSentToAddress()) {
						fromAddress = t.getFromAddress().toBase58();
					} else {
						fromAddress = new Address(networkParameters,
								Utils.sha256hash160(t.getConnectedOutput().getScriptPubKey().getPubKey())).toBase58();

					}

					if (!fromAddress.isEmpty())
						return fromAddress;
				} catch (Exception e) {
					// No address found.
				}
			}
			return fromAddress;
		}
		return fromAddress;
	}

	//
	public Transaction generateVirtualMiningRewardTX(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {
		if (enableOrderMatchExecutionChain(block)) {
			return generateVirtualMiningRewardTXFeeBased(block, blockStore);
		} else {
			return generateVirtualMiningRewardTX1(block, blockStore);
		}
	}

	public Transaction generateVirtualMiningRewardTXFeeBased(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {
		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Set<Sha256Hash> candidateBlocks = rewardInfo.getBlocks();

		// Build transaction outputs sorted by addresses
		Transaction tx = new Transaction(networkParameters);

		// Reward the consensus block with the static reward
		tx.addOutput(Coin.FEE_DEFAULT.times(countRewardTXFeeBased(candidateBlocks, blockStore)),
				new Address(networkParameters, block.getMinerAddress()));
		tx.setMemo(new MemoInfo("Reward"));
		// The input does not really need to be a valid signature, as long
		// as it has the right general form and is slightly different for
		// different tx
		TransactionInput input = new TransactionInput(networkParameters, tx, Script
				.createInputScript(block.getPrevBlockHash().getBytes(), block.getPrevBranchBlockHash().getBytes()));
		tx.addInput(input);
		return tx;
	}

	public long countRewardTXFeeBased(Set<Sha256Hash> candidateBlocks, BlockStoreInterface store)
			throws BlockStoreException

	{
		long re = 0;
		for (Sha256Hash s : candidateBlocks) {
			Block t = getBlock(s, store);
			if (t.getBlockType() == Block.Type.BLOCKTYPE_CONTRACT_EXECUTE) {
				re = re + new ContractExecutionResult().parseChecked(t.getTransactions().get(0).getData())
						.getReferencedBlocks().size();
			} else if (t.getBlockType() == Block.Type.BLOCKTYPE_ORDER_EXECUTE) {

				re = re + new OrderExecutionResult().parseChecked(t.getTransactions().get(0).getData())
						.getReferencedBlocks().size();
			} else {
				re = re + 1;
			}
		}
		return re;
	}

	/**
	 * Deterministically creates a mining reward transaction based on the previous
	 * blocks and previous reward transaction. DOES NOT CHECK FOR SOLIDITY. You have
	 * to ensure that the approved blocks result in an eligible reward block.
	 * 
	 * @return mining reward transaction
	 */
	public Transaction generateVirtualMiningRewardTX1(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Set<Sha256Hash> candidateBlocks = rewardInfo.getBlocks();

		// Count how many blocks from miners in the reward interval are approved
		// and build rewards
		Queue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		for (Sha256Hash bHash : candidateBlocks) {
			blockQueue.add(getBlockWrap(bHash, blockStore));
		}

		// Initialize
		Set<BlockWrap> currentHeightBlocks = new HashSet<>();
		Map<BlockWrap, Set<Sha256Hash>> snapshotWeights = new HashMap<>();
		Map<Address, Long> finalRewardCount = new HashMap<>();
		BlockWrap currentBlock, approvedBlock;
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

		// Build transaction outputs sorted by addresses
		Transaction tx = new Transaction(networkParameters);

		// Reward the consensus block with the static reward
		tx.addOutput(Coin.SATOSHI.times(NetworkParameters.REWARD_AMOUNT_BLOCK_REWARD), consensusBlockMiner);

		// Reward twice: once the consensus block, once the normal block maker
		// of good quantiles
		for (Entry<Address, Long> entry : finalRewardCount.entrySet().stream().sorted(Entry.comparingByKey())
				.toList()) {
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
				.sorted(Comparator.comparingLong(b -> snapshotWeights.get(b).size()).reversed()).toList()) {
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

	protected void solidifyReward(Block block, BlockStoreInterface blockStore) throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
		long currChainLength = blockStore.getRewardChainLength(prevRewardHash) + 1;
		long difficulty = rewardInfo.getDifficultyTargetReward();

		blockStore.insertReward(block.getHash(), prevRewardHash, difficulty, currChainLength);

	}

	protected void insertVirtualUTXOs(Block block, Transaction virtualTx, BlockStoreInterface blockStore) {
		try {
			ArrayList<Transaction> txs = new ArrayList<>();
			txs.add(virtualTx);
			connectUTXOs(block, txs, blockStore);
		} catch (BlockStoreException e) {
			// Expected after reorgs
			// logger.warn("Probably reinserting reward: ", e);
		}
	}

	public void solidifyBlock(Block block, SolidityState solidityState, boolean setMilestoneSuccess,
			BlockStoreInterface blockStore) throws BlockStoreException {
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

	public void solidifyBlocks(RewardInfo currRewardInfo, BlockStoreInterface store) throws BlockStoreException {
		Comparator<Block> comparator = Comparator.comparingLong(Block::getHeight).thenComparing(Block::getHash);
		TreeSet<Block> referencedBlocks = new TreeSet<>(comparator);
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			Block block = getBlock(hash, store);
			if (block != null)
				referencedBlocks.add(block);
		}
		for (Block block : referencedBlocks) {
			solidifyWaiting(block, store);
		}
	}

	public void solidifyWaiting(Block block, BlockStoreInterface store) throws BlockStoreException {

		SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,jsonmapper)
				.checkSolidity(block, false, store, false);
		// allow here unsolid block, as sync may do only the referenced blocks
		if (SolidityState.State.MissingPredecessor.equals(solidityState.getState())) {
			solidifyBlock(block, SolidityState.getSuccessState(), false, store);
		} else {
			solidifyBlock(block, solidityState, false, store);
		}
	}

	public boolean getUTXOConfirmed(TransactionOutPoint txout, BlockStoreInterface store) throws BlockStoreException {
		return store.getOutputConfirmation(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
	}

	public BlockEvaluation getUTXOSpender(TransactionOutPoint txout, BlockStoreInterface store)
			throws BlockStoreException {
		return store.getTransactionOutputSpender(txout.getBlockHash(), txout.getTxHash(), txout.getIndex());
	}

	public long getCurrentMaxHeight(TXReward maxConfirmedReward, BlockStoreInterface store) throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return NetworkParameters.FORWARD_BLOCK_HORIZON;
		return store.get(maxConfirmedReward.getBlockHash()).getHeight() + NetworkParameters.FORWARD_BLOCK_HORIZON;
	}

	public long getCurrentCutoffHeight(TXReward maxConfirmedReward, BlockStoreInterface store)
			throws BlockStoreException {
		// TXReward maxConfirmedReward = store.getMaxConfirmedReward();
		if (maxConfirmedReward == null)
			return 0;
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.MILESTONE_CUTOFF);
		TXReward confirmedAtHeightReward = store.getRewardConfirmedAtHeight(chainlength);
		return store.get(confirmedAtHeightReward.getBlockHash()).getHeight();
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

	public TokensumsMap checkToken(BlockStoreInterface store) throws BlockStoreException, UTXOProviderException {

		TokensumsMap tokensumset = new TokensumsMap();

		Map<String, BigInteger> tokensumsInitial = tokensumInitial(store);
		Set<String> tokenids = tokensumsInitial.keySet();
		for (String tokenid : tokenids) {
			Tokensums tokensums = new Tokensums();
			tokensums.setTokenid(tokenid);
			tokensums.setUtxos(getOutputs(tokenid, store));
			tokensums.setOrders(orders(tokenid, store));
			tokensums.setInitial(tokensumsInitial.get(tokenid));
			tokensums.setContracts(contracts(tokenid, store));
			tokensums.calculate();
			tokensumset.getTokensumsMap().put(tokenid, tokensums);
		}
		return tokensumset;
	}

	private List<OrderRecord> orders(String tokenid, BlockStoreInterface store) throws BlockStoreException {
		return store.getAllOpenOrdersSorted(null, tokenid);

	}

	private List<ContractEventRecord> contracts(String tokenid, BlockStoreInterface store) throws BlockStoreException {
		return store.getContractEventRecordOpen(tokenid);

	}

	public Map<String, BigInteger> tokensumInitial(BlockStoreInterface store) throws BlockStoreException {

		return store.getTokenAmountMap();
	}

	private List<UTXO> getOutputs(String tokenid, BlockStoreInterface store) throws UTXOProviderException {
		// Must be sorted with the key of
		return store.getOpenAllOutputs(tokenid);
	}

	/*
	 * contract execution forms chained, it will takes all the chained contract
	 * execution until to last execution in rewards
	 */
	public void collectReferencedChainedExecutions(Set<BlockWrap> blocks, Block.Type blocktype,
			Set<BlockWrap> collected, Set<BlockWrap> tobeUnconfirms, BlockStoreInterface store)
			throws BlockStoreException {
		BlockWrap lastContractExecution = null;

		// get the last EXECUTE
		for (BlockWrap block : blocks) {
			if (blocktype.equals(block.getBlock().getBlockType())) {
				if (lastContractExecution == null) {
					lastContractExecution = block;
				} else {
					if (lastContractExecution.getBlock().getHeight() < block.getBlock().getHeight()) {
						lastContractExecution = block;
					}
				}
			} else {
				collected.add(block);
			}
		}
		// backward to get all chained EXECUTE until milestone
		if (lastContractExecution != null) {
			collected.addAll(collectReferencedChaineExecutions(lastContractExecution, store));
			collectFollowChaineExecutions(lastContractExecution, tobeUnconfirms, store);
		}

	}

	private void collectFollowChaineExecutions(BlockWrap startExecution, Set<BlockWrap> blocks,
			BlockStoreInterface store) throws BlockStoreException {

		PriorityQueue<BlockWrap> blockQueue = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		Set<Sha256Hash> blockQueueSet = new HashSet<>();
		blockQueue.add(startExecution);
		blockQueueSet.add(startExecution.getBlockHash());

		while (!blockQueue.isEmpty()) {
			BlockWrap block = blockQueue.poll();
			blockQueueSet.remove(block.getBlockHash());

			// no milestone blocks can be here
			if (block.getBlockEvaluation().getMilestone() > 0) {
				throw new VerificationException("no milestone block can be here" + block);
			}
			// Nothing added if already in set
			if (checkExists(blocks, block))
				continue;
			// not the startExecution
			if (!startExecution.getBlockHash().equals(block.getBlockHash())) {
				blocks.add(block);
			}
			if (Type.BLOCKTYPE_CONTRACT_EXECUTE.equals(block.getBlock().getBlockType())) {
				List<Contractresult> allRequiredBlockHashes = store
						.getContractresultWithPrev(block.getBlock().getHash());
				for (Contractresult req : allRequiredBlockHashes) {
					if (!blockQueueSet.contains(req.getBlockHash())) {
						BlockWrap pred = getBlockWrap(req.getBlockHash(), store);
						blockQueueSet.add(req.getBlockHash());
						blockQueue.add(pred);
					}

				}
			}
			if (Type.BLOCKTYPE_ORDER_EXECUTE.equals(block.getBlock().getBlockType())) {
				List<Orderresult> allRequiredBlockHashes = store.getOrderresultWithPrev(block.getBlock().getHash());
				for (Orderresult req : allRequiredBlockHashes) {
					if (!blockQueueSet.contains(req.getBlockHash())) {
						BlockWrap pred = getBlockWrap(req.getBlockHash(), store);
						blockQueueSet.add(req.getBlockHash());
						blockQueue.add(pred);
					}

				}
			}
		}

	}

	public void collectExecutionChained(BlockStoreInterface store, Set<BlockWrap> blocks, Set<BlockWrap> collected,
			Set<BlockWrap> tobeUnconfirms) throws BlockStoreException {
		collectReferencedChainedExecutions(blocks, Block.Type.BLOCKTYPE_CONTRACT_EXECUTE, collected, tobeUnconfirms,
				store);
		collectReferencedChainedExecutions(blocks, Block.Type.BLOCKTYPE_ORDER_EXECUTE, collected, tobeUnconfirms,
				store);

	}

	public boolean checkExists(Set<BlockWrap> allApproved, BlockWrap newBlock) {
		for (BlockWrap b : allApproved) {
			if (b.getBlockHash().equals(newBlock.getBlockHash())) {
				return true;
			}
		}
		return false;
	}
	/*
	 * return all Execution Blocks not in milestone and chained from headExecution
	 * to the Execution in milestone or begin.
	 */
	public Set<BlockWrap> collectReferencedChaineExecutions(BlockWrap headExecution, BlockStoreInterface store)
			throws BlockStoreException {

		Set<BlockWrap> re = new HashSet<>();
		boolean brokenChained = true;
		BlockWrap startingBlock = headExecution;
		while (startingBlock != null) {
			re.add(startingBlock);
			startingBlock = getBlockWrap(getExecutionPrev(startingBlock.getBlock()), store);

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
			return new HashSet<>();
		} else {
			return re;
		}
	}


}
