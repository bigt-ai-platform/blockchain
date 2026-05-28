package net.bigtangle.server.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.Transaction;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.kafka.KafkaConfiguration;
import net.bigtangle.kafka.KafkaMessageProducer;
import net.bigtangle.params.NetworkParameters;
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
	@Autowired
	protected ServerConfiguration serverConfiguration;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
	private static final Logger logger = LoggerFactory.getLogger(BlockSaveService.class);

	public void saveBlock(Block block, BlockStoreInterface store) throws Exception {
		blockgraph.addBlock(block, false, store);
		// no broadcastBlock, if there is error of blockgraph.add
		broadcastBlock(block);
	}

	public void broadcastBlock(Block block) {
		try {
			if ("".equalsIgnoreCase(kafkaConfiguration.getBootstrapServers()))
				return;
			KafkaMessageProducer kafkaMessageProducer = new KafkaMessageProducer(kafkaConfiguration);
			kafkaMessageProducer.sendMessage(block.bitcoinSerialize(), serverConfiguration.getMineraddress());
		} catch (InterruptedException | ExecutionException | IOException e) {
			logger.warn(block.toString(), e);
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
			block.solve();
			saveBlock(block, store);
			for (BatchBlock batchBlock : batchBlocks) {
				store.deleteBatchBlock(batchBlock.getHash());
			}
		} finally {
			store.close();
		}
	}
}
