package net.bigtangle.server.service;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class SlashingService {

    private static final Logger log = LoggerFactory.getLogger(SlashingService.class);

    private final HashMap<String, AttestationData> latestAttestation = new HashMap<>();

    @Autowired
    private StakeService stakeService;

    public void checkDoubleVote(AttestationData att) {
        String key = Utils.HEX.encode(att.getValidatorPubkey()) + ":" + att.getSlot();
        AttestationData existing = latestAttestation.get(key);
        if (existing != null && !existing.getBeaconBlockHash().equals(att.getBeaconBlockHash())) {
            log.warn("SLASHING: double vote by pubkey={} at slot={}",
                    Utils.HEX.encode(att.getValidatorPubkey()), att.getSlot());
        }
        latestAttestation.put(key, att);
    }

    public void checkSurroundVote(AttestationData att) {
        String key = Utils.HEX.encode(att.getValidatorPubkey());
        AttestationData prev = latestAttestation.get(key + ":latest");
        if (prev != null) {
            boolean surrounds = prev.getSourceCheckpoint().equals(att.getTargetCheckpoint())
                    && att.getSourceCheckpoint().equals(prev.getTargetCheckpoint());
            if (surrounds) {
                log.warn("SLASHING: surround vote by pubkey={}",
                        Utils.HEX.encode(att.getValidatorPubkey()));
            }
        }
        latestAttestation.put(key + ":latest", att);
    }

    public void processSlashing(byte[] pubkey, BlockStoreInterface store) throws Exception {
        stakeService.slashValidator(pubkey, store);
    }
}
