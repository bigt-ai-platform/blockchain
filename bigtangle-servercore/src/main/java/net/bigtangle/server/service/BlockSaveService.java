package net.bigtangle.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.kafka.KafkaConfiguration;
import net.bigtangle.kafka.KafkaMessageProducer;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.data.TipsQueue;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/**
 * <p>
 * Provides services for blocks.
 * </p>
 */
@Service
public class BlockSaveService {

	@Autowired
	protected StoreService storeService;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	BlockStoreService blockgraph;
	@Autowired
	protected KafkaConfiguration kafkaConfiguration;
	@Autowired
	protected ServerConfiguration serverConfiguration;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	@Autowired
	protected MempoolService mempoolService;
	@Autowired
	protected FeeService feeService;
	@Autowired
	protected ScheduleConfiguration scheduleConfiguration;
	private static final Logger logger = LoggerFactory.getLogger(BlockSaveService.class);

	public static int BATCH_TX_PER_BLOCK = 50000; // adjustable for testing
	private static final ExecutorService parallelBatchPool = Executors.newFixedThreadPool(
			Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

	public void saveBlock(Block block, BlockStoreInterface store) throws Exception {
		blockgraph.addBlock(block, false, store);
		broadcastBlock(block);
	}

	/** Batch variant: skips transaction re-verification, solidity checks,
	 *  Minio object storage, AND cache operations.  Batch blocks are
	 *  transient mempool dumps that don't need archival — the PostgreSQL
	 *  row alone suffices for the MCMC bridge. */
	public void saveBatchBlock(Block block, BlockStoreInterface store) throws Exception {
		try (AutoCloseable flag = net.bigtangle.store.DatabaseFullBlockStoreBase.skipMinioForBatch();
		     AutoCloseable cacheFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.skipCacheForBatch();
		     AutoCloseable copyFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.usePgCopyForBatch()) {
			blockgraph.addNonChain(block, true, store, true, true);
		}
		broadcastBlock(block);
	}

	public void broadcastBlock(Block block) {
		broadcastBytes(block.bitcoinSerialize());
	}

	public void broadcastTransaction(Transaction tx) {
		try {
			broadcastBytes(tx.bitcoinSerialize());
		} catch (Exception e) {
			logger.warn("broadcastTransaction error", e);
		}
	}

	private void broadcastBytes(byte[] data) {
		try {
			if ("".equalsIgnoreCase(kafkaConfiguration.getBootstrapServers()))
				return;
			KafkaMessageProducer kafkaMessageProducer = new KafkaMessageProducer(kafkaConfiguration);
			kafkaMessageProducer.sendMessage(data, "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG");
		} catch (InterruptedException | ExecutionException | IOException e) {
			logger.warn("broadcastBytes error", e);
		}
	}

	public void batchBlocks() throws BlockStoreException, Exception {
		BlockStoreInterface store = storeService.getStore();
		try {
			List<BatchBlock> batchBlocks = store.getBatchBlockList();
			if (batchBlocks.isEmpty()) {
				return;
			}
			Block block = cacheBlockPrototypeService.getBlockPrototype(store);
			for (BatchBlock batchBlock : batchBlocks) {
				byte[] payloadBytes = batchBlock.getBlock();
				Block putBlock = this.networkParameters.getDefaultSerializer().makeBlock(payloadBytes);
				for (Transaction transaction : putBlock.getTransactions()) {
					block.addTransaction(transaction);
				}
			}
			if (block.getTransactions().size() == 0) {
				return;
			}
			saveBlock(block, store);
			for (BatchBlock batchBlock : batchBlocks) {
				store.deleteBatchBlock(batchBlock.getHash());
			}
		} finally {
			store.close();
		}
	}

	public int batchBlocksFromMempool() throws Exception {
		Map<BlockType, List<Transaction>> txnsByType = mempoolService.drainAllByType();
		if (txnsByType.isEmpty()) {
			return 0;
		}
		int totalBatched = 0;
		for (List<Transaction> txns : txnsByType.values()) {
			totalBatched += batchTransactionGroup(txns);
		}
		if (scheduleConfiguration.isPosEnabled()) {
			feeService.updateBaseFee(totalBatched);
		}
		return totalBatched;
	}

	private int batchTransactionGroup(List<Transaction> txns) throws Exception {
		if (txns.size() <= BATCH_TX_PER_BLOCK) {
			BlockStoreInterface store = storeService.getStore();
			try {
				Block block = cacheBlockPrototypeService.getBlockPrototype(store);
				for (Transaction tx : txns) {
					block.addTransaction(tx);
				}
				setBlockTypeFromTransactions(block);
				saveBatchBlock(block, store);
			} finally {
				store.close();
			}
			return txns.size();
		}
		List<List<Transaction>> groups = new ArrayList<>();
		for (int i = 0; i < txns.size(); i += BATCH_TX_PER_BLOCK) {
			groups.add(txns.subList(i, Math.min(i + BATCH_TX_PER_BLOCK, txns.size())));
		}
		BlockStoreInterface store = storeService.getStore();
		try {
			TipsQueue tipsQueue = store.getTipsQueue();
			if (tipsQueue == null) {
				return 0;
			}
			Block proto = networkParameters.getDefaultSerializer().makeBlock(tipsQueue.getBlock());
			// Pre-fetch predecessor blocks once — avoids N redundant DB reads
			Block predBlock = store.get(proto.getPrevBlockHash());
			Block predBranchBlock = store.get(proto.getPrevBranchBlockHash());
			@SuppressWarnings("unchecked")
			CompletableFuture<Void>[] futures = new CompletableFuture[groups.size()];
			// Pre-open one DB connection per worker thread and share them
			// across groups, avoiding open/close churn on every block.
			BlockStoreInterface[] stores = new BlockStoreInterface[groups.size()];
			try {
				for (int g = 0; g < groups.size(); g++) {
					stores[g] = storeService.getStore();
				}
				for (int g = 0; g < groups.size(); g++) {
					final List<Transaction> group = groups.get(g);
					final BlockStoreInterface s = stores[g];
					if (g > 0) {
						store.insertTipsQueue(new TipsQueue(java.util.Arrays.copyOf(
								proto.getHash().getBytes(), 32),
								proto.unsafeBitcoinSerialize(), proto.getHeight(), proto.getTimeSeconds()));
					}
					futures[g] = CompletableFuture.runAsync(() -> {
						try {
							Block b = Block.createBlock(networkParameters,
									predBlock, predBranchBlock);
							for (Transaction tx : group) {
								b.addTransaction(tx);
							}
							setBlockTypeFromTransactions(b);
							saveBatchBlock(b, s);
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					}, parallelBatchPool);
				}
				CompletableFuture.allOf(futures).get();
			} finally {
				for (BlockStoreInterface s : stores) {
					if (s != null) s.close();
				}
			}
		} finally {
			store.close();
		}
		return txns.size();
	}

	public static void setBlockTypeFromTransactions(Block block) {
		if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
			return;
		}
		String dataClassName = block.getTransactions().get(0).getDataClassName();
		if (dataClassName == null) {
			return;
		}
		switch (dataClassName) {
		case "OrderOpen":
			block.setBlockType(BlockType.BLOCKTYPE_ORDER_OPEN);
			break;
		case "OrderCancelInfo":
			block.setBlockType(BlockType.BLOCKTYPE_ORDER_CANCEL);
			break;
		case "ContractEventInfo":
			block.setBlockType(BlockType.BLOCKTYPE_CONTRACT_EVENT);
			break;
		case "ContractEventCancelInfo":
			block.setBlockType(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
			break;
		case "UserSettingDataInfo":
			block.setBlockType(BlockType.BLOCKTYPE_USERDATA);
			break;
		default:
			break;
		}
	}
}
