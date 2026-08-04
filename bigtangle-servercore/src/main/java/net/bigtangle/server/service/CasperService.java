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
                    }
                }
                // Bootstrap finality: the genesis checkpoint is the root of
                // trust (justified + finalized) and must survive restarts.
                if (checkpoints.isEmpty()) {
                    Checkpoint genesis = new Checkpoint(
                            UtilGeneseBlock.createGenesis(networkParameters).getHash(), 0);
                    genesis.justified = true;
                    genesis.finalized = true;
                    checkpoints.put(0L, genesis);
                    persistCheckpoint(genesis, store);
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior Casper state to restore", e);
        }
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
     * epoch boundary's chainlength (the last beacon of the previous epoch,
     * assuming one beacon per slot). Every node that has confirmed the same
     * chain derives the same checkpoint. When the boundary is not yet confirmed
     * a transient (non-cached) checkpoint is returned so the cache is never
     * poisoned with a node-local value.
     */
    public Checkpoint ensureCheckpoint(long epoch, BlockStoreInterface store) {
        Checkpoint existing = checkpoints.get(epoch);
        if (existing != null) {
            return existing;
        }
        TXReward boundary = null;
        try {
            boundary = store.getRewardConfirmedAtHeight(epoch * SlotService.SLOTS_PER_EPOCH);
        } catch (Exception e) {
            boundary = null;
        }
        if (boundary == null) {
            // Boundary not confirmed yet: return a transient checkpoint that is
            // NOT cached, so it cannot poison future lookups.
            Checkpoint transientCp = new Checkpoint(confirmedHeadOrGenesis(store), epoch);
            if (epoch == 0) {
                transientCp.justified = true;
                transientCp.finalized = true;
            }
            return transientCp;
        }
        Checkpoint cp = new Checkpoint(boundary.getBlockHash(), epoch);
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

    /** True if {@code chainAncestor} is an ancestor of (or equal to) {@code blockHash} in the reward chain. */
    public boolean descendsFrom(Sha256Hash blockHash, Sha256Hash chainAncestor, BlockStoreInterface store) {
        if (blockHash == null || chainAncestor == null) {
            return false;
        }
        Sha256Hash cursor = blockHash;
        java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
        int guard = 0;
        while (cursor != null && visited.add(cursor) && guard++ < 10_000) {
            if (cursor.equals(chainAncestor)) {
                return true;
            }
            try {
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    break;
                }
                net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                        .parseChecked(b.getTransactions().get(0).getData());
                cursor = ri != null ? ri.getPrevRewardHash() : null;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
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

        ghostService.processAttestation(att, store);
        store.saveAttestationVote(att.getBeaconBlockHash(), att.getValidatorPubkey(),
                stakeService.getEffectiveStake(att.getValidatorPubkey(), store));

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

        gossipService.broadcastAttestation(att);
    }

    /** Verifies the attestation signature against the declared validator pubkey. */
    public boolean verifyAttestation(AttestationData att) {
        return att != null && att.verifySignature();
    }

    public void finalizeCheckpoint(long epoch, BlockStoreInterface store) throws Exception {
        Checkpoint target = checkpoints.get(epoch);
        if (target == null) {
            return;
        }

        // A checkpoint finalizes only when its IMMEDIATE parent (epoch-1) is
        // finalized AND it is justified — a one-epoch link. Using the parent
        // (not the highest justified) means every epoch in between can finalize
        // in sequence and never stalls.
        if (target.justified) {
            ensureCheckpoint(epoch - 1, store);
            Checkpoint parent = checkpoints.get(epoch - 1);
            if (parent != null && parent.finalized && !target.finalized) {
                target.finalized = true;
                log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
                persistCheckpoint(target, store);
            }
            return;
        }

        Checkpoint justifySource = getJustifiedCheckpoint();
        if (justifySource == null || justifySource.epoch >= target.epoch) {
            return;
        }

        BigInteger totalStake = stakeService.getTotalActiveStake(store);
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
            return;
        }

        // A vote for the target counts if it came from ANY justified source,
        // so an advancing justified checkpoint cannot orphan pending votes.
        BigInteger votedStake = getVotedStake(target.blockHash, store);
        BigInteger twoThirds = totalStake.multiply(BigInteger.valueOf(2))
                .divide(BigInteger.valueOf(3));

        if (votedStake.compareTo(twoThirds) >= 0) {
            target.justified = true;
            log.info("Checkpoint justified: epoch={}, block={}, votedStake={}/{}",
                    epoch, target.blockHash, votedStake, totalStake);
            persistCheckpoint(target, store);

            Checkpoint parent = checkpoints.get(epoch - 1);
            if (parent != null && parent.finalized) {
                target.finalized = true;
                log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
                persistCheckpoint(target, store);
            }
        } else {
            log.debug("Checkpoint not justified: epoch={}, votedStake={}/{} (need {})",
                    epoch, votedStake, totalStake, twoThirds);
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
     * Stake that has attested the target checkpoint from ANY justified source
     * checkpoint. Keyed by pubkey hex — never by byte[], which uses identity
     * equality — and filtered by the exact target, so unrelated votes are not
     * counted while pending votes are not orphaned by an advancing source.
     */
    private BigInteger getVotedStake(Sha256Hash target, BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        Map<String, BigInteger> stakeByPubkey = new HashMap<>();
        for (StakeRecord v : validators) {
            stakeByPubkey.put(Utils.HEX.encode(v.getPubkey()), v.getAmount());
        }

        BigInteger voted = BigInteger.ZERO;
        for (Map.Entry<String, Sha256Hash> entry : latestVoteTargets.entrySet()) {
            if (!target.equals(entry.getValue())) {
                continue;
            }
            Sha256Hash src = latestVoteSources.get(entry.getKey());
            if (src == null || !isJustifiedCheckpointHash(src)) {
                continue;
            }
            BigInteger stake = stakeByPubkey.getOrDefault(entry.getKey(), BigInteger.ZERO);
            voted = voted.add(stake);
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
