package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
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

    public Sha256Hash executeGhost(Sha256Hash root, BlockStoreInterface store) throws Exception {
        Sha256Hash head = root;
        while (true) {
            List<Sha256Hash> children = getChildren(head, store);
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

            if (bestChild == null || bestWeight <= 0) break;
            head = bestChild;
        }
        return head;
    }

    public Sha256Hash getDagRoot(BlockStoreInterface store) throws Exception {
        return executeGhost(Sha256Hash.ZERO_HASH, store);
    }

    public List<AttestationData> collectAttestations(long slot, BlockStoreInterface store) throws Exception {
        return new ArrayList<>();
    }

    private List<Sha256Hash> getChildren(Sha256Hash hash, BlockStoreInterface store) {
        return new ArrayList<>();
    }
}
