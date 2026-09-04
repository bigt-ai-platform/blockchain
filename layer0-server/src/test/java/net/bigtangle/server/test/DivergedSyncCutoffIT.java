package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.core.StakeRecord;

/**
 * Reproduces the load-lane wedge found by MeshLoad V46 (attackvector §26): a
 * node that fell behind onto a minority fork under duplicate-resubmit storms
 * stopped advancing, and RESTARTING it wedged it permanently — the sync worker
 * NPE-looped forever because no confirmed reward exists at the cutoff
 * chainlength of the diverged chain:
 *
 * <ul>
 * <li>{@code SyncBlockService.requestNonChainBlocks}: {@code
 * store.getRewardConfirmedAtHeight(chainlength)} null →
 * {@code .getBlockHash()} NPE every sync pass → node never syncs.</li>
 * <li>{@code ServiceBase.getCurrentCutoffHeight} (via
 * {@code connectingOrphans}): same null dereference → orphan reconnect dies
 * too.</li>
 * </ul>
 *
 * <p>Fix: both sites treat a missing cutoff reward as "cutoff undeterminable"
 * (return 0 / skip the auxiliary repair this pass) with a loud warn, matching
 * the pre-existing null-tolerant convention in {@code syncChain}. Restart
 * alone must heal; only a DB wipe was able to recover before the fix.
 */
public class DivergedSyncCutoffIT extends AbstractIntegrationTest {

	@org.springframework.beans.factory.annotation.Autowired
	private StakeService stakeService;

	@Override
	@BeforeEach
	public void setUp() throws Exception {
		super.setUp();
		ghostService.restoreState();
		casperService.restoreState();
		for (int i = 0; i < 3; i++) {
			PQKey k = PQKey.createNew();
			store.saveStakeDeposit(new StakeRecord(k.getPubKey(), StakeService.MIN_STAKE, k.getPubKeyHash()));
			stakeService.activateValidator(k.getPubKey(), 0, store);
		}
	}

	/** Builds a beacon chain of {@code n} beacons from genesis, returning the head. */
	private Block buildChain(int n, List<Block> out) throws Exception {		Sha256Hash prev = UtilGeneseBlock.createGenesis(networkParameters).getHash();
		Block head = null;
		for (int i = 0; i < n; i++) {
			head = makeRewardBlock(prev, prev, prev);
			assertNotNull(head, "beacon " + i + " must be created");
			out.add(head);
			prev = head.getHash();
		}
		blockGraph.updateChain(false);
		return head;
	}

	/**
	 * THE §26 REPRO: with a confirmed chain longer than CHAINLENGTH_CUTOFF, remove
	 * the confirmation of the reward exactly at the cutoff chainlength (the
	 * diverged-restart state: max confirmed exists, cutoff row does not). Both
	 * cutoff paths must degrade gracefully instead of throwing NPE.
	 */
	@Test
	public void testMissingCutoffRewardDoesNotNpeSync() throws Exception {
		List<Block> chain = new ArrayList<>();
		buildChain(NetworkParameters.CHAINLENGTH_CUTOFF + 5, chain);

		TXReward maxConfirmed = cacheBlockService.getMaxConfirmedReward(store);
		assertNotNull(maxConfirmed, "chain must confirm");
		long victimCl = Math.max(0,
				maxConfirmed.getChainLength() - NetworkParameters.CHAINLENGTH_CUTOFF);
		assertTrue(victimCl > 0, "need a non-genesis cutoff row to remove, maxCl=" + maxConfirmed.getChainLength());

		// Create the diverged-restart state: confirmed reward missing exactly
		// at the cutoff chainlength (the query filters confirmed=true).
		Sha256Hash victimHash = null;
		for (TXReward r : store.getAllConfirmedReward()) {
			if (r.getChainLength() == victimCl) {
				victimHash = r.getBlockHash();
				break;
			}
		}
		assertNotNull(victimHash, "expected a confirmed reward at cutoff cl=" + victimCl);
		store.updateRewardConfirmed(victimHash, false);
		assertNull(store.getRewardConfirmedAtHeight(victimCl),
				"precondition: no confirmed reward at cutoff cl=" + victimCl);

		// 1) Orphan-reconnect cutoff must fall back to 0, not NPE.
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long cut = assertDoesNotThrow(
				() -> serviceBase.getCurrentCutoffHeight(cacheBlockService.getMaxConfirmedReward(store), store),
				"getCurrentCutoffHeight must not throw on a missing cutoff reward");
		assertEquals(0, cut, "missing cutoff reward must disable staleness pruning (cut=0)");

		// 2) Non-chain bulk repair must skip the pass, not NPE. The guard
		// returns before any HTTP, so an unreachable seed proves no network
		// was needed and no NPE was thrown.
		assertDoesNotThrow(
				() -> syncBlockService.requestNonChainBlocks("http://127.0.0.1:9/", store),
				"requestNonChainBlocks must skip (not NPE) on a missing cutoff reward");
	}

