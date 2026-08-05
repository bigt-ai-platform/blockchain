package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.server.service.StoreService;

@Service
public class GhostService {

    private static final Logger log = LoggerFactory.getLogger(GhostService.class);

    private final ConcurrentHashMap<Sha256Hash, Long> forkChoiceVotes = new ConcurrentHashMap<>();
    // LMD: each validator's LATEST vote only. A validator voting repeatedly must
    // first retract its previous vote, so weights are the sum of current stake
    // over each validator's latest vote — never an ever-growing accumulator.
    private final ConcurrentHashMap<String, Sha256Hash> latestVoteBeacons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> latestVoteWeights = new ConcurrentHashMap<>();

    @Autowired
    private StakeService stakeService;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private StoreService storeService;

    // @Lazy breaks the GhostService <-> CasperService cycle (CasperService
    // forwards attestations here).
    @Lazy
    @Autowired
    private net.bigtangle.server.service.CasperService casperService;

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<Sha256Hash, Long> saved = store.getSummedAttestationVotes();
                forkChoiceVotes.putAll(saved);
                // Restore each validator's latest vote so a future vote retracts
                // the correct previous weight (in-memory and persisted LMD agree).
                for (net.bigtangle.store.BlockStoreInterface.LatestVote v : store.getLatestAttestationVotes()) {
                    String pk = net.bigtangle.core.Utils.HEX.encode(v.pubkey);
                    latestVoteBeacons.put(pk, v.blockHash);
                    latestVoteWeights.put(pk, v.weight);
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior fork-choice state to restore", e);
        }
    }

    public void processAttestation(AttestationData att, BlockStoreInterface store) throws Exception {
        // Only active, bonded validators contribute weight to fork choice.
        // Unknown or slashed validators get ZERO weight, never a default.
        long weight = stakeService.getEffectiveStake(att.getValidatorPubkey(), store);
        if (weight <= 0) {
            return;
        }
        String pk = net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey());
        Sha256Hash newBeacon = att.getBeaconBlockHash();
        if (newBeacon == null) {
            return;
        }
        // LMD: retract the validator's previous latest vote before adding the new
        // one, both in memory and in the persisted store.
        Sha256Hash prevBeacon = latestVoteBeacons.get(pk);
        long prevWeight = latestVoteWeights.getOrDefault(pk, 0L);
        if (prevBeacon != null && !prevBeacon.equals(newBeacon) && prevWeight > 0) {
            Long updated = forkChoiceVotes.computeIfPresent(prevBeacon, (h, w) -> w - prevWeight);
            if (updated == null || updated <= 0) {
                forkChoiceVotes.remove(prevBeacon);
            }
            store.deleteAttestationVote(prevBeacon, att.getValidatorPubkey());
        }
        // A re-vote for the SAME head is the common case (the confirmed head is
        // unchanged between slots): adjust only by the weight delta. Adding the
        // full weight again would inflate the in-memory weight above the single
        // persisted (pubkey, blockhash) row and make fork choice diverge after
        // a restart.
        long delta = prevBeacon != null && prevBeacon.equals(newBeacon) ? weight - prevWeight : weight;
        if (delta != 0) {
            forkChoiceVotes.merge(newBeacon, delta, Long::sum);
            Long total = forkChoiceVotes.get(newBeacon);
            if (total != null && total <= 0) {
                forkChoiceVotes.remove(newBeacon);
            }
        }
        latestVoteBeacons.put(pk, newBeacon);
        latestVoteWeights.put(pk, weight);
        store.saveAttestationVote(newBeacon, att.getValidatorPubkey(), weight, att.getSlot());
    }

    public Sha256Hash executeGhost(Sha256Hash root, BlockStoreInterface store) throws Exception {
        return executeGhost(root, store, null);
    }

    public Sha256Hash executeGhost(Sha256Hash root, BlockStoreInterface store, Sha256Hash excludeSubtree)
            throws Exception {
        Set<Sha256Hash> excluded = new HashSet<>();
        if (excludeSubtree != null) {
            collectSubtree(excludeSubtree, excluded, store);
        }
        Sha256Hash head = root;
        while (true) {
            List<Sha256Hash> children = getChildren(head, store);
            children.removeAll(excluded);
            if (children.isEmpty()) break;

            Sha256Hash bestChild = null;
            long bestWeight = -1;

            for (Sha256Hash child : children) {
                long weight = forkChoiceVotes.getOrDefault(child, 0L);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    bestChild = child;
                }
            }

            if (bestChild == null) break;
            head = bestChild;
        }
        return head;
    }

    public List<Sha256Hash> getTwoTips(BlockStoreInterface store) throws Exception {
        Sha256Hash root = getDagRoot(store);
        Sha256Hash tip1 = executeGhost(root, store, null);
        Sha256Hash tip2 = executeGhost(root, store, tip1);
        if (tip2.equals(tip1) || tip2.equals(root)) {
            tip2 = tip1;
        }
        List<Sha256Hash> tips = new ArrayList<>();
        tips.add(tip1);
        tips.add(tip2);
        return tips;
    }

    public Sha256Hash getDagRoot(BlockStoreInterface store) throws Exception {
        // GHOST starts at the highest JUSTIFIED checkpoint (the reward beacon of
        // that epoch boundary), never at genesis — votes below a justified
        // checkpoint cannot drag the fork choice across finalized history.
        if (casperService != null) {
            net.bigtangle.server.service.CasperService.Checkpoint justified = casperService.getJustifiedCheckpoint();
            if (justified != null && justified.blockHash != null) {
                return justified.blockHash;
            }
        }
        return UtilGeneseBlock.createGenesis(networkParameters).getHash();
    }

    public ConcurrentHashMap<Sha256Hash, Long> getForkChoiceVotes() {
        return new ConcurrentHashMap<>(forkChoiceVotes);
    }

    public void loadVotes(Map<Sha256Hash, Long> votes) {
        if (votes != null) {
            forkChoiceVotes.putAll(votes);
        }
    }

    public List<AttestationData> collectAttestations(long slot, BlockStoreInterface store) throws Exception {
        return store.getAttestationsForSlot(slot);
    }

    private List<Sha256Hash> getChildren(Sha256Hash hash, BlockStoreInterface store) throws Exception {
        return store.getBlocksByPrevHash(hash);
    }

    private void collectSubtree(Sha256Hash root, Set<Sha256Hash> out, BlockStoreInterface store) throws Exception {
        if (root == null || out.contains(root)) return;
        out.add(root);
        for (Sha256Hash child : getChildren(root, store)) {
            if (!out.contains(child)) {
                collectSubtree(child, out, store);
            }
        }
    }
}
