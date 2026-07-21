/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceOrderExecution;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/**
 * <p>
 * A OrderExecutionService provides service for create and validate the contract
 * common execution.
 * </p>
 */
@Service
public class OrderExecutionService {

	@Autowired
	protected BlockStoreService blockGraph;
	@Autowired
	private BlockService blockService;
	@Autowired
	protected ServerConfiguration serverConfiguration;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	private StoreService storeService;
	@Autowired
	private ScheduleConfiguration scheduleConfiguration;
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	private BlockSaveService blockSaveService;
	@Autowired
	protected ObjectMapper jsonmapper;
	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final String LOCKID = this.getClass().getName();

	/**
	 * Scheduled update function that updates the Tangle
	 *
	 */

	// createOrderExecution is time boxed and can run parallel.
	public void startSingleProcess() throws BlockStoreException {

		BlockStoreInterface store = storeService.getStore();

		try {
			// log.info("create OrderExecution started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else if (lock.getLocktime() < System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate()) {
				log.info(" OrderExecution locked is fored delete   {} < {}", lock.getLocktime(),
						System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate());
				store.deleteLockobject(LOCKID);
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else {
				// log.info("OrderExecution running return: " +
				// Utils.dateTimeFormat(lock.getLocktime()));
			}
			if (canrun) {
				createOrderExecution(store);
				store.deleteLockobject(LOCKID);
			}

		} catch (Exception e) {
			log.error("create OrderExecution end  ", e);
			store.deleteLockobject(LOCKID);
		} finally {
			store.close();
		}

	}

	public Block createOrderExecution(BlockStoreInterface store) throws Exception {
		Block contractExecution = createOrderExecutionDo(store);
		if (contractExecution != null) {
			// log.debug(" createOrder block is created: " + contractExecution);
			blockSaveService.saveBlock(contractExecution, store);
			return contractExecution;
		}
		return null;
	}

	/**
	 * Runs the OrderExecution making logic
	 * 
	 * @return the new block or block voted on
	 */

	public Block createOrderExecutionDo(BlockStoreInterface store) throws Exception {

		// Stopwatch watch = Stopwatch.createStarted();
		try {
			Block b = cacheBlockPrototypeService.getBlockPrototype(store);

			if (new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
					.enableOrderMatchExecutionChain(b)) {

				return createOrderExecution(b, store);
			} else {
				return null;
			}
		} catch (Exception e) {
			return null;
		}
	}

	public Block createOrderExecution(Block block, BlockStoreInterface store)
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException, IOException {
		block.setBlockType(BlockType.BLOCKTYPE_ORDER_EXECUTE);
		// Build transaction for block
		Transaction tx = new Transaction(networkParameters);
		block.addTransaction(tx);

		// Read previous reward block's data
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long prevChainLength = block.getLastMiningRewardBlock();
		Set<BlockWrap> referencedblocks = new HashSet<>();

		long cutoffheight = serviceBase.getCurrentCutoffHeight(cacheBlockService.getMaxConfirmedReward(store), store);

		List<BlockType> ordertypes = new ArrayList<>();
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_CANCEL);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_OPEN);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_EXECUTE);
		// add all blocks of dependencies
		serviceBase.dagBlockHashesFrom(referencedblocks, blockService.getBlockWrap(block.getPrevBlockHash(), store),
				cutoffheight, prevChainLength, ordertypes, true, false, store);
		serviceBase.dagBlockHashesFrom(referencedblocks,
				blockService.getBlockWrap(block.getPrevBranchBlockHash(), store), cutoffheight, prevChainLength,
				ordertypes, true, false, store);

		Orderresult prevMilestoneExecution = store.getMaxMilestoneOrderresult();

		Orderresult lastConfirmedExecution = store.getMaxConfirmedOrderresult();
		// only the lastConfirmedExecution is in the referencedblocks as DAG
		if (serviceBase.findBlock(referencedblocks, lastConfirmedExecution.getBlockHash()) == null) {
			if (lastConfirmedExecution.getChainlength() != 0 && lastConfirmedExecution.getMilestone() < 0) {
				log.debug("lastConfirmedExecution not in referencedblocks {}", referencedblocks.size());
				log.debug("lastConfirmedExecution  block {}",
						blockService.getBlockWrap(lastConfirmedExecution.getBlockHash(), store));
				return null;
			}
		}
		// referencedblocks may be empty, but there are order matching valid from
		Set<BlockWrap> prevsNotMilestoneChainedBlocks = serviceBase.collectPrevsOrderChain(lastConfirmedExecution,
				prevMilestoneExecution, store);
		Set<BlockWrap> collectNotSpents = collectNotAreadyCollected(referencedblocks, prevsNotMilestoneChainedBlocks);

		OrderExecutionResult result = new ServiceOrderExecution(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper)
				.orderMatching(block, lastConfirmedExecution, serviceBase.getHashSet(collectNotSpents), store);

		// do not create the execution block, if there is no new referencedblocks and no
		// match
		if (result == null || (result.getOutputTx().getOutputs().isEmpty() && collectNotSpents.isEmpty()))
			return null;

		tx.setData(result.toByteArray());

		blockService.adjustHeightRequiredBlocks(block, store);

		return blockSolve(block, Utils.decodeCompactBits(block.getDifficultyTarget()));
	}

	protected Set<BlockWrap> collectNotAreadyCollected(Set<BlockWrap> collectedBlocks, Set<BlockWrap> prevs)
			throws IOException {
		Set<BlockWrap> collectNews = new HashSet<>();
		Set<Sha256Hash> alreadyCollected = collectNotSpentFrom(prevs);
		for (BlockWrap b : collectedBlocks) {
			if (!b.getBlock().getBlockType().equals(BlockType.BLOCKTYPE_ORDER_EXECUTE)) {
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
			OrderExecutionResult result = new OrderExecutionResult()
					.parse(b.getBlock().getTransactions().get(0).getData());

			collectOrdersNoSpents.addAll(result.getReferencedBlocks());
		}
		return collectOrdersNoSpents;
	}

	private Block blockSolve(Block block, final BigInteger chainTargetFinal)
			throws InterruptedException, ExecutionException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit((Callable) () -> {
			// log.debug(" contractExecution block solve started : " + chainTargetFinal + "
			// \n for block" + block);
			block.solve(chainTargetFinal);
			return "";
		});
		Stopwatch watch = Stopwatch.createStarted();
		try {
			handler.get(scheduleConfiguration.getMiningrate(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.debug(" contractExecution solve Timeout  {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
			handler.cancel(true);
			return null;
		} finally {
			executor.shutdownNow();
		}
		return block;
	}

}
