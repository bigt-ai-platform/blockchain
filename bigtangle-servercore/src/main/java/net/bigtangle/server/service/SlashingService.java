package net.bigtangle.server.service;

import java.util.ArrayList;
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
import net.bigtangle.core.Utils;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class SlashingService {

    private static final Logger log = LoggerFactory.getLogger(SlashingService.class);

    /**
     * Votes are pruned by EPOCH AGE, not by a raw count, so a validator cannot
     * push a slashable vote out of the window by voting a lot. A vote stays for
     * this many epochs past the newest vote; an older vote can no longer form a
     * slashable pair with a new vote. A hard cap bounds memory against a
     * spammy validator (only ~32 votes/epoch are possible in practice).
     */
    private static final int SLASHING_LOOKBACK_EPOCHS = 8;
    private static final int MAX_VOTE_HISTORY = 4096;

    private final ConcurrentHashMap<String, List<AttestationData>> voteHistory = new ConcurrentHashMap<>();

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
                    if (!e.getKey().startsWith("att_")) {
                        continue;
                    }
                    String pubkeyHex = e.getKey().substring(4);
                    List<AttestationData> list = parseHistory(
                            new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                    if (!list.isEmpty()) {
                        voteHistory.put(pubkeyHex, list);
                    }
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.trace("No prior slashing state to restore", e);
        }
    }

    /** Parses a persisted per-validator vote list ("slot|...|sig" entries joined by ';'). */
    private List<AttestationData> parseHistory(String raw) {
        List<AttestationData> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        for (String entry : raw.split(";")) {
            AttestationData att = parsePersisted(entry);
            if (att != null) {
                list.add(att);
            }
        }
        return list;
    }

    private AttestationData parsePersisted(String raw) {
        try {
            String[] parts = raw.split("\\|");
            if (parts.length < 3) {
                return null;
            }
            AttestationData att = new AttestationData();
            att.setSlot(Long.parseLong(parts[0]));
            att.setBeaconBlockHash(Sha256Hash.wrap(Utils.HEX.decode(parts[1])));
            att.setValidatorPubkey(Utils.HEX.decode(parts[2]));
            if (parts.length >= 4) att.setEpoch(Long.parseLong(parts[3]));
            if (parts.length >= 5) att.setSourceEpoch(Long.parseLong(parts[4]));
            if (parts.length >= 6) att.setTargetEpoch(Long.parseLong(parts[5]));
            if (parts.length >= 7 && !parts[6].isEmpty()) {
                att.setSourceCheckpoint(Sha256Hash.wrap(Utils.HEX.decode(parts[6])));
            }
            if (parts.length >= 8 && !parts[7].isEmpty()) {
                att.setTargetCheckpoint(Sha256Hash.wrap(Utils.HEX.decode(parts[7])));
            }
            if (parts.length >= 9 && !parts[8].isEmpty()) {
                att.setSignature(Utils.HEX.decode(parts[8]));
            }
            return att;
        } catch (Exception e) {
            return null;
        }
    }

    private String serialize(AttestationData att) {
        StringBuilder sb = new StringBuilder();
        sb.append(att.getSlot()).append('|').append(att.getBeaconBlockHash().toString()).append('|')
                .append(Utils.HEX.encode(att.getValidatorPubkey())).append('|').append(att.getEpoch()).append('|')
                .append(att.getSourceEpoch()).append('|').append(att.getTargetEpoch()).append('|')
                .append(att.getSourceCheckpoint() != null ? att.getSourceCheckpoint().toString() : "").append('|')
                .append(att.getTargetCheckpoint() != null ? att.getTargetCheckpoint().toString() : "").append('|')
                .append(att.getSignature() != null ? Utils.HEX.encode(att.getSignature()) : "");
        return sb.toString();
    }

    /** Persists the validator's FULL vote history under a single key per pubkey. */
    private void persistAttestation(List<AttestationData> history) {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                StringBuilder sb = new StringBuilder();
                for (AttestationData att : history) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(serialize(att));
                }
                String pubkeyHex = history.isEmpty() ? "" : Utils.HEX.encode(history.get(0).getValidatorPubkey());
                store.savePosState("slash", "att_" + pubkeyHex,
                        sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.debug("Failed to persist slashing state", e);
        }
    }

    /**
     * Records a vote in the per-validator history, pruning by EPOCH AGE (a vote
     * older than {@link #SLASHING_LOOKBACK_EPOCHS} past the newest can no longer
     * form a slashable pair) and persisting the full history.
     */
    private void recordVote(AttestationData att) {
        String key = Utils.HEX.encode(att.getValidatorPubkey());
        List<AttestationData> history = voteHistory.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (history) {
            // Idempotent: processVote runs checkDoubleVote then checkSurroundVote;
            // avoid recording the same vote twice.
            if (!history.isEmpty() && sameVote(history.get(history.size() - 1), att)) {
                return;
            }
            long newestEpoch = att.getEpoch();
            for (AttestationData v : history) {
                newestEpoch = Math.max(newestEpoch, v.getEpoch());
            }
            long cut = newestEpoch - SLASHING_LOOKBACK_EPOCHS;
            history.removeIf(v -> v.getTargetEpoch() >= 0 && v.getTargetEpoch() < cut);
            history.add(att);
            while (history.size() > MAX_VOTE_HISTORY) {
                history.remove(0);
            }
        }
        persistAttestation(history);
    }

    private boolean sameVote(AttestationData a, AttestationData b) {
        return a.getSlot() == b.getSlot()
                && a.getBeaconBlockHash().equals(b.getBeaconBlockHash())
                && a.getSourceEpoch() == b.getSourceEpoch()
                && a.getTargetEpoch() == b.getTargetEpoch();
    }

    /**
     * Detects a double vote: the same validator attesting two different heads
     * for the same slot. Records the vote and returns the conflicting prior
     * attestation (the evidence for a slashing block), or null if no double.
     */
    public AttestationData checkDoubleVote(AttestationData att) {
        if (att.getValidatorPubkey() == null) {
            return null;
        }
        String key = Utils.HEX.encode(att.getValidatorPubkey());
        List<AttestationData> history = voteHistory.get(key);
        if (history != null) {
            synchronized (history) {
                for (AttestationData existing : history) {
                    if (existing.getSlot() == att.getSlot()
                            && !existing.getBeaconBlockHash().equals(att.getBeaconBlockHash())) {
                        log.warn("SLASHING: double vote by pubkey={} at slot={}",
                                key, att.getSlot());
                        recordVote(att);
                        return existing;
                    }
                }
            }
        }
        recordVote(att);
        return null;
    }

    /**
     * Detects a surround vote via epoch containment against ALL of the
     * validator's recent votes (a single-latest comparison misses surround
     * violations). Records the vote and returns the conflicting prior
     * attestation (the evidence), or null if no surround.
     */
    public AttestationData checkSurroundVote(AttestationData att) {
        if (att.getValidatorPubkey() == null) {
            return null;
        }
        String key = Utils.HEX.encode(att.getValidatorPubkey());
        List<AttestationData> history = voteHistory.get(key);
        if (history != null) {
            synchronized (history) {
                for (AttestationData prev : history) {
                    if (prev.getSourceEpoch() < 0 || prev.getTargetEpoch() < 0
                            || att.getSourceEpoch() < 0 || att.getTargetEpoch() < 0) {
                        continue;
                    }
                    boolean surrounds = prev.getSourceEpoch() < att.getSourceEpoch()
                            && att.getTargetEpoch() < prev.getTargetEpoch();
                    boolean surrounded = att.getSourceEpoch() < prev.getSourceEpoch()
                            && prev.getTargetEpoch() < att.getTargetEpoch();
                    if (surrounds || surrounded) {
                        log.warn("SLASHING: surround vote by pubkey={}", key);
                        recordVote(att);
                        return prev;
                    }
                }
            }
        }
        recordVote(att);
        return null;
    }

    public void processSlashing(byte[] pubkey, BlockStoreInterface store) throws Exception {
        stakeService.slashValidator(pubkey, store);
    }
}
