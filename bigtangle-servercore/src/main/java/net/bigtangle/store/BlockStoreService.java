/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.GenericInvalidityException;
import net.bigtangle.exception.VerificationException.MissingDependencyException;
import net.bigtangle.exception.VerificationException.UnsolidException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.data.SolidityState.State;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceVerifyReward;

/**
 * <p>
 * A FullBlockStoreImpl works in conjunction with a
 * {@link DatabaseFullBlockStore} to verify all the rules of the BigTangle
 * system. Chain block as reward block is added first into ChainBlockQueue as
 * other blocks will be added in parallel. The process of ChainBlockQueue by
 * update chain is locked by database. Chain block will add to chain if there is
 * no exception. if the reward block is unsolid as missing previous block, then
 * it will trigger a sync and be deleted.
 * <p>
 * </p>
 */
@Service
public class BlockStoreService {

	private static final Logger log = LoggerFactory.getLogger(BlockStoreService.class);

	@Autowired
	protected NetworkParameters networkParameters;

	@Autowired
	ServerConfiguration serverConfiguration;

	@Autowired
	private StoreService storeService;
	@Autowired
	protected MempoolService mempoolService;
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected ObjectMapper jsonmapper;

	// Resolved lazily to break the blockStoreService -> stakeService ->
	// blockSaveService -> blockStoreService cycle.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.StakeService> stakeServiceProvider;

	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.RandaoService> randaoServiceProvider;

	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.CasperService> casperServiceProvider;
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.SlotService> slotServiceProvider;

	// Test-only escape hatch: some test harnesses deliberately seed token blocks
	// onto an L1 chain whose production params exclude TOKEN_CREATION. The
	// allow-set gate below is the real production enforcement; this switch lets
	// that setup bypass it in a scoped manner (a counter, so the HTTP request
	// thread of a test's signToken call also sees it). NEVER used by production
	// code. Reset in every test's setUp.
	private static final java.util.concurrent.atomic.AtomicInteger skipAllowedBlockTypeCheck =
			new java.util.concurrent.atomic.AtomicInteger(0);

	/** Test-only: scoped bypass of the layer block-type allow-set gate. */
	public static AutoCloseable skipAllowedBlockTypeCheckForTest() {
		skipAllowedBlockTypeCheck.incrementAndGet();
		return () -> skipAllowedBlockTypeCheck.decrementAndGet();
	}

	/** Test-only: clears any stray bypass so a failed test cannot leak it. */
	public static void resetAllowedBlockTypeCheckForTest() {
		skipAllowedBlockTypeCheck.set(0);
	}

	/**
	 * Layer scoping gate: a node only accepts the block types its
	 * {@link NetworkParameters} allow. This is the enforcement point that used to
	 * be dead code in {@code checkBlockBeforeSave} — every ingestion path
	 * (gossip, sync, batch, local save) funnels through {@link #addNonChain} /
	 * {@link #addChain}, so the allow-set is now consulted everywhere. L0-only
	 * types (e.g. TOKEN_CREATION) cannot be smuggled onto an L1 chain.
	 */
	private void checkAllowedBlockType(Block block) throws BlockStoreException {
		if (skipAllowedBlockTypeCheck.get() > 0) {
			return;
		}
		if (block.getBlockType() == null
				|| !networkParameters.getAllowedBlockTypes().contains(block.getBlockType())) {
			throw new VerificationException("Block type " + block.getBlockType() + " is not allowed on chain "
					+ networkParameters.getChainId());
		}
	}

	public boolean addBlock(Block block, boolean allowUnsolid, BlockStoreInterface store) throws BlockStoreException {
		boolean added;
		if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
			added = addChain(block, store);
		} else {
			added = addNonChain(block, allowUnsolid, store);
		}
		// Spend-pending tracking is unnecessary in PoS mode: double-spend
		// prevention is handled by the mempool and validator consensus
		// (GHOST/Casper) rather than UTXO-level flags.  Re-enable for PoW
		// chains by adding a configuration flag.

