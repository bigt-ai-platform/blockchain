package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
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
	@Autowired(required = false)
	protected KafkaMessageProducer kafkaMessageProducer;
	@Autowired(required = false)
	protected GossipProtocol gossipProtocol;
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
	@Autowired
	protected net.bigtangle.server.service.CacheBlockService cacheBlockService;
	@Autowired
	protected com.fasterxml.jackson.databind.ObjectMapper jsonmapper;
	private static final Logger logger = LoggerFactory.getLogger(BlockSaveService.class);

	public static int BATCH_TX_PER_BLOCK = 50000; // adjustable for testing
	private static final ExecutorService parallelBatchPool = Executors.newFixedThreadPool(
			Math.max(8, Runtime.getRuntime().availableProcessors() * 2));

	public void saveBlock(Block block, BlockStoreInterface store) throws Exception {
		blockgraph.addBlock(block, false, store);
		accumulateBlockFees(block, store);
		broadcastBlock(block);
	}

	/** Permissive variant used by token creation (MultiSignServiceCreate).
	 *  The block has already passed checkFullTokenSolidity, so strict
	 *  re-validation in addBlock would reject it for unrelated reasons.
	 *  Also seeds MCMC weight + TipsQueue so the beacon chain picks it up. */
	public void saveBlockPermissive(Block block, BlockStoreInterface store) throws Exception {
		blockgraph.addNonChain(block, true, store, true, true);
		accumulateBlockFees(block, store);
		broadcastBlock(block);
	}

	/** Batch variant: skips transaction re-verification, solidity checks,
	 *  AND cache operations.  Batch blocks are
	 *  transient mempool dumps that don't need archival — the PostgreSQL
	 *  row alone suffices for the MCMC bridge. */
	public void saveBatchBlock(Block block, BlockStoreInterface store) throws Exception {
		try (AutoCloseable cacheFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.skipCacheForBatch();
		     AutoCloseable copyFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.usePgCopyForBatch()) {
			blockgraph.addNonChain(block, true, store, true, true);
		}
		accumulateBlockFees(block, store);
		broadcastBlock(block);
	}

	private void accumulateBlockFees(Block block, BlockStoreInterface store) throws Exception {
		if (block.getTransactions() == null) return;

		String chainId = networkParameters.getChainId();
		java.math.BigInteger feeSurplus = java.math.BigInteger.ZERO;
		for (Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase() || tx.getInputs() == null) continue;
			java.math.BigInteger txIn = java.math.BigInteger.ZERO;
			java.math.BigInteger txOut = java.math.BigInteger.ZERO;
			for (TransactionOutput out : tx.getOutputs()) {
				if (out.getValue().isBIG()) {
					txOut = txOut.add(out.getValue().getValue());
				}
			}
			for (TransactionInput in : tx.getInputs()) {
				net.bigtangle.core.Coin inValue = null;
				TransactionOutput connected = in.getOutpoint().getConnectedOutput();
				if (connected != null) {
					inValue = connected.getValue();
				} else {
					net.bigtangle.core.UTXO utxo = store.getTransactionOutput(
							in.getOutpoint().getBlockHash(),
							in.getOutpoint().getTxHash(),
							in.getOutpoint().getIndex());
					if (utxo != null) inValue = utxo.getValue();
				}
				if (inValue != null && inValue.isBIG()) {
					txIn = txIn.add(inValue.getValue());
				}
			}
			java.math.BigInteger surplus = txIn.subtract(txOut);
			if (surplus.compareTo(java.math.BigInteger.ZERO) > 0) {
				feeSurplus = feeSurplus.add(surplus);
			}
		}

		if (feeSurplus.compareTo(java.math.BigInteger.ZERO) <= 0) return;

		byte[] existing = store.getPosState("fee", chainId);
		java.math.BigInteger current = existing == null
				? java.math.BigInteger.ZERO
				: new java.math.BigInteger(existing);
		java.math.BigInteger updated = current.add(feeSurplus);
		store.savePosState("fee", chainId, updated.toByteArray());
	}

	public void broadcastBlock(Block block) {
		broadcastBytes(block.bitcoinSerialize(), true);
	}

	public void broadcastTransaction(Transaction tx) {
		try {
			broadcastBytes(tx.bitcoinSerialize(), false);
		} catch (Exception e) {
			logger.warn("broadcastTransaction error", e);
		}
	}

	private void broadcastBytes(byte[] data, boolean isBlock) {
		if (isBlock) {
			Sha256Hash hash = Sha256Hash.of(data);
			if (kafkaMessageProducer != null)
				kafkaMessageProducer.sendBlock(hash.toString(), data);
		} else {
			try {
				Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(data);
				if (kafkaMessageProducer != null)
					kafkaMessageProducer.sendTransaction(tx.getHash().toString(), data);
			} catch (Exception e) {
				logger.warn("broadcastTransaction serialization error", e);
			}
		}
		if (gossipProtocol != null) {
			if (isBlock) {
				try {
					Block block = networkParameters.getDefaultSerializer().makeBlock(data);
					gossipProtocol.broadcastBlock(block);
				} catch (Exception e) {
					logger.warn("gossip block error", e);
				}
			} else {
				try {
					Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(data);
					gossipProtocol.broadcastTransaction(tx);
				} catch (Exception e) {
					logger.warn("gossip tx error", e);
				}
			}
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
		if (feeService != null) {
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
