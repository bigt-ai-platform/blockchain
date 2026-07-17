package net.bigtangle.server.service;

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
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

    @Autowired
    private StoreService storeService;

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<String, byte[]> saved = store.getPosStateByService("slash");
                for (Map.Entry<String, byte[]> e : saved.entrySet()) {
                    if (e.getKey().startsWith("att_")) {
                        String[] parts = new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
                        if (parts.length >= 3) {
                            AttestationData att = new AttestationData();
                            att.setSlot(Long.parseLong(parts[0]));
                            att.setBeaconBlockHash(Sha256Hash.wrap(Utils.HEX.decode(parts[1])));
                            att.setValidatorPubkey(Utils.HEX.decode(parts[2]));
                            latestAttestation.put(e.getKey().substring(4), att);
                        }
                    }
                }
            } finally {
                store.close();
            }
        } catch (Exception ex) {
            log.debug("No prior slashing state to restore", ex);
        }
    }

    public boolean checkDoubleVote(AttestationData att) {
        String key = Utils.HEX.encode(att.getValidatorPubkey()) + ":" + att.getSlot();
        AttestationData existing = latestAttestation.get(key);
        if (existing != null && !existing.getBeaconBlockHash().equals(att.getBeaconBlockHash())) {
            log.warn("SLASHING: double vote by pubkey={} at slot={}",
                    Utils.HEX.encode(att.getValidatorPubkey()), att.getSlot());
            return true;
        }
        latestAttestation.put(key, att);
        persistAttestation(key, att);
        return false;
    }

    public void checkSurroundVote(AttestationData att) {
        String key = Utils.HEX.encode(att.getValidatorPubkey());
        AttestationData prev = latestAttestation.get(key + ":latest");
        if (prev != null) {
            boolean surrounds = prev.getSourceCheckpoint() != null && att.getTargetCheckpoint() != null
                    && prev.getSourceCheckpoint().equals(att.getTargetCheckpoint())
                    && att.getSourceCheckpoint() != null
                    && att.getSourceCheckpoint().equals(prev.getTargetCheckpoint());
            if (surrounds) {
                log.warn("SLASHING: surround vote by pubkey={}",
                        Utils.HEX.encode(att.getValidatorPubkey()));
            }
        }
        latestAttestation.put(key + ":latest", att);
        persistAttestation(key + ":latest", att);
    }

    public void processSlashing(byte[] pubkey, BlockStoreInterface store) throws Exception {
        stakeService.slashValidator(pubkey, store);
    }

    private void persistAttestation(String key, AttestationData att) {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                String val = att.getSlot() + "|" + att.getBeaconBlockHash().toString() + "|"
                        + Utils.HEX.encode(att.getValidatorPubkey());
                store.savePosState("slash", "att_" + key, val.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.debug("Failed to persist slashing state", e);
        }
    }
}
