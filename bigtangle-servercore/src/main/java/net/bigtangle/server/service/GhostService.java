package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class GhostService {

    private static final Logger log = LoggerFactory.getLogger(GhostService.class);

    private final ConcurrentHashMap<Sha256Hash, Long> forkChoiceVotes = new ConcurrentHashMap<>();

    @Autowired
    private StakeService stakeService;

    public void processAttestation(AttestationData att, BlockStoreInterface store) throws Exception {
        forkChoiceVotes.merge(att.getBeaconBlockHash(), 1L, Long::sum);
    }

    /** Walk GHOST from root: at each level pick the child with most attestation votes. */
    public Sha256Hash executeGhost(Sha256Hash root, BlockStoreInterface store) throws Exception {
        return executeGhost(root, store, null);
    }

    /** Same as executeGhost but excludes an entire subtree from consideration. */
    public Sha256Hash executeGhost(Sha256Hash root, BlockStoreInterface store, Sha256Hash excludeSubtree)
            throws Exception {
        Set<Sha256Hash> excluded = new HashSet<>();
        if (excludeSubtree != null) {
            collectSubtree(excludeSubtree, excluded, store);
        }
        Sha256Hash head = root;
        while (true) {
            List<Sha256Hash> children = getChildren(head, store);
            if (children.isEmpty()) break;

            Sha256Hash bestChild = null;
            long bestWeight = -1;

            for (Sha256Hash child : children) {
                if (excluded.contains(child)) continue;
                long weight = forkChoiceVotes.getOrDefault(child, 0L);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    bestChild = child;
                }
            }

            if (bestChild == null || bestWeight <= 0) break;
            head = bestChild;
        }
        return head;
    }

    /** Pick two distinct DAG tips. Pass 1 gets the heaviest; pass 2 excludes its subtree. */
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
        return executeGhost(Sha256Hash.ZERO_HASH, store, null);
    }

    /** Supply a copy of the current vote weights so callers can inspect them. */
    public ConcurrentHashMap<Sha256Hash, Long> getForkChoiceVotes() {
        return new ConcurrentHashMap<>(forkChoiceVotes);
    }

    public List<AttestationData> collectAttestations(long slot, BlockStoreInterface store) throws Exception {
        return new ArrayList<>();
    }

    /** Children = blocks whose prevBlockHash or prevBranchBlockHash equals hash. */
    private List<Sha256Hash> getChildren(Sha256Hash hash, BlockStoreInterface store) throws Exception {
        return store.getBlocksByPrevHash(hash);
    }

    /** Recursively collect all hashes in the subtree of root. */
    private void collectSubtree(Sha256Hash root, Set<Sha256Hash> out, BlockStoreInterface store) throws Exception {
        out.add(root);
        for (Sha256Hash child : getChildren(root, store)) {
            if (!out.contains(child)) {
                collectSubtree(child, out, store);
            }
        }
    }
}
