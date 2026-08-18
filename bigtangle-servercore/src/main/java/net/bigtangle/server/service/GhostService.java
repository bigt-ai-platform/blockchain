package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.HashMap;
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
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
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
    // Validators caught equivocating (double/surround vote). Discarded from fork
    // choice entirely (Ethereum PR #2845), so their weight can't tip the balance
    // between two forks (the "balancing attack, LMD edition").
    private final java.util.Set<String> equivocatingValidators = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    // @Lazy breaks the GhostService <-> SlotService cycle (SlotService uses
    // GhostService for fork choice; the proposer boost reads the slot/proposer).
    @Lazy
    @Autowired
    private SlotService slotService;

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
        String pk = net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey());
        // Equivocating validators are discarded from fork choice entirely.
        if (equivocatingValidators.contains(pk)) {
            return;
        }
        // Only active, bonded validators contribute weight to fork choice.
        // Unknown or slashed validators get ZERO weight, never a default.
        long weight = stakeService.getEffectiveStake(att.getValidatorPubkey(), store);
        if (weight <= 0) {
            return;
        }
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

    /**
     * Marks a validator as equivocating and retracts its fork-choice weight, so
     * it can no longer tip the balance between two forks (Ethereum PR #2845).
     * Called when a double/surround vote is detected.
     */
    public void markEquivocating(byte[] pubkey) {
        String pk = net.bigtangle.core.Utils.HEX.encode(pubkey);
        if (equivocatingValidators.add(pk)) {
            Sha256Hash prevBeacon = latestVoteBeacons.remove(pk);
            long prevWeight = latestVoteWeights.getOrDefault(pk, 0L);
            latestVoteWeights.remove(pk);
            if (prevBeacon != null && prevWeight > 0) {
                Long updated = forkChoiceVotes.computeIfPresent(prevBeacon, (h, w) -> w - prevWeight);
                if (updated == null || updated <= 0) {
                    forkChoiceVotes.remove(prevBeacon);
                }
            }
        }
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
        // Fork-choice weight: at/above the on-chain-attestation activation height
        // the weight is a deterministic function of the confirmed chain (LMD head
        // votes); below it, the local gossip vote view.
        Map<Sha256Hash, Long> weights;
        if (net.bigtangle.server.service.CasperService.onChainAttestationActive(store)) {
            weights = chainForkChoiceVotes(store);
        } else {
            weights = forkChoiceVotes;
        }
        weights = applyProposerBoost(weights, store);
        // LMD-GHOST walks by ACCUMULATED subtree weight: a validator voting for
        // a deep head implicitly supports every ancestor on its branch, so the
        // weight of a child is the sum of all votes in its subtree. Comparing
        // only direct per-block votes would tie-break arbitrarily at the fork
        // point (votes sit on the tip, not on a1/b1) and could pick the wrong
        // branch. Memoize the accumulation for the duration of this walk.
        Map<Sha256Hash, Long> accumulated = new HashMap<>();
        Set<Sha256Hash> accumulating = new HashSet<>();
        Sha256Hash head = root;
        while (true) {
            List<Sha256Hash> children = getChildren(head, store);
            children.removeAll(excluded);
            if (children.isEmpty()) break;

            Sha256Hash bestChild = null;
            long bestWeight = -1;

            // Deterministic tie-break (by hash, descending) so every node
            // selects the SAME GHOST head on equal-weight forks — required for
            // cross-node convergence (getBlocksByPrevHash has no ORDER BY).
            List<Sha256Hash> sorted = new ArrayList<>(children);
            sorted.sort(java.util.Comparator.comparing(Sha256Hash::toString).reversed());
            for (Sha256Hash child : sorted) {
                long weight = subtreeWeight(child, weights, accumulated, accumulating, store, 0);
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

    /**
     * Accumulated attestation weight of the subtree rooted at {@code hash}:
     * the direct vote weight of {@code hash} plus the accumulated weight of
     * every reward-chain descendant. Bounded by
     * {@link net.bigtangle.server.service.CasperService#ATTESTATION_LOOKBACK_SLOTS}
     * so a vote deep on a long branch still counts toward every ancestor.
     */
    private long subtreeWeight(Sha256Hash hash, Map<Sha256Hash, Long> weights,
            Map<Sha256Hash, Long> memo, Set<Sha256Hash> inProgress, BlockStoreInterface store, int depth)
            throws Exception {
        if (hash == null || depth >= net.bigtangle.server.service.CasperService.ATTESTATION_LOOKBACK_SLOTS) {
            return 0;
        }
        Long cached = memo.get(hash);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(hash)) {
            return 0; // cycle guard
        }
        long sum = weights.getOrDefault(hash, 0L);
        for (Sha256Hash child : getChildren(hash, store)) {
            sum += subtreeWeight(child, weights, memo, inProgress, store, depth + 1);
        }
        inProgress.remove(hash);
        memo.put(hash, sum);
        return sum;
    }

    /**
     * Deterministic LMD-GHOST head-vote weight derived from the ON-CHAIN
     * embedded attestations: each validator's LATEST embedded head vote (by
     * slot) contributes its effective stake to the voted block. Empty when the
     * confirmed chain carries no embedded attestations (pre-fork).
     */
    private Map<Sha256Hash, Long> chainForkChoiceVotes(BlockStoreInterface store) {
        Map<String, AttestationData> latestHead = new HashMap<>();
        try {
            TXReward tip = store.getMaxConfirmedReward();
            if (tip == null) {
                return new HashMap<>();
            }
            Sha256Hash cursor = tip.getBlockHash();
            Set<Sha256Hash> visited = new HashSet<>();
            int count = 0;
            while (cursor != null && visited.add(cursor)
                    && count < net.bigtangle.server.service.CasperService.ATTESTATION_LOOKBACK_SLOTS) {
                count++;
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    break;
                }
                for (AttestationData att : embeddedAttestationsOf(b)) {
                    String pk = Utils.HEX.encode(att.getValidatorPubkey());
                    AttestationData existing = latestHead.get(pk);
                    if (existing == null || att.getSlot() > existing.getSlot()) {
                        latestHead.put(pk, att);
                    }
                }
                RewardInfo ri = new RewardInfo().parseChecked(b.getTransactions().get(0).getData());
                cursor = ri != null ? ri.getPrevRewardHash() : null;
            }
        } catch (Exception e) {
            log.debug("Failed to derive chain fork-choice votes", e);
        }
        Map<Sha256Hash, Long> weights = new HashMap<>();
        for (AttestationData att : latestHead.values()) {
            if (att.getBeaconBlockHash() == null) {
                continue;
            }
            if (equivocatingValidators.contains(net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey()))) {
                continue; // discard equivocating validators (PR #2845)
            }
            long w = 0;
            try {
                w = stakeService.getEffectiveStake(att.getValidatorPubkey(), store);
            } catch (Exception e) {
                w = 0;
            }
            if (w <= 0) {
                continue;
            }
            weights.merge(att.getBeaconBlockHash(), w, Long::sum);
        }
        return weights;
    }

    /** The attestations embedded in a beacon's SlotData, or empty. */
    private List<AttestationData> embeddedAttestationsOf(Block b) {
        for (Transaction tx : b.getTransactions()) {
            if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
                try {
                    net.bigtangle.core.SlotData sd = net.bigtangle.utils.Json.jsonmapper()
                            .readValue(tx.getData(), net.bigtangle.core.SlotData.class);
                    if (sd != null && sd.getAttestations() != null) {
                        return sd.getAttestations();
                    }
                } catch (Exception e) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    /**
     * Proposer boost (Ethereum PROPOSER_SCORE_BOOST): adds 40% of total active
     * stake to the current slot proposer's beacon so a just-proposed block is
     * not cheaply reorged. Local fork-choice defense, not a consensus rule.
     */
    private Map<Sha256Hash, Long> applyProposerBoost(Map<Sha256Hash, Long> weights, BlockStoreInterface store) {
        try {
            long slot = slotService.getCurrentSlot();
            byte[] existing = store.getPosState("pos", "slotsight_" + slot);
            if (existing == null) {
                return weights;
            }
            String s = new String(existing, java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = s.split(",");
            if (parts.length == 0 || parts[0].isEmpty()) {
                return weights;
            }
            Sha256Hash beacon = Sha256Hash.wrap(Utils.HEX.decode(parts[0]));
            java.math.BigInteger total = stakeService.getTotalActiveStake(store);
            long boost = total.multiply(java.math.BigInteger.valueOf(40))
                    .divide(java.math.BigInteger.valueOf(100)).longValue();
            if (boost <= 0) {
                return weights;
            }
            Map<Sha256Hash, Long> boosted = new HashMap<>(weights);
            boosted.merge(beacon, boost, Long::sum);
            return boosted;
        } catch (Exception e) {
            log.debug("Proposer boost skipped: {}", e.getMessage());
            return weights;
        }
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
        // LMD-GHOST over the REWARD CHAIN: children are the beacons whose reward
        // parent (txreward.prevblockhash) is this block. A beacon's DAG
        // prevblockhash points at its trunk/branch tips, NOT its reward ancestor,
        // so walking the DAG would select a DAG tip as the "head" and the
        // producer would build on a non-reward block.
        return store.getRewardChainChildren(hash);
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
