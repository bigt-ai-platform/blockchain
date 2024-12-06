/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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

import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.exception.VerificationException.GenericInvalidityException;
import net.bigtangle.core.exception.VerificationException.MissingDependencyException;
import net.bigtangle.core.exception.VerificationException.UnsolidException;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.data.SolidityState.State;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceBaseReward;
import net.bigtangle.utils.Gzip;

/**
 * <p>
 * A FullBlockStoreImpl works in conjunction with a
 * {@link DatabaseFullBlockStore} to verify all the rules of the BigTangle
 * system. Chain block as reward block is added first into ChainBlockQueue as
 * other blocks will be added in parallel. The process of ChainBlockQueue by
 * update chain is locked by database. Chain block will add to chain if there is
 * no exception. if the reward block is unsolid as missing previous block, then
 * it will trigger a sync and be deleted.
 * 
 * </p>
 */
@Service
public class FullBlockStoreImpl {

	private static final Logger log = LoggerFactory.getLogger(FullBlockStoreImpl.class);

	@Autowired
	protected NetworkParameters networkParameters;

	@Autowired
	ServerConfiguration serverConfiguration;

	@Autowired
	private StoreService storeService;
	@Autowired
	protected CacheBlockService cacheBlockService;

	public boolean add(Block block, boolean allowUnsolid, FullBlockStore store) throws BlockStoreException {
		boolean added;
		if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
			added = addChain(block, allowUnsolid, true, store);
		} else {
			added = addNonChain(block, allowUnsolid, store);
		}
		// update spend of origin UTXO to avoid create of double spent and account
		// balance
		if (added) {
			updateTransactionOutputSpendPending(block);
		}

