package net.bigtangle.server.service;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.bouncycastle.crypto.bls.BLS12_381BasicScheme;
import org.bouncycastle.crypto.bls.BLS12_381G1;
import org.bouncycastle.crypto.bls.BLS12_381G2Point;
import org.bouncycastle.crypto.bls.BLS12_381Serialization;
import org.bouncycastle.math.ec.ECPoint;

import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class RandaoService {

    private static final Logger log = LoggerFactory.getLogger(RandaoService.class);

    /** Domain separator for BLS key derivation (RFC 9380 keyGen keyInfo). */
    private static final byte[] BLS_KEY_INFO = "bigtangle-randao-slot".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final ConcurrentHashMap<Long, byte[]> randaoMixes = new ConcurrentHashMap<>();

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
     * Canonical message a proposer signs for its RANDAO reveal: a domain-prefixed
     * hash over the slot.
     */
    public static Sha256Hash revealMessage(long slot) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16);
        buf.put("RANDAO".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buf.putLong(slot);
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, buf.position());
        return Sha256Hash.of(out);
    }

    /**
     * The validator's BLS secret scalar, derived deterministically from its
     * ML-DSA private key. The validator's node (which holds the ML-DSA private
     * key) can always recompute it; the corresponding public key is registered
     * on-chain in the STAKE deposit so every validator can verify reveals.
     */
    private static BigInteger blsSecretScalar(PQKey validatorKey) {
        byte[] priv = validatorKey.getMLDSAPrivateKey();
        if (priv == null) {
            return null; // public-only key: cannot sign a reveal
        }
        byte[] ikm = sha256(priv);
        return BLS12_381BasicScheme.keyGen(ikm, BLS_KEY_INFO);
    }

    /** BLS signature over a message using the validator's derived BLS key. */
    public static byte[] blsSign(PQKey validatorKey, byte[] message) {
        BigInteger sk = blsSecretScalar(validatorKey);
        if (sk == null) {
            return null;
        }
        return BLS12_381Serialization.compressG2(BLS12_381BasicScheme.sign(sk, message));
    }

    /**
     * The BLS public key for a validator, derived deterministically from its
     * ML-DSA private key. Registered on-chain in the STAKE deposit.
     */
    public static byte[] blsPubkey(PQKey validatorKey) {
        BigInteger sk = blsSecretScalar(validatorKey);
        if (sk == null) {
            return null;
        }
        return BLS12_381Serialization.compressG1(BLS12_381BasicScheme.skToPk(sk));
    }

    /**
     * Verifies a RANDAO reveal against a validator's registered BLS public key.
     * BLS12-381 is a UNIQUE signature scheme: for a given (key, message) there is
     * exactly one valid signature, so the reveal bytes are FORCED by (key, slot) —
     * a proposer cannot grind a favourable mix by re-rolling signatures.
     */
    public static boolean verifyReveal(byte[] blsPubkey, long slot, byte[] revealSig) {
        try {
            ECPoint pk = BLS12_381Serialization.decompressG1(blsPubkey, BLS12_381G1.createCurve());
            BLS12_381G2Point sig = BLS12_381Serialization.decompressG2(revealSig);
            return BLS12_381BasicScheme.verify(pk, revealMessage(slot).getBytes(), sig);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates a registered BLS public key: exactly a 48-byte compressed G1
     * point on the BLS12-381 subgroup. Called when a STAKE deposit is accepted,
     * so a malformed key can never enter the active set and create an
     * unfillable proposer slot.
     */
    public static boolean isValidBlsPubkey(byte[] blsPubkey) {
        if (blsPubkey == null || blsPubkey.length != 48) {
            return false;
        }
        try {
            ECPoint pk = BLS12_381Serialization.decompressG1(blsPubkey, BLS12_381G1.createCurve());
            return pk != null && BLS12_381BasicScheme.keyValidate(pk);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Proof of possession for the registered BLS key: a BLS signature over the
     * validator's ML-DSA public key, using the BLS secret key. Binds the BLS key
     * to the ML-DSA identity so a rogue validator cannot register a BLS key it
     * does not control.
     */
    public static byte[] blsProofOfPossession(PQKey validatorKey) {
        BigInteger sk = blsSecretScalar(validatorKey);
        if (sk == null) {
            return null;
        }
        return BLS12_381Serialization.compressG2(
                BLS12_381BasicScheme.sign(sk, validatorKey.getPubKey()));
    }

    /**
     * Verifies a proof of possession: {@code pop} must be a valid BLS signature
     * over {@code mldsaPubkey} under {@code blsPubkey}.
     */
    public static boolean verifyProofOfPossession(byte[] blsPubkey, byte[] mldsaPubkey, byte[] pop) {
        try {
            ECPoint pk = BLS12_381Serialization.decompressG1(blsPubkey, BLS12_381G1.createCurve());
            BLS12_381G2Point sig = BLS12_381Serialization.decompressG2(pop);
            return BLS12_381BasicScheme.verify(pk, mldsaPubkey, sig);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The reveal is the validator's unique BLS signature over the slot message.
     * Never persisted — recomputed on demand from the ML-DSA private key.
     */
    public byte[] computeReveal(PQKey validatorKey, long slot) {
        BigInteger sk = blsSecretScalar(validatorKey);
        if (sk == null) {
            return null; // no private key material: cannot participate in RANDAO
        }
        return BLS12_381Serialization.compressG2(
                BLS12_381BasicScheme.sign(sk, revealMessage(slot).getBytes()));
    }

    /**
     * Mix used to select the proposer for {@code slot}: the IMMUTABLE finalized
     * snapshot of the epoch two epochs earlier (persisted once at the epoch
     * boundary by {@link #finalizeEpochMix}). A late-confirming beacon can never
     * retroactively move the value blocks already validated against. Before the
     * snapshot exists (bootstrap / a node that has not crossed the boundary) the
     * deterministic SHA-256(epoch) seed is used — never the live accumulator.
     */
    public byte[] getSelectionMix(long slot, BlockStoreInterface store) {
        long epoch = slot / 32;
        long sourceEpoch = epoch - 2;
        if (sourceEpoch < 0) {
            return sha256(String.valueOf(sourceEpoch).getBytes());
        }
        byte[] finalized = null;
        if (store != null) {
            try {
                finalized = store.getPosState("randao", "mixfinal_" + sourceEpoch);
            } catch (Exception e) {
                log.debug("Failed to read finalized RANDAO mix for epoch {}", sourceEpoch, e);
            }
        }
        // Never fall back to the live accumulator: before the immutable snapshot
        // exists, the deterministic seed is used so every node agrees.
        return finalized != null ? finalized : sha256(String.valueOf(sourceEpoch).getBytes());
    }

    /**
     * Freezes the live mix of {@code epoch} into an immutable {@code mixfinal_}
     * snapshot when the epoch-boundary beacon (the first confirmed beacon of the
     * FOLLOWING epoch, i.e. the lowest reward-chainlength beacon whose slot has
     * crossed into the next epoch) confirms. The caller MUST process confirmed
     * beacons in reward-chainlength order, so the boundary is the same chain
     * position on every node and the frozen value — the mix accumulated from
     * exactly the epoch-{@code epoch} beacons confirmed before the boundary — is
     * identical everywhere. Once written it is never rewritten, so proposer
     * selection (which reads it two epochs later) is stable against late or
     * reorged beacons. FAIL-CLOSED: a persistence failure propagates and aborts
     * the confirming batch rather than silently diverging.
     */
    public void finalizeEpochMix(long epoch, BlockStoreInterface store) throws BlockStoreException {
        if (epoch < 0) {
            return;
        }
        if (store.getPosState("randao", "mixfinal_" + epoch) == null) {
            byte[] live = store.getPosState("randao", "mix_" + epoch);
            byte[] value = live != null ? live : sha256(String.valueOf(epoch).getBytes());
            store.savePosState("randao", "mixfinal_" + epoch, value);
        }
    }

    public byte[] getRandaoMix(long slot) {
        long epoch = slot / 32;
        return randaoMixes.getOrDefault(epoch, sha256(String.valueOf(epoch).getBytes()));
    }

    /**
     * Mixes an on-chain RANDAO reveal (from a confirmed beacon's SlotData) into
     * the epoch mix and persists it. When {@code store} is non-null the write
     * rides the caller's batch and the in-memory cache is NOT updated — the
     * caller must reload the affected epochs after the batch COMMITS (see
     * reloadMix), so an aborted batch rolls the mix back in memory too. A
     * persistence failure THROWS (fail-closed).
     *
     * <p>The reveal is MANDATORY: a missing reveal is rejected and the mix is
     * NOT updated. The reveal itself was verified at beacon acceptance as a
     * unique BLS signature over the slot by the registered slot proposer, so the
     * fold can never inject grindable bytes.
     */
    public void applyReveal(long slot, byte[] reveal, BlockStoreInterface store) {
        if (reveal == null || reveal.length == 0) {
            log.warn("Rejecting RANDAO reveal for slot {}: reveal is missing", slot);
            return;
        }
        byte[] folded = sha256(reveal);
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
                store.savePosState("randao", "mix_" + epoch, xor(base, folded));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist RANDAO mix for epoch " + epoch, e);
            }
            return;
        }
        randaoMixes.put(epoch, xor(randaoMixes.getOrDefault(epoch, epochSeed), folded));
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
    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
