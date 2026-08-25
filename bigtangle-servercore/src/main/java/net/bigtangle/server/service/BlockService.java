package net.bigtangle.server.service;

import java.util.ArrayList;
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
import java.util.HashSet;
import java.util.Set;
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

	// Resolved lazily to break any store/service cycles involving CasperService.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.CasperService> casperServiceProvider;

	// Feeds the gossip-observed fork-choice view (GhostService.observeBeacon)
	// with every ingested beacon; lazily resolved to avoid service cycles.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.GhostService> ghostServiceProvider;

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
		// Validate + guard the block's transactions BEFORE creating the
		// batchblock row. The old order (insert then submit) left a row behind
		// when submit rejected a double-spend tx; the batch pipeline then
		// materialised a conflicting block whose spends deadlocked beacon
		// confirmation (a beacon referencing both the legit and the double-spend
		// block is rejected) and stalled the chain. Rejecting here keeps the
		// block from ever reaching the DAG.
		mempoolService.submit(block);
		store.insertBatchBlock(block);
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

	/**
	 * Batch fetch: raw serialized blocks for the requested hashes, skipping
	 * unknown ones. Serves {@code requestMissingReferenced} so a lagging node
	 * pulls its missing DAG set in a handful of requests instead of one HTTP
	 * round-trip per hash.
	 *
	 * <p>Falls back to this node's pending-connect queue: a queued beacon's
	 * ONLY mesh-wide copy can live there (its block-table row appears when the
	 * queue entry finally connects). Without the fallback a peer holding the
	 * bytes answers 404-ish "absent", every requester marks the block permanently
	 * unservable, and all beacons referencing it defer forever.
	 */
	public GetBlockListResponse getBlocksByHashList(List<String> hashHexs, BlockStoreInterface store)
			throws BlockStoreException {
		List<byte[]> blocks = new ArrayList<>();
		if (hashHexs == null) {
			return GetBlockListResponse.create(blocks);
		}
		Set<Sha256Hash> absent = new HashSet<>();
		for (String hex : hashHexs) {
			Block block = getBlock(Sha256Hash.wrap(hex), store);
			if (block != null) {
				blocks.add(block.bitcoinSerialize());
			} else {
				absent.add(Sha256Hash.wrap(hex));
			}
		}
		if (!absent.isEmpty()) {
			try {
				for (net.bigtangle.server.data.ChainBlockQueue cb : store.selectChainblockqueue(false, 10_000)) {
					Sha256Hash queued = Sha256Hash.wrap(cb.getHash());
					if (absent.remove(queued)) {
						blocks.add(cb.getBlock());
						if (absent.isEmpty()) {
							break;
						}
					}
				}
			} catch (Exception e) {
				logger.debug("queue fallback in getBlocksByHashList failed: {}", e.getMessage());
			}
		}
		return GetBlockListResponse.create(blocks);
	}

	public GetBlockListResponse blocksFromNonChainHeigth(long cutoffHeight, long maxHeight, int limit,
			BlockStoreInterface store) throws BlockStoreException {

		// Serve from the REQUESTER's cutoff, not ours: a lagging node asks for
		// everything above its own confirmed tip. Math.max with this node's
		// head-derived cutoff excluded exactly the range the requester was
		// missing, making bulk repair impossible (it always returned 0 blocks
		// to any node behind the proposer). Paged by maxHeight/limit so one
		// response never overflows JSON string limits.
		return GetBlockListResponse.create(store.blocksFromNonChainHeigth(cutoffHeight, maxHeight, limit));
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
		logger.debug(" addConnected  Blockhash={} height ={}", makeBlock.getHashAsString(),
				makeBlock.getHeight());
		return addConnectedBlock(makeBlock, allowUnsolid);
	}

	/**
	 * Striped per-hash locks. The Kafka stream thread and the gossip HTTP
	 * executor deliver the SAME block within milliseconds of each other;
	 * without serialization both threads pass existBlock() and race through
	 * addBlock, where concurrent batch writes can roll back the winner's row.
	 * The block then exists on NO node while already-referenced beacons defer
	 * forever (observed as 'chain-connect deferred' storms and a chainlength
	 * frozen below the first Casper epoch boundary — hence zero finality).
	 */
	private static final int INGEST_STRIPES = 64;
	private static final Object[] INGEST_LOCKS = new Object[INGEST_STRIPES];
	static {
		for (int i = 0; i < INGEST_STRIPES; i++) {
			INGEST_LOCKS[i] = new Object();
		}
	}

	public Optional<Block> addConnectedBlock(Block block, boolean allowUnsolid) throws BlockStoreException {
		final Object stripe = INGEST_LOCKS[Math.floorMod(block.getHash().hashCode(), INGEST_STRIPES)];
		synchronized (stripe) {
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
						// Feed the gossip-observed fork-choice view so GHOST
						// weighs majority attestations as soon as they ARRIVE
						// (kafka stream or gossip), not only after confirmation.
						net.bigtangle.server.service.GhostService ghost = ghostServiceProvider.getIfAvailable();
						if (ghost != null) {
							try {
								ghost.observeBeacon(block);
							} catch (Exception e1) {
								logger.debug("observeBeacon failed: {}", e1.getMessage());
							}
						}
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

		GetTXRewardResponse response = GetTXRewardResponse.create(cacheBlockService.getMaxConfirmedReward(store));
		// Advertise the local justified/finalized Casper checkpoints so a sync
		// peer can prefer the finality-compatible chain instead of blindly
		// downloading the longest one (which connectRewardBlock would refuse).
		net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
		if (casper != null) {
			net.bigtangle.server.service.CasperService.Checkpoint justified = casper.getJustifiedCheckpoint();
			if (justified != null) {
				response.setJustifiedBlockHash(justified.getBlockHash().toString());
				response.setJustifiedEpoch(justified.getEpoch());
			}
			net.bigtangle.server.service.CasperService.Checkpoint finalized = casper.getLastFinalizedCheckpoint();
			if (finalized != null) {
				response.setFinalizedBlockHash(finalized.getBlockHash().toString());
				response.setFinalizedEpoch(finalized.getEpoch());
				try {
					response.setFinalizedChainLength(store.getRewardChainLength(finalized.getBlockHash()));
				} catch (Exception e) {
					logger.debug("finalized chainlength lookup failed: {}", e.getMessage());
				}
			}
		}
		return response;

	}

	public GetTXRewardListResponse getAllConfirmedReward(BlockStoreInterface store) throws BlockStoreException {

		return GetTXRewardListResponse.create(store.getAllConfirmedReward());

	}
}
