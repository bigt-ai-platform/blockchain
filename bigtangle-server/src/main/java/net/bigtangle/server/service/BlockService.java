package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Block.Type;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.exception.VerificationException.ProofOfWorkException;
import net.bigtangle.core.exception.VerificationException.UnsolidException;
import net.bigtangle.core.response.AbstractResponse;
import net.bigtangle.core.response.GetBlockEvaluationsResponse;
import net.bigtangle.core.response.GetBlockListResponse;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;
import net.bigtangle.utils.Gzip;

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
	protected TipsService tipService;
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	protected ObjectMapper jsonmapper;
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

	public Block getNewBlockPrototype(BlockStoreInterface store) throws BlockStoreException {
		Pair<BlockWrap, BlockWrap> tipsToApprove = getValidatedBlockPair(store);
		Block b = Block.createBlock(networkParameters, tipsToApprove.getLeft().getBlock(),
				tipsToApprove.getRight().getBlock());
		b.setMinerAddress(Address.fromBase58(networkParameters, serverConfiguration.getMineraddress()).getHash160());

		return b;
	}

	/*
	 * prefer tip from two different previous block. This is modified mcmc
	 */
	private Pair<BlockWrap, BlockWrap> getValidatedBlockPair(BlockStoreInterface store) throws BlockStoreException {
		Pair<BlockWrap, BlockWrap> candidate = tipService.getValidatedBlockPair(store);

		if (!candidate.getLeft().equals(candidate.getRight())) {
			return candidate;
		}
		for (int i = 0; i < 2; i++) {
			Pair<BlockWrap, BlockWrap> paar = tipService.getValidatedBlockPair(store);
			if (!paar.getLeft().getBlock().getHash().equals(paar.getRight().getBlock().getHash())) {
				return paar;
			}
		}
		return candidate;
	}

	/*
	 * Block byte[] bytes
	 */
	public Optional<Block> addConnectedFromKafka(byte[] key, byte[] bytes) {

		try {
			logger.debug("addConnectedFromKafka from sendkey:{}", Arrays.toString(key));
			return addConnected(Gzip.decompressOut(bytes), true);
		} catch (VerificationException e) {
			return Optional.empty();
		} catch (Exception e) {
			logger.debug("addConnectedFromKafka with sendkey:{}", Arrays.toString(key), e);
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
					if (block.getBlockType() == Type.BLOCKTYPE_REWARD) {
						logger.debug(" connected received chain block  {}", block.getLastMiningRewardBlock());
					}
					blockgraph.addBlock(block, allowUnsolid, store);
					// removeBlockPrototype(block,store);
					return Optional.of(block);
				} catch (ProofOfWorkException | UnsolidException e) {
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

	public void adjustHeightRequiredBlocks(Block block, BlockStoreInterface store)
			throws BlockStoreException, NoBlockException {
		block = adjustPrototype(block, store);
		long h = calcHeightRequiredBlocks(block, store);
		if (h > block.getHeight()) {
			logger.debug("adjustHeightRequiredBlocks{} to {}", block, h);
			block.setHeight(h);
		}
	}

	public Block adjustPrototype(Block block, BlockStoreInterface store) throws BlockStoreException {
		// two hours for just getBlockPrototype
		int delaySeconds = 7200;

		if (block.getTimeSeconds() < System.currentTimeMillis() / 1000 - delaySeconds) {
			logger.debug("adjustPrototype {}", block);
			Block newblock = getNewBlockPrototype(store);
			for (Transaction transaction : block.getTransactions()) {
				newblock.addTransaction(transaction);
			}
			return newblock;
		}
		return block;
	}

	public long calcHeightRequiredBlocks(Block block, BlockStoreInterface store) throws BlockStoreException {

		Set<Sha256Hash> allrequireds = new HashSet<>();
		List<Block> result = new ArrayList<>();
		allrequireds.add(block.getPrevBlockHash());
		allrequireds.add(block.getPrevBranchBlockHash());
		for (Sha256Hash pred : allrequireds)
			result.add(store.get(pred));

		long height = 0;
		for (Block b : result) {
			height = Math.max(height, b.getHeight());
		}
		return height + 1;
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

}
