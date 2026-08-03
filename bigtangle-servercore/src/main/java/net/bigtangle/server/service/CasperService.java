package net.bigtangle.server.service;

import java.math.BigInteger;
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
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Utils;
import net.bigtangle.store.BlockStoreInterface;

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
    }

    private final ConcurrentHashMap<Long, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> latestVotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Sha256Hash> latestVoteBeacons = new ConcurrentHashMap<>();

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

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<String, byte[]> saved = store.getPosStateByService("casper");
                for (Map.Entry<String, byte[]> e : saved.entrySet()) {
                    if (e.getKey().startsWith("vote_")) {
                        String pubkey = e.getKey().substring(5);
                        long slot = new java.math.BigInteger(e.getValue()).longValue();
                        latestVotes.put(pubkey, slot);
                    } else if (e.getKey().startsWith("ckpt_")) {
                        long epoch = Long.parseLong(e.getKey().substring(5));
                        String[] parts = new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8).split(",");
                        Checkpoint cp = new Checkpoint(Sha256Hash.wrap(net.bigtangle.core.Utils.HEX.decode(parts[0])), epoch);
                        cp.justified = Boolean.parseBoolean(parts[1]);
                        cp.finalized = Boolean.parseBoolean(parts[2]);
                        checkpoints.put(epoch, cp);
                    }
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior Casper state to restore", e);
        }
    }

    public void processSlot(long slot, Sha256Hash beaconHash,
            List<AttestationData> attestations, BlockStoreInterface store) throws Exception {
        for (AttestationData att : attestations) {
            processVote(att, store);
        }
    }

    public void processVote(AttestationData att, BlockStoreInterface store) throws Exception {
        String vkey = Utils.HEX.encode(att.getValidatorPubkey());
        Long lastSlot = latestVotes.get(vkey);
        Sha256Hash lastBeacon = latestVoteBeacons.get(vkey);

        boolean isDoubleVote = slashingService.checkDoubleVote(att);
        slashingService.checkSurroundVote(att);

        // A gossip-relayed duplicate of the IDENTICAL attestation (same slot and
        // same beacon head) is not a double-vote — only a conflicting vote for
        // the same slot is slashable. Without this, a node that both receives
        // the direct submitAttestation and a gossip relay of the same vote would
        // slash its own validator.
        boolean duplicateRelay = lastSlot != null && lastSlot == att.getSlot()
                && att.getBeaconBlockHash().equals(lastBeacon);

        if (isDoubleVote || (!duplicateRelay && lastSlot != null && lastSlot >= att.getSlot())) {
            log.warn("Slashing: double vote by pubkey={} slot={}", vkey, att.getSlot());
            slashingService.processSlashing(att.getValidatorPubkey(), store);
            return;
        }
        latestVotes.put(vkey, att.getSlot());
        latestVoteBeacons.put(vkey, att.getBeaconBlockHash());
        ghostService.processAttestation(att, store);
        store.saveAttestationVote(att.getBeaconBlockHash(), att.getValidatorPubkey(),
                stakeService.getEffectiveStake(att.getValidatorPubkey(), store));
        store.savePosState("casper", "vote_" + vkey,
                java.math.BigInteger.valueOf(att.getSlot()).toByteArray());

        gossipService.broadcastAttestation(att);
    }

    public void finalizeCheckpoint(long epoch, BlockStoreInterface store) throws Exception {
        Checkpoint target = checkpoints.get(epoch);
        if (target == null) return;

        Checkpoint source = checkpoints.get(epoch - 1);
        if (source == null || !source.justified) return;

        BigInteger totalStake = stakeService.getTotalActiveStake(store);
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) return;

        BigInteger votedStake = getVotedStake(source.blockHash, target.blockHash, store);
        BigInteger twoThirds = totalStake.multiply(BigInteger.valueOf(2))
                .divide(BigInteger.valueOf(3));

        if (votedStake.compareTo(twoThirds) >= 0) {
            target.justified = true;
            log.info("Checkpoint justified: epoch={}, block={}", epoch, target.blockHash);
            persistCheckpoint(target, store);

            if (source.finalized) {
                target.finalized = true;
                log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
                persistCheckpoint(target, store);
            }
        }
    }

    private void persistCheckpoint(Checkpoint cp, BlockStoreInterface store) {
        try {
            String val = cp.blockHash.toString() + "," + cp.justified + "," + cp.finalized;
            store.savePosState("casper", "ckpt_" + cp.epoch, val.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Failed to persist checkpoint", e);
        }
    }

    private BigInteger getVotedStake(Sha256Hash source, Sha256Hash target,
            BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        Map<byte[], BigInteger> stakeByPubkey = new HashMap<>();
        for (StakeRecord v : validators) {
            stakeByPubkey.put(v.getPubkey(), v.getAmount());
        }

        BigInteger voted = BigInteger.ZERO;
        for (Map.Entry<String, Long> entry : latestVotes.entrySet()) {
            byte[] pubkey = Utils.HEX.decode(entry.getKey());
            BigInteger stake = stakeByPubkey.getOrDefault(pubkey, BigInteger.ZERO);
            voted = voted.add(stake);
        }
        return voted;
    }
}
