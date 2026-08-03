/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.base;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.Supplier;

import jakarta.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderRecord;
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
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserData;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.script.Script;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.handler.BlockTypeHandlerRegistry;
import net.bigtangle.store.BlockStoreInterface;

public abstract class ServiceBase {
	protected ServerConfiguration serverConfiguration;
	protected NetworkParameters networkParameters;
	protected CacheBlockService cacheBlockService;
	protected ObjectMapper jsonmapper;
	/**
	 * Per-{@link net.bigtangle.core.BlockType} strategy handlers. Layer modules
	 * register handlers here so the per-type validation/confirmation arms can be
	 * extracted out of the base class. Null until a subclass calls
	 * {@link #handlerRegistry()}. See LAYERING-PLAN.md.
	 */
	private BlockTypeHandlerRegistry blockTypeHandlerRegistry;

	/**
	 * Global (static) handler registrations. Layer modules call
	 * {@link #registerGlobalHandler(BlockType, Supplier)} at startup. Every new
	 * {@code ServiceBase} instance copies these into its per-instance registry
	 * during construction, so no instance is ever created without the handlers
	 * its layer needs.
	 */
	private static final List<GlobalRegistration> globalRegistrations = Collections.synchronizedList(new ArrayList<>());

	private static class GlobalRegistration {
		final BlockType type;
		final Supplier<net.bigtangle.server.service.base.handler.BlockTypeHandler> supplier;

		GlobalRegistration(BlockType type, Supplier<net.bigtangle.server.service.base.handler.BlockTypeHandler> supplier) {
			this.type = type;
			this.supplier = supplier;
		}
	}

