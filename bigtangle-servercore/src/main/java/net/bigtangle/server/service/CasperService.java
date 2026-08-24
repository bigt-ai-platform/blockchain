package net.bigtangle.server.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	/** Votes targeting epochs this far below the chain epoch are stale-state poison. */
	private static final long ATTEST_MAX_STALE_EPOCHS = 8;

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

    // Pubkeys this node has already proposed a SLASHING block for (per restart).
    // Guards against re-proposing on every duplicate delivery of an
    // equivocating vote pair.
    private final Set<String> slashingReported = ConcurrentHashMap.newKeySet();

    @Autowired
    private GhostService ghostService;

    @Autowired
    private net.bigtangle.server.config.ServerConfiguration serverConfiguration;

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

    /** Slot-tick interval (pos.slotIntervalMs); must match SlotService's epoch base. */
    @org.springframework.beans.factory.annotation.Value("${pos.slotIntervalMs:12000}")
    private long slotIntervalMs = SlotService.SLOT_DURATION_MS;

    /** Bounded clock-skew tolerance (pos.maxClockSkewMs, 0 = default one slot).
     *  See {@link net.bigtangle.server.service.SlotService#maxClockSkewMs()}. */
    @org.springframework.beans.factory.annotation.Value("${pos.maxClockSkewMs:0}")
    private long maxClockSkewMs = 0;

    private long maxClockSkewMs() {
        return maxClockSkewMs > 0 ? maxClockSkewMs : slotIntervalMs;
    }

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
        // Idempotent reload: rebuild in-memory state from persisted rows, never
        // merge into stale maps (so a test/restart reset cannot leak old votes).
        checkpoints.clear();
        latestVotes.clear();
        latestVoteBeacons.clear();
        latestVoteSources.clear();
        latestVoteTargets.clear();
        epochVoteTargets.clear();
        epochVoteSources.clear();
        includedCacheTip = null;
        includedCache = java.util.Collections.emptyMap();
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
     * PURE FUNCTION of the confirmed chain: the last confirmed beacon with slot
     * &lt; epoch*32 (the epoch boundary, see {@link #epochBoundaryHash}). This
     * is deterministic AND stable for the whole epoch — beacons of the epoch
     * have slots at/above the boundary, so the boundary never changes while the
     * epoch advances. Because the boundary is a confirmed, immutable block, the
     * checkpoint can be cached as soon as it is derivable; a reorg invalidates
     * it via {@link #invalidateCheckpointsFrom}. When the boundary is not yet
     * derivable (epoch 0 pre-genesis, or an empty chain) a transient
     * (non-cached) checkpoint is returned so the cache is never poisoned with a
     * node-local value.
     */
    public Checkpoint ensureCheckpoint(long epoch, BlockStoreInterface store) {
        Checkpoint existing = checkpoints.get(epoch);
        if (existing != null) {
            return existing;
        }
        Sha256Hash boundaryHash = epochBoundaryHash(epoch, store);
        if (boundaryHash == null) {
            // Boundary not derivable yet: return a transient checkpoint that is
            // NOT cached, so it cannot poison future lookups. The transient
            // target is DETERMINISTIC wherever possible: the previous epoch's
            // cached chain-derived checkpoint (identical on every synced node)
            // instead of this node's confirmed head, which is node-local and
            // would fragment attestation targets during the first confirmed
            // position of each epoch.
            Checkpoint prev = epoch > 0 ? checkpoints.get(epoch - 1) : null;
            if (prev != null && prev.blockHash != null) {
                return new Checkpoint(prev.blockHash, epoch);
            }
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
     * The SlotData slot of a beacon, or null when it carries none (legacy).
     */
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

    /**
     * The DETERMINISTIC attestation-target checkpoint for {@code epoch}: the
     * last CONFIRMED beacon at chainlength {@code &lt;= epoch*32} (the epoch
     * boundary). The boundary is derived from the CONFIRMED reward chain's
     * position (RewardInfo.chainlength), NOT from the beacon's absolute
     * wall-clock slot.
     *
     * <p>Beacon slots are absolute wall-clock slots since the 2018 genesis
     * (slot ~21,000,000 today), so a slot-relative walk ({@code slot < epoch*32})
     * can never find the boundary for the FIRST chain epochs on a fresh chain —
     * every beacon has a huge slot, the walk always falls through to genesis,
     * and every node derives a fragmented/identical-to-genesis checkpoint, so
     * 2/3 justification never forms. Chainlength, by contrast, is a pure
     * function of the confirmed reward chain: position {@code E*32} is
     * deterministic and identical on every node that has confirmed the same
     * chain, so honest attestations all target the same checkpoint.
     *
     * <p>Returns the genesis hash when {@code epoch} is 0 or the chain has not
     * yet reached the boundary (the genesis checkpoint is the root of trust).
     */
    public Sha256Hash epochBoundaryHash(long epoch, BlockStoreInterface store) {
        long boundaryChainlength = epoch * SlotService.SLOTS_PER_EPOCH;
        try {
            TXReward tip = cacheBlockService.getMaxConfirmedReward(store);
            if (tip == null) {
                return null;
            }
            if (tip.getChainLength() <= boundaryChainlength) {
                // The chain has not yet reached this epoch's boundary: the
                // highest confirmed position is the current floor, but for the
                // target epoch the boundary is the confirmed beacon at exactly
                // boundaryChainlength. If the chain is still inside this epoch
                // (tip <= boundary), the checkpoint for THIS epoch is not yet
                // confirmable — the last complete epoch boundary is the tip of
                // the previous one. Callers (ensureCheckpoint) only create a
                // cached checkpoint once the boundary is confirmed; until then a
                // transient checkpoint is returned and no finality is attempted.
                return null;
            }
            Sha256Hash cursor = tip.getBlockHash();
            java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
            while (cursor != null && visited.add(cursor)) {
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    // Reached genesis: it is the boundary of epoch 0 and the
                    // floor for every later epoch's walk.
                    return cursor;
                }
                net.bigtangle.core.RewardInfo ri;
                try {
                    ri = new net.bigtangle.core.RewardInfo()
                            .parseChecked(b.getTransactions().get(0).getData());
                } catch (Exception e) {
                    return cursor;
                }
                long cl = ri.getChainlength();
                if (cl > boundaryChainlength) {
                    // Still inside (or past) epoch `epoch`: walk back toward the
                    // first beacon at/below the boundary.
                    cursor = ri.getPrevRewardHash();
                    continue;
                }
                // First confirmed beacon at/below the boundary chainlength: the
                // deterministic epoch checkpoint.
                return cursor;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
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

        // Cheap rejection FIRST: unknown/slashed/not-yet-active pubkeys are
        // dropped before any expensive work, so the endpoint cannot be spammed
        // with random-key garbage that forces a PQ/BLS signature verification
        // per request (DoS cost bound).
        if (stakeService.getEffectiveStake(att.getValidatorPubkey(), store) <= 0) {
            log.warn("Rejecting attestation from non-validator pubkey={} slot={}", vkey, att.getSlot());
            return;
        }

        // Authenticate the attestation before it influences any state.
        if (!verifyAttestation(att)) {
            log.warn("Rejecting unauthenticated attestation from pubkey={} slot={}", vkey, att.getSlot());
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
        // The wall epoch uses the bounded clock-skew upper bound: a node whose
        // clock is behind by up to maxClockSkewMs must not reject attestations
        // for the REAL current epoch as "far future".
        long wallEpoch = SlotService.epochAt(System.currentTimeMillis() + maxClockSkewMs(), slotIntervalMs);
        if (att.getTargetEpoch() > wallEpoch + 1) {
            log.warn("Rejecting far-future attestation from pubkey={} slot={} (wall epoch {})",
                    vkey, att.getSlot(), wallEpoch);
            return;
        }

        // Reject votes anchored to checkpoints hopelessly behind the chain:
        // they come from validators whose justification state is stale (mass
        // restart / catch-up) and can never contribute to real quorum — but
        // silently counting them hides the stall. Visible + excluded.
        long chainEpochNow = net.bigtangle.server.service.SlotService.currentChainEpoch(store);
        if (att.getTargetEpoch() < chainEpochNow - ATTEST_MAX_STALE_EPOCHS) {
            log.warn("Rejecting stale-epoch attestation from pubkey={} slot={} target={} (chain epoch {})",
                    vkey, att.getSlot(), att.getTargetEpoch(), chainEpochNow);
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
            // Discard the equivocating validator from fork choice (PR #2845).
            ghostService.markEquivocating(att.getValidatorPubkey());
            // Propose the slashing block ONCE per equivocating pubkey: every
            // node that observes both conflicting votes would otherwise build
            // its own SLASHING block on its local head, forking the reward
            // chain N ways per event. Application is idempotent either way;
            // this only avoids the duplicate-proposal noise.
            if (slashingReported.add(vkey)) {
                stakeService.submitSlashing(evidence, att, store);
            } else {
                log.debug("Slashing evidence for pubkey={} already reported", vkey);
            }
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
        persistFullAttestation(att, store);

        // Kafka is the reliable vote transport (the gossip HTTP path proved
        // lossy under load — see AttestationStreamHandler). Relaying every
        // first-seen vote via HTTP on TOP of kafka triple-delivered each
        // attestation: receivers paid HTTP parse + BLS verify + DB writes
        // per redundant copy, dominating CPU at ~0 useful blocks/min.
        if (!serverConfiguration.getRunKafkaStream()) {
            gossipService.broadcastAttestation(att);
        }
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
     * Whether on-chain embedded attestations are the source of truth for
     * justification/fork-choice. A HARD height gate (not a "non-empty" heuristic):
     * at/above {@code POS_BEACON_SLOTDATA_ACTIVATION} votes are read from the
     * embedded chain set; below it the gossip view is used. This makes the fork
     * transition deterministic — every node on the same chain height uses the
     * same source.
     */
    public static boolean onChainAttestationActive(BlockStoreInterface store) {
        try {
            TXReward tip = store.getMaxConfirmedReward();
            return tip != null && tip.getChainLength() >= NetworkParameters.posAttestationActivation();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deterministic root over an (unordered) set of attestations: sorts by
     * message hash and hashes the concatenation. The same set yields the same
     * root on every node — the inclusion commitment the proposer signs.
     */
    public static Sha256Hash computeAttestationRoot(List<AttestationData> attestations) {
        if (attestations == null || attestations.isEmpty()) {
            return Sha256Hash.ZERO_HASH;
        }
        List<AttestationData> sorted = new ArrayList<>(attestations);
        sorted.sort(Comparator.comparing(a -> a.getMessageHash().toString()));
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        for (AttestationData a : sorted) {
            baos.write(a.getMessageHash().getBytes(), 0, 32);
        }
        return Sha256Hash.of(baos.toByteArray());
    }

    /**
     * Zero-padded slot in the pos_state attestation key so lexicographic key
     * order matches slot order. This lets the epoch prune delete the stale range
     * in a single statement and keeps the per-slot prefix read exact.
     */
    static String attestationKey(long slot, byte[] validatorPubkey) {
        return "att_" + String.format("%020d", slot) + "_" + Utils.HEX.encode(validatorPubkey);
    }

    /**
     * Upper bound on attestations a proposer embeds in one beacon. Bounds beacon
     * size (each attestation is a signed JSON payload) until BLS aggregation
     * lands; with one vote per validator per slot it only binds above ~1k
     * validators. Selection is deterministic (newest slots first, pubkey
     * tie-break) so the commitment root always matches the embedded set.
     */
    public static final int MAX_ATTESTATIONS_PER_BEACON = 1024;

    /** The newest {@link #MAX_ATTESTATIONS_PER_BEACON} attestations, deterministically selected. */
    static List<AttestationData> capAttestations(List<AttestationData> attestations) {
        if (attestations == null || attestations.size() <= MAX_ATTESTATIONS_PER_BEACON) {
            return attestations;
        }
        List<AttestationData> sorted = new ArrayList<>(attestations);
        sorted.sort(Comparator.comparingLong(AttestationData::getSlot)
                .thenComparing(a -> Utils.HEX.encode(a.getValidatorPubkey())));
        return new ArrayList<>(sorted.subList(sorted.size() - MAX_ATTESTATIONS_PER_BEACON, sorted.size()));
    }

    /** Persists the full (signed) attestation so it can be committed into the beacon. */
    private void persistFullAttestation(AttestationData att, BlockStoreInterface store) {
        try {
            byte[] json = net.bigtangle.utils.Json.jsonmapper().writeValueAsBytes(att);
            store.savePosState("attestation",
                    attestationKey(att.getSlot(), att.getValidatorPubkey()), json);
        } catch (Exception e) {
            log.debug("Failed to persist full attestation for slot {}", att.getSlot(), e);
        }
    }

    /**
     * Carry-forward window for attestation embedding: a vote is offered to
     * EVERY proposer for this many slots after it was cast, not just to the
     * proposer of the same slot. The old same-slot-only rule made embedding a
     * lottery — the single proposer of slot N had to observe the vote BEFORE
     * building, otherwise the vote never entered ANY beacon, leaving early
     * confirmed history without embedded attestations. That starved both
     * justification (0-weight checkpoints) and bootstrap-fork reconciliation
     * (branch walks found no votes to compare).
     */
    public static final long EMBED_CARRY_SLOTS = 32;
    /** Upper bound on embedded attestations per beacon (payload safety). */
    public static final int EMBED_MAX_ATTESTATIONS = 256;

    /** The full (signed) attestations persisted for {@code slot} on this node. */
    private List<AttestationData> readAttestationsForSlot(long slot, BlockStoreInterface store) {
        List<AttestationData> list = new ArrayList<>();
        String prefix = "att_" + String.format("%020d", slot) + "_";
        try {
            // Prefix-scoped read: only this slot's attestations are loaded, so the
            // per-slot proposer path stays O(attestations of the slot) instead of
            // scanning the whole "attestation" service map.
            for (Map.Entry<String, byte[]> e : store.getPosStateByServicePrefix("attestation", prefix).entrySet()) {
                try {
                    AttestationData att = net.bigtangle.utils.Json.jsonmapper()
                            .readValue(e.getValue(), AttestationData.class);
                    if (att != null) {
                        list.add(att);
                    }
                } catch (Exception ex) {
                    // skip malformed entry
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read attestations for slot {}", slot, e);
        }
        return list;
    }

    /**
     * Attestations to embed in the beacon proposed for {@code slot}: this
     * slot's votes plus the LATEST-per-validator vote from the trailing
     * {@link #EMBED_CARRY_SLOTS} window. Latest-wins per validator keeps the
     * set LMD-consistent (a later vote supersedes an earlier one from the same
     * validator) and bounded.
     */
    public List<AttestationData> getAttestationsForSlot(long slot, BlockStoreInterface store) {
        Map<String, AttestationData> latest = new java.util.LinkedHashMap<>();
        for (AttestationData att : readAttestationsForSlot(slot, store)) {
            latest.put(Utils.HEX.encode(att.getValidatorPubkey()), att);
        }
        long from = Math.max(0, slot - EMBED_CARRY_SLOTS);
        for (long s = slot - 1; s >= from && latest.size() < EMBED_MAX_ATTESTATIONS; s--) {
            for (AttestationData att : readAttestationsForSlot(s, store)) {
                String pk = Utils.HEX.encode(att.getValidatorPubkey());
                AttestationData existing = latest.get(pk);
                if (existing == null || att.getSlot() > existing.getSlot()) {
                    latest.put(pk, att);
                }
            }
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * Bounds per-(validator, target-epoch) vote records during finality stalls:
     * votes whose target epoch is older than this window past the evaluated
     * epoch can no longer contribute to a live checkpoint, so they are pruned to
     * bound memory. NOTE: this is a bookkeeping bound only — justification
     * itself uses a pure 2/3-of-total-stake threshold, never this window.
     */
    public static final long INACTIVITY_WINDOW_EPOCHS = 8;

    /**
     * Bouncing-attack defense (Ethereum): a justified checkpoint may only switch
     * to a competing (non-descendant) chain during the first this-many slots of
     * an epoch; afterwards it must descend from the current justified checkpoint.
     */
    public static final long SAFE_SLOTS_TO_UPDATE_JUSTIFIED = 8;

    /**
     * Chain-read lookback (slots) for on-chain embedded attestations: 10 epochs
     * covers the inactivity window (8) plus the 2-epoch reward lookback. Must be
     * longer than the reward-chain horizon — the attestation read needs to reach
     * further back than {@code CHAINLENGTH_CUTOFF}.
     */
    public static final int ATTESTATION_LOOKBACK_SLOTS = 320;

    /**
     * The inactivity leak (Ethereum): when the chain fails to finalize for more
     * than this many epochs, validators that stopped voting have their effective
     * weight drained so the remaining online stake regains 2/3 and finality
     * resumes. The leak is a pure function of CHAIN state (on-chain embedded
     * attestations + finality delay), never the node's local vote view.
     */
    public static final long INACTIVITY_PENALTY_THRESHOLD_EPOCHS = 4;
    /** Quadratic-drain divisor for the leak (Ethereum's inactivity score analog). */
    public static final long INACTIVITY_LEAK_DIVISOR = 64;
    /** pos_state service namespace for the real inactivity leak bookkeeping. */
    public static final String LEAK_SERVICE = "leak";

    /**
     * The 2/3 justification threshold over the TOTAL active stake — Ethereum's
     * rule. It is a pure function of chain state, never the node's local vote
     * view, so every node that applied the same chain derives the identical
     * threshold and cannot disagree on justification.
     */
    private BigInteger justificationThreshold(BigInteger totalStake) {
        return totalStake.multiply(BigInteger.valueOf(2)).divide(BigInteger.valueOf(3));
    }

    /**
     * Bouncing-attack defense: a justified checkpoint may only switch to a
     * competing (non-descendant) chain during the first
     * {@link #SAFE_SLOTS_TO_UPDATE_JUSTIFIED} slots of the current epoch; after
     * that it must descend from the current justified checkpoint (same chain).
     */
    private boolean canSwitchJustification(Checkpoint target, Checkpoint source, BlockStoreInterface store) {
        if (!onChainAttestationActive(store)) {
            return true; // pre-fork: checkpoints may be synthetic (bootstrap/tests)
        }
        // The safe window uses the EARLIEST plausible wall-clock time (now minus
        // the bounded skew): a node whose clock is ahead must not think the
        // window already closed and refuse a switch the rest of the network
        // allows — fail-safe on the permissive side.
        long earliestNow = System.currentTimeMillis() - maxClockSkewMs();
        if (SlotService.currentSlotInEpoch(earliestNow, slotIntervalMs) < SAFE_SLOTS_TO_UPDATE_JUSTIFIED) {
            return true;
        }
        try {
            return descendsFrom(target.blockHash, source.blockHash, store);
        } catch (Exception e) {
            log.debug("Justification descent check failed: {}", e.getMessage());
            return true; // fail-open on resolution errors (avoid liveness stall)
        }
    }

    /**
     * Total active stake for the justification denominator at {@code epoch}.
     * Sums the effective balances of the validators active as of that epoch.
     * The REAL inactivity leak ({@link #applyInactivityLeak}) has already
     * reduced the stake.amount of non-voters at each epoch boundary, so this
     * sum shrinks naturally when finality stalls — the online majority regains
     * 2/3 of the reduced total and finality resumes. Fully deterministic —
     * derived from chain state only.
     */
    private BigInteger leakedTotalStake(BlockStoreInterface store, long epoch) {
        try {
            List<StakeRecord> active = store.getActiveStakeDeposits(epoch);
            BigInteger total = BigInteger.ZERO;
            for (StakeRecord v : active) {
                total = total.add(StakeService.effectiveBalance(v));
            }
            return total;
        } catch (Exception e) {
            log.debug("Failed to compute leaked total stake", e);
            return BigInteger.ZERO;
        }
    }

    /**
     * REAL inactivity leak: when finality is stalled past the threshold, each
     * active validator that did NOT attest the just-ended epoch has its ACTUAL
     * bonded balance reduced quadratically (stake.amount * 64 / (64 + delay^2)),
     * applied deterministically at the epoch boundary (the first beacon of the
     * following epoch confirms). This is a real balance bleed — not a virtual
     * denominator drain — so leaked validators' weight, rewards and withdrawn
     * bond all shrink. Reorg-aware: the reductions are keyed to the boundary
     * beacon and reverted by {@link #revertInactivityLeak} on unconfirm.
     *
     * <p>Only runs post-fork ({@link #onChainAttestationActive}), where the
     * voter set is the deterministic chain-read set. Pre-fork the vote view is
     * not chain-authoritative, so no balance is destroyed.
     */
    public void applyInactivityLeak(long epoch, Block boundaryBeacon, BlockStoreInterface store) {
        if (boundaryBeacon == null) {
            return;
        }
        try {
            // Idempotent: this boundary beacon has already applied its leak.
            String appliedKey = "applied_" + boundaryBeacon.getHashAsString();
            if (store.getPosState(LEAK_SERVICE, appliedKey) != null) {
                return;
            }
            Set<String> voters = votersForEpoch(epoch, store);
            if (voters == null) {
                return; // pre-fork — no chain-authoritative voter set
            }
            Checkpoint fin = getLastFinalizedCheckpoint();
            long delay = fin != null ? epoch - fin.epoch : 0;
            if (delay <= INACTIVITY_PENALTY_THRESHOLD_EPOCHS) {
                store.savePosState(LEAK_SERVICE, appliedKey, new byte[] { 1 });
                return; // no leak while finality is healthy
            }
            long div = INACTIVITY_LEAK_DIVISOR + delay * delay;
            for (StakeRecord v : store.getActiveStakeDeposits(epoch)) {
                if (voters.contains(Utils.HEX.encode(v.getPubkey()))) {
                    continue;
                }
                // Record the pre-leak balance for reorg revert, then reduce it.
                store.savePosState(LEAK_SERVICE,
                        "pre_" + boundaryBeacon.getHashAsString() + "_" + Utils.HEX.encode(v.getPubkey()),
                        v.getAmount().toByteArray());
                BigInteger reduced = v.getAmount()
                        .multiply(BigInteger.valueOf(INACTIVITY_LEAK_DIVISOR))
                        .divide(BigInteger.valueOf(div));
                if (reduced.compareTo(v.getAmount()) >= 0) {
                    continue; // no reduction — nothing to record
                }
                store.updateStakeDepositAmount(v.getPubkey(), reduced.longValue(),
                        v.getBlockHash(), v.getTxHash(), v.getActivatedEpoch());
                log.info("Inactivity leak: epoch={} reduced stake of {} {} -> {}",
                        epoch, Utils.HEX.encode(v.getPubkey()), v.getAmount(), reduced);
            }
            store.savePosState(LEAK_SERVICE, appliedKey, new byte[] { 1 });
        } catch (Exception e) {
            log.debug("Inactivity leak application failed for epoch {} beacon {}: {}",
                    epoch, boundaryBeacon.getHashAsString(), e.getMessage());
        }
    }

    /**
     * Reverts the inactivity leak applied by a boundary beacon that was
     * unconfirmed: restores each leaked validator's pre-leak balance. Idempotent
     * — a beacon that never applied a leak has nothing to revert.
     */
    public void revertInactivityLeak(Block boundaryBeacon, BlockStoreInterface store) {
        if (boundaryBeacon == null) {
            return;
        }
        try {
            String appliedKey = "applied_" + boundaryBeacon.getHashAsString();
            if (store.getPosState(LEAK_SERVICE, appliedKey) == null) {
                return;
            }
            String prefix = "pre_" + boundaryBeacon.getHashAsString() + "_";
            // Prefix-scoped read: only THIS boundary beacon's pre-leak rows are
            // loaded, not the whole leak namespace (every other boundary's
            // records stay untouched on disk).
            for (Map.Entry<String, byte[]> e : store.getPosStateByServicePrefix(LEAK_SERVICE, prefix).entrySet()) {
                byte[] pubkey = Utils.HEX.decode(e.getKey().substring(prefix.length()));
                BigInteger pre = new BigInteger(e.getValue());
                StakeRecord v = store.getStakeDeposit(pubkey);
                if (v != null) {
                    store.updateStakeDepositAmount(pubkey, pre.longValue(),
                            v.getBlockHash(), v.getTxHash(), v.getActivatedEpoch());
                }
                store.deletePosState(LEAK_SERVICE, e.getKey());
            }
            store.deletePosState(LEAK_SERVICE, appliedKey);
            log.info("Reorg: reverted inactivity leak for beacon {}",
                    boundaryBeacon.getHashAsString());
        } catch (Exception e) {
            log.debug("Inactivity leak revert failed for beacon {}: {}",
                    boundaryBeacon.getHashAsString(), e.getMessage());
        }
    }

    /** Pubkeys (hex) that attested the given target epoch, from on-chain embedded
     *  attestations. Returns {@code null} pre-fork (below the activation height),
     *  which callers treat as "reward all active validators". Deterministic
     *  post-fork — the basis for per-attestation rewards.
     */
    public static Set<String> votersForEpoch(long epoch, BlockStoreInterface store) {
        if (!onChainAttestationActive(store)) {
            return null;
        }
        Set<String> voters = new HashSet<>();
        for (AttestationData att : includedAttestations(store).values()) {
            if (att.getTargetEpoch() == epoch) {
                voters.add(Utils.HEX.encode(att.getValidatorPubkey()));
            }
        }
        return voters;
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

        // One chain-read inclusion set per evaluation: justification and both
        // finalization rules read the same confirmed tip, so the 320-slot walk
        // must not repeat per vote sum (memoized per tip — see
        // {@link #includedAttestations}).
        Map<String, AttestationData> included = onChainAttestationActive(store)
                ? includedAttestations(store) : null;

        BigInteger totalStake = leakedTotalStake(store, epoch);
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
            return;
        }
        BigInteger twoThirds = justificationThreshold(totalStake);

        if (!target.justified) {
            Checkpoint justifySource = getJustifiedCheckpoint();
            if (justifySource != null && justifySource.epoch < target.epoch) {
                // A vote for the target counts if it came from ANY justified
                // source, so an advancing justified checkpoint cannot orphan
                // pending votes. Counting is per (validator, target-epoch):
                // later votes in newer epochs never erase this epoch's votes.
                BigInteger votedStake = votedStakeFor(target, null, store, included);
                if (votedStake.compareTo(twoThirds) >= 0
                        && canSwitchJustification(target, justifySource, store)) {
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
            BigInteger link = votedStakeFor(target, parent.blockHash, store, included);
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
     * Drops cached and persisted checkpoints at/above {@code epoch} so they are
     * re-derived from the (possibly reorged) canonical chain. Called by the
     * confirm/unconfirm path when beacons at/above the epoch are reverted. The
     * genesis checkpoint (epoch 0) and any finalized checkpoint BELOW the reorg
     * point are left intact — the fork-choice rule never lets a reorg cross a
     * finalized checkpoint, so only non-finalized checkpoints need re-deriving.
     */
    public void invalidateCheckpointsFrom(long epoch, BlockStoreInterface store) {
        checkpoints.keySet().removeIf(e -> e >= epoch);
        if (store != null) {
            try {
                for (String key : store.getPosStateByService("casper").keySet()) {
                    if (!key.startsWith("ckpt_")) {
                        continue;
                    }
                    try {
                        long e = Long.parseLong(key.substring(5));
                        if (e >= epoch) {
                            store.deletePosState("casper", key);
                        }
                    } catch (NumberFormatException ignore) {
                        // non-numeric key — leave it
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to invalidate persisted checkpoints from epoch {}", epoch, e);
            }
        }
    }

    /**
     * Stake that has attested the target checkpoint. Counted from the ON-CHAIN
     * embedded attestations at/above the activation height; below it from the
     * node-local gossip vote view. When {@code requiredSource} is null the vote
     * counts from ANY justified source; when non-null only votes with exactly
     * that source count (used for the consecutive-epoch supermajority link that
     * finalizes the parent).
     *
     * <p>{@code included} is a precomputed chain-read inclusion set (from
     * {@link #collectIncludedAttestations}); callers that evaluate several
     * vote sums against the same confirmed tip pass it once so the 320-slot
     * chain walk runs a single time per evaluation. {@code null} = compute it.
     */
    private BigInteger votedStakeFor(Checkpoint target, Sha256Hash requiredSource,
            BlockStoreInterface store, Map<String, AttestationData> included) throws Exception {
        if (onChainAttestationActive(store)) {
            Map<String, AttestationData> inc = included != null ? included : includedAttestations(store);
            return votedStakeFromChain(target, requiredSource, store, inc);
        }
        return votedStakeFromGossip(target, requiredSource, store);
    }

    // Memoized chain-read inclusion set, keyed by the confirmed tip hash. The
    // walk is a pure function of the confirmed chain (identical tip hash ⇒
    // identical ancestry ⇒ identical result), so one memoized walk per tip
    // serves every consumer in a confirm batch: finality vote sums, the
    // inactivity-leak voter set and the epoch-reward split each used to repeat
    // the ATTESTATION_LOOKBACK_SLOTS block walk independently.
    private static final Object INCLUDED_CACHE_LOCK = new Object();
    private static volatile Sha256Hash includedCacheTip;
    private static volatile Map<String, AttestationData> includedCache = java.util.Collections.emptyMap();

    /**
     * {@link #collectIncludedAttestations} memoized per confirmed tip. Racy
     * recomputation is harmless (pure function), so reads stay lock-free.
     */
    static Map<String, AttestationData> includedAttestations(BlockStoreInterface store) {
        Sha256Hash tip;
        try {
            TXReward t = store.getMaxConfirmedReward();
            tip = t != null ? t.getBlockHash() : null;
        } catch (Exception e) {
            return collectIncludedAttestations(store);
        }
        if (tip == null) {
            return new HashMap<>();
        }
        Map<String, AttestationData> cached = includedCache;
        if (tip.equals(includedCacheTip) && cached != null) {
            return cached;
        }
        Map<String, AttestationData> fresh = collectIncludedAttestations(store);
        synchronized (INCLUDED_CACHE_LOCK) {
            includedCacheTip = tip;
            includedCache = fresh;
        }
        return fresh;
    }

    /**
     * The latest ON-CHAIN embedded attestation per (validator, target-epoch),
     * walked from the confirmed tip back through the reward chain. This is the
     * deterministic inclusion set every node derives identically from the
     * confirmed chain — the source of truth for justification post-fork.
     */
    static Map<String, AttestationData> collectIncludedAttestations(BlockStoreInterface store) {
        Map<String, AttestationData> latest = new HashMap<>();
        try {
            TXReward tip = store.getMaxConfirmedReward();
            if (tip == null) {
                return latest;
            }
            Sha256Hash cursor = tip.getBlockHash();
            Set<Sha256Hash> visited = new HashSet<>();
            int count = 0;
            while (cursor != null && visited.add(cursor)
                    && count < ATTESTATION_LOOKBACK_SLOTS) {
                count++;
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    break;
                }
                for (AttestationData att : embeddedAttestationsOf(b)) {
                    String key = Utils.HEX.encode(att.getValidatorPubkey()) + ":" + att.getTargetEpoch();
                    AttestationData existing = latest.get(key);
                    if (existing == null || att.getSlot() > existing.getSlot()) {
                        latest.put(key, att);
                    }
                }
                net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                        .parseChecked(b.getTransactions().get(0).getData());
                cursor = ri != null ? ri.getPrevRewardHash() : null;
            }
        } catch (Exception e) {
            log.debug("Failed to collect included attestations", e);
        }
        return latest;
    }

    /** The attestations embedded in a beacon's SlotData, or empty. */
    static List<AttestationData> embeddedAttestationsOf(Block b) {
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

    private BigInteger votedStakeFromChain(Checkpoint target, Sha256Hash requiredSource,
            BlockStoreInterface store, Map<String, AttestationData> included) throws Exception {
        // Stake is counted only for validators active at the TARGET epoch: a
        // deposit still in its activation-delay window has zero weight.
        List<StakeRecord> validators = store.getActiveStakeDeposits(target.epoch);
        Map<String, BigInteger> stakeByPubkey = new HashMap<>();
        for (StakeRecord v : validators) {
            stakeByPubkey.put(Utils.HEX.encode(v.getPubkey()), StakeService.effectiveBalance(v));
        }
        BigInteger voted = BigInteger.ZERO;
        for (AttestationData att : included.values()) {
            if (att.getTargetEpoch() != target.epoch) {
                continue;
            }
            if (att.getTargetCheckpoint() == null || !att.getTargetCheckpoint().equals(target.blockHash)) {
                continue;
            }
            if (requiredSource != null) {
                if (!requiredSource.equals(att.getSourceCheckpoint())) {
                    continue;
                }
            } else if (att.getSourceCheckpoint() == null || !isJustifiedCheckpointHash(att.getSourceCheckpoint())) {
                continue;
            }
            voted = voted.add(stakeByPubkey.getOrDefault(Utils.HEX.encode(att.getValidatorPubkey()), BigInteger.ZERO));
        }
        return voted;
    }

    private BigInteger votedStakeFromGossip(Checkpoint target, Sha256Hash requiredSource,
            BlockStoreInterface store) throws Exception {
        // Stake is counted only for validators active at the TARGET epoch: a
        // deposit still in its activation-delay window has zero weight.
        List<StakeRecord> validators = store.getActiveStakeDeposits(target.epoch);
        Map<String, BigInteger> stakeByPubkey = new HashMap<>();
        for (StakeRecord v : validators) {
            stakeByPubkey.put(Utils.HEX.encode(v.getPubkey()), StakeService.effectiveBalance(v));
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
