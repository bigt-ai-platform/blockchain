package net.bigtangle.server.service;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Utils;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class RandaoService {

    private static final Logger log = LoggerFactory.getLogger(RandaoService.class);

    private final ConcurrentHashMap<Long, byte[]> randaoMixes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> commitments = new ConcurrentHashMap<>();

    @Autowired
    private StoreService storeService;

    @PostConstruct
    public void restoreState() {
        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                Map<String, byte[]> saved = store.getPosStateByService("randao");
                for (Map.Entry<String, byte[]> e : saved.entrySet()) {
                    if (e.getKey().startsWith("mix_")) {
                        long epoch = Long.parseLong(e.getKey().substring(4));
                        randaoMixes.put(epoch, e.getValue());
                    }
                }
            } finally {
                store.close();
            }
        } catch (Exception ex) {
            log.trace("No prior RANDAO state to restore", ex);
        }
    }

    public byte[] commit(byte[] pubkey, long slot) {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        byte[] hash = sha256(secret);
        String key = Utils.HEX.encode(pubkey) + ":" + slot;
        commitments.put(key, hash);
        persistCommitment(key, hash);
        return hash;
    }

    public byte[] computeReveal(byte[] pubkey, long slot) throws Exception {
        return sha256(pubkey);
    }

    public void reveal(byte[] pubkey, long slot, byte[] reveal) {
        String key = Utils.HEX.encode(pubkey) + ":" + slot;
        byte[] expected = commitments.remove(key);
        if (expected == null) return;

        byte[] computed = sha256(reveal);
        if (!Arrays.equals(expected, computed)) {
            log.warn("Invalid RANDAO reveal for pubkey={} slot={}",
                    Utils.HEX.encode(pubkey), slot);
            return;
        }

        long epoch = slot / 32;
        byte[] currentMix = randaoMixes.getOrDefault(epoch, new byte[32]);
        for (int i = 0; i < 32; i++) {
            currentMix[i] ^= reveal[i];
        }
        randaoMixes.put(epoch, currentMix);

        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            store.savePosState("randao", "mix_" + epoch, currentMix);
        } catch (Exception e) {
            log.debug("Failed to persist RANDAO mix", e);
        } finally {
            try { if (store != null) store.close(); } catch (Exception e) {}
        }
    }

    public byte[] getRandaoMix(long slot) {
        long epoch = slot / 32;
        return randaoMixes.getOrDefault(epoch, sha256(String.valueOf(epoch).getBytes()));
    }

    private void persistCommitment(String key, byte[] hash) {
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            store.savePosState("randao", "cmt_" + key, hash);
        } catch (Exception e) {
            log.debug("Failed to persist commitment", e);
        } finally {
            try { if (store != null) store.close(); } catch (Exception e) {}
        }
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
