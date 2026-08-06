package net.bigtangle.server.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Casper FFG finality over the LMD-GHOST fork choice.
 *
 * <p>Checkpoints are created at each epoch boundary from the current beacon
 * head. A checkpoint is justified when more than 2/3 of the active stake has
 * attested for its source→target pair, and finalized when its parent is already
 * finalized. Attestations are authenticated (signature over the full message)
 * before they are counted, and only genuine double votes trigger slashing —
 * out-of-order or duplicate delivery of an identical vote never slashes.
 */
@Service
public class CasperService {

    private static final Logger log = LoggerFactory.getLogger(CasperService.class);

    public static class Checkpoint {
        Sha256Hash blockHash;
        long epoch;
        boolean justified;
        boolean finalized;

        Checkpoint(Sha256Hash blockHash, long epoch) {
            this.blockHash = blockHash;
            this.epoch = epoch;
        }

        public Sha256Hash getBlockHash() {
            return blockHash;
        }

        public long getEpoch() {
            return epoch;
        }

        public boolean isJustified() {
            return justified;
        }

        public boolean isFinalized() {
            return finalized;
        }
    }

    private final ConcurrentHashMap<Long, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> latestVotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sha256Hash> latestVoteBeacons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sha256Hash> latestVoteSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sha256Hash> latestVoteTargets = new ConcurrentHashMap<>();
    // Per-(validator, target-epoch) vote records: a vote for epoch E keeps
    // counting toward E's checkpoint even after the validator votes in a later
    // epoch (Ethereum's attestation inclusion window). With latest-vote-only
    // accounting, a vote stops counting the moment the validator's next-epoch
    // vote arrives, so justification at the epoch boundary depends on each
    // node's wall-clock/gossip timing and diverges between nodes.
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Sha256Hash>> epochVoteTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Sha256Hash>> epochVoteSources = new ConcurrentHashMap<>();

    @Autowired
    private GhostService ghostService;

    @Autowired
    private StakeService stakeService;

    @Autowired
    private SlashingService slashingService;

    @Autowired
    private GossipService gossipService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private NetworkParameters networkParameters;

