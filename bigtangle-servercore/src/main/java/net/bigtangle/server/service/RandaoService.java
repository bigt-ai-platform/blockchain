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

import net.bigtangle.core.PQKey;
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

    /**
     * The secret is derived deterministically from the validator's private key
     * and the slot, so it is NEVER persisted — only the commitment (SHA-256 of
     * the secret) is stored. computeReveal recomputes the same secret later.
     */
    private byte[] deriveSecret(PQKey validatorKey, long slot) {
        byte[] keyMaterial = validatorKey.getSecretBytes();
        if (keyMaterial == null) {
            // No private key material: cannot derive a secret. Returning null
            // (rather than a public value) means this key cannot commit/reveal.
            return null;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(keyMaterial.length + 8);
        buf.put(keyMaterial);
        buf.putLong(slot);
        return sha256(buf.array());
    }

    public byte[] commit(PQKey validatorKey, long slot) {
        byte[] secret = deriveSecret(validatorKey, slot);
        if (secret == null) {
            return null;
        }
        byte[] hash = sha256(secret);
        String key = Utils.HEX.encode(validatorKey.getPubKey()) + ":" + slot;
        commitments.put(key, hash);
        persistCommitment(key, hash);
        return hash;
    }

    public byte[] computeReveal(PQKey validatorKey, long slot) {
        return deriveSecret(validatorKey, slot);
    }

    public void reveal(byte[] pubkey, long slot, byte[] reveal) {
        String key = Utils.HEX.encode(pubkey) + ":" + slot;
        byte[] expected = commitments.get(key);
        if (expected == null) return;
        if (reveal == null || reveal.length == 0) {
            log.warn("Empty RANDAO reveal for pubkey={} slot={}",
                    Utils.HEX.encode(pubkey), slot);
            return;
        }

        byte[] computed = sha256(reveal);
        if (!Arrays.equals(expected, computed)) {
            log.warn("Invalid RANDAO reveal for pubkey={} slot={}",
                    Utils.HEX.encode(pubkey), slot);
            return;
        }
        commitments.remove(key);

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
            store.deletePosState("randao", "cmt_" + key);
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

    /**
     * Mixes an on-chain RANDAO reveal (from a confirmed beacon's SlotData) into
     * the epoch mix. Deterministic for every node that accepts the same beacon.
     */
    public void applyReveal(long slot, byte[] reveal) {
        if (reveal == null || reveal.length != 32) {
            log.warn("Rejecting malformed RANDAO reveal (expected 32 bytes) for slot {}", slot);
            return;
        }
        long epoch = slot / 32;
        byte[] currentMix = randaoMixes.getOrDefault(epoch, new byte[32]).clone();
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
