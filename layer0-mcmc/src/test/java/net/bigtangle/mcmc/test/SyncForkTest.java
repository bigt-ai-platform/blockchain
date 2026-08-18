/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

/**
 * Local reproduction of the PRODUCTION sync/divergence problem:
 *
 * node B falls behind on its OWN fork while node A builds a longer canonical
 * chain. The sync path (addFromSync → connectingOrphans → requestMissingReferenced
 * → processChainConnected) must pull A's chain and, because fork choice is
 * LMD-GHOST (attestation weight from the highest justified checkpoint), B must
 * reorg onto A's GHOST-winning head — NOT merely the longer chain.
 */
public class SyncForkTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private GhostService ghostService;
    @org.springframework.beans.factory.annotation.Autowired
    private StakeService stakeService;
    @org.springframework.beans.factory.annotation.Autowired
    private net.bigtangle.server.service.CasperService casperService;

    private List<PQKey> validators;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        // Clear the shared GhostService in-memory fork-choice votes so tests in
        // the same JVM cannot leak stale attestations into a fresh store.
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

    /** Serializes a chain so it can be replayed as if synced from a remote node. */
    private List<byte[]> serializeChain(List<Block> chain) {
        List<byte[]> out = new ArrayList<>();
        for (Block b : chain) {
            out.add(b.unsafeBitcoinSerialize());
        }
        return out;
    }

    /** Casts validator attestation votes for the given beacon head. */
    private void attestTo(Sha256Hash head, PQKey k) throws Exception {
        net.bigtangle.core.AttestationData att = new net.bigtangle.core.AttestationData();
        att.setSlot(1);
        att.setEpoch(0);
        att.setSourceEpoch(0);
        att.setTargetEpoch(0);
        att.setBeaconBlockHash(head);
        att.setValidatorPubkey(k.getPubKey());
        att.setBlsPubkey(net.bigtangle.server.service.RandaoService.blsPubkey(k));
        att.setSignature(net.bigtangle.server.service.RandaoService.blsSign(k, att.getMessageHash().getBytes()));
        ghostService.processAttestation(att, store);
    }

    @Test
    public void testSyncDivergedNodeReorgsToGhostWinningChain() throws Exception {
        // ---- Node A: canonical chain of 6 beacons, 2 validators attest to its head.
        List<Block> chainA = new ArrayList<>();
        Block aHead = buildChain(6, chainA);
        attestTo(aHead.getHash(), validators.get(0));
        attestTo(aHead.getHash(), validators.get(1));

        // GHOST must select A's head as the fork-choice winner (2 votes deep on A).
        Sha256Hash ghostHead = ghostService.executeGhost(ghostService.getDagRoot(store), store);
        assertEquals(aHead.getHash(), ghostHead, "GHOST head must be A's head after attestations");

        List<byte[]> remoteABlocks = serializeChain(chainA);

        // ---- Node B: a node that diverged and built its OWN shorter fork.
        // Reset the store to simulate a fresh/different node, then build a
        // competing 3-beacon fork from genesis (different content → different
        // hashes). Its attestation view still prefers A (votes persist in the
        // shared GhostService gossip view — B heard A's attestations via gossip).
        // Validator 3 votes DIRECTLY on B's head: a direct-weight implementation
        // would then pick B at the fork (its head carries 1 vote immediately),
        // while LMD-GHOST subtree accumulation correctly prefers A (2 votes deep).
        resetStore();
        List<Block> chainB = new ArrayList<>();
        Block bHead = buildChain(3, chainB);
        assertFalse(bHead.getHash().equals(aHead.getHash()), "fork B must differ from chain A");
        attestTo(bHead.getHash(), validators.get(2));
        // B's own fork is locally confirmed.
        TXReward before = cacheBlockService.getMaxConfirmedReward(store);
        assertEquals(bHead.getHash(), before.getBlockHash());

        // ---- Sync: B pulls A's chain through the exact sync code path.
        // 1. addFromSync — the path requestBlocks/syncChain uses for chain blocks.
        for (byte[] data : remoteABlocks) {
            Block b = networkParameters.getDefaultSerializer().makeBlock(data);
            blockGraph.addFromSync(b, true, store);
        }
        // 2. connectingOrphans — retries queued beacons whose parents just arrived.
        syncBlockService.connectingOrphans(store);
        // 3. requestMissingReferenced — actively fetches the fork-gap ancestors.
        syncBlockService.requestMissingReferenced(store);
        // 4. processChainConnected — drain the queue, run fork choice + reorg.
        blockGraph.processChainConnected(store, false, false);
        blockGraph.updateChain(false);

        // ---- Assert: B has converged onto A's canonical head.
        TXReward after = cacheBlockService.getMaxConfirmedReward(store);
        assertEquals(aHead.getHash(), after.getBlockHash(),
                "B must reorg onto A's GHOST-winning head after sync");
        assertEquals(6L, after.getChainLength());

        // The loser fork must be rolled back (unconfirmed).
        assertFalse(getBlockEvaluation(bHead.getHash(), store).isConfirmed(),
                "loser fork B head must be un-confirmed after reorg");
        // A's head must be confirmed.
        assertTrue(getBlockEvaluation(aHead.getHash(), store).isConfirmed(),
                "A's head must be confirmed after reorg");
    }

    @Test
    public void testGhostAccumulatesSubtreeWeight() throws Exception {
        // Votes land on the TIP; the walk must accumulate them up the branch so
        // the fork point follows the branch carrying the attestations.
        List<Block> chainA = new ArrayList<>();
        Block aHead = buildChain(4, chainA);
        attestTo(aHead.getHash(), validators.get(0));
        attestTo(aHead.getHash(), validators.get(1));

        // Add a competing single-beacon fork (different content) on the same
        // store, with ONE direct vote on its head. A direct-weight walk picks B
        // (its head has 1 vote immediately at the fork); subtree accumulation
        // correctly prefers A (2 votes deep on the longer branch).
        List<Block> chainB = new ArrayList<>();
        Block bHead = buildChain(1, chainB);
        assertFalse(bHead.getHash().equals(aHead.getHash()));
        attestTo(bHead.getHash(), validators.get(2));

        Sha256Hash ghostHead = ghostService.executeGhost(ghostService.getDagRoot(store), store);
        assertEquals(aHead.getHash(), ghostHead,
                "GHOST must follow the branch whose deep tip carries the attestation weight");
    }
}
