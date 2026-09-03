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
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.params.NetworkParameters;
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
	private Block buildChain(int n, List<Block> out) throws Exception {
		Sha256Hash prev = UtilGeneseBlock.createGenesis(networkParameters).getHash();
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
}
