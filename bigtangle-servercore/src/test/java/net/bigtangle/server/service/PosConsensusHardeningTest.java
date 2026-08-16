package net.bigtangle.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;

public class PosConsensusHardeningTest {

    private static AttestationData att(long slot, long sourceEpoch, long targetEpoch,
            Sha256Hash head, Sha256Hash target) {
        AttestationData a = new AttestationData();
        a.setSlot(slot);
        a.setEpoch(slot / 32);
        a.setSourceEpoch(sourceEpoch);
        a.setTargetEpoch(targetEpoch);
        a.setBeaconBlockHash(head);
        a.setTargetCheckpoint(target);
        return a;
    }

    private static Sha256Hash h(int b) {
        return Sha256Hash.of(new byte[] { (byte) b });
    }

    // ---- 1.2: double-vote detection must cover BOTH Ethereum forms ----

    @Test
    public void doubleVoteSameSlotDifferentHead() {
        AttestationData a = att(10, 0, 0, h(1), h(1));
        AttestationData b = att(10, 0, 0, h(2), h(1));
        assertTrue(SlashingService.isDoubleVote(a, b));
    }

    @Test
    public void doubleVoteSameTargetEpochDifferentTargetRoot() {
        // Different slots, same target epoch, two different target checkpoints:
        // Ethereum's second double-vote form — previously NOT detected.
        AttestationData a = att(33, 0, 1, h(1), h(1));
        AttestationData b = att(40, 0, 1, h(2), h(2));
        assertTrue(SlashingService.isDoubleVote(a, b));
    }

    @Test
    public void identicalVotesAreNotDoubleVote() {
        AttestationData a = att(10, 0, 0, h(1), h(1));
        AttestationData b = att(10, 0, 0, h(1), h(1));
        assertFalse(SlashingService.isDoubleVote(a, b));
    }

    @Test
    public void differentEpochVotesAreNotDoubleVote() {
        AttestationData a = att(33, 0, 1, h(1), h(1));
        AttestationData b = att(65, 1, 2, h(2), h(2));
        assertFalse(SlashingService.isDoubleVote(a, b));
    }

    // ---- Surround vote (unchanged semantics, now via shared helper) ----

    @Test
    public void surroundVoteDetected() {
        // a: (0, 3) surrounds b: (1, 2)
        AttestationData a = att(0, 0, 3, h(1), h(1));
        AttestationData b = att(33, 1, 2, h(2), h(2));
        assertTrue(SlashingService.isSurroundVote(a, b));
        assertTrue(SlashingService.isSurroundVote(b, a));
    }

    @Test
    public void nonSurroundVoteNotDetected() {
        AttestationData a = att(0, 0, 1, h(1), h(1));
        AttestationData b = att(33, 0, 2, h(2), h(2));
        assertFalse(SlashingService.isSurroundVote(a, b));
    }

    // ---- 2.2: effective-balance cap bounds per-validator influence ----

    @Test
    public void effectiveBalanceIsCapped() {
        assertEquals(StakeService.MIN_STAKE, StakeService.effectiveBalance(StakeService.MIN_STAKE));
        assertEquals(StakeService.MAX_EFFECTIVE_BALANCE,
                StakeService.effectiveBalance(StakeService.MIN_STAKE.multiply(BigInteger.valueOf(1000))));
        assertEquals(BigInteger.ZERO, StakeService.effectiveBalance(BigInteger.ZERO));
        assertEquals(BigInteger.ZERO, StakeService.effectiveBalance((BigInteger) null));
    }

    @Test
    public void churnLimitHasMinimumAndScales() {
        assertEquals(StakeService.MIN_PER_EPOCH_CHURN_LIMIT, StakeService.churnLimit(0));
        assertEquals(StakeService.MIN_PER_EPOCH_CHURN_LIMIT, StakeService.churnLimit(100));
        assertEquals(StakeService.MIN_PER_EPOCH_CHURN_LIMIT,
                StakeService.churnLimit(StakeService.CHURN_LIMIT_QUOTIENT - 1));
        assertEquals(5, StakeService.churnLimit(5L * StakeService.CHURN_LIMIT_QUOTIENT));
    }

    @Test
    public void proposerSelectionUsesCappedWeight() {
        // Two validators: one with 1000x stake, one with 1x. Capped effective
        // balance makes them equal, so both are selected over many slots.
        List<StakeRecord> validators = new ArrayList<>();
        validators.add(new StakeRecord(new byte[32], StakeService.MIN_STAKE.multiply(BigInteger.valueOf(1000)), null));
        validators.add(new StakeRecord(new byte[32], StakeService.MIN_STAKE, null));
        byte[] mix = Sha256Hash.of("mix".getBytes()).getBytes();

        int count0 = 0;
        int count1 = 0;
        for (long slot = 0; slot < 400; slot++) {
            long idx = SlotService.selectProposerForSlot(slot, validators, mix);
            assertTrue(idx == 0 || idx == 1, "index out of range: " + idx);
            if (idx == 0) {
                count0++;
            } else {
                count1++;
            }
        }
        // With equal effective weight, both must be selected a healthy share of
        // the time (a 1000:1 raw-weight scheme would give ~0 to validator 1).
        assertTrue(count0 > 100, "validator 0 too rarely selected: " + count0);
        assertTrue(count1 > 100, "validator 1 too rarely selected: " + count1);
    }

