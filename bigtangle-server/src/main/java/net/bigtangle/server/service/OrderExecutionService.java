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

import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceOrderExecution;
import net.bigtangle.store.FullBlockStore;
import net.bigtangle.store.FullBlockStoreImpl;

/**
 * <p>
 * A OrderExecutionService provides service for create and validate the contract
 * common execution.
 * </p>
 */
@Service
public class OrderExecutionService {

	@Autowired
	protected FullBlockStoreImpl blockGraph;
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

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final String LOCKID = this.getClass().getName();

	/**
	 * Scheduled update function that updates the Tangle
	 *
     */

	// createOrderExecution is time boxed and can run parallel.
	public void startSingleProcess() throws BlockStoreException {

		FullBlockStore store = storeService.getStore();

		try {
			// log.info("create OrderExecution started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else if (lock.getLocktime() < System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate()) {
                log.info(" OrderExecution locked is fored delete   {} < {}", lock.getLocktime(), System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate());
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

	public Block createOrderExecution(FullBlockStore store) throws Exception {
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

	public Block createOrderExecutionDo(FullBlockStore store) throws Exception {

		// Stopwatch watch = Stopwatch.createStarted();
		Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        if (new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
				.enableOrderMatchExecutionChain(b)) {

			return createOrderExecution(b, store);
		} else {
			return null;
		}

	}

	public Block createOrderExecution(Block block, FullBlockStore store)
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException, IOException {
		block.setBlockType(Block.Type.BLOCKTYPE_ORDER_EXECUTE);
		// Build transaction for block
		Transaction tx = new Transaction(networkParameters);
		block.addTransaction(tx);

		// Read previous reward block's data
		Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
		long prevChainLength = block.getLastMiningRewardBlock();
		Set<BlockWrap> referencedblocks = new HashSet<>();

		long cutoffheight = blockService.getRewardCutoffHeight(prevRewardHash, store);

		List<Block.Type> ordertypes = new ArrayList<>();
		ordertypes.add(Block.Type.BLOCKTYPE_ORDER_CANCEL);
		ordertypes.add(Block.Type.BLOCKTYPE_ORDER_OPEN);
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService);
		// add all blocks of dependencies
		serviceBase.addReferencedBlockHashesTo(referencedblocks,
				blockService.getBlockWrap(block.getPrevBlockHash(), store), cutoffheight, prevChainLength,
                ordertypes, true, store);
		serviceBase.addReferencedBlockHashesTo(referencedblocks,
				blockService.getBlockWrap(block.getPrevBranchBlockHash(), store), cutoffheight, prevChainLength,
                ordertypes, true, store);

		Orderresult prevMilestone = store.getMaxMilestoneOrderresult();

		Orderresult prevMilestoneExecution = prevMilestone == null ? Orderresult.zeroOrderresult() : prevMilestone;

		List<Orderresult> prevNotMilestons = store.getConfirmedOrderresultNotMilestone();

		Set<BlockWrap> prevsNotMilestoneChainedBlocks = serviceBase.collectPrevsChain(prevNotMilestons,
				prevMilestoneExecution, store);
		// take last NotMilestons chain confirmed and set others as not confirmed

		Orderresult lastExecution = prevMilestoneExecution;
		if (!prevsNotMilestoneChainedBlocks.isEmpty()) {
			lastExecution = getLast(prevsNotMilestoneChainedBlocks, store);
			unconfimedNonChained(prevsNotMilestoneChainedBlocks, prevNotMilestons, store, serviceBase);
		}

		Set<BlockWrap> collectNotSpents = collectNotAreadyCollected(referencedblocks, prevsNotMilestoneChainedBlocks);

		OrderExecutionResult result = new ServiceOrderExecution(serverConfiguration, networkParameters,
				cacheBlockService).orderMatching(block, lastExecution, serviceBase.getHashSet(collectNotSpents), store);

		// do not create the execution block, if there is no new referencedblocks and no
		// match
		if (result == null || (result.getOutputTx().getOutputs().isEmpty() && collectNotSpents.isEmpty()))
			return null;

		tx.setData(result.toByteArray());

		blockService.adjustHeightRequiredBlocks(block, store);

		return blockSolve(block, Utils.decodeCompactBits(block.getDifficultyTarget()));
	}

	//
	protected void unconfimedNonChained(Set<BlockWrap> prevsNotMilestoneChainedBlocks,
			List<Orderresult> prevNotMilestons, FullBlockStore store, ServiceBaseConnect serviceBase)
			throws BlockStoreException {
		// find the longest chained execution connected to last milestone
		for (Orderresult prevNotMilestone : prevNotMilestons) {
			if (prevsNotMilestoneChainedBlocks.stream()
					.noneMatch(n -> n.getBlockHash().equals(prevNotMilestone.getBlockHash()))) {
				serviceBase.confirmOrderExecute(serviceBase.getBlock(prevNotMilestone.getBlockHash(), store), -1,
						false, store);
			}

		}

	}

	protected Orderresult getLast(Set<BlockWrap> prevs, FullBlockStore store) throws BlockStoreException {
		BlockWrap re = null;
		for (BlockWrap b : prevs) {
			if (re == null)
				re = b;
			else {
				if (b.getBlock().getHeight() > re.getBlock().getHeight())
					re = b;
			}
		}

		return store.getOrderResult(re.getBlock().getHash());
	}

	protected Set<BlockWrap> collectNotAreadyCollected(Set<BlockWrap> collectedBlocks, Set<BlockWrap> prevs)
			throws IOException {
		Set<BlockWrap> collectNews = new HashSet<>();
		Set<Sha256Hash> alreadyCollected = collectNotSpentFrom(prevs);
		for (BlockWrap b : collectedBlocks) {
			// check height
			if (!alreadyCollected.contains(b.getBlockHash())) {
				collectNews.add(b);
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
