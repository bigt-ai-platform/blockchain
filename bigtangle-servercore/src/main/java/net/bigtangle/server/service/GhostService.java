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

    @Autowired
    private StakeService stakeService;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private StoreService storeService;

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<Sha256Hash, Long> saved = store.getSummedAttestationVotes();
                forkChoiceVotes.putAll(saved);
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior fork-choice state to restore", e);
        }
    }

    public void processAttestation(AttestationData att, BlockStoreInterface store) throws Exception {
        long weight = stakeService.getEffectiveStake(att.getValidatorPubkey(), store);
        if (weight <= 0) {
            weight = 32_000_000L;
        }
        forkChoiceVotes.merge(att.getBeaconBlockHash(), weight, Long::sum);
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
