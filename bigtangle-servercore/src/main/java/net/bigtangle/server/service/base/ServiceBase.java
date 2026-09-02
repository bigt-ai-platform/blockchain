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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ServiceBase.class);
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
				BLOCKTYPE_CONTRACTEVENT_CANCEL, BLOCKTYPE_STAKE, BLOCKTYPE_SLASHING, BLOCKTYPE_EXIT, BLOCKTYPE_EVM_DEPLOY,
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

		// Parsed-instance cache: batch blocks are multi-MB zipped payloads;
		// beacon-connect re-reads the same hashes several times per cycle and
		// re-decompression dominated it. Shared/read-only by convention (same
		// contract as ServiceBaseConfirmation.cacheParsedBlock).
		Block cached = ServiceBaseConfirmation.getCachedParsedBlock(blockhash);
		if (cached != null) {
			return cached;
		}

		byte[] re = cacheBlockService.getBlock(blockhash, store);
		if (re == null)
			return null;
		try {
			Block block = networkParameters.getDefaultSerializer()
					.makeZippedBlockStream(new ByteArrayInputStream(re));
			ServiceBaseConfirmation.cacheParsedBlock(block);
			return block;
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

			return new BlockWrap(block, v, networkParameters);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	public BlockWrap initBlockWrap(Block block ) throws BlockStoreException {

		return new BlockWrap(block, BlockEvaluation.buildInitial(block), networkParameters);

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

	/**
	 * The reward-chain cutoff height below which a referenced block would already
	 * have been rewarded: walks the confirmed reward chain back from
	 * {@code prevRewardHash} to the genesis floor. Returns {@code -1} when a
	 * predecessor block is not (yet) in the store — the cutoff cannot be
	 * determined, and callers MUST defer rather than reject (a lagging node must
	 * never permanently reject a valid beacon; previously a missing predecessor
	 * NPE'd here and hard-failed the whole confirmation pipeline). Genesis is
	 * checked BEFORE parsing, so the genesis coinbase is never misread as a
	 * RewardInfo (which yielded a garbage prevRewardHash and a downstream NPE).
	 */
	public long getRewardCutoffHeight(Sha256Hash prevRewardHash, BlockStoreInterface store) throws BlockStoreException {
		// The genesis floor is taken from the STORE (the reward block at
		// chainlength 0), not from UtilGeneseBlock.createGenesis(networkParameters):
		// the latter reconstructs the genesis from process-local config (the
		// genesis CSV), which can DIFFER between the API server and a shared-DB
		// process — a mismatched genesis hash then makes the walk NPE on
		// the coinbase parse. The stored genesis is identical on every process
		// sharing the DB.
		TXReward genesisReward = store.getRewardConfirmedAtHeight(0);
		Sha256Hash genesisHash = genesisReward != null ? genesisReward.getBlockHash()
				: UtilGeneseBlock.createGenesis(networkParameters).getHash();
		Sha256Hash currPrevRewardHash = prevRewardHash;
		for (int i = 0; i < NetworkParameters.CHAINLENGTH_CUTOFF; i++) {
			if (currPrevRewardHash.equals(genesisHash)) {
				return 0;
			}
			Block currRewardBlock = getBlock(currPrevRewardHash, store);
			if (currRewardBlock == null) {
				// Predecessor not synced yet — cutoff undeterminable. Defer.
				return -1;
			}
			RewardInfo currRewardInfo = new RewardInfo()
					.parseChecked(currRewardBlock.getTransactions().get(0).getData());
			currPrevRewardHash = currRewardInfo.getPrevRewardHash();
		}
		Block tail = getBlock(currPrevRewardHash, store);
		return tail != null ? tail.getHeight() : -1;
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

		// RAISE-TO-EMBEDDED REPAIR. The stored reward-chainlength for this
		// beacon is derived from its prevRewardHash's row (+1). Under a
		// concurrent adversarial race a beacon can be solidified out of order,
		// before its prevRewardHash row exists: getRewardChainLength(prev)
		// returns -1 and this beacon is stamped chainlength 0 (then 1, 2, ...
		// down the winning chain). Because txreward inserts are ON CONFLICT DO
		// NOTHING, the later correct solidify is silently dropped and the
		// winning chain stays collapsed at cl 0..N forever — the observed
		// second wedge: handleNewBestChain reconnect reads the corrupt low rows
		// and can never exceed the frozen head. The block's OWN RewardInfo
		// (verified proposer signature + solidity) authoritatively claims the
		// true chainlength, so raise the stored row to it when it is higher.
		long embeddedChainLength = rewardInfo.getChainlength();
		if (embeddedChainLength > currChainLength) {
			try {
				long stored = blockStore.getRewardChainLength(block.getHash());
				if (stored < embeddedChainLength) {
					if (stored < 0) {
						blockStore.insertReward(block.getHash(), prevRewardHash, embeddedChainLength);
					} else {
						blockStore.updateRewardChainlength(block.getHash(), embeddedChainLength);
					}
					logger.info("solidifyReward: repaired collapsed reward row for {}: stored cl={} -> embedded cl={}",
							block.getHash(), stored, embeddedChainLength);
				}
			} catch (Exception e) {
				logger.debug("solidifyReward raise-to-embedded skipped for {}: {}", block.getHash(), e.getMessage());
			}
			return;
		}

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
			// If already set, nothing to do here... (read only the small
			// evaluation row — avoids re-reading + decompressing the whole block)
			BlockEvaluation storedEval = blockStore.getBlockEvaluationsByhashs(block.getHash());
			if (storedEval != null && storedEval.getSolid() == 2)
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

	/**
	 * Bounded-work parallel variant. pos.parallelSolidify (default true) +
	 * a non-null {@code storeSupplier} enable it; each candidate block is
	 * EVALUATED read-only on its own short-lived connection, then decisions
	 * are applied sequentially on the caller's transactional store in the
	 * original height order. This keeps confirm-connect cycles fixed-cost at
	 * backlog depth instead of O(backlog) sequential verification.
	 */
	public void solidifyBlocks(RewardInfo currRewardInfo, BlockStoreInterface store) throws BlockStoreException {
		solidifyBlocks(currRewardInfo, store, null);
	}

	public void solidifyBlocks(RewardInfo currRewardInfo, BlockStoreInterface store,
			java.util.function.Supplier<BlockStoreInterface> storeSupplier) throws BlockStoreException {
		Comparator<Block> comparator = Comparator.comparingLong(Block::getHeight).thenComparing(Block::getHash);
		TreeSet<Block> referencedBlocks = new TreeSet<>(comparator);
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			Block block = getBlock(hash, store);
			if (block != null)
				referencedBlocks.add(block);
		}
		boolean parallel = Boolean.parseBoolean(System.getProperty("pos.parallelSolidify", "true"))
				&& storeSupplier != null && referencedBlocks.size() > 1
				&& !Boolean.parseBoolean(System.getProperty("pos.solidifyParallelDisable", "false"));

		if (!parallel) {
			for (Block block : referencedBlocks) {
				solidifyWaiting(block, store);
			}
			return;
		}

		int threads = Math.min(Integer.getInteger("pos.solidifyParallelism",
				Runtime.getRuntime().availableProcessors() / 2), referencedBlocks.size());
		ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
		try {
			Map<Block, SolidityState> decided = new HashMap<>();
			List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
			for (Block b0 : referencedBlocks) {
				futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
					BlockStoreInterface s0 = null;
					try {
						s0 = storeSupplier.get();
						SolidityState st = evaluateSolidityForParallel(b0, s0);
						synchronized (decided) {
							decided.put(b0, st);
						}
					} catch (Exception ex) {
						synchronized (decided) {
							decided.put(b0, null); // marker: fall back sequentially
						}
					} finally {
						if (s0 != null) try { s0.close(); } catch (Exception ignore) {}
					}
				}, pool));
			}
			java.util.concurrent.CompletableFuture.allOf(
					futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

			for (Block b : referencedBlocks) {
				SolidityState st = decided.getOrDefault(b, null);
				if (st == null) { solidifyWaiting(b, store); continue; }
				if (SolidityState.State.MissingPredecessor.equals(st.getState())) {
					// PARITY GUARD: parallel readers cannot see writes still
					// pending in this connect's open batch transaction —
					// re-resolve on the MAIN store exactly as legacy did.
					Block prev = store.get(b.getPrevBlockHash());
					Block prevBranch = store.get(b.getPrevBranchBlockHash());
					if (prev != null && prevBranch != null && allInputsExist(b, store)) {
						st = SolidityState.getSuccessState();
					}
				}
				solidifyBlock(b, st, false, store);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** READ-ONLY evaluation for the parallel phase: returns the final state
	 *  that legacy solidifyWaiting would hand to solidifyBlock. */
	private SolidityState evaluateSolidityForParallel(Block block, BlockStoreInterface s)
			throws BlockStoreException {
		BlockEvaluation eval = s.getBlockEvaluationsByhashs(block.getHash());
		if (eval != null && eval.getSolid() >= 0
				&& cacheBlockService.isTxValidated(block.getHash())
				&& s.get(block.getPrevBlockHash()) != null
				&& s.get(block.getPrevBranchBlockHash()) != null) {
			return SolidityState.getSuccessState();
		}
		SolidityState st = new ServiceBaseCheck(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper).checkSolidity(block, false, s, false);
		if (SolidityState.State.MissingPredecessor.equals(st.getState())
				&& s.get(block.getPrevBlockHash()) != null
				&& s.get(block.getPrevBranchBlockHash()) != null
				&& allInputsExist(block, s)) {
			return SolidityState.getSuccessState();
		}
		return st;
	}

	public void solidifyBlocksLegacy(RewardInfo currRewardInfo, BlockStoreInterface store) throws BlockStoreException {
		for (Block block : referencedSorted(currRewardInfo, store)) {
			solidifyWaiting(block, store);
		}
	}

	private TreeSet<Block> referencedSorted(RewardInfo currRewardInfo, BlockStoreInterface store) throws BlockStoreException {
		Comparator<Block> comparator = Comparator.comparingLong(Block::getHeight).thenComparing(Block::getHash);
		TreeSet<Block> referencedBlocks = new TreeSet<>(comparator);
		for (Sha256Hash hash : currRewardInfo.getBlocks()) {
			Block block = getBlock(hash, store);
			if (block != null)
				referencedBlocks.add(block);
		}
		return referencedBlocks;
	}

	public void solidifyWaiting(Block block, BlockStoreInterface store) throws BlockStoreException {
		BlockEvaluation eval = store.getBlockEvaluationsByhashs(block.getHash());
		if (eval != null && eval.getSolid() == 2) {
			return;
		}
		// Every tx was already fully verified on this node (mempool ingest for
		// local batch blocks, full solidity check at peer-block ingest): skip
		// the redundant O(txs) re-verification and go straight to the
		// solidify side effects (UTXO connect, type-specific handlers).
		if (eval != null && eval.getSolid() >= 0
				&& cacheBlockService.isTxValidated(block.getHash())
				&& store.get(block.getPrevBlockHash()) != null
				&& store.get(block.getPrevBranchBlockHash()) != null) {
			solidifyBlock(block, SolidityState.getSuccessState(), false, store);
			return;
		}
		SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper).checkSolidity(block, false, store, false);
		if (SolidityState.State.MissingPredecessor.equals(solidityState.getState())) {
			// R2: only force-solid when the missing dependency is a PREDECESSOR
			// BLOCK that has since arrived. A MissingPredecessor caused by a
			// NON-EXISTENT input UTXO must NOT be force-solided: that would
			// bypass the type-specific handler and solidify a block spending
			// phantom outputs (a consensus hole). When the predecessor / UTXO
			// source has arrived, the regular checkSolidity pass below would have
			// returned Success on its own.
			Block prev = store.get(block.getPrevBlockHash());
			Block prevBranch = store.get(block.getPrevBranchBlockHash());
			if (prev != null && prevBranch != null && allInputsExist(block, store)) {
				solidifyBlock(block, SolidityState.getSuccessState(), false, store);
			} else {
				solidifyBlock(block, solidityState, false, store);
			}
		} else {
			solidifyBlock(block, solidityState, false, store);
		}
	}

	/**
	 * True when every non-coinbase input of every transaction in the block
	 * resolves to a stored UTXO.
	 */
	private boolean allInputsExist(Block block, BlockStoreInterface store) throws BlockStoreException {
		for (Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase()) {
				continue;
			}
			for (TransactionInput in : tx.getInputs()) {
				if (store.getTransactionOutput(in.getOutpoint().getBlockHash(),
						in.getOutpoint().getTxHash(), in.getOutpoint().getIndex()) == null) {
					return false;
				}
			}
		}
		return true;
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
		// Order tables exist on every layer (the reward pipeline reads them);
		// always query.
		return store.getAllOpenOrdersSorted(null, tokenid);

	}

	private List<ContractEventRecord> contracts(String tokenid, BlockStoreInterface store) throws BlockStoreException {
		// A layer-minimal store (e.g. Layer 0 / l1-order) has no contract tables;
		// skip the read.
		if (!store.hasContractDomain()) {
			return new java.util.ArrayList<ContractEventRecord>();
		}
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
