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
import org.springframework.scheduling.annotation.Scheduled;
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

    // Chain-read fork-choice weights are a pure function of the CONFIRMED chain
    // tip, so they are memoized per tip hash: executeGhost runs several times
    // per slot (propose + getTwoTips + attest duty) and each uncached run walks
    // ATTESTATION_LOOKBACK_SLOTS confirmed blocks. The cache key is the tip, so
    // any newly confirmed beacon invalidates it naturally.
    private final Object chainVotesLock = new Object();
    private Sha256Hash chainVotesTip;
    private Map<Sha256Hash, Long> chainVotesCache = java.util.Collections.emptyMap();

    // GOSSIP-OBSERVED fork-choice view: each validator's LATEST embedded
    // attestation seen on ANY ingested beacon (kafka stream or gossip),
    // regardless of which branch that beacon sits on. The confirmed-chain
    // derivation above is self-referential after a split — each camp's chain
    // carries only its own validators' votes, so every node computes "my branch
    // wins" and the mesh never reconciles (a 5-node bootstrap split stayed
    // frozen for dozens of slots for exactly this reason). Merging the observed
    // view lets GHOST see majority weight as soon as the votes ARRIVE rather
    // than after they confirm, collapsing forks within ~one epoch.
    private final ConcurrentHashMap<String, AttestationData> observedLatestVotes = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong observedMaxSlot =
            new java.util.concurrent.atomic.AtomicLong(-1);
    private final java.util.concurrent.atomic.AtomicLong observedVersion =
            new java.util.concurrent.atomic.AtomicLong(0);
    // Memoization for the union derivation (see effectiveForkChoiceWeights):
    // executeGhost runs several times per slot, and PQ/BLS re-verification is
    // expensive; the version counter invalidates on every observation.
    private final Object effLock = new Object();
    private String effCacheKey;
    private Map<Sha256Hash, Long> effCache = java.util.Collections.emptyMap();

    // @Lazy breaks the GhostService <-> StakeService <-> BlockSaveService
    // cycle (stakeService is only touched at runtime, never at startup).
    @Lazy
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

    // Lazy provider (not field injection) — ValidatorDutyService itself
    // depends on GhostService, a direct field would be a boot-ordering cycle.
    @Lazy
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<ValidatorDutyService> validatorDutyServiceProvider;

    @PostConstruct
    public void restoreState() {
        reloadForkChoiceFromStore();
    }

    /**
     * Keeps the in-memory fork-choice vote view in sync with the persisted
     * attestation votes. Attestations arriving via the gossip mesh are processed
     * by the layer0-server process (submitAttestation endpoint); the layer0-mcmc
     * process — the one that runs GHOST fork choice and proposes — has no such
     * endpoint and would otherwise only ever see ITS OWN validator's vote in
     * memory. That blinds each mcmc to every peer's vote below the on-chain
     * activation height: each node builds on its own fork and attests its own
     * head, so the network never converges. The DB is the shared source of truth
     * (every process writes votes there), so periodically re-deriving the maps
     * from it restores the "fork-choice weight shared via gossiped attestations"
     * invariant across processes.
     */
    @Scheduled(fixedDelayString = "${pos.forkChoiceSyncMs:5000}")
    public void reloadForkChoiceFromStore() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<Sha256Hash, Long> saved = store.getSummedAttestationVotes();
                forkChoiceVotes.clear();
                // NOTE: equivocatingValidators is deliberately NOT cleared
                // here. Marks come from authenticated double-vote evidence
                // (like the slot-sighting registry) and must survive the
                // periodic reload — clearing them every cycle would let an
                // equivocating validator regain fork-choice weight between
                // reloads. Also drop the memoized chain-read weights so they
                // are rebuilt under the current mark set.
                synchronized (chainVotesLock) {
                    chainVotesTip = null;
                }
                if (saved != null) {
                    forkChoiceVotes.putAll(saved);
                }
                latestVoteBeacons.clear();
                latestVoteWeights.clear();
                for (net.bigtangle.store.BlockStoreInterface.LatestVote v : store.getLatestAttestationVotes()) {
                    String pk = net.bigtangle.core.Utils.HEX.encode(v.pubkey);
                    latestVoteBeacons.put(pk, v.blockHash);
                    latestVoteWeights.put(pk, v.weight);
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior fork-choice state to reload", e);
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
            // Rebuild the memoized chain-read weights without this validator.
            synchronized (chainVotesLock) {
                chainVotesTip = null;
            }
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
        // votes) UNIONED with the gossip-observed view; below it, the local gossip
        // vote view.
        Map<Sha256Hash, Long> weights;
        if (net.bigtangle.server.service.CasperService.onChainAttestationActive(store)) {
            weights = effectiveForkChoiceWeights(store);
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
    /**
     * Attestation lookback window in SLOTS for this network: the epoch-based
     * lookback ({@link net.bigtangle.server.service.CasperService#ATTESTATION_LOOKBACK_EPOCHS})
     * converted with the network's slots-per-epoch.
     */
    private long attestationLookbackSlots() {
        return net.bigtangle.server.service.CasperService.ATTESTATION_LOOKBACK_EPOCHS
                * networkParameters.getSlotsPerEpoch();
    }

    private long subtreeWeight(Sha256Hash hash, Map<Sha256Hash, Long> weights,
            Map<Sha256Hash, Long> memo, Set<Sha256Hash> inProgress, BlockStoreInterface store, int depth)
            throws Exception {
        if (hash == null || depth >= attestationLookbackSlots()) {
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
     * confirmed chain carries no embedded attestations (pre-fork). Memoized per
     * confirmed tip (see the cache fields above).
     */
    private Map<Sha256Hash, Long> chainForkChoiceVotes(BlockStoreInterface store) {
        Sha256Hash tipHash = null;
        try {
            TXReward tip = store.getMaxConfirmedReward();
            tipHash = tip != null ? tip.getBlockHash() : null;
        } catch (Exception e) {
            log.debug("Failed to read confirmed tip for fork-choice votes", e);
            return deriveChainForkChoiceVotes(store);
        }
        if (tipHash == null) {
            return new HashMap<>();
        }
        synchronized (chainVotesLock) {
            if (tipHash.equals(chainVotesTip)) {
                return chainVotesCache;
            }
        }
        Map<Sha256Hash, Long> fresh = deriveChainForkChoiceVotes(store);
        synchronized (chainVotesLock) {
            chainVotesTip = tipHash;
            chainVotesCache = fresh;
        }
        return fresh;
    }

    /** The uncached chain walk behind {@link #chainForkChoiceVotes}. */
    private Map<Sha256Hash, Long> deriveChainForkChoiceVotes(BlockStoreInterface store) {
        Sha256Hash start;
        try {
            TXReward tip = store.getMaxConfirmedReward();
            start = tip != null ? tip.getBlockHash() : null;
        } catch (Exception e) {
            start = null;
        }
        if (start == null) {
            return new HashMap<>();
        }
        return deriveChainForkChoiceVotesFrom(start, store);
    }

    /**
     * Fork-choice votes derived from the embedded attestations of the reward
     * chain ENDING at {@code startHash}, walked backwards up to the lookback.
     *
     * <p>Public because bootstrap-fork reconciliation needs the SAME derivation
     * for a COMPETING branch: chain-derived weights read only from this node's
     * own confirmed chain, so after an early first-wins split every node sees
     * "my branch wins" forever — even though the majority branch's 2/3-stake
     * attestation proof is already stored locally. Weighing both branches by
     * their own embedded votes is what lets a minority node adopt the majority.
     */
    public Map<Sha256Hash, Long> deriveChainForkChoiceVotesFrom(Sha256Hash startHash,
            BlockStoreInterface store) {
        return weighLatest(deriveChainLatestHeadFrom(startHash, store), store);
    }

    /**
     * Walk backwards from {@code startHash} along the reward chain (bounded by
     * the attestation lookback) collecting each validator's HIGHEST-slot
     * embedded attestation. The raw LMD map behind
     * {@link #deriveChainForkChoiceVotesFrom}.
     */
    private Map<String, AttestationData> deriveChainLatestHeadFrom(Sha256Hash startHash,
            BlockStoreInterface store) {
        Map<String, AttestationData> latestHead = new HashMap<>();
        try {
            Sha256Hash cursor = startHash;
            Set<Sha256Hash> visited = new HashSet<>();
            int count = 0;
            while (cursor != null && visited.add(cursor)
                    && count < attestationLookbackSlots()) {
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
        return latestHead;
    }

    /** Stake-weigh a per-validator latest-vote map into per-beacon weights. */
    private Map<Sha256Hash, Long> weighLatest(Map<String, AttestationData> latest,
            BlockStoreInterface store) {
        Map<Sha256Hash, Long> weights = new HashMap<>();
        for (AttestationData att : latest.values()) {
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

    /**
     * Fork-choice weights for GHOST: LMD over the UNION of (a) attestations
     * embedded in the confirmed chain and (b) the gossip-observed view from
     * every ingested beacon ({@link #observeBeacon}). Per validator only the
     * HIGHEST-slot vote counts across both sources, so a validator present in
     * both contributes exactly once — no double counting. Memoized per
     * (confirmed tip, observation version).
     */
    private Map<Sha256Hash, Long> effectiveForkChoiceWeights(BlockStoreInterface store)
            throws Exception {
        Sha256Hash tipHash = null;
        try {
            TXReward tip = store.getMaxConfirmedReward();
            tipHash = tip != null ? tip.getBlockHash() : null;
        } catch (Exception e) {
            log.debug("Failed to read confirmed tip", e);
        }
        String key = (tipHash == null ? "null" : tipHash.toString())
                + "#" + observedVersion.get();
        synchronized (effLock) {
            if (key.equals(effCacheKey)) {
                return effCache;
            }
        }
        Map<String, AttestationData> latest = new HashMap<>();
        if (tipHash != null) {
            latest.putAll(deriveChainLatestHeadFrom(tipHash, store));
        }
        observedLatestVotes.forEach((pk, att) -> {
            AttestationData cur = latest.get(pk);
            if (cur == null || att.getSlot() > cur.getSlot()) {
                latest.put(pk, att);
            }
        });
        Map<Sha256Hash, Long> fresh = weighLatest(latest, store);
        synchronized (effLock) {
            effCacheKey = key;
            effCache = fresh;
        }
        return fresh;
    }

    /**
     * Record the embedded attestations of an ingested beacon into the
     * gossip-observed LMD view. Called from BlockService on EVERY accepted
     * ingest path (kafka stream + gossip), so the view reflects what this node
     * has actually heard — the same trust model as Ethereum's attestation
     * gossip subnet. Only BLS-signature-valid attestations are recorded
     * (embeddedAttestationsOf filters), one vote per validator by slot.
     */
    public void observeBeacon(Block b) {
        if (b == null || b.getBlockType() != BlockType.BLOCKTYPE_BEACON) {
            return;
        }
        boolean changed = false;
        for (AttestationData att : embeddedAttestationsOf(b)) {
            if (att == null || att.getValidatorPubkey() == null || att.getBeaconBlockHash() == null) {
                continue;
            }
            String pk = net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey());
            if (equivocatingValidators.contains(pk)) {
                continue;
            }
            observedMaxSlot.accumulateAndGet(att.getSlot(), Math::max);
            AttestationData cur = observedLatestVotes.get(pk);
            if (cur == null || att.getSlot() > cur.getSlot()) {
                observedLatestVotes.put(pk, att);
                changed = true;
            }
        }
        // Evict entries that fell out of the lookback window so memory stays
        // bounded and ancient votes cannot resurrect an abandoned branch.
        long cutoff = observedMaxSlot.get() - attestationLookbackSlots();
        if (cutoff > 0) {
            observedLatestVotes.values().removeIf(a -> a.getSlot() < cutoff);
        }
        if (changed) {
            observedVersion.incrementAndGet();
        }
    }

    /**
     * TRUE LMD reconciliation decision: should the branch ending at
     * {@code candidateTip} replace the branch ending at {@code currentTip}?
     *
     * <p>Per validator, the vote belongs to whichever branch carries its
     * HIGHEST-slot attestation (latest vote wins, Ethereum LMD rule). Branch-
     * local counting is ambiguous — a validator that switched sides leaves
     * votes in BOTH histories, so two branches can each claim 2 of 3 voters
     * (observed: 49 adoption reversions). Slot comparison makes the tallies
     * globally consistent: a validator contributes to exactly one branch.
     *
     * @return true when the candidate holds strictly more latest-votes than
     *         the current branch AND a strict majority of {@code totalValidators}
     */
    public boolean shouldAdoptBranch(BlockStoreInterface store, Sha256Hash currentTip,
            Sha256Hash candidateTip, long totalValidators) {
        Map<String, AttestationData> cur = latestEmbeddedVotes(currentTip, store);
        Map<String, AttestationData> cand = latestEmbeddedVotes(candidateTip, store);
        // EXCLUDE this node's own vote: right after a stall the minority node
        // has just attested its own (stale) tip, so its self-vote can briefly
        // out-slot the majority's votes and revert a correct adoption — the
        // node must follow OTHERS' consensus, not re-rank itself into it.
        String selfPk = null;
        ValidatorDutyService duty = validatorDutyServiceProvider.getIfAvailable();
        if (duty != null && duty.getValidatorKey() != null) {
            selfPk = Utils.HEX.encode(duty.getValidatorKey().getPubKey());
        }
        int candTally = 0;
        int curTally = 0;
        java.util.Set<String> all = new java.util.HashSet<>(cur.keySet());
        all.addAll(cand.keySet());
        for (String pk : all) {
            if (pk.equals(selfPk) || equivocatingValidators.contains(pk)) {
                continue;
            }
            AttestationData c = cand.get(pk);
            AttestationData u = cur.get(pk);
            long cs = c != null ? c.getSlot() : Long.MIN_VALUE;
            long us = u != null ? u.getSlot() : Long.MIN_VALUE;
            if (cs > us) {
                candTally++;
            } else if (us > cs) {
                curTally++;
            }
        }
        boolean wins = candTally > curTally && candTally * 2 > totalValidators;
        log.info("branch reconciliation check: candidate={} current={} total={} → adopt={}",
                candTally, curTally, totalValidators, wins);
        return wins;
    }

    /**
     * Distinct validators whose latest embedded vote lies on {@code branchTip}'s
     * branch. Integer-valued ON PURPOSE: summed stake flips-flops when one side's
     * ancestry is only partially stored (freshly reconciled node mid-backfill),
     * causing adopt/adoption-reverse oscillation. Voter COUNT is stable under
     * partial walks — a missing ancestor can only undercount BOTH sides equally
     * near the common ancestor, never invert the majority.
     */
    public long branchVoterCount(Sha256Hash branchTip, BlockStoreInterface store) {
        long count = 0;
        for (AttestationData att : latestEmbeddedVotes(branchTip, store).values()) {
            if (att.getBeaconBlockHash() == null) {
                continue;
            }
            if (equivocatingValidators.contains(net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey()))) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * Total voting weight behind {@code branchTip}'s branch: one unit per
     * distinct validator, weighted by effective stake when resolvable.
     */
    public long branchVoteWeight(Sha256Hash branchTip, BlockStoreInterface store) {
        long total = 0;
        for (AttestationData att : latestEmbeddedVotes(branchTip, store).values()) {
            if (att.getBeaconBlockHash() == null) {
                continue;
            }
            if (equivocatingValidators.contains(net.bigtangle.core.Utils.HEX.encode(att.getValidatorPubkey()))) {
                continue;
            }
            long w = 1;
            try {
                long s = stakeService.getEffectiveStake(att.getValidatorPubkey(), store);
                if (s > 0) {
                    w = s;
                }
            } catch (Exception e) {
                // keep unit weight
            }
            total += w;
        }
        return total;
    }

    /**
     * Latest-slot embedded attestation per validator pubkey along the reward
     * chain ending at {@code startHash}, walked backwards up to the lookback.
     */
    private Map<String, AttestationData> latestEmbeddedVotes(Sha256Hash startHash,
            BlockStoreInterface store) {
        Map<String, AttestationData> latest = new HashMap<>();
        try {
            Sha256Hash cursor = startHash;
            Set<Sha256Hash> visited = new HashSet<>();
            int count = 0;
            while (cursor != null && visited.add(cursor)
                    && count < attestationLookbackSlots()) {
                count++;
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    break;
                }
                for (AttestationData att : embeddedAttestationsOf(b)) {
                    String pk = Utils.HEX.encode(att.getValidatorPubkey());
                    AttestationData existing = latest.get(pk);
                    if (existing == null || att.getSlot() > existing.getSlot()) {
                        latest.put(pk, att);
                    }
                }
                RewardInfo ri = new RewardInfo().parseChecked(b.getTransactions().get(0).getData());
                cursor = ri != null ? ri.getPrevRewardHash() : null;
            }
        } catch (Exception e) {
            log.debug("Failed to collect embedded votes", e);
        }
        return latest;
    }

    /** The attestations embedded in a beacon's SlotData, or empty. */
    private List<AttestationData> embeddedAttestationsOf(Block b) {
        for (Transaction tx : b.getTransactions()) {
            if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
                try {
                    net.bigtangle.core.SlotData sd = net.bigtangle.utils.Json.jsonmapper()
                            .readValue(tx.getData(), net.bigtangle.core.SlotData.class);
                    if (sd != null && sd.getAttestations() != null) {
                        // BLS-verdict-cached filter (see CasperService): a
                        // Byzantine proposer must not be able to forge other
                        // validators' votes into its own branch's evidence.
                        return CasperService.signatureValid(sd.getAttestations());
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
        // Read the FULL signed attestations persisted for the slot (pos_state
        // service='attestation'). store.getAttestationsForSlot reads the LMD
        // attestation_votes table, which only keeps {beaconHash, pubkey, slot} —
        // no signature — so votes read back from there fail verifySignature.
        return casperService.getAttestationsForSlot(slot, store);
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
