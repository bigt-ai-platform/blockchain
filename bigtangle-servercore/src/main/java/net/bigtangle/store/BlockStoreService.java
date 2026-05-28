/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.util.ArrayList;
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
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.server.config.MinioConfig;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.data.SolidityState.State;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.base.MinioService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceVerifyReward;
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
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected ObjectMapper jsonmapper;

	@Autowired
	protected MinioConfig minioConfig;
	
	public boolean addBlock(Block block, boolean allowUnsolid, BlockStoreInterface store) throws BlockStoreException {
		boolean added;
		if (block.getBlockType() == BlockType.BLOCKTYPE_REWARD) {
			added = addChain(block, store);
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
	public void addFromSync(Block block, boolean allowUnsolid, BlockStoreInterface store) throws BlockStoreException {

		if (block.getBlockType() == BlockType.BLOCKTYPE_REWARD) {
			addChain(block, store);
		} else {
			addNonChain(block, allowUnsolid, store, true);
		}

	}

	public boolean addChain(Block block, BlockStoreInterface store) throws BlockStoreException {

		// Check the block is partially formally valid and fulfills PoW
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
		// first undo the mc confirm for calculation chain confirm
		updateChainConnected();
		if (confirmTimebox)
			updateConfirmedTimeBoxed();
		else
			updateConfirmed();
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
				} else {
					// if (lock.getLocktime() < System.currentTimeMillis() - 10000)
					// log.info("updateChain running start = " +
					// Utils.dateTimeFormat(lock.getLocktime()));
				}
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				updateUnConfirmedDo(store);
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
					Gzip.compress(block.unsafeBitcoinSerialize()), block.getLastMiningRewardBlock(), orphan,
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
		try {
			// It can be down lock for update of this on database
			Block block = networkParameters.getDefaultSerializer().makeBlock(chainBlockQueue.getBlock());
			saveChainConnected(block, store);
		} finally {
			deleteChainQueue(chainBlockQueue, store);
		}
	}

	private void saveChainConnected(Block block, BlockStoreInterface store)
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
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
						.solidifyBlocks(currRewardInfo, store);
			} catch (MissingDependencyException e) {
				log.warn("Block isFailState. MissingDependencyException{} MissingDependencyException{}", block, e);
				return;
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

//		if( block.getHeight()==9) {
 	 	 	log.debug("addNonChain"+ block.toString());
		//	log.debug("addNonChain bin="+Utils.HEX.encode( block.unsafeBitcoinSerialize()) );
//		}
		// Check the block is partially formally valid and fulfills PoW

		block.verifyHeader();
		block.verifyTransactions();

		// allow non chain block predecessors not solid
		SolidityState solidityState = new SolidityState(State.Success, null, false);
		try {
			solidityState = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.checkSolidity(block, !allowUnsolid, blockStore, allowMissingPredecessor);
		} catch (Exception e) {
			if (!allowUnsolid)
				throw e;
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
				cacheBlockService, jsonmapper);
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
		cacheBlockService.cachePutBlock(block, store);
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyBlock(block, solidityState, false, store);
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

	public void confirmDo(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight, long prevMilestoneNumber)
			throws BlockStoreException {

		confirmDo(blockStore, cutoffHeight, blockStore.getBlocksToConfirm(cutoffHeight, maxHeight), true,
				prevMilestoneNumber);
	}

	public void confirmDo(BlockStoreInterface blockStore, long cutoffHeight, Set<BlockWrap> blocksToAdd,
			boolean resolveConflict, long prevMilestoneNumber) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();
			Set<BlockWrap> blocks = new HashSet<>();
			// use the add to filter and check
			for (BlockWrap b : blocksToAdd) {
				serviceBase.dagBlockHashesFrom(blocks, b, cutoffHeight, prevMilestoneNumber, null, true, false,
						blockStore);
			}
			// Set<BlockWrap> toAdd = serviceBase.addUnconfirmBlocksChainedPrev(blockStore,
			// blocks);
			// if (resolveConflict) {
			// VALIDITY CHECKS, remove the conflicts
			// serviceBase.resolveAllConflicts(blocksToAdd, cutoffHeight, blockStore);
			// }
			// Execute must be chained for confirm
			serviceBase.checkExecutionChained(blockStore, blocks);

			// Finally add the resolved new blocks to the confirmed set
			serviceBase.confirmBlocksSorted(blockStore, -1, true, blocks, new HashSet<>());

			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}
	}

	public void unconfirmDo(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight)
			throws BlockStoreException {
		updateMilestoneConflicts(blockStore, cutoffHeight, maxHeight);
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();

			// Unconfirm anything not confirmed by milestone
			List<Sha256Hash> wipeBlocks = blockStore.blocksNotMilestoneFromHeigth(cutoffHeight);

			HashSet<BlockWrap> blocksToUnconfirm = new HashSet<>();

			for (Sha256Hash b : wipeBlocks) {
				BlockWrap block = serviceBase.getBlockWrap(b, blockStore);
				if (checkChainHeadExecution(block.getBlock(), serviceBase, blockStore)) {
					blocksToUnconfirm.add(block);
				}
			}
			// Set<BlockWrap> unconfirmBlocksChainedFollow =
			// serviceBase.addUnconfirmBlocksChainedFollow(blockStore, blocksToUnconfirm);
			// log.debug("unconfirmDo size= " + unconfirmBlocksChainedFollow.size());
			serviceBase.unconfirmBlocksSorted(blockStore, blocksToUnconfirm, new HashSet<>(), true);

			blockStore.commitDatabaseBatchWrite();
		} catch (Exception e) {
			blockStore.abortDatabaseBatchWrite();
			throw e;
		} finally {
			blockStore.defaultDatabaseBatchWrite();
		}
	}

	public boolean checkChainHeadExecution(Block block, ServiceBaseConnect serviceBase, BlockStoreInterface store)
			throws BlockStoreException {

		switch (block.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			ContractExecutionResult c = new ContractExecutionResult()
					.parseChecked(block.getTransactions().get(0).getData());
			Block head = serviceBase
					.getBlock(store.getMaxConfirmedContractresult(c.getContracttokenid()).getBlockHash(), store);
			return head == null || serviceBase.getExecuteChainlength(block) >= serviceBase.getExecuteChainlength(head);
		case BLOCKTYPE_ORDER_EXECUTE:
			Block headorder = serviceBase.getBlock(store.getMaxConfirmedOrderresult().getBlockHash(), store);
			return headorder == null
					|| serviceBase.getExecuteChainlength(block) >= serviceBase.getExecuteChainlength(headorder);
		default:
			return true;
		}

	}

	/*
	 * set the execute as obsolete as solid to the chained
	 */
	public void updateChainHeadExecutionObsolete(Block block, ServiceBaseConnect serviceBase, BlockStoreInterface store)
			throws BlockStoreException {

		int backsteps = 5;
		switch (block.getBlockType()) {
		case BLOCKTYPE_CONTRACT_EXECUTE:
			ContractExecutionResult c = new ContractExecutionResult()
					.parseChecked(block.getTransactions().get(0).getData());
			Block head = serviceBase
					.getBlock(store.getMaxMilestoneContractresult(c.getContracttokenid()).getBlockHash(), store);

			if (head != null
					&& serviceBase.getExecuteChainlength(block) < serviceBase.getExecuteChainlength(head) + backsteps) {
				store.updateBlockEvaluationSolid(block.getHash(), -1 * serviceBase.getExecuteChainlength(head));
			}
			;
		case BLOCKTYPE_ORDER_EXECUTE:
			Block headorder = serviceBase.getBlock(store.getMaxMilestoneOrderresult().getBlockHash(), store);
			if (headorder != null
					&& serviceBase.getExecuteChainlength(block) < serviceBase.getExecuteChainlength(headorder)
							+ backsteps) {
				store.updateBlockEvaluationSolid(block.getHash(), -1 * serviceBase.getExecuteChainlength(headorder));
			}
		default:
		}

	}

	public void updateMilestoneConflicts(BlockStoreInterface blockStore, long cutoffHeight, long maxHeight)
			throws BlockStoreException {

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		try {
			blockStore.beginDatabaseBatchWrite();
			// First remove any blocks that should no longer be in the milestone
			// HashSet<BlockEvaluation> blocksToRemove = blockStore.getBlocksToUnconfirm();

			// Unconfirm anything not confirmed by milestone
			List<Sha256Hash> wipeBlocks = blockStore.blocksNotMilestoneFromHeigth(cutoffHeight);

			HashSet<BlockWrap> blocksToUnconfirm = new HashSet<>();

			for (Sha256Hash b : wipeBlocks) {
				BlockWrap re = serviceBase.getBlockWrap(b, blockStore);
				updateChainHeadExecutionObsolete(re.getBlock(), serviceBase, blockStore);
				blocksToUnconfirm.add(re);
			}
			serviceBase.updateMilestoneConflicts(blocksToUnconfirm, blockStore);
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