		return added;
	}

	/*
	 * speedup of sync without updateTransactionOutputSpendPending.
	 */
	public void addFromSync(Block block, boolean allowUnsolid, FullBlockStore store) throws BlockStoreException {

		if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
			addChain(block, allowUnsolid, true, store);
		} else {
			addNonChain(block, allowUnsolid, store, true);
		}

	}

	public boolean add(Block block, boolean allowUnsolid, boolean updatechain, FullBlockStore store)
			throws BlockStoreException {
		boolean a = add(block, allowUnsolid, store);
		if (updatechain) {
			updateChain();
		}
		return a;
	}

	public boolean addChain(Block block, boolean allowUnsolid, boolean tryConnecting, FullBlockStore store)
			throws BlockStoreException {

		// Check the block is partially formally valid and fulfills PoW
		block.verifyHeader();
		block.verifyTransactions();
		// no more check add data
		saveChainBlockQueue(block, store, false);

		return true;
	}

	/*
	 * updateChainConnected and confirm can not run in parallel
	 */
	public void updateChain() throws BlockStoreException {
		updateChainConnected();
		// updateConfirmedTimeBoxed();
	}

	public void updateChainConnected() throws BlockStoreException {
		String LOCKID = "chain";
		int LockTime = 1000000;
		FullBlockStore store = storeService.getStore();
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
					// if (lock.getLocktime() < System.currentTimeMillis() - 10000)
					// log.info("updateChain running start = " +
					// Utils.dateTimeFormat(lock.getLocktime()));
				}
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				updateConfirmedDo(store);
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

	private void saveChainBlockQueue(Block block, FullBlockStore store, boolean orphan) throws BlockStoreException {
		// save the block
		try {
			store.beginDatabaseBatchWrite();
			ChainBlockQueue chainBlockQueue = new ChainBlockQueue(block.getHash().getBytes(),
					Gzip.compress(block.unsafeBitcoinSerialize()), block.getLastMiningRewardBlock(), orphan,
					block.getTimeSeconds());
			store.insertChainBlockQueue(chainBlockQueue);
			store.commitDatabaseBatchWrite();
		} catch (Exception e) {
			store.abortDatabaseBatchWrite();
			throw e;
		} finally {
			store.defaultDatabaseBatchWrite();

		}
	}

	/*
	 *  
	 */
	public void processChainConnected(FullBlockStore store, boolean updatelowchain, boolean throwException)
			throws VerificationException, BlockStoreException {
		List<ChainBlockQueue> cbs = store.selectChainblockqueue(false, serverConfiguration.getSyncblocks());
		if (cbs != null && !cbs.isEmpty()) {
			Stopwatch watch = Stopwatch.createStarted();
			log.info("selectChainblockqueue with size  " + cbs.size());
			// check only do add if there is longer chain as saved in database
			TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
			ChainBlockQueue maxFromQuery = cbs.get(cbs.size() - 1);
			if (!updatelowchain && maxConfirmedReward.getChainLength() > maxFromQuery.getChainlength()) {
				log.info("not longest chain in  selectChainblockqueue {}  < {}", maxFromQuery.toString(),
						maxConfirmedReward.toString());
				return;
			}
			for (ChainBlockQueue chainBlockQueue : cbs) {
				if (throwException) {
					saveChainConnected(chainBlockQueue, store);
				} else {
					try {
						saveChainConnected(chainBlockQueue, store);
					} catch (Exception e) {
						log.info("saveChainConnected failed   " + chainBlockQueue.toString(), e);
					}
				}

			}
			log.info("saveChainConnected time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
		}

	}

	private void saveChainConnected(ChainBlockQueue chainBlockQueue, FullBlockStore store)
			throws VerificationException, BlockStoreException {
		try {
			// It can be down lock for update of this on database
			Block block = networkParameters.getDefaultSerializer().makeBlock(chainBlockQueue.getBlock());
			saveChainConnected(block, store);
		} finally {
			deleteChainQueue(chainBlockQueue, store);
		}
	}

	private void saveChainConnected(Block block, FullBlockStore store)
			throws VerificationException, BlockStoreException {
		try {
			store.beginDatabaseBatchWrite();

			// Check the block is partially formally valid and fulfills PoW
			block.verifyHeader();
			block.verifyTransactions();

			// Solidify referenced blocks
			RewardInfo currRewardInfo = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());

			// Solidify referenced blocks
			try {
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
						.solidifyBlocks(currRewardInfo, store);
			} catch (MissingDependencyException e) {
				log.warn("Block isFailState. MissingDependencyException" + block.toString()
						+ " MissingDependencyException" + e.toString());
				return;
			}
			SolidityState solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters,
					cacheBlockService).checkChainSolidity(block, true, store);

			if (solidityState.isDirectlyMissing()) {
				log.debug("Block isDirectlyMissing. saveChainConnected stop to save." + block.toString());
				// sync the lastest chain from remote start from the -2 rewards
				// syncBlockService.startSingleProcess(block.getLastMiningRewardBlock()
				// - 2, false);
				return;
			}

			if (solidityState.isFailState()) {
				log.warn("Block isFailState. remove it from ChainBlockQueue." + block.toString());
				return;
			}
			// Inherit solidity from predecessors if they are not solid
			// solidityState = serviceBase.getMinPredecessorSolidity(block, false, store);

			// Sanity check
			if (solidityState.isFailState() || solidityState.getState() == State.MissingPredecessor) {
				log.debug("Block isFailState. remove it from ChainBlockQueue." + block.toString());
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

	private void deleteChainQueue(ChainBlockQueue chainBlockQueue, FullBlockStore store) throws BlockStoreException {
		List<ChainBlockQueue> l = new ArrayList<ChainBlockQueue>();
		l.add(chainBlockQueue);
		store.deleteChainBlockQueue(l);
	}

	public boolean addNonChain(Block block, boolean allowUnsolid, FullBlockStore blockStore)
			throws BlockStoreException {
		return addNonChain(block, allowUnsolid, blockStore, false);
	}

	public boolean addNonChain(Block block, boolean allowUnsolid, FullBlockStore blockStore,
			boolean allowMissingPredecessor) throws BlockStoreException {

//		if( block.getHeight()==9) {
//	 		log.debug(block.toString());
//		}
		// Check the block is partially formally valid and fulfills PoW

		block.verifyHeader();
		block.verifyTransactions();

		// allow non chain block predecessors not solid
		SolidityState solidityState = new SolidityState(State.Success, null, false);
		try {
			solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService)
					.checkSolidity(block, !allowUnsolid, blockStore, allowMissingPredecessor);
		} catch (Exception e) {
			if (!allowUnsolid)
				throw e;
		}
		if (solidityState.isFailState()) {
			log.debug(solidityState.toString() + " block " + block.toString());
		}
		// If explicitly wanted (e.g. new block from local clients), this
		// block must strictly be solid now.
		if (!allowUnsolid) {
			switch (solidityState.getState()) {
			case MissingPredecessor:
				if (!allowMissingPredecessor)
					throw new UnsolidException();
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

	public Set<Sha256Hash> checkMissing(Block block, FullBlockStore blockStore) throws BlockStoreException {

		// missing predecessors
		return new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
				.getMissingPredecessors(block, blockStore);

	}

	private void connectRewardBlock(final Block block, SolidityState solidityState, FullBlockStore store)
			throws BlockStoreException, VerificationException {

		if (solidityState.isFailState()) {
			connect(block, solidityState, store);
			return;
		}
		Block head = store.get(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
		if (block.getRewardInfo().getPrevRewardHash().equals(head.getHash())) {
			connect(block, solidityState, store);
			new ServiceBaseReward(serverConfiguration, networkParameters, cacheBlockService)
					.checkRewardChainConfirmReferenced(block, store);
		} else {
			// This block connects to somewhere other than the top of the best
			// known chain. We treat these differently.

			boolean haveNewBestChain = block.getRewardInfo().getChainlength() > head.getRewardInfo().getChainlength();
			// TODO check this
			// block.getRewardInfo().moreWorkThan(head.getRewardInfo());
			if (haveNewBestChain) {
				log.info("Block is causing a re-organize");
				connect(block, solidityState, store);
				new ServiceBaseReward(serverConfiguration, networkParameters, cacheBlockService)
						.handleNewBestChain(block, store);
			} else {
				// parallel chain, save as unconfirmed
				connect(block, solidityState, store);
			}

		}
	}

	/**
	 * Inserts the specified block into the DB
	 * 
	 * @param block         the block
	 * @param solidityState
	 * @param height        the block's height
	 * @throws BlockStoreException
	 * @throws VerificationException
	 */
	private void connect(final Block block, SolidityState solidityState, FullBlockStore store)
			throws BlockStoreException, VerificationException {
		store.put(block);
		cacheBlockService.cacheBlock(block, store);
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService).solidifyBlock(block,
				solidityState, false, store);
	}

	// TODO update other output data can be deadlock, as non chain block
	// run in parallel
	private void updateTransactionOutputSpendPending(Block block) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit(new Callable() {
			@Override
			public String call() throws Exception {
				return updateTransactionOutputSpendPendingDo(block);
			}
		});
		try {
			handler.get(2000l, TimeUnit.MILLISECONDS);
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
		FullBlockStore blockStore = storeService.getStore();
		try {
			updateTransactionOutputSpendPending(block, blockStore);
			new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
					.evictTransactionsAndBlockEva(block, blockStore);
			// Initialize MCMC
			if (blockStore.getMCMC(block.getHash()) == null) {
				ArrayList<DepthAndWeight> depthAndWeight = new ArrayList<DepthAndWeight>();
				depthAndWeight.add(new DepthAndWeight(block.getHash(), 1, 0));
				blockStore.updateBlockEvaluationWeightAndDepth(depthAndWeight);
			}
		} finally {
			if (blockStore != null)
				blockStore.close();
		}
		return "";
	}

	private void updateTransactionOutputSpendPending(Block block, FullBlockStore blockStore)
			throws BlockStoreException {
		for (final Transaction tx : block.getTransactions()) {
			boolean isCoinBase = tx.isCoinBase();
			List<UTXO> spendPending = new ArrayList<UTXO>();
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

	public void updateConfirmedDo(FullBlockStore blockStore) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService);
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(blockStore);

		try {
			blockStore.beginDatabaseBatchWrite();
			// First remove any blocks that should no longer be in the milestone
			HashSet<BlockEvaluation> blocksToRemove = blockStore.getBlocksToUnconfirm();

			HashSet<BlockWrap> blocksToRemoveBlocks = new HashSet<BlockWrap>();
		
			for (BlockEvaluation b : blocksToRemove) {
				blocksToRemoveBlocks.add(serviceBase.getBlockWrap(b.getBlockHash(), blockStore));
			}
			serviceBase.unconfirmBlocksSorted(blockStore, -1, blocksToRemoveBlocks, new HashSet<>());

			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}

		try {
			blockStore.beginDatabaseBatchWrite();

			long cutoffHeight = serviceBase.getCurrentCutoffHeight(maxConfirmedReward, blockStore);
			long maxHeight = serviceBase.getCurrentMaxHeight(maxConfirmedReward, blockStore);

			// Now try to find blocks that can be added to the milestone.
			// DISALLOWS UNSOLID
			TreeSet<BlockWrap> blocksToAdd = blockStore.getBlocksToConfirm(cutoffHeight, maxHeight);

			// VALIDITY CHECKS
			serviceBase.resolveAllConflicts(blocksToAdd, cutoffHeight, blockStore);

			// Execute must be chained for confirm
			// Finally add the resolved new blocks to the confirmed set
			serviceBase.confirmBlocksSorted(blockStore, -1,
					serviceBase.collectExecutionChained(blockStore, Collections.synchronizedSet(blocksToAdd)),
					new HashSet<>());

			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}

	}

	private void updateConfirmed() throws BlockStoreException {
		String LOCKID = "chain";
		int LockTime = 1000000;
		FullBlockStore store = storeService.getStore();
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
						log.info("updateConfirmed running start = " + Utils.dateTimeFormat(lock.getLocktime()));
				}
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				updateConfirmedDo(store);
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

	public void cleanUp(FullBlockStore store) throws BlockStoreException {
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		cleanUpDo(maxConfirmedReward, store);
	}

	public void cleanUpDo(TXReward maxConfirmedReward, FullBlockStore store) throws BlockStoreException {

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
	private void updateConfirmedTimeBoxed() throws BlockStoreException {

		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit(new Callable() {
			@Override
			public String call() throws Exception {
				updateConfirmed();
				return "";
			}
		});
		try {
			handler.get(3000l, TimeUnit.MILLISECONDS);
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
