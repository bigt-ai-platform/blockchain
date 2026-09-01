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
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.kafka.KafkaConfiguration;
import net.bigtangle.kafka.KafkaMessageProducer;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.BatchBlock;
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
	// Resolved lazily via ObjectProvider to break the circular reference
	// blockSaveService -> crosstangleProcessorImpl -> anchorService -> blockSaveService.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<CrosstangleProcessor> crosstangleProcessorProvider;
	// Resolved lazily via ObjectProvider to break the circular reference
	// blockSaveService -> stakeService -> blockSaveService.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<StakeService> stakeServiceProvider;
	@Autowired
	protected RandaoService randaoService;
	private static final Logger logger = LoggerFactory.getLogger(BlockSaveService.class);

	public static int BATCH_TX_PER_BLOCK = Integer.getInteger("batch.txPerBlock", 2000); // adjustable for testing
	public static int BATCH_PARALLELISM = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
	private static final ExecutorService parallelBatchPool = Executors.newFixedThreadPool(BATCH_PARALLELISM);

	public void saveBlock(Block block, BlockStoreInterface store) throws Exception {
		blockgraph.addBlock(block, false, store);
		accumulateBlockFees(block, store);
		notifyChainDerived(block, store);
		broadcastBlock(block);
	}

	/** Permissive variant used by token creation (MultiSignServiceCreate).
	 *  The block has already passed checkFullTokenSolidity, so strict
	 *  re-validation in addBlock would reject it for unrelated reasons. */
	public void saveBlockPermissive(Block block, BlockStoreInterface store) throws Exception {
		// Same stale-hash guard as saveBatchBlock: token creations and other
		// client-built blocks reach persistence through this path, and a tx
		// hash cached before later mutations bakes a wrong merkle root into
		// the stored bytes (peers then reject the block on sync forever).
		block.invalidateTransactionHashes();
		// CROSSTANGLE blocks are cross-chain VALUE messages (peg-in/peg-out,
		// anchors): they must pass real consensus validation
		// (L0AnchorHandler.checkFull verifies scriptSig ownership, value
		// conservation and the anchor), so they are saved fail-closed — a block
		// that fails validation is rejected, never force-marked solid.
		if (block.getBlockType() == net.bigtangle.core.BlockType.BLOCKTYPE_CROSSTANGLE) {
			blockgraph.addNonChain(block, false, store, true, true);
		} else {
			blockgraph.addNonChain(block, true, store, true, true);
		}
		// addNonChain → solidifyBlock sets solid=1 for MissingCalculation
		// state (inherited from prototype predecessors); mark the block fully
		// solid so downstream chain-derived processing sees it immediately.
		store.updateBlockEvaluationSolid(block.getHash(), 2);
		// Mark the spent UTXOs as spend-pending so a wallet does not reuse a
		// fee UTXO already committed to this (as-yet unconfirmed) block —
		// otherwise two rapid token creations pick the same fee UTXO and
		// produce conflicting blocks that never confirm.
		blockgraph.updateTransactionOutputSpendPendingDo(block);
		accumulateBlockFees(block, store);
		notifyCrosstangle(block, store);
		notifyChainDerived(block, store);
		broadcastBlock(block);
	}

	/** Batch variant: skips transaction re-verification, solidity checks,
	 *  AND cache operations.  Batch blocks are
	 *  transient mempool dumps that don't need archival. */
	public void saveBatchBlock(Block block, BlockStoreInterface store) throws Exception {
		// Guard against stale in-memory tx hash caches: mempool tx objects can
		// carry a hash computed before later field changes. The merkle root is
		// written from the cached leaves while a syncing peer recomputes them
		// from the serialized bytes — producing a block whose root never
		// verifies anywhere else (observed as a permanent L1 sync stall).
		// Forcing recalculation here makes creator and verifier agree by
		// construction.
		block.invalidateTransactionHashes();
		store.setBatchDurability(true);
		try (AutoCloseable cacheFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.skipCacheForBatch();
		     AutoCloseable copyFlag = net.bigtangle.store.DatabaseFullBlockStoreBase.usePgCopyForBatch()) {
			blockgraph.addNonChain(block, true, store, true, true);
		}
		store.setBatchDurability(false);
		// Every tx in a batch block passed mempool verification on this node
		// before assembly — record that so beacon connect skips the redundant
		// per-tx re-verification pass.
		cacheBlockService.markTxValidated(block.getHash());
		// Hand the parsed instance to the beacon sweep's memo so the next slot
		// does not re-deserialize this block from its stored bytes.
		net.bigtangle.server.service.base.ServiceBaseConfirmation.cacheParsedBlock(block);
		accumulateBlockFees(block, store);
		markStatus(block, net.bigtangle.server.data.TransactionStatus.BATCHED, store);
		notifyCrosstangle(block, store);
		notifyChainDerived(block, store);
		broadcastBlock(block);
	}

	/** Applies chain-derived state (validator deposits, slashing, exits, ...) when a block is saved. */
	private void notifyChainDerived(Block block, BlockStoreInterface store) throws Exception {
		StakeService stakeService = stakeServiceProvider.getIfAvailable();
		if (stakeService == null) {
			return;
		}
		if (block.getBlockType() == net.bigtangle.core.BlockType.BLOCKTYPE_STAKE) {
			stakeService.applyStakeBlock(block, store);
		} else if (block.getBlockType() == net.bigtangle.core.BlockType.BLOCKTYPE_SLASHING) {
			stakeService.applySlashingBlock(block, store);
		} else if (block.getBlockType() == net.bigtangle.core.BlockType.BLOCKTYPE_EXIT) {
			stakeService.applyExitBlock(block, store);
		}
		// RANDAO reveals are folded into the mix ONLY on confirmation
		// (BlockStoreService.confirmDo), never at save time, so the mix is a
		// pure function of the confirmed chain rather than arrival order.
	}

	/** Hands CROSSTANGLE (cross-chain message) blocks to the bridge module. */
	private void notifyCrosstangle(Block block, BlockStoreInterface store) throws Exception {
		CrosstangleProcessor crosstangleProcessor = crosstangleProcessorProvider.getIfAvailable();
		if (crosstangleProcessor == null || block.getBlockType() != net.bigtangle.core.BlockType.BLOCKTYPE_CROSSTANGLE) {
			return;
		}
		crosstangleProcessor.onCrosstangleBlockSaved(block, store);
	}

	/** Best-effort status write for every user transaction in a block. */
	private void markStatus(Block block, net.bigtangle.server.data.TransactionStatus status,
			BlockStoreInterface store) {
		try {
			net.bigtangle.server.data.TransactionStatusRecord.markBlock(store, block, status, null,
					networkParameters);
		} catch (Exception e) {
			logger.debug("Failed to record {} status for block {}: {}", status, block.getHash(), e.getMessage());
		}
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
			// Batch-read missing input UTXOs per referenced (block, tx) instead of
			// one DB round-trip per input.
			java.util.Map<TransactionOutPoint, net.bigtangle.core.UTXO> missing = new java.util.HashMap<>();
			java.util.Map<TransactionOutPoint, net.bigtangle.core.Coin> inValues = new java.util.HashMap<>();
			for (TransactionInput in : tx.getInputs()) {
				TransactionOutput connected = in.getOutpoint().getConnectedOutput();
				if (connected != null) {
					inValues.put(in.getOutpoint(), connected.getValue());
				} else {
					missing.put(in.getOutpoint(), null);
				}
			}
			if (!missing.isEmpty()) {
				java.util.Map<Sha256Hash, java.util.List<TransactionOutPoint>> byTx = new java.util.HashMap<>();
				for (TransactionOutPoint op : missing.keySet()) {
					byTx.computeIfAbsent(op.getTxHash(), k -> new java.util.ArrayList<>()).add(op);
				}
				for (java.util.Map.Entry<Sha256Hash, java.util.List<TransactionOutPoint>> e : byTx.entrySet()) {
					java.util.List<Long> indices = new java.util.ArrayList<>();
					for (TransactionOutPoint op : e.getValue()) {
						indices.add(op.getIndex());
					}
					java.util.Map<Long, net.bigtangle.core.UTXO> got = store.getTransactionOutputs(
							e.getValue().get(0).getBlockHash(), e.getKey(), indices);
					for (TransactionOutPoint op : e.getValue()) {
						net.bigtangle.core.UTXO utxo = got.get(op.getIndex());
						if (utxo != null) {
							inValues.put(op, utxo.getValue());
						}
					}
				}
			}
			for (java.util.Map.Entry<TransactionOutPoint, net.bigtangle.core.Coin> e : inValues.entrySet()) {
				net.bigtangle.core.Coin inValue = e.getValue();
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
		// GOSSIP-FALLBACK ONLY: with kafka consumers active the topic already
		// delivers to every node; the HTTP copies duplicated ingest (racing
		// addConnectedBlock on the same hash) and burned CPU on re-verify.
		if (serverConfiguration.getRunKafkaStream()) {
			return;
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
			// CROSSTANGLE (cross-chain message / anchor) transactions are batched
			// SEPARATELY with the block type preserved: folding them into the
			// TRANSFER prototype would silently drop the type, so
			// notifyCrosstangle / the L0AnchorHandler never run and the anchor is
			// never recorded on L0. The fail-closed validation in
			// addNonChain/saveBatchBlock then applies to this path too.
			Block crosstangle = null;
			for (BatchBlock batchBlock : batchBlocks) {
				byte[] payloadBytes = batchBlock.getBlock();
				Block putBlock = this.networkParameters.getDefaultSerializer().makeBlock(payloadBytes);
				for (Transaction transaction : putBlock.getTransactions()) {
					if (net.bigtangle.core.BlockType.BLOCKTYPE_CROSSTANGLE == putBlock.getBlockType()
							|| "LayerAnchor".equals(transaction.getDataClassName())) {
						if (crosstangle == null) {
							crosstangle = cacheBlockPrototypeService.getBlockPrototype(store);
							crosstangle.setBlockType(net.bigtangle.core.BlockType.BLOCKTYPE_CROSSTANGLE);
						}
						crosstangle.addTransaction(transaction);
					} else {
						block.addTransaction(transaction);
					}
				}
			}
			if (crosstangle != null && !crosstangle.getTransactions().isEmpty()) {
				try {
					saveBatchBlock(crosstangle, store);
					markStatus(crosstangle, net.bigtangle.server.data.TransactionStatus.IN_BLOCK, store);
				} catch (Exception e) {
					// R1: a CROSSTANGLE batch that fails consensus validation must
					// NOT poison every later batchBlocks() run. Its rows are still
					// deleted below (fail-closed drop of the invalid block), so the
					// scheduled consolidation can make progress.
					logger.warn("Dropping invalid CROSSTANGLE batch rows ({}): {}",
							crosstangle.getHashAsString(), e.getMessage());
				}
			}
			if (block.getTransactions().isEmpty()) {
				for (BatchBlock batchBlock : batchBlocks) {
					store.deleteBatchBlock(batchBlock.getHash());
				}
				return;
			}
			saveBlock(block, store);
			markStatus(block, net.bigtangle.server.data.TransactionStatus.IN_BLOCK, store);
			for (BatchBlock batchBlock : batchBlocks) {
				store.deleteBatchBlock(batchBlock.getHash());
			}
		} finally {
			store.close();
		}
	}

	public int batchBlocksFromMempool() throws Exception {
		// Drain in bounded windows: peak memory is one window of parsed txs
		// (~4 groups x 2000), not the whole backlog. A 50000-tx backlog drains
		// as ~7 windows instead of one multi-GB materialization.
		final int chunk = BATCH_TX_PER_BLOCK * 4;
		int totalBatched = 0;
		while (true) {
			Map<BlockType, List<Transaction>> txnsByType = mempoolService.drainAllByType(chunk);
			if (txnsByType.isEmpty()) {
				break;
			}
			int window = 0;
			for (List<Transaction> txns : txnsByType.values()) {
				try {
					totalBatched += batchTransactionGroup(txns);
				} catch (Exception e) {
					// REQUEUE on failed save: drained txs lost here would
					// silently vanish while their spend-pending claims kept
					// blocking resubmission (measured: whole windows of 8000
					// txs vaporised, wallet capacities halved per run).
					logger.warn("Batch window save failed, requeueing {} txs",
							txns.size(), e);
					mempoolService.requeue(txns);
				}
				window += txns.size();
			}
			if (feeService != null) {
				feeService.updateBaseFee(totalBatched);
			}
			if (window < chunk) {
				break;
			}
		}
		return totalBatched;
	}

	/**
	 * Hard ceiling for one batch block's serialized size. MAX_DEFAULT_BLOCK_SIZE
	 * is 20 MB; keep a margin for the block header, the varint sizing error of
	 * the optimal-encoding estimate, and the kafka envelope (32 MB producer /
	 * broker limit). Every published block must be transportable, or peers
	 * reject it with LargerThanMaxBlockSize and the mesh forks permanently.
	 */
	static final int MAX_BATCH_BLOCK_BYTES = Integer.getInteger("batch.maxBlockBytes", 16 * 1024 * 1024);

	/**
	 * Split drained transactions into batch-block groups bounded BOTH by
	 * {@link #BATCH_TX_PER_BLOCK} (count) and {@link #MAX_BATCH_BLOCK_BYTES}
	 * (serialized size, using the cheap optimal-encoding estimate). A group
	 * over the byte cap is transportable only in part: peers reject oversized
	 * blocks with LargerThanMaxBlockSize, so those transactions would confirm
	 * on the creating node alone and the mesh forks permanently.
	 */
	static List<List<Transaction>> groupBySize(List<Transaction> txns) {
		List<List<Transaction>> groups = new ArrayList<>();
		List<Transaction> current = new ArrayList<>();
		long bytes = 0;
		for (Transaction tx : txns) {
			int sz = tx.getOptimalEncodingMessageSize();
			if (!current.isEmpty() && (current.size() >= BATCH_TX_PER_BLOCK || bytes + sz > MAX_BATCH_BLOCK_BYTES)) {
				groups.add(current);
				current = new ArrayList<>();
				bytes = 0;
			}
			// A single transaction over the cap still goes in its own group —
			// it cannot be split, and dropping it would strand its UTXO.
			current.add(tx);
			bytes += sz;
		}
		if (!current.isEmpty()) {
			groups.add(current);
		}
		return groups;
	}

	private int batchTransactionGroup(List<Transaction> txns) throws Exception {
		// BYTE-SAFE grouping: a batch block of batch.txPerBlock PQ-signed
		// transactions is 20-48 MB — over MAX_DEFAULT_BLOCK_SIZE (20 MB), so
		// peers reject it with LargerThanMaxBlockSize and it NEVER syncs: its
		// transactions confirm only on the creating node and the mesh forks
		// permanently. Cap every group by serialized size (with margin for the
		// block header + kafka envelope) so every published block is
		// transportable.
		List<List<Transaction>> groups = groupBySize(txns);
		if (groups.size() == 1) {
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
		BlockStoreInterface store = storeService.getStore();
		try {
			Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
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
		case "EVMTransactionInfo":
			try {
				net.bigtangle.core.EVMTransactionInfo evmInfo = new net.bigtangle.core.EVMTransactionInfo()
						.parseChecked(block.getTransactions().get(0).getData());
				block.setBlockType(
						evmInfo.isDeploy() ? BlockType.BLOCKTYPE_EVM_DEPLOY : BlockType.BLOCKTYPE_EVM_CALL);
			} catch (RuntimeException e) {
				block.setBlockType(BlockType.BLOCKTYPE_EVM_CALL);
			}
			break;
		case "UserSettingDataInfo":
			block.setBlockType(BlockType.BLOCKTYPE_USERDATA);
			break;
		case "LayerAnchor":
			// Cross-chain anchors must keep their CROSSTANGLE type, otherwise
			// the block is batched as TRANSFER, notifyCrosstangle never fires
			// and the anchor is never recorded on L0.
			block.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
			break;
		default:
			break;
		}
	}
}