    /**
     * Optional weak-subjectivity anchor: "{@code epoch}:{hex-blockhash}". A
     * long-range attacker who controlled historic keys can fork the chain from
     * before any on-chain finality. The operator's node pins the finalized floor
     * to this operator-provided checkpoint (obtained out-of-band), so a fork
     * that rewrites pre-checkpoint history can never be adopted. Empty disables
     * it. Fail-closed: a malformed value or a checkpoint that conflicts with the
     * node's stored chain prevents startup.
     */
    @org.springframework.beans.factory.annotation.Value("${pos.weakSubjectivityCheckpoint:}")
    private String weakSubjectivityCheckpoint = "";

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<String, byte[]> saved = store.getPosStateByService("casper");
                for (Map.Entry<String, byte[]> e : saved.entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith("vote_")) {
                        String pubkey = key.substring(5);
                        long slot = new java.math.BigInteger(e.getValue()).longValue();
                        latestVotes.put(pubkey, slot);
                    } else if (key.startsWith("src_")) {
                        latestVoteSources.put(key.substring(4), new Sha256Hash(e.getValue()));
                    } else if (key.startsWith("tgt_")) {
                        latestVoteTargets.put(key.substring(4), new Sha256Hash(e.getValue()));
                    } else if (key.startsWith("beacon_")) {
                        latestVoteBeacons.put(key.substring(7), new Sha256Hash(e.getValue()));
                    } else if (key.startsWith("ckpt_")) {
                        long epoch = Long.parseLong(key.substring(5));
                        String[] parts = new String(e.getValue(), StandardCharsets.UTF_8).split(",");
                        Checkpoint cp = new Checkpoint(Sha256Hash.wrap(net.bigtangle.core.Utils.HEX.decode(parts[0])), epoch);
                        cp.justified = Boolean.parseBoolean(parts[1]);
                        cp.finalized = Boolean.parseBoolean(parts[2]);
                        checkpoints.put(epoch, cp);
                    } else if (key.startsWith("evotes_")) {
                        String pubkey = key.substring(7);
                        String raw = new String(e.getValue(), StandardCharsets.UTF_8);
                        for (String part : raw.split(";")) {
                            String[] p = part.split(":");
                            if (p.length == 3) {
                                try {
                                    long ep = Long.parseLong(p[0]);
                                    epochVoteTargets.computeIfAbsent(pubkey, k -> new ConcurrentHashMap<>())
                                            .put(ep, Sha256Hash.wrap(Utils.HEX.decode(p[1])));
                                    epochVoteSources.computeIfAbsent(pubkey, k -> new ConcurrentHashMap<>())
                                            .put(ep, Sha256Hash.wrap(Utils.HEX.decode(p[2])));
                                } catch (Exception ignored) {
                                    // skip malformed entry
                                }
                            }
                        }
                    }
                }
                applyWeakSubjectivityCheckpoint(store);
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior Casper state to restore", e);
        }
        // Bootstrap finality: the genesis checkpoint is the root of trust
        // (justified + finalized) and must survive restarts. It is seeded even
        // when the store read above failed (e.g. schema not yet created during
        // startup), so finality always has a non-empty anchor.
        if (checkpoints.isEmpty()) {
            Checkpoint genesis = new Checkpoint(
                    UtilGeneseBlock.createGenesis(networkParameters).getHash(), 0);
            genesis.justified = true;
            genesis.finalized = true;
            checkpoints.put(0L, genesis);
            try {
                BlockStoreInterface store = storeService.getStore();
                try {
                    persistCheckpoint(genesis, store);
                } finally {
                    store.close();
                }
            } catch (Exception ignored) {
                // Persist is best-effort here; the in-memory anchor still boots.
            }
        }
    }

    /**
     * Pins the finalized floor to the operator's weak-subjectivity checkpoint
     * (see {@link #weakSubjectivityCheckpoint}). Fail-closed: a checkpoint that
     * conflicts with the node's stored chain (the operator's anchor differs from
     * what the node confirmed — evidence of a wrong chain or a stale anchor)
     * prevents startup.
     */
    private void applyWeakSubjectivityCheckpoint(BlockStoreInterface store) {
        if (weakSubjectivityCheckpoint == null || weakSubjectivityCheckpoint.isBlank()) {
            return;
        }
        long epoch;
        Sha256Hash hash;
        try {
            String[] parts = weakSubjectivityCheckpoint.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("expected epoch:hex-blockhash");
            }
            epoch = Long.parseLong(parts[0]);
            hash = Sha256Hash.wrap(Utils.HEX.decode(parts[1]));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid weak-subjectivity checkpoint config '" + weakSubjectivityCheckpoint + "'", e);
        }
        Checkpoint existing = checkpoints.get(epoch);
        if (existing != null) {
            if (!existing.blockHash.equals(hash)) {
                throw new IllegalStateException(
                        "Weak-subjectivity checkpoint at epoch " + epoch + " conflicts with the stored chain");
            }
            if (!existing.justified || !existing.finalized) {
                existing.justified = true;
                existing.finalized = true;
                persistCheckpoint(existing, store);
            }
            return;
        }
        Checkpoint ws = new Checkpoint(hash, epoch);
        ws.justified = true;
        ws.finalized = true;
        checkpoints.put(epoch, ws);
        persistCheckpoint(ws, store);
        log.info("Weak-subjectivity anchor pinned: epoch={}, block={}", epoch, hash);
    }

    /** Returns the existing checkpoint for {@code epoch} or creates it at {@code blockHash}. */
    public Checkpoint ensureCheckpoint(long epoch, Sha256Hash blockHash) {
        return checkpoints.computeIfAbsent(epoch, e -> {
            Checkpoint cp = new Checkpoint(blockHash, e);
            // The genesis checkpoint is the root of trust: justified + finalized.
            if (e == 0) {
                cp.justified = true;
                cp.finalized = true;
            }
            persistCheckpoint(cp, null);
            return cp;
        });
    }

    /**
     * Chain-derived checkpoint creation. The checkpoint hash for an epoch is a
     * PURE FUNCTION of the confirmed chain: the confirmed reward block at the
     * SLOT-based epoch boundary — the last beacon with slot &lt; epoch*32 (i.e.
     * the last beacon of the previous epoch). This stays correct under slot
     * drift (missed slots), where a chainlength-derived boundary would land in
     * the wrong epoch. When the boundary is not yet confirmed a transient
     * (non-cached) checkpoint is returned so the cache is never poisoned with a
     * node-local value. Legacy beacons without SlotData fall back to the
     * chainlength boundary.
     */
    public Checkpoint ensureCheckpoint(long epoch, BlockStoreInterface store) {
        Checkpoint existing = checkpoints.get(epoch);
        if (existing != null) {
            return existing;
        }
        Sha256Hash boundaryHash = slotBoundaryHash(epoch, store);
        if (boundaryHash == null) {
            // Boundary not confirmed yet: return a transient checkpoint that is
            // NOT cached, so it cannot poison future lookups.
            Checkpoint transientCp = new Checkpoint(confirmedHeadOrGenesis(store), epoch);
            if (epoch == 0) {
                transientCp.justified = true;
                transientCp.finalized = true;
            }
            return transientCp;
        }
        Checkpoint cp = new Checkpoint(boundaryHash, epoch);
        if (epoch == 0) {
            cp.justified = true;
            cp.finalized = true;
        }
        checkpoints.put(epoch, cp);
        try {
            String val = cp.blockHash.toString() + "," + cp.justified + "," + cp.finalized;
            store.savePosState("casper", "ckpt_" + cp.epoch,
                    val.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.debug("Failed to persist checkpoint at creation", ex);
        }
        return cp;
    }

    /**
     * The confirmed beacon at the slot-based epoch boundary: the last beacon
     * with slot &lt; {@code epoch*32}, found by walking the reward chain back
     * from the tip. Null when the chain has not yet entered {@code epoch} (the
     * boundary is not final) or a boundary cannot be derived.
     */
    private Sha256Hash slotBoundaryHash(long epoch, BlockStoreInterface store) {
        long boundarySlot = epoch * SlotService.SLOTS_PER_EPOCH;
        try {
            TXReward tip = cacheBlockService.getMaxConfirmedReward(store);
            if (tip == null) {
                return null;
            }
            Sha256Hash cursor = tip.getBlockHash();
            java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
            // The boundary is only CONFIRMED once the walk observes a beacon at
            // or beyond it (a beacon only confirms when the next one arrives).
            // Until then the boundary is not yet buried: returning the tip would
            // cache a PREMATURE boundary (a mid-epoch beacon) that later proves
            // wrong — attestation targets would fragment by exact hash and
            // justification could split/stall on a healthy network. So return
            // null (keeping the transient, uncached checkpoint path in
            // ensureCheckpoint) unless the boundary was actually buried.
            boolean boundaryBuried = false;
            while (cursor != null && visited.add(cursor)) {
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    return null;
                }
                Long slot = slotOf(b);
                if (slot == null) {
                    // Legacy beacon without SlotData: fall back to the
                    // chainlength boundary (pre-SlotData chains).
                    TXReward legacy = store.getRewardConfirmedAtHeight(boundarySlot);
                    return legacy != null ? legacy.getBlockHash() : null;
                }
                if (slot >= boundarySlot) {
                    // Still in (or past) epoch `epoch`: keep walking back toward
                    // the first beacon below the boundary.
                    boundaryBuried = true;
                    net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                            .parseChecked(b.getTransactions().get(0).getData());
                    if (ri == null) {
                        return null;
                    }
                    cursor = ri.getPrevRewardHash();
                    continue;
                }
                // First beacon below the boundary while walking from a tip at or
                // above it: the last beacon of epoch-1 is the checkpoint. If the
                // boundary is not buried (the tip itself is below it) the true
                // boundary is not yet confirmed — return null, not the tip.
                return boundaryBuried ? cursor : null;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** The SlotData slot of a beacon, or null when it carries none (legacy). */
    private Long slotOf(Block b) {
        try {
            for (Transaction tx : b.getTransactions()) {
                if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
                    net.bigtangle.core.SlotData sd = net.bigtangle.utils.Json.jsonmapper()
                            .readValue(tx.getData(), net.bigtangle.core.SlotData.class);
                    if (sd != null) {
                        return sd.getSlot();
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private Sha256Hash confirmedHeadOrGenesis(BlockStoreInterface store) {
        try {
            TXReward r = cacheBlockService.getMaxConfirmedReward(store);
            if (r != null) {
                return r.getBlockHash();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return UtilGeneseBlock.createGenesis(networkParameters).getHash();
    }

    public boolean isCheckpointJustified(long epoch) {
        Checkpoint cp = checkpoints.get(epoch);
        return cp != null && cp.justified;
    }

    public boolean isCheckpointFinalized(long epoch) {
        Checkpoint cp = checkpoints.get(epoch);
        return cp != null && cp.finalized;
    }

    /** The highest justified checkpoint (the source for the next epoch's votes). */
    public Checkpoint getJustifiedCheckpoint() {
        Checkpoint best = null;
        for (Checkpoint cp : checkpoints.values()) {
            if (cp.justified && (best == null || cp.epoch > best.epoch)) {
                best = cp;
            }
        }
        return best;
    }

    /** The highest FINALIZED checkpoint — the immutable anchor of fork choice. */
    public Checkpoint getLastFinalizedCheckpoint() {
        Checkpoint best = null;
        for (Checkpoint cp : checkpoints.values()) {
            if (cp.finalized && (best == null || cp.epoch > best.epoch)) {
                best = cp;
            }
        }
        return best;
    }

    /**
     * True if the reward chain containing {@code startHash} descends from (or
     * is) {@code chainAncestor}. {@code startHash} MUST already be persisted —
     * callers pass the new block's prevRewardHash (whose existence solidity
     * checking guaranteed), never the not-yet-connected tip itself: an
     * unresolvable start hash returns false, which would refuse every reorg.
     * The walk is bounded by the chainlength delta to the anchor (chainlength
     * strictly decreases along prevRewardHash links), so a stalled-finality
     * anchor far behind the head can never turn this into an unbounded scan or
     * a wrong refusal.
     */
    public boolean descendsFrom(Sha256Hash startHash, Sha256Hash chainAncestor, BlockStoreInterface store) {
        if (startHash == null || chainAncestor == null) {
            return false;
        }
        long anchorChainlength = rewardChainlengthOf(chainAncestor, store);
        Sha256Hash cursor = startHash;
        java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(chainAncestor)) {
                return true;
            }
            try {
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    return false;
                }
                net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                        .parseChecked(b.getTransactions().get(0).getData());
                if (ri == null) {
                    return false;
                }
                // At/below the anchor's height the anchor can no longer be met.
                if (anchorChainlength >= 0 && ri.getChainlength() <= anchorChainlength) {
                    return false;
                }
                cursor = ri.getPrevRewardHash();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** Reward chainlength of a stored beacon, or -1 for genesis/unparseable. */
    private long rewardChainlengthOf(Sha256Hash hash, BlockStoreInterface store) {
        try {
            Block b = store.get(hash);
            if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                return -1;
            }
            net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                    .parseChecked(b.getTransactions().get(0).getData());
            return ri != null ? ri.getChainlength() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public void processSlot(long slot, Sha256Hash beaconHash,
            List<AttestationData> attestations, BlockStoreInterface store) throws Exception {
        if (beaconHash != null) {
            ensureCheckpoint(slot / 32, store);
        }
        for (AttestationData att : attestations) {
            processVote(att, store);
        }
    }

    public void processVote(AttestationData att, BlockStoreInterface store) throws Exception {
        if (att.getValidatorPubkey() == null) {
            return;
        }
        String vkey = Utils.HEX.encode(att.getValidatorPubkey());

        // Authenticate the attestation before it influences any state.
        if (!verifyAttestation(att)) {
            log.warn("Rejecting unauthenticated attestation from pubkey={} slot={}", vkey, att.getSlot());
            return;
        }

        // Only active validators can attest; unknown/slashed pubkeys are
        // rejected BEFORE touching any vote/slashing/gossip state, so the
        // endpoint cannot cause unbounded memory or DB growth.
        if (stakeService.getEffectiveStake(att.getValidatorPubkey(), store) <= 0) {
            log.warn("Rejecting attestation from non-validator pubkey={} slot={}", vkey, att.getSlot());
            return;
        }

        // Sanity: the vote's epoch must be its slot's own epoch (honest votes
        // always satisfy this), and far-future targets are rejected — otherwise
        // a validator could inflate per-epoch vote records with arbitrary
        // epochs. The TARGET epoch may legitimately be older than the slot's
        // epoch (a late vote still counts toward its target, like Ethereum's
        // attestation inclusion window).
        if (att.getEpoch() != att.getSlot() / 32) {
            log.warn("Rejecting attestation with inconsistent epoch from pubkey={} slot={} epoch={}",
                    vkey, att.getSlot(), att.getEpoch());
            return;
        }
        long wallEpoch = SlotService.epochAt(System.currentTimeMillis());
        if (att.getTargetEpoch() > wallEpoch + 1) {
            log.warn("Rejecting far-future attestation from pubkey={} slot={} (wall epoch {})",
                    vkey, att.getSlot(), wallEpoch);
            return;
        }

        // Only a genuine double vote (two different heads for the same slot)
        // or a surround vote slashes. The slash is proposed as a consensus
        // BLOCKTYPE_SLASHING block (validated + applied by every node), never
        // by directly mutating local UTXO state.
        AttestationData evidence = slashingService.checkDoubleVote(att);
        if (evidence == null) {
            evidence = slashingService.checkSurroundVote(att);
        }
        if (evidence != null) {
            log.warn("Slashing: slashable vote by pubkey={} slot={}", vkey, att.getSlot());
            stakeService.submitSlashing(evidence, att, store);
            return;
        }

        // Ignore stale/duplicate votes; the latest vote is the highest slot seen.
        Long lastSlot = latestVotes.get(vkey);
        if (lastSlot != null && att.getSlot() <= lastSlot) {
            return;
        }

        latestVotes.put(vkey, att.getSlot());
        latestVoteBeacons.put(vkey, att.getBeaconBlockHash());
        latestVoteSources.put(vkey, att.getSourceCheckpoint() != null ? att.getSourceCheckpoint() : Sha256Hash.ZERO_HASH);
        latestVoteTargets.put(vkey, att.getTargetCheckpoint() != null ? att.getTargetCheckpoint() : Sha256Hash.ZERO_HASH);

        // Per-epoch record: the vote keeps counting toward its target epoch's
        // checkpoint even after this validator votes in later epochs.
        epochVoteTargets.computeIfAbsent(vkey, k -> new ConcurrentHashMap<>()).put(att.getTargetEpoch(),
                att.getTargetCheckpoint() != null ? att.getTargetCheckpoint() : Sha256Hash.ZERO_HASH);
        epochVoteSources.computeIfAbsent(vkey, k -> new ConcurrentHashMap<>()).put(att.getTargetEpoch(),
                att.getSourceCheckpoint() != null ? att.getSourceCheckpoint() : Sha256Hash.ZERO_HASH);

        // ghostService.processAttestation also persists the vote (LMD: one row
        // per validator) — no separate save here.
        ghostService.processAttestation(att, store);

        store.savePosState("casper", "vote_" + vkey,
                java.math.BigInteger.valueOf(att.getSlot()).toByteArray());
        if (att.getSourceCheckpoint() != null) {
            store.savePosState("casper", "src_" + vkey, att.getSourceCheckpoint().getBytes());
        }
        if (att.getTargetCheckpoint() != null) {
            store.savePosState("casper", "tgt_" + vkey, att.getTargetCheckpoint().getBytes());
        }
        if (att.getBeaconBlockHash() != null) {
            store.savePosState("casper", "beacon_" + vkey, att.getBeaconBlockHash().getBytes());
        }
        persistEpochVotes(vkey, store);

        gossipService.broadcastAttestation(att);
    }

    /** Persists the validator's per-epoch vote window (epochs below the finality floor are dropped). */
    private void persistEpochVotes(String vkey, BlockStoreInterface store) {
        try {
            Checkpoint fin = getLastFinalizedCheckpoint();
            long floor = fin != null ? fin.epoch - 1 : 0;
            ConcurrentHashMap<Long, Sha256Hash> tgts = epochVoteTargets.get(vkey);
            ConcurrentHashMap<Long, Sha256Hash> srcs = epochVoteSources.get(vkey);
            StringBuilder sb = new StringBuilder();
            if (tgts != null) {
                for (Map.Entry<Long, Sha256Hash> en : tgts.entrySet()) {
                    if (en.getKey() < floor) {
                        continue;
                    }
                    Sha256Hash src = srcs != null ? srcs.get(en.getKey()) : null;
                    sb.append(en.getKey()).append(':').append(en.getValue().toString()).append(':')
                            .append(src != null ? src.toString() : Sha256Hash.ZERO_HASH.toString()).append(';');
                }
            }
            store.savePosState("casper", "evotes_" + vkey, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Failed to persist epoch votes", e);
        }
    }

    /** Drops per-epoch vote records that can no longer influence justification/finalization. */
    private void pruneEpochVotes(long finalizedEpoch) {
        long floor = finalizedEpoch - 1; // one epoch of slack
        for (ConcurrentHashMap<Long, Sha256Hash> m : epochVoteTargets.values()) {
            m.keySet().removeIf(e -> e < floor);
        }
        for (ConcurrentHashMap<Long, Sha256Hash> m : epochVoteSources.values()) {
            m.keySet().removeIf(e -> e < floor);
        }
    }

    /**
     * Bounds the per-epoch vote maps against growth during finality stalls: any
     * vote whose target epoch is older than {@code currentEpoch} minus the
     * inactivity-leak window (plus slack) can no longer contribute to a live
     * checkpoint, so it is dropped. Runs unconditionally at each checkpoint
     * evaluation, independent of whether finality actually advances.
     */
    private void pruneStaleEpochVotes(long currentEpoch) {
        long floor = currentEpoch - INACTIVITY_WINDOW_EPOCHS - 1;
        for (ConcurrentHashMap<Long, Sha256Hash> m : epochVoteTargets.values()) {
            m.keySet().removeIf(e -> e < floor);
        }
        for (ConcurrentHashMap<Long, Sha256Hash> m : epochVoteSources.values()) {
            m.keySet().removeIf(e -> e < floor);
        }
    }

    /** Verifies the attestation signature against the declared validator pubkey. */
    public boolean verifyAttestation(AttestationData att) {
        return att != null && att.verifySignature();
    }

    /**
     * A validator counts toward the justification denominator only if it voted
     * within the last {@code INACTIVITY_WINDOW_EPOCHS} epochs (the inactivity
     * leak): on a healthy network everyone votes every epoch and the denominator
     * equals the total stake; if a large fraction of stake stops voting, its
     * weight drains out of the denominator after the window passes, so the
     * remaining online stake can reach 2/3 again and finality resumes.
     */
    public static final long INACTIVITY_WINDOW_EPOCHS = 8;

    /**
     * The 2/3 justification threshold over the ACTIVELY VOTING stake, floored at
     * 1/3 of the total stake: the floor stops a tiny online minority (e.g. a
     * freshly synced node with a sparse vote view) from justifying checkpoints
     * alone — recovering finality always requires at least ~2/9 of total stake
     * voting for the same target. The reference epoch is derived from the vote
     * view (never the wall clock), so nodes that have seen the same votes
     * compute the same threshold.
     */
    private BigInteger justificationThreshold(long targetEpoch, BigInteger totalStake,
            BlockStoreInterface store) throws Exception {
        // The reference epoch is the newest vote of an ACTIVE validator — never
        // the wall clock, and never a non-active key's stale vote view.
        long referenceEpoch = targetEpoch;
        java.util.List<StakeRecord> active = store.getActiveStakeDeposits();
        for (StakeRecord v : active) {
            Long s = latestVotes.get(Utils.HEX.encode(v.getPubkey()));
            if (s != null) {
                referenceEpoch = Math.max(referenceEpoch, s / 32);
            }
        }
        long activeFloor = referenceEpoch - INACTIVITY_WINDOW_EPOCHS;
        BigInteger onlineStake = BigInteger.ZERO;
        for (StakeRecord v : active) {
            Long lastSlot = latestVotes.get(Utils.HEX.encode(v.getPubkey()));
            if (lastSlot != null && lastSlot / 32 >= activeFloor) {
                onlineStake = onlineStake.add(v.getAmount());
            }
        }
        BigInteger denominator = onlineStake.max(totalStake.divide(BigInteger.valueOf(3)));
        return denominator.multiply(BigInteger.valueOf(2)).divide(BigInteger.valueOf(3));
    }

    public void finalizeCheckpoint(long epoch, BlockStoreInterface store) throws Exception {
        if (epoch < 0) {
            return;
        }
        // Bounded vote history: even when finality is stalled, prune per-epoch
        // vote records that can no longer influence justification/finalization.
        // The reference is the CHAIN epoch being evaluated (which advances every
        // epoch even without finality), never the wall clock — a node's clock can
        // be far ahead of the chain. The inactivity-leak window is
        // INACTIVITY_WINDOW_EPOCHS, so votes for epochs older than epoch - window
        // - slack are beyond the reach of any live checkpoint; without this, a
        // long stall would grow epochVoteTargets/epochVoteSources without bound.
        pruneStaleEpochVotes(epoch);
        // Chain-derived creation: any node (including non-proposing relays)
        // derives the checkpoint from confirmed chain state. Returning early on
        // a missing local entry would mean finality only advances on proposer
        // nodes.
        Checkpoint target = ensureCheckpoint(epoch, store);
        if (target == null || checkpoints.get(epoch) != target) {
            // Missing, or TRANSIENT (the epoch boundary is not yet confirmed
            // locally): justifying/finalizing a transient checkpoint would
            // persist a checkpoint whose hash is the current confirmed head
            // instead of the epoch boundary.
            return;
        }
        ensureCheckpoint(epoch - 1, store);
        Checkpoint parent = checkpoints.get(epoch - 1);

        BigInteger totalStake = stakeService.getTotalActiveStake(store);
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
            return;
        }
        BigInteger twoThirds = justificationThreshold(epoch, totalStake, store);

        if (!target.justified) {
            Checkpoint justifySource = getJustifiedCheckpoint();
            if (justifySource != null && justifySource.epoch < target.epoch) {
                // A vote for the target counts if it came from ANY justified
                // source, so an advancing justified checkpoint cannot orphan
                // pending votes. Counting is per (validator, target-epoch):
                // later votes in newer epochs never erase this epoch's votes.
                BigInteger votedStake = votedStakeFor(target, null, store);
                if (votedStake.compareTo(twoThirds) >= 0) {
                    target.justified = true;
                    log.info("Checkpoint justified: epoch={}, block={}, votedStake={}/{}",
                            epoch, target.blockHash, votedStake, totalStake);
                    persistCheckpoint(target, store);
                } else {
                    log.debug("Checkpoint not justified: epoch={}, votedStake={}/{} (need {})",
                            epoch, votedStake, totalStake, twoThirds);
                }
            }
        }

        // Finalization (Ethereum's rules, adapted):
        // (a) target justified + parent already finalized → the target finalizes.
        // (b) target justified + parent justified (not finalized) + a 2/3
        //     supermajority link parent→target → the PARENT finalizes (and the
        //     target via (a)). Rule (b) is what lets finality RESUME after an
        //     epoch that failed to justify: requiring an already-finalized
        //     direct parent alone would stall finalization forever after a
        //     single missed justification.
        if (target.justified && !target.finalized && parent != null && parent.finalized) {
            target.finalized = true;
            log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
            persistCheckpoint(target, store);
            pruneEpochVotes(target.epoch);
        }
        if (target.justified && parent != null && parent.justified && !parent.finalized) {
            BigInteger link = votedStakeFor(target, parent.blockHash, store);
            if (link.compareTo(twoThirds) >= 0) {
                parent.finalized = true;
                log.info("Checkpoint FINALIZED via consecutive-epoch link: epoch={}, block={}",
                        parent.epoch, parent.blockHash);
                persistCheckpoint(parent, store);
                pruneEpochVotes(parent.epoch);
                if (!target.finalized) {
                    target.finalized = true;
                    log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
                    persistCheckpoint(target, store);
                    pruneEpochVotes(target.epoch);
                }
            }
        }
    }

    private void persistCheckpoint(Checkpoint cp, BlockStoreInterface store) {
        String val = cp.blockHash.toString() + "," + cp.justified + "," + cp.finalized;
        if (store != null) {
            try {
                store.savePosState("casper", "ckpt_" + cp.epoch, val.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.debug("Failed to persist checkpoint", e);
            }
        } else {
            // Store is only null during ensureCheckpoint creation; persist lazily
            // at the next store-bearing call (finalizeCheckpoint/processEpoch).
            log.debug("Checkpoint {} created (persist deferred)", cp.epoch);
        }
    }

    /**
     * Stake that has attested the target checkpoint — counted from the
     * per-(validator, target-epoch) vote records, so a vote keeps counting
     * toward its epoch's checkpoint even after the validator votes in a later
     * epoch. When {@code requiredSource} is null the vote counts from ANY
     * justified source (so an advancing justified checkpoint cannot orphan
     * pending votes); when non-null only votes with exactly that source count
     * (used for the consecutive-epoch supermajority link that finalizes the
     * parent). Keyed by pubkey hex — never by byte[], which uses identity
     * equality.
     */
    private BigInteger votedStakeFor(Checkpoint target, Sha256Hash requiredSource,
            BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        Map<String, BigInteger> stakeByPubkey = new HashMap<>();
        for (StakeRecord v : validators) {
            stakeByPubkey.put(Utils.HEX.encode(v.getPubkey()), v.getAmount());
        }

        BigInteger voted = BigInteger.ZERO;
        for (Map.Entry<String, ConcurrentHashMap<Long, Sha256Hash>> entry : epochVoteTargets.entrySet()) {
            Sha256Hash tgt = entry.getValue().get(target.epoch);
            if (tgt == null || !tgt.equals(target.blockHash)) {
                continue;
            }
            ConcurrentHashMap<Long, Sha256Hash> srcs = epochVoteSources.get(entry.getKey());
            Sha256Hash src = srcs != null ? srcs.get(target.epoch) : null;
            if (requiredSource != null) {
                if (!requiredSource.equals(src)) {
                    continue;
                }
            } else if (src == null || !isJustifiedCheckpointHash(src)) {
                continue;
            }
            voted = voted.add(stakeByPubkey.getOrDefault(entry.getKey(), BigInteger.ZERO));
        }
        return voted;
    }

    private boolean isJustifiedCheckpointHash(Sha256Hash hash) {
        for (Checkpoint cp : checkpoints.values()) {
            if (cp.justified && hash.equals(cp.blockHash)) {
                return true;
            }
        }
        return false;
    }
}
