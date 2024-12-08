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
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceContract;
import net.bigtangle.store.FullBlockStore;
import net.bigtangle.store.FullBlockStoreImpl;

/**
 * <p>
 * A ContractExecutionService provides service for create and validate the
 * contract common execution.
 * </p>
 */
@Service
public class ContractExecutionService {

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

	// createContractExecution is time boxed and can run parallel.
	public void startSingleProcess() throws BlockStoreException {

		FullBlockStore store = storeService.getStore();

		try {
			// log.info("create ContractExecution started");
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else if (lock.getLocktime() < System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate()) {
                log.info(" ContractExecution locked is fored delete   {} < {}", lock.getLocktime(), System.currentTimeMillis() - 5 * scheduleConfiguration.getMiningrate());
				store.deleteLockobject(LOCKID);
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else {
				// log.info("ContractExecution running return: " +
				// Utils.dateTimeFormat(lock.getLocktime()));
			}
			if (canrun) {
				createContractExecution(store);
				store.deleteLockobject(LOCKID);
			}

		} catch (Exception e) {
			log.error("create ContractExecution end  ", e);
			store.deleteLockobject(LOCKID);
		} finally {
			store.close();
		}

	}

	public void createContractExecution(FullBlockStore store) throws Exception {

		// select all contractid from the table with unspent event
		for (Token contract  : getOpenContract(store)) {
			Block contractExecution = createContractExecution(contract, store);
			if (contractExecution != null) {
                log.debug(" contractExecution block is created: {}", contractExecution);
				blockSaveService.saveBlock(contractExecution, store);
			}
		}

	}
	//Add valid check
	public List<Token> getOpenContract(FullBlockStore store) throws Exception {
	 return store.getTokenTypeList(TokenType.contract.ordinal());
	
	}
	/**
	 * Runs the ContractExecution making logic
	 * 
	 * @return the new block or block voted on
     */

	public Block createContractExecution(Token contract, FullBlockStore store) throws Exception {

		// Stopwatch watch = Stopwatch.createStarted();
		Block b = cacheBlockPrototypeService.getBlockPrototype(store);
		// log.debug(" getValidatedContractExecutionBlockPair time {} ms.",
		// watch.elapsed(TimeUnit.MILLISECONDS));
		return createContractExecution(b, contract, store);

	}

	public Block createContractExecution(Block block, Token contract, FullBlockStore store)
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException, IOException {

		block.setBlockType(Block.Type.BLOCKTYPE_CONTRACT_EXECUTE);
		// Build transaction for block
		Transaction tx = new Transaction(networkParameters);
		block.addTransaction(tx);
		// collect the order block as reference
		// Read previous reward block's data
		Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
		long prevChainLength = block.getLastMiningRewardBlock();
		Set<BlockWrap> referencedblocks = new HashSet<>();
		long cutoffheight = blockService.getRewardCutoffHeight(prevRewardHash, store);

		List<Block.Type> referencedOrdertypes = new ArrayList<>();
		referencedOrdertypes.add(Block.Type.BLOCKTYPE_CONTRACT_EVENT);
		referencedOrdertypes.add(Block.Type.BLOCKTYPE_CONTRACTEVENT_CANCEL);

		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService);
		// add all blocks of dependencies

		serviceBase.addReferencedBlockHashesTo(referencedblocks,
				blockService.getBlockWrap(block.getPrevBlockHash(), store), cutoffheight, prevChainLength, true,
				referencedOrdertypes, true, store);
		serviceBase.addReferencedBlockHashesTo(referencedblocks,
				blockService.getBlockWrap(block.getPrevBranchBlockHash(), store), cutoffheight, prevChainLength, true,
				referencedOrdertypes, true, store);

		Contractresult prevMilestone = store.getMaxMilestoneContractresult(contract.getTokenid());

		Contractresult prevMilestoneExecution = prevMilestone == null ? Contractresult.firstContractresult()
				: prevMilestone;
		List<Contractresult> prevNotMilestons = store.getConfirmedContractresultNotMilestone(contract.getTokenid());

		Set<BlockWrap> prevsNotMilestoneChainedBlocks = serviceBase.collectPrevsChain(prevNotMilestons,
				prevMilestoneExecution, store);
		// take last NotMilestons chain confirmed and set others as not confirmed

		Contractresult lastExecution = prevMilestoneExecution;
		if (!prevsNotMilestoneChainedBlocks.isEmpty()) {
			lastExecution = getLast(prevsNotMilestoneChainedBlocks, store); 
			unconfimedNonChained(prevsNotMilestoneChainedBlocks, prevNotMilestons, store, serviceBase);
		}

		Set<BlockWrap> collectNotSpents = collectNotAreadyCollected(referencedblocks, prevsNotMilestoneChainedBlocks);

		ContractExecutionResult result = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService)
				.executeContract(block, store, contract, lastExecution, serviceBase.getHashSet(collectNotSpents));

		// do not create the execution block, if there is no new referencedblocks
		if (result == null || (result.getOutputTx().getOutputs().isEmpty() && collectNotSpents.isEmpty()))
			return null;

		tx.setData(result.toByteArray());

		blockService.adjustHeightRequiredBlocks(block, store);

		return blockSolve(block, Utils.decodeCompactBits(block.getDifficultyTarget()));
	}

	//
	protected void unconfimedNonChained(Set<BlockWrap> prevsNotMilestoneChainedBlocks,
			List<Contractresult> prevNotMilestons, FullBlockStore store, ServiceBaseConnect serviceBase)
			throws BlockStoreException {
		// find the longest chained execution connected to last milestone
		for (Contractresult prevNotMilestone : prevNotMilestons) {
			if (prevsNotMilestoneChainedBlocks.stream()
					.noneMatch(n -> n.getBlockHash().equals(prevNotMilestone.getBlockHash()))) {
				serviceBase.confirmContractExecute(serviceBase.getBlock(prevNotMilestone.getBlockHash(), store),-1,true,
						store);
			}

		}

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

	protected Contractresult getLast(Set<BlockWrap> prevs, FullBlockStore store)
			throws BlockStoreException {
		BlockWrap re = null;
		for (BlockWrap b : prevs) {
			if (re == null)
				re = b;
			else {
				if (b.getBlock().getHeight() > re.getBlock().getHeight())
					re = b;
			}
		}

		return store.getContractresult(re.getBlock().getHash());
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
		// log.debug("contractExecution Solved time {} ms.",
		// watch.elapsed(TimeUnit.MILLISECONDS));
		return block;
	}

}
