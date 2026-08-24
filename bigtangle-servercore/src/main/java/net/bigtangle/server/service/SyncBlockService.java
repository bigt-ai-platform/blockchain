/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetTransactionListResponse;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceVerifyReward;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * <p>
 * Provides services for sync blocks from remote servers via p2p. sync remote
 * chain data from chainlength, if chainlength = null, then sync the chain data
 * from the total rating with chain 100% For the sync from given checkpoint, the
 * server must be restarted.
 * </p>
 */
@Service
public class SyncBlockService {

	@Autowired
	protected NetworkParameters networkParameters;
	@Autowired
	BlockStoreService blockgraph;

	@Autowired
	private BlockService blockService;

	@Autowired
	private net.bigtangle.kafka.BlockStreamHandler blockStreamHandler;

	@Autowired
	protected ObjectMapper jsonmapper;

	@Autowired
	protected ServerConfiguration serverConfiguration;
	private static final Logger log = LoggerFactory.getLogger(SyncBlockService.class);
	private static final String LOCKID = "sync";

	@Autowired
	private ScheduleConfiguration scheduleConfiguration;
	@Autowired
	protected CacheBlockService cacheBlockService;
	@Autowired
	private StoreService storeService;
	@Autowired
	private MempoolService mempoolService;
	// Resolved lazily to avoid store/service cycles involving CasperService.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.CasperService> casperServiceProvider;
	// Resolved lazily to avoid store/service cycles involving GhostService.
	@Autowired
	protected org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.GhostService> ghostServiceProvider;
	 

	// default start sync of chain and non chain data
	public void startSingleProcess() throws BlockStoreException {
		TXReward my = null;
		BlockStoreInterface store = storeService.getStore();
		try {
			my = cacheBlockService.getMaxConfirmedReward(store);
		} finally {
			store.close();
		}
		if (my != null) {
			startSingleProcess(my.getChainLength() - 100, true);
		}
	}