    @Test
    public void proposerSelectionDeterministic() {
        List<StakeRecord> validators = new ArrayList<>();
        validators.add(new StakeRecord(new byte[32], StakeService.MIN_STAKE, null));
        validators.add(new StakeRecord(new byte[32], StakeService.MIN_STAKE, null));
        byte[] mix = Sha256Hash.of("mix".getBytes()).getBytes();
        long a = SlotService.selectProposerForSlot(42, validators, mix);
        long b = SlotService.selectProposerForSlot(42, validators, mix);
        assertEquals(a, b);
    }

    // ---- 1.3: reorg-safe checkpoint invalidation ----

    @Test
    public void checkpointInvalidationReDerivesAtAndAboveEpoch() {
        CasperService c = new CasperService();
        CasperService.Checkpoint cp5 = c.ensureCheckpoint(5L, h(5));
        CasperService.Checkpoint cp6 = c.ensureCheckpoint(6L, h(6));
        assertSame(cp6, c.ensureCheckpoint(6L, h(6)), "checkpoint is cached");

        c.invalidateCheckpointsFrom(6L, null);

        assertNotSame(cp6, c.ensureCheckpoint(6L, h(6)), "epoch 6 re-derived after invalidation");
        assertSame(cp5, c.ensureCheckpoint(5L, h(5)), "epoch 5 (below floor) is preserved");
    }

    @Test
    public void checkpointInvalidationPreservesGenesis() {
        CasperService c = new CasperService();
        c.ensureCheckpoint(0L, h(0));
        assertTrue(c.isCheckpointFinalized(0));

        c.invalidateCheckpointsFrom(1L, null);

        assertTrue(c.isCheckpointFinalized(0), "genesis checkpoint survives invalidation");
    }

    // ---- Inclusion commitment: deterministic attestation root ----

    @Test
    public void attestationRootIsOrderIndependent() {
        AttestationData a = att(1, 0, 0, h(1), h(1));
        AttestationData b = att(2, 0, 0, h(2), h(2));

        List<AttestationData> ab = new ArrayList<>(List.of(a, b));
        List<AttestationData> ba = new ArrayList<>(List.of(b, a));

        assertEquals(CasperService.computeAttestationRoot(ab),
                CasperService.computeAttestationRoot(ba),
                "root must not depend on input order");
    }

    @Test
    public void attestationRootEmptyAndDistinct() {
        assertEquals(Sha256Hash.ZERO_HASH, CasperService.computeAttestationRoot(List.of()));
        assertEquals(Sha256Hash.ZERO_HASH, CasperService.computeAttestationRoot(null));

        AttestationData a = att(1, 0, 0, h(1), h(1));
        AttestationData b = att(2, 0, 0, h(2), h(2));
        assertFalse(CasperService.computeAttestationRoot(List.of(a))
                .equals(CasperService.computeAttestationRoot(List.of(b))),
                "different sets yield different roots");
    }

    @Test
    public void slotDataRoundTripsEmbeddedAttestations() throws Exception {
        SlotData sd = new SlotData(1, 0, 0, h(1));
        List<AttestationData> list = new ArrayList<>(List.of(att(2, 0, 0, h(2), h(2)),
                att(3, 0, 0, h(3), h(3))));
        sd.setAttestations(list);
        sd.setAttestationRoot(CasperService.computeAttestationRoot(list));

        byte[] json = net.bigtangle.utils.Json.jsonmapper().writeValueAsBytes(sd);
        SlotData back = net.bigtangle.utils.Json.jsonmapper().readValue(json, SlotData.class);

        assertEquals(2, back.getAttestations().size(), "embedded attestations survive JSON round-trip");
        assertEquals(CasperService.computeAttestationRoot(back.getAttestations()),
                back.getAttestationRoot(), "root still matches after round-trip");
    }

    @Test
    public void attestationBlsSignatureVerifiesAndDetectsTamper() {
        PQKey key = PQKey.createNew();
        AttestationData a = att(1, 0, 0, h(1), h(1));
        a.setValidatorPubkey(key.getPubKey());
        a.setBlsPubkey(RandaoService.blsPubkey(key));
        a.setSignature(RandaoService.blsSign(key, a.getMessageHash().getBytes()));
        assertTrue(a.verifySignature(), "a BLS-signed attestation must verify");

        // Tampered signature must fail (this is what verifyEmbeddedAttestations
        // and slashing-proof validation rely on).
        byte[] sig = a.getSignature().clone();
        sig[0] ^= 1;
        AttestationData b = att(1, 0, 0, h(1), h(1));
        b.setValidatorPubkey(key.getPubKey());
        b.setBlsPubkey(RandaoService.blsPubkey(key));
        b.setSignature(sig);
        assertFalse(b.verifySignature(), "a tampered signature must not verify");

        // Unsigned attestation must not verify.
        AttestationData c = att(1, 0, 0, h(1), h(1));
        c.setValidatorPubkey(key.getPubKey());
        c.setBlsPubkey(RandaoService.blsPubkey(key));
        assertFalse(c.verifySignature(), "an unsigned attestation must not verify");
    }
}
