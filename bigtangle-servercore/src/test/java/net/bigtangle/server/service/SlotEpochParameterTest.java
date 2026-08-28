package net.bigtangle.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;

/**
 * Epoch-length parameterization (Phase 2 of the shorter-finality rollout):
 * slots-per-epoch is a per-network consensus value
 * ({@link NetworkParameters#getSlotsPerEpoch()}), and every epoch boundary
 * computation must honor it — a single hardcoded /32 site would split the
 * network (attestation sanity verifies epoch == slot / slotsPerEpoch).
 */
public class SlotEpochParameterTest {

    @Test
    public void mainnetRunsShorterEpochsTestNetKeepsLegacy() {
        // Mainnet ships 8-slot epochs (finality ≈ 2 epochs × 8 slots × slot
        // interval); the test net keeps 32 until its suites are migrated.
        assertEquals(8L, MainNetParams.get().getSlotsPerEpoch());
        assertEquals(32L, TestParams.get().getSlotsPerEpoch());
    }

    @Test
    public void epochArithmeticFollowsConfiguredLength() {
        long e = 8;
        NetworkParameters p = MainNetParams.get();
        assertEquals(0L, p.getEpochForSlot(0));
        assertEquals(0L, p.getEpochForSlot(e - 1));
        assertEquals(1L, p.getEpochForSlot(e));
        assertEquals(1L, p.getEpochForSlot(2 * e - 1));
        assertEquals(2L, p.getEpochForSlot(2 * e));
        assertEquals(0L, p.getSlotInEpoch(e));
        assertEquals(1L, p.getSlotInEpoch(e + 1));
        assertEquals(e - 1, p.getSlotInEpoch(2 * e - 1));
    }

    @Test
    public void slotSanityUsesConfiguredEpochBoundaries() {
        long e = 8;
        // 8-slot epochs: slot 8 is the epoch-1 boundary, not slot 32.
        assertTrue(SlotService.isEpochStartSlot(e, e));
        assertFalse(SlotService.isEpochStartSlot(e + 1, e));
        assertTrue(SlotService.slotSequenceValid(e, 1, e - 1, e));
        assertFalse(SlotService.slotSequenceValid(e, 0, e - 1, e),
                "declared epoch must equal slot / slotsPerEpoch");
        // 32-slot epochs (test net): slot 32 is the boundary, slot 8 is not.
        assertTrue(SlotService.isEpochStartSlot(32, 32));
        assertFalse(SlotService.isEpochStartSlot(8, 32));
        assertTrue(SlotService.slotSequenceValid(32, 1, 31, 32));
    }

    @Test
    public void wallClockEpochFollowsConfiguredLength() {
        long genesisMs = 1532896109000L;
        long interval = 12_000L;
        long e = 8;
        long now = System.currentTimeMillis();
        long slot = (now - genesisMs) / interval;
        assertEquals(slot / e, SlotService.epochAt(now, interval, e));
        assertEquals(slot % e, SlotService.currentSlotInEpoch(now, interval, e));
    }

    @Test
    public void attestationLookbackScalesWithEpochLength() {
        // 10 epochs of history: 320 slots at 32/epoch, 80 at 8/epoch — the
        // wall-history semantics are preserved, not the slot count.
        assertEquals(CasperService.ATTESTATION_LOOKBACK_EPOCHS * MainNetParams.get().getSlotsPerEpoch(),
                CasperService.ATTESTATION_LOOKBACK_EPOCHS * 8L);
        assertEquals(CasperService.ATTESTATION_LOOKBACK_EPOCHS * TestParams.get().getSlotsPerEpoch(),
                CasperService.ATTESTATION_LOOKBACK_EPOCHS * 32L);
    }

    @Test
    public void inactivityLeakGraceIsWallTimePreserving() {
        // 128-slot canonical grace: 4 epochs at 32, 16 at 8, 32 at 4 — the
        // wall-time tolerance before offline stake leaks must not shrink with
        // epoch length.
        assertEquals(4L, CasperService.inactivityPenaltyThresholdEpochs(32));
        assertEquals(16L, CasperService.inactivityPenaltyThresholdEpochs(8));
        assertEquals(32L, CasperService.inactivityPenaltyThresholdEpochs(4));
    }

    @Test
    public void justifiedSwitchWindowKeepsQuarterOfEpoch() {
        // 25 % of an epoch (min 1 slot): 8/32, 2/8, 1/4 — never the whole
        // epoch, so the bouncing-attack defense stays effective.
        assertEquals(8L, CasperService.safeSlotsToUpdateJustified(32));
        assertEquals(2L, CasperService.safeSlotsToUpdateJustified(8));
        assertEquals(1L, CasperService.safeSlotsToUpdateJustified(4));
    }

    @Test
    public void withdrawalDelayIsWallTimePreserving() {
        // 8192-slot canonical bond lock (≈ 7.6 h at 12 s): 256 epochs at 32,
        // 1024 at 8, 2048 at 4 — a slashed validator must not unbond faster
        // just because epochs got shorter.
        assertEquals(256L, StakeService.withdrawalDelayEpochs(32));
        assertEquals(1024L, StakeService.withdrawalDelayEpochs(8));
        assertEquals(2048L, StakeService.withdrawalDelayEpochs(4));
    }
}