	/**
	 * Persistent sync worker: a sync cycle that overruns its schedule is
	 * allowed to FINISH. Cancelling/interrupting it mid-flight (the previous
	 * submit+get(timeout)+cancel(true)+shutdownNow pattern) killed the in-
	 * progress block fetch exactly when it reached the slow WAN peer — the
	 * only peer holding the missing blocks — so a lagging node could never
	 * download from it and fell permanently behind (observed as
	 * InterruptedIOException "interrupted" on every getBlockByHash to the far
	 * node). Overlap is prevented by the flag instead of interruption.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean syncRunning = new java.util.concurrent.atomic.AtomicBoolean(
			false);
	private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "sync-worker");
		t.setDaemon(true);
		return t;
	});

	public void startSingleProcess(Long chainlength, boolean nonchain) throws BlockStoreException {
		if (!syncRunning.compareAndSet(false, true)) {
			log.debug(" sync already running, skipping tick ");
			return;
		}
		syncExecutor.submit(() -> {
			try {
				startSingleProcessDo(chainlength, nonchain);
			} catch (Exception e) {
				log.debug(" sync error ", e);
			} finally {
				syncRunning.set(false);
			}
		});
	}

	public void startSingleProcessDo(Long chainlength, boolean nonchain) throws BlockStoreException {

		BlockStoreInterface store = storeService.getStore();
		try {
			LockObject lock = store.selectLockobject(LOCKID);
			boolean canrun = false;
			if (lock == null) {
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else if (lock.getLocktime() < System.currentTimeMillis() - scheduleConfiguration.getSyncrate() * 100) {
				log.info("sync   out date delete and insert: " + Utils.dateTimeFormat(lock.getLocktime()));
				store.deleteLockobject(LOCKID);
				store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
				canrun = true;
			} else {
				log.info("sync running  at start = " + Utils.dateTimeFormat(lock.getLocktime()));
			}
			if (canrun) {
				Stopwatch watch = Stopwatch.createStarted();
				// Bulk repair FIRST: pull every non-chain block above our tip
				// in pages before the expensive reactive walks. When far
				// behind, the reactive per-reference fetches could never
				// converge (each 500+ beacon × N refs round-trip took minutes
				// while the mesh kept producing), and this bulk pass ran last
				// — effectively never. With transfers present locally, the
				// passes below connect queued beacons immediately.
				syncNonChained(store);
				connectingOrphans(store);
				syncChain(-1L, false, store);
				requestMissingReferenced(store);
				syncMempool(store);
				store.deleteLockobject(LOCKID);
				// if (watch.elapsed(TimeUnit.MILLISECONDS) > 1000)
				log.info("sync time {} ms.", watch.elapsed(TimeUnit.MILLISECONDS));
				watch.stop();
			}
		} catch (Exception e) {
			log.error("sync ", e);
			if (!e.getLocalizedMessage().contains("java.sql.SQLIntegrityConstraintViolationException")) {
				store.deleteLockobject(LOCKID);
			}
		} finally {
			store.close();
		}

	}

	public void startInit() throws Exception {

		BlockStoreInterface store = storeService.getStore();
		try {
			log.debug(" Start SyncBlockService startInit: ");
			cleanupChainBlockQueue(store);
			store.deleteAllLockobject();
			syncChain(-1l, true, store);
			// Bulk repair BEFORE the reactive walk (same ordering as
			// startSingleProcessDo). blocksFromChainLength only serves blocks
			// already height-assigned on the remote, so referenced transfer
			// blocks are routinely absent after the chain pull — and
			// requestMissingReferenced then fetched them one HTTP round-trip
			// per hash (hours on a cold node while serviceReady stays false).
			// The paged non-chain pull covers them in bulk; the per-hash walk
			// below only handles the leftovers.
			connectingOrphans(store);
			syncNonChained(store);
			requestMissingReferenced(store);
			blockgraph.updateChain();
			log.debug(" end startInit: ");
		} finally {
			store.close();
		}

	}

	public void requestPrev(Block block, BlockStoreInterface store) {
		try {
			if (block.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
				return;
			}

			Block storedBlock0 = null;

			storedBlock0 = blockService.getBlock(block.getPrevBlockHash(), store);

			if (storedBlock0 == null) {
				byte[] re = requestBlock(block.getPrevBlockHash(), store);
				if (re != null) {
					Block req = (Block) networkParameters.getDefaultSerializer().makeBlock(re);
					blockgraph.addBlock(req, true, store);
				}
			}
			Block storedBlock1 = null;

			storedBlock1 = blockService.getBlock(block.getPrevBranchBlockHash(), store);

			if (storedBlock1 == null) {
				byte[] re = requestBlock(block.getPrevBranchBlockHash(), store);
				if (re != null) {
					Block req = networkParameters.getDefaultSerializer().makeBlock(re);
					blockgraph.addBlock(req, true, store);
				}
			}
		} catch (Exception e) {
			log.debug("", e);
		}
	}

	public long getTimeSeconds(int days) throws Exception {
		return System.currentTimeMillis() / 1000 - days * 60 * 24 * 60L;
	}

	public byte[] requestBlock(Sha256Hash hash, BlockStoreInterface store) {
		// block from network peers
		// log.debug("requestBlock" + hash.toString());
		// Never HTTP-ask ourselves: the caller already checked the local
		// store, and a self-request queues behind this node's busy Tomcat
		// workers (~1 s under repair load) for a block we just said we do
		// not have. Only remote peers can help here. Peers are matched by
		// port — unique per node in the mesh.
		String ownPort = ":" + serverConfiguration.getPort();
		String[] re = serverConfiguration.getRequester().split(",");
		List<String> badserver = new ArrayList<>();
		byte[] data = null;
		for (String s : re) {
			s = s == null ? null : s.trim();
			if (s != null && !s.isEmpty() && !s.endsWith(ownPort) && !badserver(badserver, s)) {
				HashMap<String, String> requestParam = new HashMap<String, String>();
				requestParam.put("hashHex", Utils.HEX.encode(hash.getBytes()));
				try {
					data = OkHttp3Util.postAndGetBlock(s.trim() + "/" + ReqCmd.getBlockByHash,
							Json.jsonmapper().writeValueAsString(requestParam));
					Block block = networkParameters.getDefaultSerializer().makeBlock(data);
					log.info("   requestBlock {} ", block.getHashAsString());
					blockgraph.addFromSync(block, true, store);
					break;
				} catch (Exception e) {
					log.debug(hash + s, e);

					badserver.add(s);
				}
			}
		}
		return data;
	}

	public boolean anyMatchConfirmedReward(Block block, List<TXReward> remotes) {
		if (block.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
			return remotes.stream().anyMatch(s -> s.getBlockHash().equals(block.getHash()));
		} else {
			return true;
		}

	}

	public void requestBlocks(long chainlengthstart, long chainlengthend, String s, BlockStoreInterface store)
			throws IOException, ProtocolException, BlockStoreException, NoBlockException {

		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("start", chainlengthstart + "");
		requestParam.put("end", chainlengthend + "");

		byte[] response = OkHttp3Util.postString(s.trim() + "/" + ReqCmd.blocksFromChainLength,
				Json.jsonmapper().writeValueAsString(requestParam));
		GetBlockListResponse blockbytelist = Json.jsonmapper().readValue(response, GetBlockListResponse.class);
		log.debug("block size: " + blockbytelist.getBlockbytelist().size() + " remote chain start: " + chainlengthstart
				+ " end: " + chainlengthend + " at server: " + s);
		List<Block> sortedBlocks = new ArrayList<Block>();
		for (byte[] data : blockbytelist.getBlockbytelist()) {
			sortedBlocks.add(networkParameters.getDefaultSerializer().makeBlock(data));

		}
		Collections.sort(sortedBlocks, new SortbyBlock());

		for (Block block : sortedBlocks) {
			// no genesis block and no spend pending set
			if (block.getHeight() > 0) {
				try {
					blockgraph.addFromSync(block, true, store);
				} catch (VerificationException e) {
					// A single malformed/poisoned remote block must not stall
					// the whole cross-chain sync forever: skip it and keep
					// pulling the rest of the chain. The block stays absent
					// locally; if it is ever needed as a dependency the
					// regular missing-predecessor handling reports it.
					log.warn("Skipping unverifiable synced block {} from {}: {}", block.getHash(), s,
							e.getMessage());
				}
			}
		}

	}

	public void requestNonĆhainBlocks(String s, BlockStoreInterface store)
			throws JsonProcessingException, IOException, ProtocolException, BlockStoreException, NoBlockException {

		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.CHAINLENGTH_CUTOFF);
		TXReward confirmedAtHeightReward = store.getRewardConfirmedAtHeight(chainlength);
		long cutoffHeight = store.get(confirmedAtHeightReward.getBlockHash()).getHeight();

		// Paged bulk repair: one response per page (ascending height). A full
		// catch-up can span hundreds of MB of JSON — a single unbounded
		// response exceeded Jackson's string limits and silently killed the
		// whole non-chain sync, permanently stalling lagging nodes. The page
		// walk resumes from the highest height seen until exhausted.
		final int PAGE_LIMIT = 300;
		final int MAX_PAGES = 500;
		long maxHeight = Long.MAX_VALUE;
		for (int page = 0; page < MAX_PAGES; page++) {
			HashMap<String, String> requestParam = new HashMap<String, String>();
			requestParam.put("cutoffHeight", cutoffHeight + "");
			requestParam.put("maxHeight", String.valueOf(maxHeight));
			requestParam.put("limit", PAGE_LIMIT + "");
			byte[] response = OkHttp3Util.postString(s.trim() + "/" + ReqCmd.blocksFromNonChainHeight,
					Json.jsonmapper().writeValueAsString(requestParam));
			GetBlockListResponse blockbytelist = Json.jsonmapper().readValue(response, GetBlockListResponse.class);
			log.info("non-chain sync page {} size {} from {}", page + 1, blockbytelist.getBlockbytelist().size(), s);
			if (blockbytelist.getBlockbytelist().isEmpty()) {
				break;
			}
			List<Block> sortedBlocks = new ArrayList<Block>();
			for (byte[] data : blockbytelist.getBlockbytelist()) {
				sortedBlocks.add(networkParameters.getDefaultSerializer().makeBlock(data));
			}
			Collections.sort(sortedBlocks, new SortbyBlock());
			for (Block block : sortedBlocks) {
				// no genesis block and no spend pending set
				if (block.getHeight() > 0) {
					blockgraph.addBlock(block, true, store);
				}
			}
			long lowest = sortedBlocks.get(0).getHeight();
			if (lowest >= maxHeight) {
				break; // no progress possible
			}
			maxHeight = lowest - 1;
		}

	}

	/*
	 * The reward chain references its predecessor by prevRewardHash and the
	 * RewardInfo.getBlocks() set. When a beacon is queued but its referenced
	 * blocks are not yet local (a fork, or a node that fell behind), nothing
	 * actively fetched those blocks: processChainConnected only retries the
	 * queue in place and connectingOrphans only covers orphan=true entries.
	 * Walk every queued beacon and fetch each missing referenced block from
	 * the requester mesh, so a lagging node can actually catch up to the
	 * canonical chain instead of retrying forever.
	 *
	 * Fetching is BATCHED: the missing hashes are collected first and pulled
	 * with getBlocksByHashList (chunks per requester). The old one-HTTP-call-
	 * per-hash walk lost the race against chain growth on any mesh where the
	 * nearest requester misses some blocks (two round-trips per ref, single
	 * thread). Ancestors discovered inside fetched blocks are collected and
	 * fetched in the next round; rounds repeat until no progress. If no
	 * requester supports the batch endpoint (rolling upgrade), fall back to
	 * the legacy per-hash walk.
	 */
	public void requestMissingReferenced(BlockStoreInterface store) throws BlockStoreException {
		List<ChainBlockQueue> cbs = store.selectChainblockqueue(false, serverConfiguration.getSyncblocks());
		if (cbs == null || cbs.isEmpty()) {
			return;
		}
		// Hashes of the blocks on the LOCAL CONFIRMED reward chain. A fetched
		// block that merely exists in the store (present but unconnected) is
		// NOT a valid anchor: the walk below must fetch every missing ancestor
		// all the way down to the confirmed chain (or genesis), otherwise the
		// gap left in the middle keeps the queued beacon unsolid forever.
		Set<Sha256Hash> confirmed = new HashSet<>();
		Sha256Hash cur = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
		int guard = 0;
		while (cur != null && confirmed.add(cur) && guard++ < 100000) {
			Block b = store.get(cur);
			if (b == null) {
				break;
			}
			if (b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
				break;
			}
			try {
				RewardInfo ri = new RewardInfo().parseChecked(b.getTransactions().get(0).getData());
				cur = ri.getPrevRewardHash();
			} catch (Exception e) {
				break;
			}
		}

		// ---- Collect every missing hash reachable from the queued beacons --
		// prev-reward chains are walked through LOCAL blocks only; unknown
		// hashes go straight into the missing set (their own ancestors are
		// picked up after they arrive, in a later round).
		Set<Sha256Hash> missing = new LinkedHashSet<>();
		// Locally PENDING bytes count as present: a queued beacon's parent is
		// often itself queued (connect is head-of-line). Listing it as missing
		// made every cycle re-fetch identical hashes from peers while the real
		// blocker was the connect order.
		Set<Sha256Hash> queuedLocal = new HashSet<>();
		try {
			for (net.bigtangle.server.data.ChainBlockQueue cbq : store.selectChainblockqueue(false, 10_000)) {
				queuedLocal.add(Sha256Hash.wrap(cbq.getHash()));
			}
		} catch (Exception e) {
			log.debug("queued-hash scan failed: {}", e.getMessage());
		}
		// BFS through the LOCAL DAG: a queued beacon's referenced blocks may be
		// present but unsolid because one of THEIR predecessors is absent.
		// Without expanding requirements recursively, a single deep hole
		// starves every descendant and the whole queue defers forever while
		// the fetch set looks empty.
		java.util.ArrayDeque<Sha256Hash> expand = new java.util.ArrayDeque<>();
		Set<Sha256Hash> visited = new HashSet<>();
		int expansions = 0;
		for (ChainBlockQueue cb : cbs) {
			Block block = networkParameters.getDefaultSerializer().makeBlock(cb.getBlock());
			if (block.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
				continue;
			}
			try {
				RewardInfo ri = new RewardInfo().parseChecked(block.getTransactions().get(0).getData());
				Sha256Hash prev = ri.getPrevRewardHash();
				guard = 0;
				while (prev != null && guard++ < 10000) {
					if (confirmed.contains(prev) || queuedLocal.contains(prev) || !missing.add(prev)) {
						break;
					}
					Block local = blockService.getBlock(prev, store);
					if (local == null) {
						break; // unknown — fetched in a later round
					}
					if (visited.add(prev)) {
						expand.push(prev);
					}
					prev = rewardParent(local);
				}
				for (Sha256Hash h : ri.getBlocks()) {
					Block local = blockService.getBlock(h, store);
					if (local == null) {
						if (!queuedLocal.contains(h)) {
							missing.add(h);
						}
					} else if (visited.add(h)) {
						expand.push(h);
					}
				}
			} catch (Exception e) {
				log.debug("requestMissingReferenced {} : {}", block.getHash(), e.getMessage());
			}
		}
		ServiceBaseCheck serviceBase = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper);
		while (!expand.isEmpty() && expansions++ < 50000) {
			Sha256Hash h = expand.pop();
			try {
				Block local = blockService.getBlock(h, store);
				if (local == null || local.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
					continue;
				}
				for (Sha256Hash req : serviceBase.getAllRequiredBlockHashes(local)) {
					if (confirmed.contains(req) || visited.contains(req)) {
						continue;
					}
					Block reqLocal = blockService.getBlock(req, store);
					if (reqLocal == null) {
						if (!queuedLocal.contains(req)) {
							missing.add(req);
						}
					} else if (visited.add(req)) {
						expand.push(req);
					}
				}
			} catch (Exception e) {
				log.debug("requestMissingReferenced expand {}: {}", h, e.getMessage());
			}
		}
		if (missing.isEmpty()) {
			return;
		}
		log.info("requestMissingReferenced: fetching {} missing block(s) in batches, sample: {}", missing.size(),
				missing.stream().limit(3).map(Sha256Hash::toString).reduce((a, b) -> a + "," + b).orElse(""));

