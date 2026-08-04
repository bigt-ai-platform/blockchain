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
        byte[] currentMix = randaoMixes.getOrDefault(epoch, sha256(String.valueOf(epoch).getBytes()));
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
        applyReveal(slot, reveal, null, null);
    }

    /**
     * Mixes an on-chain RANDAO reveal (from a confirmed beacon's SlotData) into
     * the epoch mix and persists it. When {@code store} is non-null the write
     * rides the caller's batch and the in-memory cache is NOT updated — the
     * caller must reload the affected epochs after the batch COMMITS (see
     * reloadMix), so an aborted batch rolls the mix back in memory too. A
     * persistence failure THROWS (fail-closed).
     *
     * When a {@code commitment} is supplied, the reveal is bound to it:
     * SHA-256(reveal) must equal the commitment (the deterministic secret the
     * proposer committed to in its beacon), otherwise the reveal is rejected
     * and the mix is NOT updated. The commitment is covered by the proposer's
     * SlotData signature, so a proposer cannot publish a reveal that disagrees
     * with the commitment it signed.
     */
    public void applyReveal(long slot, byte[] reveal, byte[] commitment, BlockStoreInterface store) {
        if (reveal == null || reveal.length != 32) {
            log.warn("Rejecting malformed RANDAO reveal (expected 32 bytes) for slot {}", slot);
            return;
        }
        if (commitment != null) {
            if (commitment.length != 32 || !Arrays.equals(sha256(reveal), commitment)) {
                log.warn("Rejecting RANDAO reveal for slot {}: SHA-256(reveal) does not match the signed commitment",
                        slot);
                return;
            }
        }
        long epoch = slot / 32;
        byte[] epochSeed = sha256(String.valueOf(epoch).getBytes());
        if (store != null) {
            // Fold against the PENDING value in the batch store (which reflects
            // prior reveals in the same batch), so consecutive reveals of the
            // same epoch accumulate instead of overwriting from a stale base.
            // The base defaults to the same SHA-256(epoch) seed getRandaoMix
            // uses, so the FIRST reveal of an epoch mixes into the seed rather
            // than replacing it with raw proposer bytes.
            try {
                byte[] current = store.getPosState("randao", "mix_" + epoch);
                byte[] base = current != null ? current : epochSeed;
                byte[] next = xor(base, reveal);
                store.savePosState("randao", "mix_" + epoch, next);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist RANDAO mix for epoch " + epoch, e);
            }
            return;
        }
        randaoMixes.put(epoch, xor(randaoMixes.getOrDefault(epoch, epochSeed), reveal));
    }

    private byte[] xor(byte[] a, byte[] b) {
        byte[] out = a.clone();
        for (int i = 0; i < 32; i++) {
            out[i] ^= b[i];
        }
        return out;
    }

    /** Reloads the in-memory mix for an epoch from persisted state (post-commit). */
    public void reloadMix(long epoch) {
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            byte[] persisted = store.getPosState("randao", "mix_" + epoch);
            if (persisted != null) {
                randaoMixes.put(epoch, persisted);
            }
        } catch (Exception e) {
            log.debug("Failed to reload RANDAO mix for epoch {}", epoch, e);
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