		return added;
	}

	/*
	 * speedup of sync without updateTransactionOutputSpendPending.
	 */
	public void addFromSync(Block block, boolean allowUnsolid, BlockStoreInterface store) throws BlockStoreException {
		checkAllowedBlockType(block);
		if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
			addChain(block, store);
		} else {
			addNonChain(block, allowUnsolid, store, true);
		}

	}

	public boolean addChain(Block block, BlockStoreInterface store) throws BlockStoreException {

		// Layer scoping: only accept block types allowed on this chain.
		checkAllowedBlockType(block);

		// Check the block is formally valid
		block.verifyHeader();
		block.verifyTransactions();
		// no more check add data
		saveChainBlockQueue(block, store, false);

		return true;
	}

	public void updateChain() throws BlockStoreException {
		updateChain(false);
	}

	/*
	 * updateChainConnected and updateConfirmed can not run in parallel
	 */
	public void updateChain(boolean confirmTimebox) throws BlockStoreException {
		updateChainConnected();
		// PoS mode — block confirmation is handled by Casper finality,
		// not PoW-style reward-chain confirmation.  Skipping the full
		// DAG scan in updateConfirmed() avoids a ~50 s bottleneck on
		// 50 k-block chains and is safe during the MCMC bridge phase.
		// updateUnConfirmedDo is likewise skipped inside
		// updateChainConnected — see the comment there.
		if (confirmTimebox)
			updateConfirmedTimeBoxed();
	}

	public void updateChainConnected() throws BlockStoreException {
		String LOCKID = "chain";
		int LockTime = 1000000;
		BlockStoreInterface store = storeService.getStore();
		try {
			// log.info("create Reward started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				try {
					store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
					canrun = true;
				} catch (Exception e) {
					// ignore
				}
			} else {
				if (lock.getLocktime() < System.currentTimeMillis() - LockTime) {
					store.deleteLockobject(LOCKID);
					store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
					canrun = true;
				}
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				// PoS mode — updateUnConfirmedDo (which scans and updates
				// every unconfirmed block) is skipped because block
				// confirmation is handled by Casper finality, not by
				// reward chainlengths.  processChainConnected still runs
				// to connect blocks into the DAG for the MCMC bridge.
				processChainConnected(store, false, true);
				store.deleteLockobject(LOCKID);
				if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
					log.info("updateChain time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
				}
				watch.stop();
			}
		} catch (Exception e) {
			store.deleteLockobject(LOCKID);
			throw e;
		} finally {
			if (store != null)
				store.close();
		}

	}

	private void saveChainBlockQueue(Block block, BlockStoreInterface store, boolean orphan)
			throws BlockStoreException {
		// save the block
		try {
			store.beginDatabaseBatchWrite();
			ChainBlockQueue chainBlockQueue = new ChainBlockQueue(block.getHash().getBytes(),
					block.unsafeBitcoinSerialize(), block.getLastMiningRewardBlock(), orphan,
					block.getTimeSeconds());
			store.insertChainBlockQueue(chainBlockQueue);
			store.commitDatabaseBatchWrite();
		} catch (Exception e) {
			if (store != null) {
				store.abortDatabaseBatchWrite();
			}
			throw e;
		} finally {
			if (store != null) {
				store.defaultDatabaseBatchWrite();
			}
		}
	}

	/*
	 *  
	 */
	public void processChainConnected(BlockStoreInterface store, boolean updatelowchain, boolean throwException)
			throws VerificationException, BlockStoreException {
		List<ChainBlockQueue> cbs = store.selectChainblockqueue(false, serverConfiguration.getSyncblocks());
		if (cbs != null && !cbs.isEmpty()) {
			Stopwatch watch = Stopwatch.createStarted();
			log.info("selectChainblockqueue with size  {}", cbs.size());
			// check only do add if there is longer chain as saved in database
			TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
			ChainBlockQueue maxFromQuery = cbs.get(cbs.size() - 1);
			if (!updatelowchain && maxConfirmedReward.getChainLength() > maxFromQuery.getChainlength()) {
				log.info("not longest chain in  selectChainblockqueue {}  < {}", maxFromQuery, maxConfirmedReward);
				return;
			}
			for (ChainBlockQueue chainBlockQueue : cbs) {
				if (throwException) {
					saveChainConnected(chainBlockQueue, store);
				} else {
					try {
						saveChainConnected(chainBlockQueue, store);
					} catch (Exception e) {
						log.info("saveChainConnected failed   {}", chainBlockQueue.toString(), e);
					}
				}

			}
			log.info("saveChainConnected time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
		}

	}

	private void saveChainConnected(ChainBlockQueue chainBlockQueue, BlockStoreInterface store)
			throws VerificationException, BlockStoreException {
		Block block = networkParameters.getDefaultSerializer().makeBlock(chainBlockQueue.getBlock());
		try {
			saveChainConnected(block, store);
			deleteChainQueue(chainBlockQueue, store);
		} catch (net.bigtangle.exception.VerificationException.InfeasiblePrototypeException
				| net.bigtangle.exception.VerificationException.MissingDependencyException e) {
			// The beacon references DAG blocks this node has not synced yet
			// (multi-node gossip lag). Dropping it would fork the chain: keep it
			// in the ChainBlockQueue and retry on the next tick, by which time
			// the referenced blocks will usually have arrived.
			log.warn("Beacon references unsynced blocks, keeping in queue for retry: {}", e.getMessage());
		} catch (Exception e) {
			deleteChainQueue(chainBlockQueue, store);
			throw e;
		}
	}

	private void saveChainConnected(Block block, BlockStoreInterface store)
			throws VerificationException, BlockStoreException {
		try {
			store.beginDatabaseBatchWrite();

			// Check the block is formally valid
			block.verifyHeader();
			block.verifyTransactions();

			// Solidify referenced blocks
			RewardInfo currRewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());

			// Solidify referenced blocks
			try {
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
						.solidifyBlocks(currRewardInfo, store);
			} catch (MissingDependencyException e) {
				// Propagate so the queue wrapper keeps this beacon for retry once
				// the missing referenced blocks have synced.
				throw e;
			}
			SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper).checkChainSolidity(block, true, store);

			if (solidityState.isDirectlyMissing()) {
				log.debug("Block isDirectlyMissing. saveChainConnected stop to save.{}", block);
				// sync the lastest chain from remote start from the -2 rewards
				// syncBlockService.startSingleProcess(block.getLastMiningRewardBlock()
				// - 2, false);
				return;
			}

			if (solidityState.isFailState()) {
				log.warn("Block isFailState. remove it from ChainBlockQueue.{}", block);
				return;
			}
			// Inherit solidity from predecessors if they are not solid
			// solidityState = serviceBase.getMinPredecessorSolidity(block, false, store);

			// Sanity check
			if (solidityState.isFailState() || solidityState.getState() == State.MissingPredecessor) {
				log.debug("Block isFailState. remove it from ChainBlockQueue.{}", block);
				return;
			}
			connectRewardBlock(block, solidityState, store);
			store.commitDatabaseBatchWrite();
		} catch (Exception e) {
			store.abortDatabaseBatchWrite();
			cacheBlockService.evictBlock(block, store);
			throw e;
		} finally {

			store.defaultDatabaseBatchWrite();
		}
	}

	private void deleteChainQueue(ChainBlockQueue chainBlockQueue, BlockStoreInterface store)
			throws BlockStoreException {
		List<ChainBlockQueue> l = new ArrayList<>();
		l.add(chainBlockQueue);
		store.deleteChainBlockQueue(l);
	}

	public boolean addNonChain(Block block, boolean allowUnsolid, BlockStoreInterface blockStore)
			throws BlockStoreException {
		return addNonChain(block, allowUnsolid, blockStore, false);
	}

	public boolean addNonChain(Block block, boolean allowUnsolid, BlockStoreInterface blockStore,
			boolean allowMissingPredecessor) throws BlockStoreException {
		return addNonChain(block, allowUnsolid, blockStore, allowMissingPredecessor, false);
	}

	public boolean addNonChain(Block block, boolean allowUnsolid, BlockStoreInterface blockStore,
			boolean allowMissingPredecessor, boolean batch) throws BlockStoreException {
	 	log.debug("addNonChain"+ block.toString());
		// Layer scoping: only accept block types allowed on this chain. Every
		// ingestion path (gossip, sync, batch, local save) reaches this method,
		// so the allow-set gate is enforced on all of them.
		checkAllowedBlockType(block);
		if (!batch) {
			block.verifyHeader();
			block.verifyTransactions();
		}

		// CROSSTANGLE blocks carry cross-chain value/messages (anchors, peg-in/out):
		// they MUST pass real consensus validation (L0AnchorHandler), so a failure —
		// whether reported as a fail state or thrown as an exception — is rejected
		// regardless of allowUnsolid. The gossip/Kafka/batch receive paths use
		// allowUnsolid=true; without this guard an invalid block would be swallowed
		// and saved solid with zero validation.
		boolean crosstangle = BlockType.BLOCKTYPE_CROSSTANGLE == block.getBlockType();
		// allow non chain block predecessors not solid
		SolidityState solidityState = new SolidityState(State.Success, null, false);
		try {
			solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.checkSolidity(block, !allowUnsolid, blockStore, allowMissingPredecessor, batch);
		} catch (Exception e) {
			if (crosstangle || !allowUnsolid)
				throw e;
		}
		if (crosstangle && solidityState.isFailState()) {
			throw new VerificationException("CROSSTANGLE block failed consensus validation: "
					+ solidityState.getState() + " " + block.getHashAsString());
		}
		if (solidityState.isFailState()) {
			// log.debug("{} block {}", solidityState, block);
		}
		// If explicitly wanted (e.g. new block from local clients), this
		// block must strictly be solid now.
		if (!allowUnsolid) {
			switch (solidityState.getState()) {
			case MissingPredecessor:
				if (!allowMissingPredecessor)
					throw new UnsolidException(solidityState.toString() + block.toString()	);
			case MissingCalculation:
			case Success:
				break;
			case Invalid:
				throw new GenericInvalidityException();
			}
		}

		// Accept the block
		try {
			blockStore.beginDatabaseBatchWrite();
			connect(block, solidityState, blockStore);
			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			cacheBlockService.evictBlock(block, blockStore);
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}

		return true;
	}

	private void connectRewardBlock(final Block block, SolidityState solidityState, BlockStoreInterface store)
			throws BlockStoreException, VerificationException {

		if (solidityState.isFailState()) {
			connect(block, solidityState, store);
			return;
		}
		Block head = store.get(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
		ServiceVerifyReward serviceVerifyReward = new ServiceVerifyReward(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper, mempoolService);
		if (serviceVerifyReward.getRewardInfo(block).getPrevRewardHash().equals(head.getHash())) {
			connect(block, solidityState, store);
			serviceVerifyReward.verifyRewardChainConfirmReferenced(block, store);
		} else {
			// This block connects to somewhere other than the top of the best
			// known chain. We treat these differently.
			boolean haveNewBestChain = serviceVerifyReward.getRewardInfo(block).getChainlength() > serviceVerifyReward
					.getRewardInfo(head).getChainlength();
			// TODO check this
			// block.getRewardInfo().moreWorkThan(head.getRewardInfo());
			// FINALITY: a reorg may only replace the head if the winning chain
			// descends from the last FINALIZED checkpoint. Any chain that does
			// not is a competing fork and is saved unconfirmed — finalized
			// history can never be reverted ("Once finalized, never reverted").
			net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
			if (haveNewBestChain && casper != null) {
				net.bigtangle.server.service.CasperService.Checkpoint finalized = casper.getLastFinalizedCheckpoint();
				// Only enforce the anchor when the finalized block is actually in
				// this store: on a fresh/reset node the checkpoint belongs to a
				// prior chain and there is nothing to anchor yet. On the live
				// chain it is always present, so a reorg that would cross it is
				// refused.
				boolean anchorKnown = finalized != null
						&& store.get(finalized.getBlockHash()) != null;
				// Walk from the new block's prevRewardHash: the tip itself is not
				// yet connected (store.put happens in connect below), so starting
				// at block.getHash() would resolve nothing and refuse every reorg.
				if (anchorKnown && !casper.descendsFrom(
						serviceVerifyReward.getRewardInfo(block).getPrevRewardHash(),
						finalized.getBlockHash(), store)) {
					log.info("Reorg refused: new chain does not descend from finalized checkpoint epoch {} ({})",
							finalized.getEpoch(), finalized.getBlockHash());
					haveNewBestChain = false;
				}
				// ATTESTATION-WEIGHTED fork choice: the canonical reward chain
				// must extend the highest JUSTIFIED checkpoint (justification is
				// stake-weighted via Casper). A longer fork that does NOT descend
				// from the highest justified checkpoint cannot win — this is what
				// gives attestations influence over which reward chain is best.
				net.bigtangle.server.service.CasperService.Checkpoint justified = casper.getJustifiedCheckpoint();
				if (haveNewBestChain && justified != null && store.get(justified.getBlockHash()) != null
						&& !casper.descendsFrom(
								serviceVerifyReward.getRewardInfo(block).getPrevRewardHash(),
								justified.getBlockHash(), store)) {
					log.info("Reorg refused: new chain does not descend from justified checkpoint epoch {} ({})",
							justified.getEpoch(), justified.getBlockHash());
					haveNewBestChain = false;
				}
			}
			if (haveNewBestChain) {
				log.info("Block is causing a re-organize");
				connect(block, solidityState, store);
				serviceVerifyReward.handleNewBestChain(block, store);
			} else {
				// parallel chain, save as unconfirmed
				connect(block, solidityState, store);
			}

		}
	}

	/**
	 * Inserts the specified block into the DB
	 * 
	 * @param block the block
	 */
	private void connect(final Block block, SolidityState solidityState, BlockStoreInterface store)
			throws BlockStoreException, VerificationException {
		store.put(block);
		if (!net.bigtangle.store.DatabaseFullBlockStoreBase.isCacheSkipped()) {
			cacheBlockService.cachePutBlock(block, store);
		}
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyBlock(block, solidityState, false, store);
		// Connect-time application of chain-derived state. This is what makes a
		// STAKE/SLASHING/EXIT block received via sync/gossip apply on EVERY node:
		// the local-save path (BlockSaveService.notifyChainDerived) and the
		// confirm batch (confirmDo) only cover locally-created blocks and
		// confirmed ones, so a synced-but-unconfirmed deposit would otherwise
		// never enter the validator set and the network could not converge on a
		// shared active set. Idempotent with both.
		net.bigtangle.server.service.StakeService stakeService = stakeServiceProvider.getIfAvailable();
		if (stakeService != null) {
			try {
				if (block.getBlockType() == BlockType.BLOCKTYPE_STAKE) {
					stakeService.applyStakeBlock(block, store);
				} else if (block.getBlockType() == BlockType.BLOCKTYPE_SLASHING) {
					stakeService.applySlashingBlock(block, store);
				} else if (block.getBlockType() == BlockType.BLOCKTYPE_EXIT) {
					stakeService.applyExitBlock(block, store);
				}
			} catch (Exception e) {
				// A single inapplicable block must not halt connection; genuine
				// storage failures still abort at the enclosing batch commit.
				log.warn("Skipping chain-derived state for connected block {}: {}",
						block.getHashAsString(), e.getMessage());
			}
		}
	}

	// TODO update other output data can be deadlock, as non chain block
	// run in parallel
	private void updateTransactionOutputSpendPending(Block block) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit((Callable) () -> updateTransactionOutputSpendPendingDo(block));
		try {
			handler.get(2000L, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.info("TimeoutException cancel updateTransactionOutputSpendPending ");
			handler.cancel(true);
		} catch (Exception e) {
			// ignore
			log.info("updateTransactionOutputSpendPending", e);
		} finally {
			executor.shutdownNow();
		}

	}

	public String updateTransactionOutputSpendPendingDo(Block block) throws BlockStoreException {
		BlockStoreInterface blockStore = storeService.getStore();
		try {
			updateTransactionOutputSpendPending(block, blockStore);
			new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.evictTransactionsAndBlockEva(block, blockStore);
			// Initialize MCMC
			if (blockStore.getMCMC(block.getHash()) == null) {
				ArrayList<DepthAndWeight> depthAndWeight = new ArrayList<>();
				depthAndWeight.add(new DepthAndWeight(block.getHash(), 1, 0));
				blockStore.updateBlockEvaluationWeightAndDepth(depthAndWeight);
			}
		} finally {
			if (blockStore != null)
				blockStore.close();
		}
		return "";
	}

	private void updateTransactionOutputSpendPending(Block block, BlockStoreInterface blockStore)
			throws BlockStoreException {
		for (final Transaction tx : block.getTransactions()) {
			boolean isCoinBase = tx.isCoinBase();
			List<UTXO> spendPending = new ArrayList<>();
			if (!isCoinBase) {
				for (int index = 0; index < tx.getInputs().size(); index++) {
					TransactionInput in = tx.getInputs().get(index);
					UTXO prevOut = blockStore.getTransactionOutput(in.getOutpoint().getBlockHash(),
							in.getOutpoint().getTxHash(), in.getOutpoint().getIndex());
					if (prevOut != null) {
						spendPending.add(prevOut);
					}
				}
			}

			blockStore.updateTransactionOutputSpendPending(spendPending);

		}
	}

	public void updateUnConfirmedDo(BlockStoreInterface blockStore) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(blockStore);
		long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, blockStore);
		long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, blockStore);
		unconfirmDo(blockStore, cutoffHeight, maxHeight);
	}

	public void confirmDo(BlockStoreInterface blockStore) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(blockStore);
		long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, blockStore);
		long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, blockStore);

		confirmDo(blockStore, cutoffHeight, maxHeight, maxConfirmedReward.getChainLength());
	}

	public void confirmDo(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight, long prevChainlength)
			throws BlockStoreException {

		confirmDo(blockStore, cutoffHeight, blockStore.getBlocksToConfirm(cutoffHeight, maxHeight), true,
				prevChainlength);
	}

	public void confirmDo(BlockStoreInterface blockStore, long cutoffHeight, Set<BlockWrap> blocksToAdd,
			boolean resolveConflict, long prevChainlength) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();
			Set<BlockWrap> blocks = new HashSet<>();
			// use the add to filter and check
			for (BlockWrap b : blocksToAdd) {
				serviceBase.dagBlockHashesFrom(blocks, b, cutoffHeight, prevChainlength, null, true, false,
						blockStore);
			}
			// Execute must be chained for confirm
			serviceBase.checkExecutionChained(blockStore, blocks);

			// Finally add the resolved new blocks to the confirmed set
			serviceBase.confirmBlocksSorted(blockStore, -1, true, blocks, new HashSet<>());

			// Confirm-time application of chain-derived state: a STAKE/SLASHING
			// block that gains confirmation has its validator deposit applied /
			// slash enforced. Idempotent with the save-time application. A single
			// block that cannot be applied (inapplicable data, or a storage error
			// on its own row) must not halt ALL confirmation, so it is logged and
			// skipped here; a genuinely broken database still aborts the batch at
			// COMMIT below.
			net.bigtangle.server.service.StakeService stakeService = stakeServiceProvider.getIfAvailable();
			if (stakeService != null) {
				for (BlockWrap b : blocks) {
					Block blk = b.getBlock();
					try {
						if (blk.getBlockType() == BlockType.BLOCKTYPE_STAKE) {
							stakeService.applyStakeBlock(blk, blockStore);
						} else if (blk.getBlockType() == BlockType.BLOCKTYPE_SLASHING) {
							stakeService.applySlashingBlock(blk, blockStore);
						} else if (blk.getBlockType() == BlockType.BLOCKTYPE_EXIT) {
							stakeService.applyExitBlock(blk, blockStore);
						}
					} catch (Exception e) {
						log.warn("Skipping chain-derived state for confirmed block {}: {}",
								blk.getHashAsString(), e.getMessage());
					}
				}
			}

			// RANDAO: fold each confirmed beacon's reveal into the mix, in
			// REWARD-CHAINLENGTH order, so the mix is a PURE function of the
			// confirmed chain (never mutated by unconfirmed or competing beacons).
			// The writes ride the batch; memory is reloaded after COMMIT so an
			// aborted batch cannot leave a divergent in-memory mix. Chainlength
			// order also makes the epoch snapshot boundary deterministic (the
			// first beacon of the following epoch in chain order freezes it), so
			// every node derives the same immutable mixfinal value.
			net.bigtangle.server.service.RandaoService randaoService = randaoServiceProvider.getIfAvailable();
			java.util.Set<Long> touchedMixEpochs = new java.util.HashSet<>();
			java.util.Set<Long> finalityEpochs = new java.util.HashSet<>();
			if (randaoService != null) {
				List<Object[]> chainBeacons = new ArrayList<>(); // {chainlength, beacon}
				for (BlockWrap b : blocks) {
					Block blk = b.getBlock();
					if (blk.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
						continue;
					}
					Long chainlength = null;
					try {
						net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
								.parseChecked(blk.getTransactions().get(0).getData());
						if (ri != null) {
							chainlength = ri.getChainlength();
						}
					} catch (Exception e) {
						log.warn("Skipping beacon with unparseable reward info {}: {}",
								blk.getHashAsString(), e.getMessage());
					}
					if (chainlength != null) {
						chainBeacons.add(new Object[] { chainlength, b });
					}
				}
				chainBeacons.sort(Comparator.comparingLong(o -> (Long) o[0]));
				for (Object[] entry : chainBeacons) {
					Block blk = ((BlockWrap) entry[1]).getBlock();
					java.util.Set<Long> epochs = applyRevealFromBeacon(randaoService, blk, blockStore);
					if (epochs != null) {
						touchedMixEpochs.addAll(epochs);
					}
					// In chainlength order the FIRST beacon of the following
					// epoch is the deterministic epoch boundary; freezing the just-
					// ended epoch's mix here produces the same immutable snapshot on
					// every node. Fail-closed: a persistence error aborts the batch.
					net.bigtangle.core.SlotData sd = slotDataOf(blk);
					if (sd != null) {
						randaoService.finalizeEpochMix(sd.getSlot() / 32 - 1, blockStore);
						// Same boundary discipline for the active validator set:
						// proposer selection two epochs later reads this immutable
						// snapshot instead of each node's live local set.
						net.bigtangle.server.service.SlotService slotService = slotServiceProvider.getIfAvailable();
						if (slotService != null) {
							slotService.snapshotValidatorsForEpoch(sd.getSlot() / 32 - 1, blockStore);
						}
						// Chain-driven finality is evaluated AFTER the commit
						// (see below): the two most recently completed slot-epochs
						// have complete votes. Only collect the epochs here.
						long slotEpoch = sd.getSlot() / 32;
						for (long e = Math.max(0, slotEpoch - 2); e < slotEpoch; e++) {
							finalityEpochs.add(e);
						}
					}
				}
			}

			// Withdrawable epochs for SLASHING/EXIT blocks are set when the
			// CONFIRMING BEACON confirms — the beacon whose RewardInfo.blocks
			// references the block drives it, so a block confirmed ahead of its
			// beacon stays PENDING (withdrawable = -1) rather than aborting the
			// batch. processWithdrawals gates on >= 0, so pending is safe.
			//
			// Beacons are applied in chainlength order and the epoch is ALWAYS
			// overwritten (never keep-first): the value must mirror the
			// currently-confirmed referencing beacon, so a stale epoch left by an
			// unconfirmed beacon can never be frozen. Chainlength order makes the
			// last writer deterministic (the highest confirmed referencing beacon
			// wins) on every node. Per-beacon/per-block failures are logged and
			// skipped — like the apply loop above, one bad block must not halt
			// ALL confirmation.
			if (stakeService != null) {
				// Parse each beacon's RewardInfo defensively BEFORE sorting, so
				// an unparseable reward info cannot throw out of the comparator
				// and abort the batch.
				List<Object[]> beacons = new ArrayList<>(); // {chainlength, beacon}
				for (BlockWrap b : blocks) {
					Block beacon = b.getBlock();
					if (beacon.getBlockType() != BlockType.BLOCKTYPE_BEACON || beacon.getTransactions().isEmpty()) {
						continue;
					}
					try {
						net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
								.parseChecked(beacon.getTransactions().get(0).getData());
						if (ri != null && ri.getBlocks() != null) {
							beacons.add(new Object[] { ri.getChainlength(), b });
						}
					} catch (Exception e) {
						log.warn("Skipping beacon with unparseable reward info {}: {}",
								beacon.getHashAsString(), e.getMessage());
					}
				}
				beacons.sort(Comparator.comparingLong(o -> (Long) o[0]));
				for (Object[] entry : beacons) {
					Block beacon = ((BlockWrap) entry[1]).getBlock();
					net.bigtangle.core.RewardInfo ri;
					try {
						ri = new net.bigtangle.core.RewardInfo()
								.parseChecked(beacon.getTransactions().get(0).getData());
					} catch (Exception e) {
						log.warn("Skipping beacon reward info {}: {}", beacon.getHashAsString(), e.getMessage());
						continue;
					}
					if (ri == null || ri.getBlocks() == null) {
						continue;
					}
					long epoch = ri.getChainlength() / net.bigtangle.server.service.SlotService.SLOTS_PER_EPOCH;
					for (Sha256Hash referenced : ri.getBlocks()) {
						Block refBlock = blockStore.get(referenced);
						if (refBlock == null) {
							continue; // not yet synced — defer
						}
						try {
							if (refBlock.getBlockType() == BlockType.BLOCKTYPE_SLASHING) {
								stakeService.applySlashingConfirmed(refBlock, epoch, blockStore);
							} else if (refBlock.getBlockType() == BlockType.BLOCKTYPE_EXIT) {
								stakeService.applyExitConfirmed(refBlock, epoch, blockStore);
							}
						} catch (Exception e) {
							log.warn("Skipping withdrawable at confirmation for block {}: {}",
									referenced, e.getMessage());
						}
					}
				}

				// Bond release is CHAIN-DRIVEN: when this batch's confirmations
				// push the chain past a withdrawable epoch, the stake record is
				// released inside the same batch — at the same chain position on
				// every node. A wall-clock-driven release would free the bond at
				// different local times on different nodes, and bond-spend
				// validation reads this table (validation divergence).
				if (!beacons.isEmpty()) {
					long batchChainEpoch = (Long) beacons.get(beacons.size() - 1)[0]
							/ net.bigtangle.server.service.SlotService.SLOTS_PER_EPOCH;
					try {
						stakeService.processWithdrawals(batchChainEpoch, blockStore);
					} catch (Exception e) {
						// Idempotent — re-executed by the next confirm batch.
						log.warn("Withdrawal processing failed at chain epoch {}: {}",
								batchChainEpoch, e.getMessage());
					}
				}
			}

			blockStore.commitDatabaseBatchWrite();

			// After a successful commit, refresh the in-memory mixes for the
			// touched epochs so getRandaoMix (used for proposing) agrees with
			// the persisted state (used for validation).
			if (randaoService != null) {
				for (Long epoch : touchedMixEpochs) {
					randaoService.reloadMix(epoch);
				}
			}

			// Chain-driven finality AFTER commit: checkpoint justified/finalized
			// flags flip in memory only now, so an aborted batch can never leave
			// phantom in-memory finality that the reorg guard would enforce.
			// Evaluating at a confirmed chain position makes the evaluation point
			// identical on every node (wall-clock epoch ticks are only a
			// backstop); finalizeCheckpoint is idempotent and monotone, and a
			// failure here must not fail the (already committed) batch — it is
			// re-evaluated at the next confirmed beacon.
			net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
			if (casper != null) {
				for (Long e : finalityEpochs) {
					try {
						casper.finalizeCheckpoint(e, blockStore);
					} catch (Exception ex) {
						log.warn("Checkpoint finalization failed for epoch {}: {}", e, ex.getMessage());
					}
				}
			}
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}
	}

	public void unconfirmDo(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight)
			throws BlockStoreException {
		updateChainlengthConflicts(blockStore, cutoffHeight, maxHeight);
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();

			// Unconfirm anything not confirmed by chainlength
			List<Sha256Hash> wipeBlocks = blockStore.blocksNotChainlengthFromHeigth(cutoffHeight);

			HashSet<BlockWrap> blocksToUnconfirm = new HashSet<>();

			for (Sha256Hash b : wipeBlocks) {
				BlockWrap block = serviceBase.getBlockWrap(b, blockStore);
				if (checkChainHeadExecution(block.getBlock(), serviceBase, blockStore)) {
					blocksToUnconfirm.add(block);
				}
			}

			serviceBase.unconfirmBlocksSorted(blockStore, blocksToUnconfirm, new HashSet<>(), true);

			// Reorg revert of chain-derived state (stake deposits, slashing)
			// for blocks that were unconfirmed.
			net.bigtangle.server.service.StakeService stakeService = stakeServiceProvider.getIfAvailable();
			if (stakeService != null) {
				for (BlockWrap b : blocksToUnconfirm) {
					Block blk = b.getBlock();
					try {
						if (blk.getBlockType() == BlockType.BLOCKTYPE_STAKE) {
							stakeService.revertStakeBlock(blk, blockStore);
						} else if (blk.getBlockType() == BlockType.BLOCKTYPE_SLASHING) {
							stakeService.revertSlashingBlock(blk, blockStore);
						} else if (blk.getBlockType() == BlockType.BLOCKTYPE_EXIT) {
							stakeService.revertExitBlock(blk, blockStore);
						} else if (blk.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
							// A beacon being unconfirmed recomputes the
							// withdrawable epoch of any SLASHING/EXIT block it
							// referenced from the remaining confirmed chain, so a
							// stale value from this beacon is never left behind to
							// become permanent.
							resetReferencedWithdrawables(stakeService, blk, blockStore);
						}
					} catch (Exception e) {
						log.warn("Failed to revert chain-derived state for block {}: {}",
								blk.getHashAsString(), e.getMessage());
					}
				}
			}

			// RANDAO: XOR each unconfirmed beacon's reveal back out of the mix
			// (XOR is its own inverse), reverting the confirmation-time fold so
			// the mix stays a pure function of the confirmed chain.
		net.bigtangle.server.service.RandaoService randaoService = randaoServiceProvider.getIfAvailable();
		java.util.Set<Long> touchedUnconfirmEpochs = new java.util.HashSet<>();
		if (randaoService != null) {
			for (BlockWrap b : blocksToUnconfirm) {
				Block blk = b.getBlock();
				if (blk.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
					java.util.Set<Long> epochs = applyRevealFromBeacon(randaoService, blk, blockStore);
					if (epochs != null) {
						touchedUnconfirmEpochs.addAll(epochs);
					}
				}
			}
		}

		// Casper: invalidate cached/persisted checkpoints at/above the epoch of
		// each unconfirmed beacon so they re-derive from the reorged chain (a
		// checkpoint cached once must not pin a now-reverted boundary hash).
		net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
		if (casper != null) {
			for (BlockWrap b : blocksToUnconfirm) {
				Block blk = b.getBlock();
				if (blk.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
					net.bigtangle.core.SlotData sd = slotDataOf(blk);
					if (sd != null) {
						casper.invalidateCheckpointsFrom(sd.getSlot() / 32, blockStore);
					}
				}
			}
		}

		blockStore.commitDatabaseBatchWrite();
			if (randaoService != null) {
				for (Long epoch : touchedUnconfirmEpochs) {
					randaoService.reloadMix(epoch);
				}
			}
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}
	}

	/**
	 * When a beacon is unconfirmed, the withdrawable epoch of every SLASHING/EXIT
	 * block it referenced is recomputed from the remaining confirmed reward chain:
	 * if another still-confirmed beacon references the block, its epoch is
	 * re-derived from that beacon; otherwise the epoch is reset to pending (-1).
	 * This ensures a stale value from an unconfirmed beacon can never become
	 * permanent, and that a block which remains confirmed is never left pending
	 * by the reorg.
	 */
	private void resetReferencedWithdrawables(net.bigtangle.server.service.StakeService stakeService, Block beacon,
			BlockStoreInterface blockStore) throws Exception {
		if (beacon.getTransactions().isEmpty()) {
			return;
		}
		net.bigtangle.core.RewardInfo ri;
		try {
			ri = new net.bigtangle.core.RewardInfo().parseChecked(beacon.getTransactions().get(0).getData());
		} catch (Exception e) {
			return;
		}
		if (ri == null || ri.getBlocks() == null) {
			return;
		}
		for (Sha256Hash referenced : ri.getBlocks()) {
			Block refBlock = blockStore.get(referenced);
			if (refBlock == null) {
				continue;
			}
			if (refBlock.getBlockType() != BlockType.BLOCKTYPE_SLASHING
					&& refBlock.getBlockType() != BlockType.BLOCKTYPE_EXIT) {
				continue;
			}
			// The unconfirmed beacon is already removed from the confirmed
			// reward chain by unconfirmBlocksSorted, so the scan below only
			// finds beacons that are still confirmed and still reference this
			// block. If none is found within the bounded scan, only fall back to
			// pending when the block is itself no longer confirmed — a block that
			// remains on the confirmed chain keeps its (confirmation-derived)
			// withdrawable; the confirm-time overwrite heals any genuinely stale
			// value on re-confirmation, and a deep referencing beacon beyond the
			// scan bound can never wrongly strand a confirmed block.
			Long stillConfirmedEpoch = findConfirmingEpochFromStore(referenced, blockStore);
			if (stillConfirmedEpoch != null) {
				if (refBlock.getBlockType() == BlockType.BLOCKTYPE_SLASHING) {
					stakeService.applySlashingConfirmed(refBlock, stillConfirmedEpoch, blockStore);
				} else {
					stakeService.applyExitConfirmed(refBlock, stillConfirmedEpoch, blockStore);
				}
			} else {
				net.bigtangle.core.BlockEvaluation eval = blockStore.getBlockEvaluationsByhashs(referenced);
				if (eval == null || !eval.isConfirmed()) {
					stakeService.clearWithdrawableForBlock(refBlock, blockStore);
				}
			}
		}
	}

	/**
	 * Scans the confirmed reward chain (bounded by CHAINLENGTH_CUTOFF) for a
	 * beacon whose RewardInfo.blocks references {@code target}, returning its
	 * chain epoch, or null if none is found. The scan walks from the tip, so the
	 * highest (most recent) confirming beacon wins. A null result means "no
	 * still-confirmed referencing beacon within the window" — callers must not
	 * treat it as proof of staleness (see resetReferencedWithdrawables).
	 */
	private Long findConfirmingEpochFromStore(Sha256Hash target, BlockStoreInterface blockStore) {
		try {
			TXReward tip = blockStore.getMaxConfirmedReward();
			if (tip == null) {
				return null;
			}
			Sha256Hash cursor = tip.getBlockHash();
			java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
			int count = 0;
			while (cursor != null && visited.add(cursor) && count < NetworkParameters.CHAINLENGTH_CUTOFF) {
				count++;
				Block beacon = blockStore.get(cursor);
				if (beacon == null) {
					return null;
				}
				if (beacon.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
					return null;
				}
				if (beacon.getBlockType() != BlockType.BLOCKTYPE_BEACON || beacon.getTransactions().isEmpty()) {
					return null;
				}
				net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
						.parseChecked(beacon.getTransactions().get(0).getData());
				if (ri != null && ri.getBlocks() != null && ri.getBlocks().contains(target)) {
					return ri.getChainlength() / net.bigtangle.server.service.SlotService.SLOTS_PER_EPOCH;
				}
				cursor = ri != null ? ri.getPrevRewardHash() : null;
			}
		} catch (Exception e) {
			log.warn("Failed to find confirming beacon for {}: {}", target, e.getMessage());
		}
		return null;
	}

	/**
	 * Folds a confirmed beacon's RANDAO reveal (from its SlotData) into the
	 * epoch mix, persisted through {@code store} so the write participates in
	 * the caller's batch. XOR is its own inverse, so the same call in
	 * unconfirmDo reverts it. Failures propagate (fail-closed).
	 */
	private java.util.Set<Long> applyRevealFromBeacon(net.bigtangle.server.service.RandaoService randaoService,
			Block blk, BlockStoreInterface store) throws BlockStoreException {
		java.util.Set<Long> epochs = new java.util.HashSet<>();
		try {
			for (Transaction tx : blk.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					net.bigtangle.core.SlotData sd = jsonmapper.readValue(tx.getData(),
							net.bigtangle.core.SlotData.class);
					if (sd != null && sd.getRandaoReveal() != null) {
						randaoService.applyReveal(sd.getSlot(), sd.getRandaoReveal(), store);
						epochs.add(sd.getSlot() / 32);
					}
					break;
				}
			}
		} catch (Exception e) {
			throw new net.bigtangle.exception.BlockStoreException(
					"Failed to fold RANDAO reveal for beacon " + blk.getHashAsString(), e);
		}
		return epochs;
	}

	/** The SlotData transaction of a beacon, or null if it carries none. */
	private net.bigtangle.core.SlotData slotDataOf(Block blk) {
		try {
			for (Transaction tx : blk.getTransactions()) {
				if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
					return jsonmapper.readValue(tx.getData(), net.bigtangle.core.SlotData.class);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse SlotData for beacon {}: {}", blk.getHashAsString(), e.getMessage());
		}
		return null;
	}

	public boolean checkChainHeadExecution(Block block, ServiceBaseConnect serviceBase, BlockStoreInterface store)
			throws BlockStoreException {
		switch (block.getBlockType()) {
		default:
			return true;
		}

	}

	/*
	 * set the execute as obsolete as solid to the chained
	 */
	public void updateChainHeadExecutionObsolete(Block block, ServiceBaseConnect serviceBase, BlockStoreInterface store)
			throws BlockStoreException {
	}

	public void updateChainlengthConflicts(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight)
			throws BlockStoreException {

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();
			// First remove any blocks that should no longer be in the chainlength
			// HashSet<BlockEvaluation> blocksToRemove = blockStore.getBlocksToUnconfirm();

			// Unconfirm anything not confirmed by chainlength
			List<Sha256Hash> wipeBlocks = blockStore.blocksNotChainlengthFromHeigth(cutoffHeight);

			HashSet<BlockWrap> blocksToUnconfirm = new HashSet<>();

			for (Sha256Hash b : wipeBlocks) {
				BlockWrap re = serviceBase.getBlockWrap(b, blockStore);
				updateChainHeadExecutionObsolete(re.getBlock(), serviceBase, blockStore);
				blocksToUnconfirm.add(re);
			}
			serviceBase.updateChainlengthConflicts(blocksToUnconfirm, blockStore);
			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}
	}

	public void updateConfirmed() throws BlockStoreException {
		String LOCKID = "chain";
		int LockTime = 1000000;
		BlockStoreInterface store = storeService.getStore();
		try {
			// log.info("create Reward started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				try {
					store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
					canrun = true;
				} catch (Exception e) {
					// ignore
				}
			} else {
				if (lock.getLocktime() < System.currentTimeMillis() - LockTime) {
					store.deleteLockobject(LOCKID);
					store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
					canrun = true;
				} else {
					if (lock.getLocktime() < System.currentTimeMillis() - 2000)
						log.info("updateConfirmed running start = {}", Utils.dateTimeFormat(lock.getLocktime()));
				}
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				confirmDo(store);
				if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
					log.info("updateConfirmedDo time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
				}
				cleanUp(store);
				if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
					log.info("cleanUp time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
				}

				store.deleteLockobject(LOCKID);
				watch.stop();
			}
		} catch (Exception e) {
			store.deleteLockobject(LOCKID);
			log.info(" ", e);
			throw e;
		} finally {
			if (store != null)
				store.close();
		}
	}

	public void cleanUp(BlockStoreInterface store) throws BlockStoreException {
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		cleanUpDo(maxConfirmedReward, store);
	}

	public void cleanUpDo(TXReward maxConfirmedReward, BlockStoreInterface store) throws BlockStoreException {

		Block rewardblock = store.get(maxConfirmedReward.getBlockHash());
		// log.info(" cleanUpDo until block " + "" + rewardblock);
		store.prunedClosedOrders(rewardblock.getTimeSeconds());
		// max keep 500 blockchain as spendblock number

		long maxRewardblock = rewardblock.getLastMiningRewardBlock() - 500;
		// log.info(" prunedHistoryUTXO until reward block " + "" + rewardblock);
		store.prunedHistoryUTXO(maxRewardblock);
		// store.prunedPriceTicker(rewardblock.getTimeSeconds() - 30 *
		// DaySeconds);

	}

	/*
	 * run timeboxed updateConfirmed, there is no transaction here. Timeout will
	 * cancel the rest of update confirm and can be update from next run
	 */
	private void updateConfirmedTimeBoxed() {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit((Callable) () -> {
			updateConfirmed();
			return "";
		});
		try {
			handler.get(3000L, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.info("Timeout cancel updateConfirmed ");
			handler.cancel(true);
		} catch (Exception e) {
			// ignore
			log.info("updateConfirmed", e);
		} finally {
			executor.shutdownNow();
		}

	}

}
