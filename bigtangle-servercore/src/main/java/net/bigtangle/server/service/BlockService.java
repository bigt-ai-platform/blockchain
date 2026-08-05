package net.bigtangle.server.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.UnsolidException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetBlockEvaluationsResponse;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/**
 * <p>
 * Provides services for blocks.
 * </p>
 */
@Service
public class BlockService {

	@Autowired
	protected StoreService storeService;

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	BlockStoreService blockgraph;

	@Autowired
	protected ServerConfiguration serverConfiguration;

 
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected ObjectMapper jsonmapper;
	@Autowired
	protected MempoolService mempoolService;

	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.StakeService> stakeServiceProvider;

	private static final Logger logger = LoggerFactory.getLogger(BlockService.class);

	public Block getBlock(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		return serviceBase.getBlock(blockhash, store);
	}

	public BlockWrap getBlockWrap(Sha256Hash blockhash, BlockStoreInterface store) throws BlockStoreException {
		return new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.getBlockWrap(blockhash, store);
	}

	public AbstractResponse searchBlock(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> address = (List<String>) request.get("address");
		String lastestAmount = request.get("lastestAmount") == null ? "0" : request.get("lastestAmount").toString();
		long height = request.get("height") == null ? 0L : Long.parseLong(request.get("height").toString());
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluations(address, lastestAmount, height,
				serverConfiguration.getMaxsearchblocks());
		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public AbstractResponse searchBlockByBlockHashs(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> blockhashs = (List<String>) request.get("blockhashs");
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluationsByhashs(blockhashs);

		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public void batchBlock(Block block, BlockStoreInterface store) throws BlockStoreException {
		store.insertBatchBlock(block);
		mempoolService.submit(block);
	}

	public void batchBlockToMempool(Block block) {
		mempoolService.submit(block);
	}

	public void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime, BlockStoreInterface store)
			throws BlockStoreException {

		store.insertMyserverblocks(prevhash, hash, inserttime);
	}

	public boolean existMyserverblocks(Sha256Hash prevhash, BlockStoreInterface store) throws BlockStoreException {

		return store.existMyserverblocks(prevhash);
	}

	public void deleteMyserverblocks(Sha256Hash prevhash, BlockStoreInterface store) throws BlockStoreException {

		store.deleteMyserverblocks(prevhash);
	}

	public GetBlockListResponse blocksFromChainLength(Long start, Long end, BlockStoreInterface store)
			throws BlockStoreException {

		return GetBlockListResponse.create(store.blocksFromChainLength(start, end));
	}

	public GetBlockListResponse blocksFromNonChainHeigth(long cutoffHeight, BlockStoreInterface store)
			throws BlockStoreException {

		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		long my = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.getCurrentCutoffHeight(maxConfirmedReward, store);
		return GetBlockListResponse.create(store.blocksFromNonChainHeigth(Math.max(cutoffHeight, my)));
	}

	/*
	 * Block byte[] bytes
	 */
	public Optional<Block> addConnectedFromKafka(byte[] key, byte[] bytes) {
		try {
			logger.debug("addConnectedFromKafka from sendkey:{}", Arrays.toString(key));
			return addConnected(bytes, true);
		} catch (VerificationException e) {
			return Optional.empty();
		} catch (Exception e) {
			logger.debug("addConnectedFromKafka with sendkey:{}", Arrays.toString(key), e);
			return Optional.empty();
		}
	}

	public Optional<Block> addConnectedFromGossip(Block block) {
		try {
			return addConnected(block.bitcoinSerialize(), true);
		} catch (Exception e) {
			logger.debug("addConnectedFromGossip error: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/*
	 * Block byte[] bytes
	 */
	public Optional<Block> addConnected(byte[] bytes, boolean allowUnsolid)
			throws ProtocolException, BlockStoreException {
		if (bytes == null)
			return Optional.empty();
		Block makeBlock = networkParameters.getDefaultSerializer().makeBlock(bytes);
		logger.debug(" addConnected  Blockhash={} height ={} block: {}", makeBlock.getHashAsString(),
				makeBlock.getHeight(), makeBlock);
		return addConnectedBlock(makeBlock, allowUnsolid);
	}

	public Optional<Block> addConnectedBlock(Block block, boolean allowUnsolid) throws BlockStoreException {
		BlockStoreInterface store = storeService.getStore();
		try {
			if (!store.existBlock(block.getHash())) {
				try {
					if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
						logger.debug(" connected received chain block  {}", block.getLastMiningRewardBlock());
						// Record the beacon's slot sighting at INGEST: a second
						// different beacon for the same slot is proposal
						// equivocation — captured here as slashable evidence,
						// regardless of which fork later wins confirmation.
						net.bigtangle.server.service.StakeService stake = stakeServiceProvider.getIfAvailable();
						if (stake != null) {
							stake.checkSlotSightingForEquivocation(block, store);
						}
					}
					blockgraph.addBlock(block, allowUnsolid, store);
					// removeBlockPrototype(block,store);
					return Optional.of(block);
				} catch (UnsolidException e) {
					return Optional.empty();
				} catch (Exception e) {
					logger.debug(" cannot add block: Blockhash={} height ={} block: {}", block.getHashAsString(),
							block.getHeight(), block, e);
					return Optional.empty();

				}
			}
		} finally {
			store.close();
		}

		return Optional.empty();
	}

	/*
	 * failed blocks without conflict for retry
	 */
	public AbstractResponse findRetryBlocks(Map<String, Object> request, BlockStoreInterface store)
			throws BlockStoreException {
		@SuppressWarnings("unchecked")
		List<String> address = (List<String>) request.get("address");
		String lastestAmount = request.get("lastestAmount") == null ? "0" : request.get("lastestAmount").toString();
		long height = request.get("height") == null ? 0L : Long.parseLong(request.get("height").toString());
		List<BlockEvaluationDisplay> evaluations = store.getSearchBlockEvaluations(address, lastestAmount, height,
				serverConfiguration.getMaxsearchblocks());
		return GetBlockEvaluationsResponse.create(evaluations);
	}

	public void checkBlockBeforeSave(Block block, BlockStoreInterface store) throws BlockStoreException {

		new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.checkBlockBeforeSave(block, store);
	}
	public GetTXRewardResponse getMaxConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		return GetTXRewardResponse.create(cacheBlockService.getMaxConfirmedReward(store));

	}

	public GetTXRewardListResponse getAllConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		return GetTXRewardListResponse.create(store.getAllConfirmedReward());

	}
}