	/**
	 * Register a handler factory globally. Every {@code ServiceBase} (and its
	 * subclasses) created after this call will include the handler.
	 * Thread-safe; intended to be called from static initializers or
	 * {@code @PostConstruct} methods.
	 */
	public static void registerGlobalHandler(BlockType type, Supplier<net.bigtangle.server.service.base.handler.BlockTypeHandler> supplier) {
		globalRegistrations.add(new GlobalRegistration(type, supplier));
	}

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
		this.jsonmapper = jsonmapper;
		// Copy global registrations into this instance's registry
		for (GlobalRegistration reg : globalRegistrations) {
			handlerRegistry().register(reg.type, reg.supplier.get());
		}
	}

	/**
	 * Lazily creates and returns the {@link BlockTypeHandlerRegistry} for this
	 * service instance. Layer modules register their handlers here; the base
	 * validation/confirmation switches consult it via
	 * {@link #handlerFor(net.bigtangle.core.BlockType)} and delegate when a
	 * handler is present.
	 */
	public BlockTypeHandlerRegistry handlerRegistry() {
		if (blockTypeHandlerRegistry == null) {
			blockTypeHandlerRegistry = new BlockTypeHandlerRegistry();
		}
		return blockTypeHandlerRegistry;
	}

	/** Convenience lookup used by the dispatch switches. */
	public java.util.Optional<net.bigtangle.server.service.base.handler.BlockTypeHandler> handlerFor(
			net.bigtangle.core.BlockType type) {
		return blockTypeHandlerRegistry == null
				? java.util.Optional.empty()
				: blockTypeHandlerRegistry.get(type);
	}

	/**
	 * get domainname token multi sign address
	 *
	 */
	public List<MultiSignAddress> queryDomainnameTokenMultiSignAddresses(Sha256Hash domainNameBlockHash,
			BlockStoreInterface store) throws BlockStoreException {
		if (domainNameBlockHash.equals(UtilGeneseBlock.createGenesis(networkParameters ).getHash())) {
			List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
			for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
				PQKey ecKey = permissionDomainname.getOutKey();
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
			result.add(new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.getBlockWrap(pred, store));
		return result;
	}

	public Set<Sha256Hash> getPredecessors(Block block) {
		Set<Sha256Hash> predecessors = new HashSet<>();
		predecessors.add(block.getPrevBlockHash());
		predecessors.add(block.getPrevBranchBlockHash());
		return predecessors;
	}

	public Set<Sha256Hash> getPredecessorsAndReferenced(Block block) {
		Set<Sha256Hash> all = getPredecessors(block);
		return all;
	}

	/**
	 * Returns all required blocks for this block, all inputs block, previous block
	 * and referenced blocks
	 */
	public Set<Sha256Hash> getAllRequiredBlockHashes(Block block) {
		return getAllRequiredBlockHashes(block, true);
	}

	public Set<Sha256Hash> getAllRequiredBlockHashes(Block block, boolean withReferenced) {
		Set<Sha256Hash> allrequireds = new HashSet<>();
		// All used transaction outputs
		final List<Transaction> transactions = block.getTransactions();

		for (final Transaction tx : transactions) {
			if (!tx.isCoinBase()) {
				for (int index = 0; index < tx.getInputs().size(); index++) {
					TransactionInput in = tx.getInputs().get(index);
					allrequireds.add(in.getOutpoint().getBlockHash());
				}
			}
		}
		switch (block.getBlockType()) {
		case BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE, BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER,
				BLOCKTYPE_USERDATA, BLOCKTYPE_CONTRACT_EVENT, BLOCKTYPE_ORDER_OPEN, BLOCKTYPE_ORDER_CANCEL,
				BLOCKTYPE_CONTRACTEVENT_CANCEL, BLOCKTYPE_STAKE, BLOCKTYPE_SLASHING, BLOCKTYPE_EVM_DEPLOY,
				BLOCKTYPE_EVM_CALL:
			break;
		case BLOCKTYPE_BEACON:
			RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());
			allrequireds.add(rewardInfo.getPrevRewardHash());
			if (withReferenced)
				allrequireds.addAll(rewardInfo.getBlocks());
			break;
		case BLOCKTYPE_TOKEN_CREATION:
			TokenInfo currentToken = new TokenInfo().parseChecked(transactions.get(0).getData());
			allrequireds.add(Sha256Hash.wrap(currentToken.getToken().getDomainNameBlockHash()));
			if (currentToken.getToken().getPrevblockhash() != null
			        && !Sha256Hash.ZERO_HASH.equals(currentToken.getToken().getPrevblockhash()))
				allrequireds.add(currentToken.getToken().getPrevblockhash());
			break;
		default:
			throw new RuntimeException("No Implementation");
		}

		return allrequireds;
	}

	public Set<BlockWrap> getReferrencedBlockWrap(Block block, BlockStoreInterface store) throws BlockStoreException {
		Set<BlockWrap> wraps = new HashSet<>();
		for (Sha256Hash hash : getReferencedBlockHashes(block)) {
			wraps.add(getBlockWrap(hash, store));
		}
		return wraps;
	}

	/*
	 * return the chained referenced blocks,
	 */
	public Set<Sha256Hash> getReferencedBlockHashes(Block block) {
		Set<Sha256Hash> allRefs = new HashSet<>();
		final List<Transaction> transactions = block.getTransactions();
		switch (block.getBlockType()) {
		case BLOCKTYPE_BEACON:
			RewardInfo rewardInfo = new RewardInfo().parseChecked(transactions.get(0).getData());
			allRefs.addAll(rewardInfo.getBlocks());
			break;
		default:
			break;
		}

		return allRefs;
	}

	public Block getBlock(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {

		byte[] re = cacheBlockService.getBlock(blockhash, store);
		if (re == null)
			return null;
		try {
			return networkParameters.getDefaultSerializer().makeZippedBlockStream(new ByteArrayInputStream(re));
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
				v = jsonmapper.readValue(be, BlockEvaluation.class);
			if (v == null)
				v = BlockEvaluation.buildInitial(block);
			byte[] blockMCMC = cacheBlockService.getBlockMCMC(blockhash, store);

			return new BlockWrap(block, v, jsonmapper.readValue(blockMCMC, BlockMCMC.class), networkParameters);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public BlockWrap initBlockWrap(Block block ) throws BlockStoreException {

		return new BlockWrap(block, BlockEvaluation.buildInitial(block), BlockMCMC.defaultBlockMCMC(block.getHash()),
				networkParameters);

	}

	public RewardInfo getRewardInfo(Block block) {
		return new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
	}

	public Sha256Hash getExecutionPrev(Block block) {
		return switch (block.getBlockType()) {
		case BLOCKTYPE_BEACON ->
			new RewardInfo().parseChecked(block.getTransactions().get(0).getData()).getPrevRewardHash();
		default -> throw new RuntimeException("Wrong block.getBlockType()");
		};
	}

	public List<Sha256Hash> getEntryPointCandidates(long currChainLength, BlockStoreInterface store)
			throws BlockStoreException {
		long minChainLength = Math.max(0, currChainLength - NetworkParameters.CHAINLENGTH_CUTOFF);
		return getBlocksInChainlengthInterval(minChainLength, currChainLength, store);
	}

	public List<Sha256Hash> getBlocksInChainlengthInterval(long minChainLength, long currChainLength,
			BlockStoreInterface store) throws BlockStoreException {
		return store.getBlocksInChainlengthInterval(minChainLength, currChainLength);

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
		for (int i = 0; i < NetworkParameters.CHAINLENGTH_CUTOFF; i++) {
			Block currRewardBlock;
			currRewardBlock = getBlock(currPrevRewardHash, store);
			RewardInfo currRewardInfo = new RewardInfo()
					.parseChecked(currRewardBlock.getTransactions().get(0).getData());
			if (currPrevRewardHash.equals(UtilGeneseBlock.createGenesis(networkParameters ).getHash()))
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
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
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
		if (domainNameBlockHash.equals(UtilGeneseBlock.createGenesis(networkParameters ).getHashAsString())) {
			List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
			for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
				PQKey ecKey = permissionDomainname.getOutKey();
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
					TransactionOutput connectedOutput = t.getConnectedOutput();
					if (connectedOutput == null)
						return "";
					if (connectedOutput.getScriptPubKey().isSentToAddress()) {
						fromAddress = t.getFromAddress().toBase58();
					} else {
						fromAddress =   Address.fromHash160(networkParameters,
								Utils.sha256hash160(connectedOutput.getScriptPubKey().getPubKey())).toBase58();

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

	// generateVirtualMiningRewardTX removed — epoch-based rewards via EpochRewardService

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

			if (!finalRewardCount.containsKey(null))
				finalRewardCount.put(Address.fromBase58(networkParameters, "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG"), 1L);
			else
				finalRewardCount.put(Address.fromBase58(networkParameters, "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG"), finalRewardCount.get(Address.fromBase58(networkParameters, "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG")) + 1);
			rewarded++;
		}
		return totalRewardCount;
	}

	protected void solidifyReward(Block block, BlockStoreInterface blockStore) throws BlockStoreException {

		RewardInfo rewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
		Sha256Hash prevRewardHash = rewardInfo.getPrevRewardHash();
		long currChainLength = blockStore.getRewardChainLength(prevRewardHash) + 1;

		blockStore.insertReward(block.getHash(), prevRewardHash, currChainLength);

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

	public void solidifyBlock(Block block, SolidityState solidityState, boolean setChainlengthSuccess,
			BlockStoreInterface blockStore) throws BlockStoreException {
		switch (solidityState.getState()) {
		case MissingCalculation:
			blockStore.updateBlockEvaluationSolid(block.getHash(), 1);
			// Connect type-specific data (orders, tokens) even when
			// calculation metadata is missing. The block content is valid —
			// only difficulty/PoW data is absent.
			connectTypeSpecificUTXOs(block, blockStore);
			if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
				solidifyReward(block, blockStore);
				return;
			}
			break;
		case MissingPredecessor:
			if (block.getBlockType() == BlockType.BLOCKTYPE_INITIAL
					&& getBlockWrap(block.getHash(), blockStore).getBlockEvaluation().getSolid() > 0) {
				throw new RuntimeException("Should not happen");
			}
			blockStore.updateBlockEvaluationSolid(block.getHash(), 0);
			break;
		case Success:
			// If already set, nothing to do here...
			if (getBlockWrap(block.getHash(), blockStore).getBlockEvaluation().getSolid() == 2)
				return;
			connectUTXOs(block, blockStore);
			connectTypeSpecificUTXOs(block, blockStore);
			calculateBlockOrderMatchingResult(block, blockStore);

			if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON && !setChainlengthSuccess) {
				// If we don't want to set the chainlength success, initialize as
				// missing calc
				blockStore.updateBlockEvaluationSolid(block.getHash(), 1);
			} else {
				// normal update
				blockStore.updateBlockEvaluationSolid(block.getHash(), 2);
			}
			if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
				solidifyReward(block, blockStore);
				return;
			}

			break;
		case Invalid:
			if (block != null)
				blockStore.updateBlockEvaluationSolid(block.getHash(), -1);
			break;
		}
		if (block != null)
			cacheBlockService.evictBlockEvaluation(block.getHash());
	}

	/** Best-effort: record CONFIRMED status (chain history) at a reward chainlength. */
	protected void markConfirmedStatus(Block block, long chainlength, BlockStoreInterface blockStore) {
		try {
			net.bigtangle.server.data.TransactionStatusRecord.markBlock(blockStore, block,
					net.bigtangle.server.data.TransactionStatus.CONFIRMED, chainlength, networkParameters);
		} catch (Exception e) {
			// status tracking is best-effort
		}
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
		BlockEvaluation eval = store.getBlockEvaluationsByhashs(block.getHash());
		if (eval != null && eval.getSolid() == 2) {
			return;
		}
		SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper).checkSolidity(block, false, store, false);
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
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.CHAINLENGTH_CUTOFF);
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

	public void checkExecutionChained(BlockStoreInterface store, Set<BlockWrap> blocks) throws BlockStoreException {
	}

	public boolean checkExists(Set<BlockWrap> allApproved, BlockWrap newBlock) {
		for (BlockWrap b : allApproved) {
			if (b.getBlockHash().equals(newBlock.getBlockHash())) {
				return true;
			}
		}
		return false;
	}

}