	/**
	 * Units regression for the orphan-prune comparison in
	 * {@code connectingOrphans}: the staleness cutoff is a reward CHAINLENGTH
	 * (compared against {@code Block.lastMiningRewardBlock}), not the block
	 * height {@code getCurrentCutoffHeight} returns. Under load the DAG height
	 * runs ahead of the confirmed chainlength, so a height cutoff wrongfully
	 * pruned still-connectable orphans as "too old".
	 *
	 * <p>Setup reproduces that production shape: chained transfer blocks first
	 * (height grows, chainlength does not), then beacons — so the cutoff
	 * block's height is well above the cutoff chainlength (asserted; the test
	 * fails on setup rather than passing vacuously if that ever stops
	 * holding).
	 */
	@Test
	public void testOrphanPruneUsesChainlengthNotHeight() throws Exception {
		// 1) Inflate DAG height ahead of chainlength: 10 production-shaped
		// beacon fillers (coinbase-only, never confirmed: height grows,
		// chainlength does not). Mirrors SlotService beacon construction.
		Block genesisTip = UtilGeneseBlock.createGenesis(networkParameters);
		Block tip = genesisTip;
		for (int i = 0; i < 10; i++) {
			Block filler = Block.createBlock(networkParameters, tip, tip);
			filler.setBlockType(BlockType.BLOCKTYPE_BEACON);
			RewardInfo fri = new RewardInfo(genesisTip.getHash(), new java.util.HashSet<>(), 0);
			Transaction ftx = new Transaction(networkParameters);
			ftx.setData(fri.toByteArray());
			filler.addTransaction(ftx);
			filler.setLastMiningRewardBlock(0);
			blockGraph.addChain(filler, store);
			// addChain does not persist block bytes (production stores them
			// on receipt); put explicitly so later beacons build on these
			// heights and getBlockWrap resolves.
			store.put(filler);
			tip = filler;
		}
		store.commitDatabaseBatchWrite();
		// 2) 45 beacons on the inflated tip -> maxCl ~46, cutoff ~6, but the
		// cutoff block's HEIGHT is ~10 higher (the production gap). First
		// beacon links reward-prev to genesis (transfers are not rewards).
		Sha256Hash genesisHash = genesisHash();
		List<Block> chain = new ArrayList<>();
		Block rolling = tip;
		Sha256Hash prevReward = genesisHash;
		for (int i = 0; i < NetworkParameters.CHAINLENGTH_CUTOFF + 5; i++) {
			rolling = makeRewardBlock(prevReward, rolling.getHash(), rolling.getHash());
			assertNotNull(rolling, "beacon " + i + " must be created");
			chain.add(rolling);
			prevReward = rolling.getHash();
		}
		blockGraph.updateChain(false);

		TXReward maxConfirmed = cacheBlockService.getMaxConfirmedReward(store);
		assertNotNull(maxConfirmed, "chain must confirm");
		long cutCl = Math.max(0, maxConfirmed.getChainLength() - NetworkParameters.CHAINLENGTH_CUTOFF);
		assertTrue(cutCl > 0, "need a non-genesis cutoff, maxCl=" + maxConfirmed.getChainLength());
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long cutH = serviceBase.getCurrentCutoffHeight(maxConfirmed, store);
		assertTrue(cutH > cutCl + 1,
				"setup must reproduce height-ahead-of-chainlength (cutH=" + cutH + ", cutCl=" + cutCl + ")");

		// 3) Queue three beacon-shaped orphans with parseable RewardInfo and a
		// missing reward-prev (so they stay queued): one just INSIDE the
		// chainlength window (must survive), one below it (must be pruned),
		// one inside the window but 3h old (must be age-pruned).
		long nowSec = System.currentTimeMillis() / 1000;
		Sha256Hash keepHash = enqueueOrphan(tip, cutCl + 1, nowSec);
		Sha256Hash dropHash = enqueueOrphan(tip, 0, nowSec);
		Sha256Hash oldHash = enqueueOrphan(tip, maxConfirmed.getChainLength(), nowSec - 3 * 3600);
		store.commitDatabaseBatchWrite();
		java.util.Set<String> before = new java.util.HashSet<>();
		for (ChainBlockQueue q : store.selectChainblockqueue(true, 100)) {
			before.add(new Sha256Hash(q.getHash()).toString());
		}
		assertTrue(before.contains(keepHash.toString()) && before.contains(dropHash.toString())
				&& before.contains(oldHash.toString()), "all 3 orphans must be queued before the pass");

		syncBlockService.connectingOrphans(store);

		java.util.Set<String> queued = new java.util.HashSet<>();
		for (ChainBlockQueue q : store.selectChainblockqueue(true, 100)) {
			queued.add(new Sha256Hash(q.getHash()).toString());
		}
		assertTrue(queued.contains(keepHash.toString()),
				"in-window orphan (lastMiningRewardBlock=" + (cutCl + 1) + ", height-cutoff=" + cutH
						+ ") must survive: prune compares chainlengths, not heights");
		assertTrue(!queued.contains(dropHash.toString()), "below-window orphan must still be pruned");
		assertTrue(!queued.contains(oldHash.toString()), "3h-old orphan must still be age-pruned");
	}

