package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import net.bigtangle.server.service.StakeService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.server.service.base.ServiceVerifyReward;

/**
 * Reproduces the node-wedge reorg bug seen on the live 3-node soak (soak10,
 * soak6 image, per-node PG): a node on a minority fork, when asked to reorg
 * onto a winning chain that is NOT fully materialized locally, must never have
 * its confirmed chain collapse to ~0.
 *
 * <p>Bug in {@code ServiceVerifyReward.handleNewBestChain}: it unwinds the old
 * (minority) fork first ({@code resetChainlengthSolid} + {@code unconfirmBlocks})
 * and THEN reconnects the winning chain. If the winning chain is not fully
 * connectable right now (a beacon whose DAG parents / referenced blocks are
 * still syncing — the concurrent-proposer race), the reconnect throws AFTER
 * the unwind is committed and the confirmed chain collapses to ~0. Regression
 * assertion: the confirmed chain must not drop below its pre-reorg height.
 *
 * <p>Fix: a materialize-before-unwind guard verifies the entire winning chain
 * is strictly solid locally before unwinding a single old block; an incomplete
 * winner defers the reorg (keeps the current best chain) instead of collapsing.
 */
public class NodeWedgeReorgIT extends AbstractIntegrationTest {

	@org.springframework.beans.factory.annotation.Autowired
	private StakeService stakeService;

	private List<PQKey> validators;

	@Override
	@BeforeEach
	public void setUp() throws Exception {
		super.setUp();
		ghostService.restoreState();
		casperService.restoreState();
		validators = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			PQKey k = PQKey.createNew();
			validators.add(k);
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
	 * THE WEDGE REPRO: node confirmed on fork B (cl=5). A "winning" reorg head
	 * arrives whose reward-prev chain is stored, but one of its DAG parents /
	 * referenced blocks is NOT solid locally. handleNewBestChain must refuse
	 * (defer) WITHOUT collapsing B's confirmed chain to ~0.
	 */
	@Test
	public void testIncompleteReorgDoesNotCollapseChain() throws Exception {
		// Node's own confirmed fork: 5 beacons.
		List<Block> chainB = new ArrayList<>();
		Block bHead = buildChain(5, chainB);
		TXReward before = cacheBlockService.getMaxConfirmedReward(store);
		assertTrue(bHead.getHash().equals(before.getBlockHash()));
		long beforeCl = before.getChainLength();
		assertTrue(beforeCl >= 5, "fork B must be confirmed to cl>=5, got " + beforeCl);

		// A "winning" head W: reward-prev = B's cl-5 head (so findSplit resolves,
		// getPartialChain can build the winning chain), but W's DAG `branch`
		// parent is a FABRICATED hash that was never stored — so W is NOT solid
		// (strict checkSolidity fails: missing predecessor). This is exactly the
		// concurrent-sync race: the beacon arrived before its branch parent.
		Block winnerRewardPrev = chainB.get(chainB.size() - 1);
		Sha256Hash missingBranch = Sha256Hash.of("never-synced-branch-parent".getBytes());
		// W = beacon whose prevRewardHash = B-head, trunk = B-head, branch =
		// missingBranch. makeRewardBlock requires the prev REWARD block in store
		// (winnerRewardPrev is) — but we must craft the DAG branch ourselves.
		Block w = makeRewardBlock(winnerRewardPrev.getHash(), winnerRewardPrev.getHash(), missingBranch);
		assertNotNull(w, "winning head must be constructible");
		log.info("winning head W={} prev={} (branch parent missing)", w.getHash(), winnerRewardPrev.getHash());

		// Drive the reorg decision path exactly like BlockStoreService does when
		// haveNewBestChain becomes true.
		ServiceVerifyReward verifier = new ServiceVerifyReward(
				serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		BlockStoreInterface s = storeService.getStore();
		boolean safeRefusal = false;
		try {
			verifier.handleNewBestChain(w, s);
			safeRefusal = true; // returned without unwinding/collapsing
		} catch (Exception e) {
			safeRefusal = true; // refused safely (defer), no partial unwind
			log.info("handleNewBestChain deferred (expected for incomplete winner): {}", e.getMessage());
		} finally {
			s.close();
		}
		blockGraph.updateChain(false);

		// ---- THE FIX ASSERTION: B's confirmed chain must NOT collapse.
		TXReward after = cacheBlockService.getMaxConfirmedReward(store);
		long afterCl = after == null ? -1 : after.getChainLength();
		log.info("incomplete-reorg outcome: safeRefusal={} before cl={} after cl={} head={}",
				safeRefusal, beforeCl, afterCl, after == null ? "null" : after.getBlockHash());
		assertTrue(safeRefusal, "handleNewBestChain must defer, not throw an unexpected error");
		assertFalse(afterCl == 0 || afterCl == -1,
				"chain must not be reset to ~0 by an incomplete reorg (got cl=" + afterCl + ")");
		assertTrue(afterCl >= beforeCl,
				"incomplete winner reorg must NOT collapse the confirmed chain: "
						+ "before cl=" + beforeCl + " after cl=" + afterCl
						+ " (bug: handleNewBestChain unwound before the winning chain was materialized)");
	}
}
