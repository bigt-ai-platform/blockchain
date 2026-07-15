package net.bigtangle.server.service;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Autowired
    private GhostService ghostService;

    @Autowired
    private StakeService stakeService;

    public void processSlot(long slot, Sha256Hash beaconHash,
            List<AttestationData> attestations, BlockStoreInterface store) throws Exception {
        for (AttestationData att : attestations) {
            processVote(att, store);
        }
    }

    public void processVote(AttestationData att, BlockStoreInterface store) throws Exception {
        String vkey = Utils.HEX.encode(att.getValidatorPubkey());
        Long lastSlot = latestVotes.get(vkey);
        if (lastSlot != null && lastSlot >= att.getSlot()) {
            log.warn("Double vote detected: pubkey={} slot={}", vkey, att.getSlot());
            return;
        }
        latestVotes.put(vkey, att.getSlot());
        ghostService.processAttestation(att, store);
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

            if (source.finalized) {
                target.finalized = true;
                log.info("Checkpoint FINALIZED: epoch={}, block={}", epoch, target.blockHash);
            }
        }
    }

    private BigInteger getVotedStake(Sha256Hash source, Sha256Hash target,
            BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        BigInteger voted = BigInteger.ZERO;

        for (Map.Entry<String, Long> entry : latestVotes.entrySet()) {
            byte[] pubkey = net.bigtangle.core.Utils.HEX.decode(entry.getKey());
            boolean isActive = validators.stream()
                    .anyMatch(v -> java.util.Arrays.equals(v.getPubkey(), pubkey));
            if (isActive) {
                voted = voted.add(BigInteger.valueOf(32_000_000L));
            }
        }
        return voted;
    }
}