	private Sha256Hash genesisHash() throws Exception {
		return UtilGeneseBlock.createGenesis(networkParameters).getHash();
	}

	/**
	 * THE §27/V63 REGRESSION: one poison orphan (unparseable RewardInfo, so
	 * {@code tryConnectingOrphans} throws) must be ISOLATED — logged and left
	 * for the age-prune — never aborting the reconnect pass. A valid
	 * connectable orphan queued BEHIND the poison must still connect and be
	 * removed. Pre-fix the pass threw out of the loop at the poison, stranding
	 * the valid orphan forever (V63 confirmed live).
	 */
	@Test
	public void testPoisonOrphanIsIsolatedAndValidOneConnects() throws Exception {
		List<Block> chain = new ArrayList<>();
		Block head = buildChain(6, chain);
		blockGraph.updateChain(false);
		TXReward maxConfirmed = cacheBlockService.getMaxConfirmedReward(store);
		assertNotNull(maxConfirmed, "chain must confirm");
		Sha256Hash confirmedRewardHash = maxConfirmed.getBlockHash();
		long nowSec = System.currentTimeMillis() / 1000;

		// Poison FIRST (sorts before the valid one: chainlength asc): beacon
		// with truncated RewardInfo -> parseChecked throws inside the pass.
		Block poison = Block.createBlock(networkParameters, head, head);
		poison.setBlockType(BlockType.BLOCKTYPE_BEACON);
		Transaction ptx = new Transaction(networkParameters);
		ptx.setData(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
		poison.addTransaction(ptx);
		poison.setLastMiningRewardBlock(1);
		store.insertChainBlockQueue(new ChainBlockQueue(poison.getHash().getBytes(), poison.bitcoinSerialize(),
				1, true, nowSec));

		// Valid orphan BEHIND it: reward-prev = the confirmed head (present in
		// store), so once reached it connects and is removed from the queue.
		Block valid = Block.createBlock(networkParameters, head, head);
		valid.setBlockType(BlockType.BLOCKTYPE_BEACON);
		RewardInfo vri = new RewardInfo(confirmedRewardHash, new java.util.HashSet<>(),
				maxConfirmed.getChainLength() + 1);
		Transaction vtx = new Transaction(networkParameters);
		vtx.setData(vri.toByteArray());
		valid.addTransaction(vtx);
		valid.setLastMiningRewardBlock(maxConfirmed.getChainLength() + 1);
		store.insertChainBlockQueue(new ChainBlockQueue(valid.getHash().getBytes(), valid.bitcoinSerialize(),
				maxConfirmed.getChainLength() + 1, true, nowSec));
		store.commitDatabaseBatchWrite();

		// The pass must survive the poison.
		assertDoesNotThrow(() -> syncBlockService.connectingOrphans(store),
				"connectingOrphans must not abort on a poison orphan");

		java.util.Set<String> queued = new java.util.HashSet<>();
		for (ChainBlockQueue q : store.selectChainblockqueue(true, 100)) {
			queued.add(new Sha256Hash(q.getHash()).toString());
		}
		assertTrue(queued.contains(poison.getHash().toString()),
				"poison orphan stays queued for the age-prune (never fatal)");
		assertTrue(!queued.contains(valid.getHash().toString()),
				"valid orphan behind the poison must still connect and leave the queue");
	}

	/**
	 * Beacon-shaped queued block with a parseable RewardInfo whose reward-prev
	 * is absent from the store, so {@code connectingOrphans} can neither
	 * connect nor (unless stale) prune it — it stays queued. Returns its hash.
	 */
	private Sha256Hash enqueueOrphan(Block parentsFrom, long lastMiningRewardBlock, long inserttimeSec)
			throws Exception {
		Block orphan = Block.createBlock(networkParameters, parentsFrom, parentsFrom);
		orphan.setBlockType(BlockType.BLOCKTYPE_BEACON);
		RewardInfo ri = new RewardInfo(
				Sha256Hash.of(("missing-reward-prev-" + lastMiningRewardBlock).getBytes()),
				new java.util.HashSet<>(), lastMiningRewardBlock);
		Transaction tx = new Transaction(networkParameters);
		tx.setData(ri.toByteArray());
		orphan.addTransaction(tx);
		orphan.setLastMiningRewardBlock(lastMiningRewardBlock);
		store.insertChainBlockQueue(new ChainBlockQueue(orphan.getHash().getBytes(), orphan.bitcoinSerialize(),
				lastMiningRewardBlock, true, inserttimeSec));
		return orphan.getHash();
	}
}
