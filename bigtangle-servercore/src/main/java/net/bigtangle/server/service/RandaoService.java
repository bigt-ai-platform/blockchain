package net.bigtangle.server.service;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Utils;

@Service
public class RandaoService {

    private static final Logger log = LoggerFactory.getLogger(RandaoService.class);

    private final ConcurrentHashMap<Long, byte[]> randaoMixes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> commitments = new ConcurrentHashMap<>();

    public byte[] commit(byte[] pubkey, long slot) {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        byte[] hash = sha256(secret);
        String key = Utils.HEX.encode(pubkey) + ":" + slot;
        commitments.put(key, hash);
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
    }

    public byte[] getRandaoMix(long slot) {
        long epoch = slot / 32;
        return randaoMixes.getOrDefault(epoch, sha256(String.valueOf(epoch).getBytes()));
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
