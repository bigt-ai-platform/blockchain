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
import java.util.List;
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
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBlockListResponse;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceVerifyReward;
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

	public void startSingleProcess(Long chainlength, boolean nonchain) throws BlockStoreException {
		// ExecutorService executor = Executors.newSingleThreadExecutor();

		ExecutorService executor = Executors.newSingleThreadExecutor();

		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Future<String> handler = executor.submit(new Callable() {
			@Override
			public String call() throws Exception {
				startSingleProcessDo(chainlength, nonchain);
				return "finish";
			}
		});
		try {
			handler.get(scheduleConfiguration.getSyncrate() * 2, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.debug(" sync  Timeout  ");
			handler.cancel(true);
		} catch (Exception e) {
			log.debug(" sync     ", e);
		} finally {
			executor.shutdownNow();
		}

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
				connectingOrphans(store);
				syncChain(-1L, false, store);
				syncNonChained(store);
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
		String[] re = serverConfiguration.getRequester().split(",");
		List<String> badserver = new ArrayList<>();
		byte[] data = null;
		for (String s : re) {
			if (s != null && !s.trim().isEmpty() && !badserver(badserver, s)) {
				HashMap<String, String> requestParam = new HashMap<String, String>();
				requestParam.put("hashHex", Utils.HEX.encode(hash.getBytes()));
				try {
					data = OkHttp3Util.postAndGetBlock(s.trim() + "/" + ReqCmd.getBlockByHash,
							Json.jsonmapper().writeValueAsString(requestParam));
					Block block = networkParameters.getDefaultSerializer().makeBlock(data);
					log.info("   requestBlock {} ", block.toString());
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
//				Set<Sha256Hash> missing = blockgraph.checkMissing(block, store);
//				if (!missing.isEmpty())
//					log.info("missing  size {}", missing.size());
//				for (Sha256Hash hash : missing) {
//					requestBlock(hash, store);
//				}
				// if (anyMatchConfirmedReward(block, remotes)) {
				blockgraph.addFromSync(block, true, store);
				// }
			}
		}

	}

	public void requestNonĆhainBlocks(String s, BlockStoreInterface store)
			throws JsonProcessingException, IOException, ProtocolException, BlockStoreException, NoBlockException {

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] response = OkHttp3Util.postString(s.trim() + "/" + ReqCmd.blocksFromNonChainHeight,
				Json.jsonmapper().writeValueAsString(requestParam));
		TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
		long chainlength = Math.max(0, maxConfirmedReward.getChainLength() - NetworkParameters.MILESTONE_CUTOFF);
		TXReward confirmedAtHeightReward = store.getRewardConfirmedAtHeight(chainlength);
		requestParam.put("cutoffHeight", store.get(confirmedAtHeightReward.getBlockHash()).getHeight() + "");
		GetBlockListResponse blockbytelist = Json.jsonmapper().readValue(response, GetBlockListResponse.class);
		log.debug("block size: " + blockbytelist.getBlockbytelist().size() + " at server: " + s);
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

	}

	public TXReward getMaxConfirmedReward(String server) throws JsonProcessingException, IOException {

		HashMap<String, String> requestParam = new HashMap<String, String>();

		byte[] response = OkHttp3Util.postString(server.trim() + "/" + ReqCmd.getChainNumber,
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTXRewardResponse aTXRewardResponse = Json.jsonmapper().readValue(response, GetTXRewardResponse.class);

		return aTXRewardResponse.getTxReward();

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
	 * blockhash with remote ;
	 */
	public static class MaxConfirmedReward {
		String server;
		TXReward aTXReward;
	}

	public void syncChain(Long chainlength, boolean initsync, BlockStoreInterface store) throws BlockStoreException {
		// mcmcService.cleanupNonSolidMissingBlocks();
		String[] re = serverConfiguration.getRequester().split(",");
		MaxConfirmedReward aMaxConfirmedReward = new MaxConfirmedReward();
		TXReward my = cacheBlockService.getMaxConfirmedReward(store);
		if (chainlength > -1) {
			TXReward my1 = store.getRewardConfirmedAtHeight(chainlength);
			if (my1 != null)
				my = my1;
		}
		log.debug(" my chain length " + my.getChainLength() + " remote " + re[0]);
		for (String s : re) {
			try {
				if (s != null && !"".equals(s)) {
					TXReward aTXReward = getMaxConfirmedReward(s.trim());
					if (aMaxConfirmedReward.aTXReward == null) {
						aMaxConfirmedReward.server = s.trim();
						aMaxConfirmedReward.aTXReward = aTXReward;
					} else {
						if (aTXReward.getChainLength() > aMaxConfirmedReward.aTXReward.getChainLength()) {
							aMaxConfirmedReward.server = s.trim();
							aMaxConfirmedReward.aTXReward = aTXReward;
						}
					}
					syncMaxConfirmedReward(aMaxConfirmedReward, my, initsync, store);
				}
			} catch (Exception e) {
				log.debug("", e);
			}
		}

	}

	/*
	 * sync the remote data that not in chain
	 */
	public void syncNonChained(BlockStoreInterface store) throws BlockStoreException {
		// mcmcService.cleanupNonSolidMissingBlocks();
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

		if (aMaxConfirmedReward.aTXReward.getChainLength() > my.getChainLength()) {

			log.debug(" start sync remote ChainLength: " + my.getChainLength() + " to: "
					+ aMaxConfirmedReward.aTXReward.getChainLength());

			for (long i = my.getChainLength(); i <= aMaxConfirmedReward.aTXReward
					.getChainLength(); i += serverConfiguration.getSyncblocks()) {
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

 
	public static class SortbyBlock implements Comparator<Block> {

		public int compare(Block a, Block b) {
			return a.getHeight() >= b.getHeight() ? 1 : -1;
		}
	}

	public static class SortbyChain implements Comparator<TXReward> {
		// Used for sorting in ascending order of
		// roll number
		public int compare(TXReward a, TXReward b) {
			return a.getChainLength() <= b.getChainLength() ? 1 : -1;
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
			log.info("deleteChainBlockQueue too old with cut {} ,   {}", cut, block.toString());
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
			log.info("syncBlockService orphan {}", block.toString());

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