		// ---- Batched rounds until no progress ------------------------------
		int rounds = 0;
		while (!missing.isEmpty() && rounds++ < 100) {
			Set<Sha256Hash> fetched = requestBlocksByHashes(missing, store);
			if (fetched.isEmpty()) {
				break;
			}
			missing.removeAll(fetched);
			// Requirements of newly arrived blocks may themselves be missing:
			// push them through the same recursive expansion.
			for (Sha256Hash h : fetched) {
				if (visited.add(h)) {
					expand.push(h);
				}
			}
			while (!expand.isEmpty() && expansions++ < 50000) {
				Sha256Hash h = expand.pop();
				try {
					Block local = blockService.getBlock(h, store);
					if (local == null || local.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
						continue;
					}
					for (Sha256Hash req : serviceBase.getAllRequiredBlockHashes(local)) {
						if (confirmed.contains(req)) {
							continue;
						}
						Block reqLocal = blockService.getBlock(req, store);
						if (reqLocal == null) {
							missing.add(req);
						} else if (visited.add(req)) {
							expand.push(req);
						}
					}
				} catch (Exception e) {
					log.debug("requestMissingReferenced expand {}: {}", h, e.getMessage());
				}
			}
		}
		if (!missing.isEmpty()) {
			// Last resort before giving up, in two tiers:
			// (1) this node's OWN pending-connect queue holds the bytes;
			// (2) the kafka consumer's retry buffer holds raw bytes whose
			//     ingest kept failing — retry them once here.
			// Either way the block becomes locally stored and servable.
			int recovered = 0;
			try {
				for (net.bigtangle.server.data.ChainBlockQueue cb : store.selectChainblockqueue(false, 10_000)) {
					if (missing.remove(Sha256Hash.wrap(cb.getHash()))) {
						blockService.addConnectedFromKafka(cb.getHash(), cb.getBlock());
						recovered++;
					}
				}
			} catch (Exception e) {
				log.debug("local queue recovery failed: {}", e.getMessage());
			}
			try {
				java.util.Map<String, byte[]> buffered = blockStreamHandler.retryBytesFor(missing);
				for (java.util.Map.Entry<String, byte[]> en : buffered.entrySet()) {
					blockService.addConnectedFromKafka(en.getKey().getBytes(), en.getValue());
					missing.remove(Sha256Hash.wrap(Utils.HEX.decode(en.getKey())));
					recovered++;
				}
			} catch (Exception e) {
				log.debug("retry-buffer recovery failed: {}", e.getMessage());
			}
			if (recovered > 0) {
				log.info("requestMissingReferenced: recovered {} block(s) from local pending queue/retry buffer",
						recovered);
			}
		}
		if (!missing.isEmpty()) {
			log.info("requestMissingReferenced: {} block(s) not served by any requester, sample: {}", missing.size(),
					missing.stream().limit(5).map(Sha256Hash::toString)
							.reduce((a, b) -> a + "," + b).orElse(""));
		}
	}

	/** Batch size for getBlocksByHashList chunks (bytes-safe small blocks). */
	private static final int HASHLIST_CHUNK = 200;

	/** Local confirmed tip's reward-chain length (the readiness position). */
	public long getLocalConfirmedChainLength(BlockStoreInterface store) throws BlockStoreException {
		return cacheBlockService.getMaxConfirmedReward(store).getChainLength();
	}

	/** Fresh store handle for callers coordinating sync/readiness waits. */
	public BlockStoreInterface getStore() throws BlockStoreException {
		return storeService.getStore();
	}

	/**
	 * Highest FINALIZED reward-chain length advertised by any requester, or -1
	 * when no requester reports one (old peer, or no finality yet). This is the
	 * readiness target for a joining node: executing through the peers'
	 * finalized checkpoint guarantees an identical UTXO state as of that point,
	 * whereas chasing the moving head may never terminate.
	 */
	public long getMaxPeerFinalizedChainLength() {
		long max = -1;
		for (String s : serverConfiguration.getRequester().split(",")) {
			s = s == null ? null : s.trim();
			if (s == null || s.isEmpty()) {
				continue;
			}
			try {
				byte[] response = OkHttp3Util.postString(s + "/" + ReqCmd.getChainNumber,
						Json.jsonmapper().writeValueAsString(new HashMap<String, String>()));
				GetTXRewardResponse r = Json.jsonmapper().readValue(response, GetTXRewardResponse.class);
				if (r != null && r.getFinalizedChainLength() != null
						&& r.getFinalizedChainLength() > max) {
					max = r.getFinalizedChainLength();
				}
			} catch (Exception e) {
				log.debug("peer finalized length {}: {}", s, e.getMessage());
			}
		}
		return max;
	}

	/**
	 * Pull the given hashes via the getBlocksByHashList batch endpoint, trying
	 * each requester in order; the first requester answering without error
	 * serves the whole chunk. Blocks are added with {@code blockgraph
	 * .addFromSync(block, true, store)} exactly like the legacy path. Returns
	 * the subset of requested hashes that was fetched and added.
	 */
	private Set<Sha256Hash> requestBlocksByHashes(Set<Sha256Hash> hashes, BlockStoreInterface store) {
		Set<Sha256Hash> fetched = new HashSet<>();
		List<Sha256Hash> list = new ArrayList<>(hashes);
		String[] re = serverConfiguration.getRequester().split(",");
		for (int from = 0; from < list.size(); from += HASHLIST_CHUNK) {
			List<Sha256Hash> chunk = list.subList(from, Math.min(from + HASHLIST_CHUNK, list.size()));
			HashMap<String, Object> requestParam = new HashMap<>();
			List<String> hexs = new ArrayList<>();
			for (Sha256Hash h : chunk) {
				hexs.add(Utils.HEX.encode(h.getBytes()));
			}
			requestParam.put("hashHexs", hexs);
			boolean served = false;
			for (String s : re) {
				s = s == null ? null : s.trim();
				if (s == null || s.isEmpty()) {
					continue;
				}
				try {
					byte[] response = OkHttp3Util.postString(s + "/" + ReqCmd.getBlocksByHashList,
							Json.jsonmapper().writeValueAsString(requestParam));
					GetBlockListResponse blocklist = Json.jsonmapper().readValue(response, GetBlockListResponse.class);
					served = true;
					if (blocklist.getBlockbytelist() == null) {
						break;
					}
					for (byte[] data : blocklist.getBlockbytelist()) {
						try {
							Block block = networkParameters.getDefaultSerializer().makeBlock(data);
							blockgraph.addFromSync(block, true, store);
							fetched.add(block.getHash());
						} catch (Exception e) {
							log.debug("batch fetch parse {}: {}", s, e.getMessage());
						}
					}
					break;
				} catch (Exception e) {
					log.debug("batch fetch from {}: {}", s, e.getMessage());
				}
			}
			if (!served) {
				// No requester supports the batch endpoint (rolling upgrade):
				// fall back to the legacy per-hash walk for this chunk.
				for (Sha256Hash h : chunk) {
					try {
						byte[] data = requestBlock(h, store);
						if (data != null) {
							fetched.add(h);
						}
					} catch (Exception e) {
						log.debug("legacy fetch {}: {}", h, e.getMessage());
					}
				}
			}
		}
		return fetched;
	}

	private Sha256Hash rewardParent(Block b) {
		if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
			return null;
		}
		try {
			return new RewardInfo().parseChecked(b.getTransactions().get(0).getData()).getPrevRewardHash();
		} catch (Exception e) {
			return null;
		}
	}

	public GetTXRewardResponse getMaxConfirmedRewardResponse(String server) throws JsonProcessingException, IOException {

		HashMap<String, String> requestParam = new HashMap<String, String>();

		byte[] response = OkHttp3Util.postString(server.trim() + "/" + ReqCmd.getChainNumber,
				Json.jsonmapper().writeValueAsString(requestParam));

		return Json.jsonmapper().readValue(response, GetTXRewardResponse.class);

	}
	public TXReward getMaxConfirmedReward(String server) throws JsonProcessingException, IOException {
		return getMaxConfirmedRewardResponse(server).getTxReward();
	}

	public List<TXReward> getAllConfirmedReward(String s) throws JsonProcessingException, IOException {

		HashMap<String, String> requestParam = new HashMap<String, String>();

		byte[] response = OkHttp3Util.postString(s.trim() + "/" + ReqCmd.getAllConfirmedReward,
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTXRewardListResponse aTXRewardResponse = Json.jsonmapper().readValue(response,
				GetTXRewardListResponse.class);

		return aTXRewardResponse.getTxReward();

	}

	public boolean badserver(List<String> badserver, String s) {
		for (String d : badserver) {
			if (d.equals(s))
				return true;
		}
		return false;
	}

	/*
	 * switch chain select * from txreward where confirmed=1 chainlength with my
	 */
	public static class MaxConfirmedReward {
		String server;
		TXReward aTXReward;
		// Remote's advertised Casper finality (hex hash), null when absent.
		String finalizedBlockHash;
		// Accumulated LMD-GHOST fork-choice weight of the remote's head. Higher
		// wins: the chain with the most validator attestation weight is canonical
		// in PoS, regardless of raw length.
		long forkChoiceWeight = Long.MIN_VALUE;
	}

	public void syncChain(Long chainlength, boolean initsync, BlockStoreInterface store) throws BlockStoreException {
		String[] re = serverConfiguration.getRequester().split(",");
		MaxConfirmedReward aMaxConfirmedReward = new MaxConfirmedReward();
		TXReward my = cacheBlockService.getMaxConfirmedReward(store);
		if (chainlength > -1) {
			TXReward my1 = store.getRewardConfirmedAtHeight(chainlength);
			if (my1 != null)
				my = my1;
		}
		log.debug(" my chain length " + my.getChainLength() + " remote " + re[0]);
		// PoS fork choice: the canonical chain is the LMD-GHOST head
		// (attestation-weighted from the highest justified checkpoint), NOT the
		// longest chain. Rank remotes by the fork-choice weight of their
		// advertised head so a node syncing from a longer but minority fork
		// (1/3 of validators) prefers the majority chain instead of forever
		// pulling its own minority fork. Longest-chain remains the tie-break.
		for (String s : re) {
			try {
				if (s != null && !"".equals(s)) {
					GetTXRewardResponse aTXRewardResponse = getMaxConfirmedRewardResponse(s.trim());
					if (aTXRewardResponse == null || aTXRewardResponse.getTxReward() == null) {
						continue;
					}
					if (finalityConflicts(s.trim(), aTXRewardResponse, store)) {
						continue;
					}
					TXReward aTXReward = aTXRewardResponse.getTxReward();
					long remoteWeight = remoteForkChoiceWeight(aTXReward.getBlockHash(), store);
					if (aMaxConfirmedReward.aTXReward == null) {
						aMaxConfirmedReward.server = s.trim();
						aMaxConfirmedReward.aTXReward = aTXReward;
						aMaxConfirmedReward.finalizedBlockHash = aTXRewardResponse.getFinalizedBlockHash();
						aMaxConfirmedReward.forkChoiceWeight = remoteWeight;
					} else if (remoteWeight > aMaxConfirmedReward.forkChoiceWeight
							|| (remoteWeight == aMaxConfirmedReward.forkChoiceWeight
									&& aTXReward.getChainLength() > aMaxConfirmedReward.aTXReward
											.getChainLength())) {
						aMaxConfirmedReward.server = s.trim();
						aMaxConfirmedReward.aTXReward = aTXReward;
						aMaxConfirmedReward.finalizedBlockHash = aTXRewardResponse.getFinalizedBlockHash();
						aMaxConfirmedReward.forkChoiceWeight = remoteWeight;
					}
				}
			} catch (Exception e) {
				log.debug("", e);
			}
		}
		// Sync only from the server with the longest known chain (or an
		// equal-length competing fork) so a single download pass covers the whole
		// gap instead of restarting from the beginning for every server.
		if (aMaxConfirmedReward.server != null) {
			try {
				syncMaxConfirmedReward(aMaxConfirmedReward, my, initsync, store);
			} catch (Exception e) {
				log.debug("", e);
			}
		}

	}

	/**
	 * A remote that has finalized a DIFFERENT checkpoint than ours cannot be
	 * synced from: every one of its chains would be refused by
	 * connectRewardBlock (which never reorgs finalized history), so we would
	 * download the whole fork and never converge. The skip only fires when we
	 * can PROVE the remote's finalized block (known in our store) is not our own
	 * finalized checkpoint — an unknown or absent remote finality is allowed and
	 * left to the connect-side guard.
	 */	/**
	 * PoS fork-choice weight of a remote's advertised head: the accumulated
	 * LMD-GHOST attestation weight of its subtree. A head on the majority chain
	 * carries most of the validator weight, so ranking remotes by this prefers
	 * the canonical chain over a longer but minority (1/3) fork. Falls back to 0
	 * when the head is unknown locally or GHOST state is unavailable, restoring
	 * the previous longest-chain behaviour.
	 */
	private long remoteForkChoiceWeight(Sha256Hash remoteHead, BlockStoreInterface store) {
		net.bigtangle.server.service.GhostService ghost = ghostServiceProvider.getIfAvailable();
		if (ghost == null) {
			return 0;
		}
		try {
			java.util.Map<Sha256Hash, Long> weights = ghost.getForkChoiceVotes();
			if (weights == null) {
				return 0;
			}
			// Accumulate the remote head's subtree weight (mirrors
			// GhostService.executeGhost). The fork-choice weight map is shared
			// across nodes via gossiped attestations, so this is a peer's
			// position in the canonical fork choice.
			java.util.Map<Sha256Hash, Long> memo = new java.util.HashMap<>();
			java.util.Set<Sha256Hash> inProgress = new java.util.HashSet<>();
			return subtreeWeightOf(remoteHead, weights, memo, inProgress, store, 0);
		} catch (Exception e) {
			log.debug("remoteForkChoiceWeight failed for {}: {}", remoteHead, e.getMessage());
			return 0;
		}
	}

	/**
	 * Accumulated attestation weight of the subtree rooted at {@code hash},
	 * mirroring GhostService.subtreeWeight so the sync target ranks by the same
	 * LMD-GHOST weight the fork choice uses.
	 */
	private long subtreeWeightOf(Sha256Hash hash, java.util.Map<Sha256Hash, Long> weights,
			java.util.Map<Sha256Hash, Long> memo, java.util.Set<Sha256Hash> inProgress,
			BlockStoreInterface store, int depth) {
		if (hash == null || depth >= net.bigtangle.server.service.CasperService.ATTESTATION_LOOKBACK_SLOTS) {
			return 0;
		}
		Long cached = memo.get(hash);
		if (cached != null) {
			return cached;
		}
		if (!inProgress.add(hash)) {
			return 0;
		}
		long sum = weights.getOrDefault(hash, 0L);
		try {
			for (Sha256Hash child : store.getRewardChainChildren(hash)) {
				sum += subtreeWeightOf(child, weights, memo, inProgress, store, depth + 1);
			}
		} catch (Exception e) {
			log.debug("subtreeWeightOf failed for {}: {}", hash, e.getMessage());
		}
		inProgress.remove(hash);
		memo.put(hash, sum);
		return sum;
	}

	private boolean finalityConflicts(String server, GetTXRewardResponse remote, BlockStoreInterface store) {		String remoteFinalizedHex = remote.getFinalizedBlockHash();
		if (remoteFinalizedHex == null || remoteFinalizedHex.isEmpty()) {
			return false;
		}
		net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
		if (casper == null) {
			return false;
		}
		net.bigtangle.server.service.CasperService.Checkpoint finalized = casper.getLastFinalizedCheckpoint();
		if (finalized == null) {
			return false;
		}
		Sha256Hash remoteFinalized;
		try {
			remoteFinalized = Sha256Hash.wrap(Utils.HEX.decode(remoteFinalizedHex));
		} catch (Exception e) {
			log.debug("invalid remote finalized hash from {}: {}", server, remoteFinalizedHex);
			return false;
		}
		if (remoteFinalized.equals(finalized.getBlockHash())) {
			return false;
		}
		// Different finalized checkpoint: skip only if the remote's finalized
		// block is actually present in our store (so it is provably a
		// conflicting-finality branch, not merely a node ahead of us).
		boolean knownLocally;
		try {
			knownLocally = blockService.getBlock(remoteFinalized, store) != null;
		} catch (Exception e) {
			knownLocally = false;
		}
		if (!knownLocally) {
			return false;
		}
		// Same-branch reconciliation: when the remote's finalized checkpoint is
		// an ancestor or a descendant of ours (both blocks on one reward chain),
		// the remote is merely ahead/behind on OUR chain — never a competing
		// finality. Skipping such a peer was the root cause of the recurring
		// "one node runs ahead, the others refuse to sync and stay stuck"
		// divergence: the lagging node saw the leader's NEWER finalized
		// checkpoint, treated it as a conflict, and refused the very chain it
		// needed. Only a true SIBLING fork (neither checkpoints on the other's
		// branch) is a real finality conflict.
		try {
			if (finalityOnSameBranch(remoteFinalized, finalized.getBlockHash(), store)) {
				return false;
			}
		} catch (Exception e) {
			log.debug("finality ancestry walk failed for {}: {}", server, e.getMessage());
		}
		// True SIBLING fork: both finalized checkpoints are known locally and
		// neither lies on the other's branch. Skipping sync forever (the old
		// behaviour) strands a node that ends up on a minority fork: it can
		// neither pull the majority chain nor reorg onto it (connectRewardBlock
		// refuses chains that do not descend from OUR finalized checkpoint).
		// Observed live on the 3-node prod mesh: one validator confirmed a
		// private branch for 60+ chainlengths while the other two agreed.
		// Heal instead: when the remote head is meaningfully LONGER than ours,
		// the remote branch carries the majority weight — revert our local
		// checkpoints above the common ancestor so the finality/justified
		// reorg guards accept the majority chain, then let sync proceed.
		if (tryReconcileSiblingFork(server, remoteFinalized, finalized, remote, store)) {
			return false;
		}
		log.info("Skip sync from {}: remote finalized {} conflicts with local finalized {}",
				server, remoteFinalized, finalized.getBlockHash());
		return true;
	}

	/**
	 * Chainlength lead the remote must have before we unwind a conflicting
	 * local finality. A minority fork grows slower than the majority chain, so
	 * a small margin is strong majority evidence; too small and a brief
	 * partition could flip a healthy node. Tune via {@code pos.finalityReconcileMargin}.
	 */
	private static final long FINALITY_RECONCILE_MARGIN = Long.getLong("pos.finalityReconcileMargin", 4);

	/**
	 * Attempts to heal a true sibling-fork finality conflict by adopting the
	 * majority (longer) chain. Returns true when reconciliation was performed,
	 * i.e. local checkpoints above the common ancestor were reverted and sync
	 * from the conflicting peer may now proceed.
	 *
	 * <p>This deliberately reverts a LOCAL finalized checkpoint — under Casper
	 * that is a safety violation, but two conflicting finalized checkpoints
	 * mean the violation has ALREADY occurred network-wide; the alternatives
	 * are a permanent partition or an operator-triggered reset. Strongly
	 * gated: only fires when the remote head leads by
	 * {@link #FINALITY_RECONCILE_MARGIN} chainlengths, and only rewinds to the
	 * common ancestor (never touches history below it).
	 */
	private boolean tryReconcileSiblingFork(String server, Sha256Hash remoteFinalized,
			net.bigtangle.server.service.CasperService.Checkpoint localFinalized, GetTXRewardResponse remote,
			BlockStoreInterface store) {
		try {
			TXReward myTip = store.getMaxConfirmedReward();
			long myChainlength = myTip != null ? myTip.getChainLength() : 0;
			long remoteHeadChainlength = remote.getTxReward() != null ? remote.getTxReward().getChainLength() : 0;
			if (remoteHeadChainlength <= myChainlength + FINALITY_RECONCILE_MARGIN) {
				return false;
			}
			Sha256Hash ancestor = commonRewardAncestor(remoteFinalized, localFinalized.getBlockHash(), store);
			if (ancestor == null) {
				log.info("Finality reconcile skipped: no common ancestor of {} and {} in local store",
						remoteFinalized, localFinalized.getBlockHash());
				return false;
			}
			long ancestorChainlength = store.getRewardChainLength(ancestor);
			long ancestorEpoch = Math.max(0, ancestorChainlength / SlotService.SLOTS_PER_EPOCH);
			net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
			if (casper == null) {
				return false;
			}
			// Drop cached AND persisted checkpoints above the ancestor so
			// connectRewardBlock's finalized/justified descent guards accept
			// the majority chain. Checkpoints at/below the ancestor stay —
			// both branches descend from them, so they remain valid.
			casper.invalidateCheckpointsFrom(ancestorEpoch + 1, store);
			log.warn("FINALITY RECONCILE (safety violation healed): peer {} head cl {} vs local cl {} with "
					+ "conflicting finalized checkpoints; reverted local checkpoints above epoch {} "
					+ "(common ancestor {} @ cl {}) to adopt the majority chain",
					server, remoteHeadChainlength, myChainlength, ancestorEpoch, ancestor, ancestorChainlength);
			return true;
		} catch (Exception e) {
			log.warn("Finality reconciliation failed for {}: {}", server, e.getMessage());
			return false;
		}
	}

	/**
	 * Deepest common ancestor of two reward-chain blocks on the fork they
	 * share. Walks {@code a}'s prev links into a set, then walks {@code b}
	 * until it hits that set. Both blocks must already exist locally (the
	 * caller only gets here after the knownLocally probe).
	 */
	private Sha256Hash commonRewardAncestor(Sha256Hash a, Sha256Hash b, BlockStoreInterface store) throws Exception {
		java.util.Set<Sha256Hash> seen = new java.util.HashSet<>();
		Sha256Hash cur = a;
		for (int i = 0; i < FINALITY_ANCESTRY_WALK_LIMIT && cur != null && seen.add(cur); i++) {
			Block blk = blockService.getBlock(cur, store);
			if (blk == null) {
				break;
			}
			cur = blk.getPrevBlockHash();
		}
		cur = b;
		for (int i = 0; i < FINALITY_ANCESTRY_WALK_LIMIT && cur != null; i++) {
			if (seen.contains(cur)) {
				return cur;
			}
			Block blk = blockService.getBlock(cur, store);
			if (blk == null) {
				return null;
			}
			cur = blk.getPrevBlockHash();
		}
		return null;
	}

	/** Max reward-chain steps to prove a finalized checkpoint is on our branch. */
	private static final int FINALITY_ANCESTRY_WALK_LIMIT = 100000;

	/**
	 * True when {@code a} is an ancestor-or-self of {@code b} on the reward
	 * chain (walking {@code b}'s prev-block links reaches {@code a}).
	 */
	private boolean isRewardAncestor(Sha256Hash a, Sha256Hash b, BlockStoreInterface store) throws Exception {
		Sha256Hash cur = b;
		for (int i = 0; i < FINALITY_ANCESTRY_WALK_LIMIT; i++) {
			if (cur == null) {
				return false;
			}
			if (cur.equals(a)) {
				return true;
			}
			Block next = blockService.getBlock(cur, store);
			if (next == null || next.getPrevBlockHash() == null) {
				return false;
			}
			cur = next.getPrevBlockHash();
		}
		return false;
	}

	/** True when the two finalized checkpoints lie on the same reward chain. */
	private boolean finalityOnSameBranch(Sha256Hash a, Sha256Hash b, BlockStoreInterface store) throws Exception {
		return isRewardAncestor(a, b, store) || isRewardAncestor(b, a, store);
	}

	/*
	 * sync the remote data that not in chain
	 */
	public void syncNonChained(BlockStoreInterface store) throws BlockStoreException {
		String[] re = serverConfiguration.getRequester().split(",");
		for (String s : re) {
			if (s != null && !"".equals(s)) {
				try {
					requestNonĆhainBlocks(s, store);
				} catch (Exception e) {
					log.debug("", e);
				}
			}
		}
	}

	/*
	 * Fetch the pending transactions of the remote servers and submit them into
	 * the local mempool so all nodes converge on the same pending set even
	 * without a gossip mesh. Rejected or already-known transactions are skipped
	 * (submitTransaction enforces the full mempool verification).
	 */
	public void syncMempool(BlockStoreInterface store) {
		String[] re = serverConfiguration.getRequester().split(",");
		for (String s : re) {
			if (s != null && !"".equals(s)) {
				try {
					requestMempool(s.trim(), store);
				} catch (Exception e) {
					log.debug("syncMempool {} ", s, e);
				}
			}
		}
	}

	public void requestMempool(String server, BlockStoreInterface store) throws IOException {
		HashMap<String, String> requestParam = new HashMap<>();
		byte[] response = OkHttp3Util.postString(server.trim() + "/" + ReqCmd.getPendingTransactions,
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTransactionListResponse txlist = Json.jsonmapper().readValue(response, GetTransactionListResponse.class);
		if (txlist == null || txlist.getTransactionlist() == null) {
			return;
		}
		for (byte[] data : txlist.getTransactionlist()) {
			try {
				Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(data);
				if (tx.isCoinBase() || inLocalBlock(tx, store)) {
					continue;
				}
				mempoolService.submitTransaction(tx);
				try {
					TransactionStatusRecord.mark(store, tx, TransactionStatus.MEMPOOL, null, null, networkParameters);
				} catch (Exception e) {
					log.debug("mempool sync status mark failed for {}: {}", tx.getHash(), e.getMessage());
				}
				log.debug("mempool sync from {} accepted {}", server, tx.getHash());
			} catch (Exception e) {
				// already pending, conflicting, or invalid — skip
				log.debug("mempool sync from {} skip: {}", server, e.getMessage());
			}
		}
	}

	/** True when the tx hash is already recorded in a local block. */
	private boolean inLocalBlock(Transaction tx, BlockStoreInterface store) {
		try {
			TransactionStatusRecord record = store.getTransactionStatus(tx.getHash());
			if (record == null || record.getStatus() == null) {
				return false;
			}
			switch (record.getStatus()) {
			case BATCHED:
			case IN_BLOCK:
			case SOLID:
			case CONFIRMED:
				return true;
			default:
				return false;
			}
		} catch (Exception e) {
			log.debug("mempool sync status check failed for {}: {}", tx.getHash(), e.getMessage());
			return false;
		}
	}

	/*
	 * check difference to remote servers and does sync. ask the remote
	 * getMaxConfirmedReward to compare the my getMaxConfirmedReward if the remote
	 * has length > my length, then find the get the list of confirmed chains data.
	 * match the block hash to find the sync chain length, then sync the chain data.
	 */
	public void syncMaxConfirmedReward(MaxConfirmedReward aMaxConfirmedReward, TXReward my, boolean initsync,
			BlockStoreInterface store) throws Exception {

		if (my == null || aMaxConfirmedReward.aTXReward == null)
			return;
		log.debug("  remote chain length  " + aMaxConfirmedReward.aTXReward.getChainLength() + " server: "
				+ aMaxConfirmedReward.server + " my chain length " + my.getChainLength());

		long remoteLength = aMaxConfirmedReward.aTXReward.getChainLength();
		long myLength = my.getChainLength();
		// Longer chain, or an equal-length competing fork with a different head.
		// Equal-length forks must also be fetched: once the remote extends its
		// fork the parent blocks are already local, so a reorg to the longest
		// chain can complete in one pass instead of stalling on orphan
		// re-requests.
		boolean longer = remoteLength > myLength;
		boolean equalFork = remoteLength == myLength
				&& !aMaxConfirmedReward.aTXReward.getBlockHash().equals(my.getBlockHash());
		// PoS: also fetch a SHORTER remote chain when it wins the GHOST fork
		// choice (more accumulated attestation weight = canonical in PoS). The
		// remote's whole chain (down to the shared fork point) is pulled by
		// requestBlocks + requestMissingReferenced so the reorg can complete.
		boolean ghostPreferred = !longer && !equalFork
				&& aMaxConfirmedReward.forkChoiceWeight > myForkChoiceWeight(my.getBlockHash(), store);
		if (longer || equalFork || ghostPreferred) {

			log.debug(" start sync remote ChainLength: " + myLength + " to: " + remoteLength
					+ " ghostPreferred=" + ghostPreferred);

			for (long i = Math.min(myLength, remoteLength); i <= Math.max(myLength, remoteLength); i += serverConfiguration
					.getSyncblocks()) {
				Stopwatch watch = Stopwatch.createStarted();
				requestBlocks(i, i + serverConfiguration.getSyncblocks() - 1, aMaxConfirmedReward.server, store);
				if (initsync) {
					// log.debug(" updateChain " );

					blockgraph.processChainConnected(store, true, false);

				}
				log.debug(" synced second=" + watch.elapsed(TimeUnit.SECONDS));
				// checkPointDatabase(i);
			}

		}
		// log.debug(" finish sync " + aMaxConfirmedReward.server + " ");
	}

	/** Local LMD-GHOST subtree weight of a block, mirroring GhostService. */
	private long myForkChoiceWeight(Sha256Hash blockHash, BlockStoreInterface store) {
		net.bigtangle.server.service.GhostService ghost = ghostServiceProvider.getIfAvailable();
		if (ghost == null) {
			return 0;
		}
		try {
			java.util.Map<Sha256Hash, Long> weights = ghost.getForkChoiceVotes();
			if (weights == null) {
				return 0;
			}
			java.util.Map<Sha256Hash, Long> memo = new java.util.HashMap<>();
			java.util.Set<Sha256Hash> inProgress = new java.util.HashSet<>();
			return subtreeWeightOf(blockHash, weights, memo, inProgress, store, 0);
		} catch (Exception e) {
			return 0;
		}
	}

 
	public static class SortbyBlock implements Comparator<Block> {

		public int compare(Block a, Block b) {
			return Long.compare(a.getHeight(), b.getHeight());
		}
	}

	public static class SortbyChain implements Comparator<TXReward> {
		// Used for sorting in ascending order of chain length
		public int compare(TXReward a, TXReward b) {
			return Long.compare(a.getChainLength(), b.getChainLength());
		}
	}

	private TXReward findSync(List<TXReward> remotes, List<TXReward> mylist) throws Exception {
		for (TXReward my : mylist) {
			TXReward f = findSync(remotes, my);
			if (f != null)
				return f;
		}
		return null;
	}

	private TXReward findSync(List<TXReward> remotes, TXReward my) throws Exception {
		for (TXReward b1 : remotes) {
			if (b1.getChainLength() == my.getChainLength() && !b1.getBlockHash().equals(my.getBlockHash())) {
				log.debug(" different chains remote " + b1 + " my " + my);
				return null;
			}
			if (b1.getBlockHash().equals(my.getBlockHash())) {
				return b1;
			}
		}
		return null;
	}

	public void cleanupChainBlockQueue(BlockStoreInterface blockStore) throws BlockStoreException {

		blockStore.deleteAllChainBlockQueue();
	}

	public void connectingOrphans(BlockStoreInterface blockStore) throws BlockStoreException {
		List<ChainBlockQueue> orphanBlocks = blockStore.selectChainblockqueue(true,
				serverConfiguration.getSyncblocks());
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(blockStore);
		long cut = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.getCurrentCutoffHeight(maxConfirmedReward, blockStore);
		if (orphanBlocks.size() > 0) {
			log.debug("Orphan  size = {}", orphanBlocks.size());
		}
		for (ChainBlockQueue orphanBlock : orphanBlocks) {

			try {
				blockStore.beginDatabaseBatchWrite();
				tryConnectingOrphans(orphanBlock, cut, blockStore);
				blockStore.commitDatabaseBatchWrite();
			} catch (Exception e) {
				blockStore.abortDatabaseBatchWrite();
				throw e;
			} finally {
				blockStore.defaultDatabaseBatchWrite();

			}
		}

	}

	/**
	 * For each block in ChainBlockQueue as orphan block, see if we can now fit it
	 * on top of the chain and if so, do so.
	 */
	private void tryConnectingOrphans(ChainBlockQueue orphanBlock, long cut, BlockStoreInterface store)
			throws VerificationException, BlockStoreException {
		// Look up the blocks previous.
		Block block = networkParameters.getDefaultSerializer().makeBlock(orphanBlock.getBlock());

		ServiceVerifyReward serviceVerifyReward = new ServiceVerifyReward(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);

		// remove too old OrphanBlock and cutoff chain length
		if (System.currentTimeMillis() - orphanBlock.getInserttime() * 1000 > 2 * 60 * 60 * 1000
				|| block.getLastMiningRewardBlock() < cut) {
			log.info("deleteChainBlockQueue too old with cut {} ,   {}", cut, block.getHashAsString());
			List<ChainBlockQueue> l = new ArrayList<ChainBlockQueue>();
			l.add(orphanBlock);
			store.deleteChainBlockQueue(l);
			return;
		}

		Block prev = store.get(serviceVerifyReward.getRewardInfo(block).getPrevRewardHash());
		if (prev == null) {

			// This is still an unconnected/orphan block.
			// if (log.isDebugEnabled())
			// log.debug("Orphan block {} is not connectable right now",
			// orphanBlock.block.getHash());
			requestBlock(serviceVerifyReward.getRewardInfo(block).getPrevRewardHash(), store);
			log.info("syncBlockService orphan {}", block.getHashAsString());

		} else {
			// Otherwise we can connect it now.
			// False here ensures we don't recurse infinitely downwards when
			// connecting huge chains.
			log.info("Connected orphan {}", block.getHash());
			List<ChainBlockQueue> l = new ArrayList<ChainBlockQueue>();
			l.add(orphanBlock);
			store.deleteChainBlockQueue(l);
			blockgraph.addChain(block, store);
		}

	}

}
