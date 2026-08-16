package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.server.service.FeeService;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.server.service.RandaoService;
import net.bigtangle.server.service.SlashingService;
import net.bigtangle.server.service.SlotService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.utils.Json;

public class PoSTest extends AbstractIntegrationTest {

    @Autowired
    private StakeService stakeService;

    @Autowired
    private SlotService slotService;

    @Autowired
    private RandaoService randaoService;

    @Autowired
    private GhostService ghostService;

    @Autowired
    private CasperService casperService;

    @Autowired
    private SlashingService slashingService;

    @Autowired
    private FeeService feeService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private net.bigtangle.server.service.ValidatorDutyService validatorDutyService;

    private PQKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        // Reset the shared Casper in-memory checkpoint/vote state (the Spring
        // bean outlives individual tests; restoreState re-derives genesis from
        // the freshly reset store).
        casperService.restoreState();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        validatorKey = PQKey.createNew();
    }

    /** Signs an attestation with the validator's derived BLS key (on-chain scheme). */
    private void signAttestation(PQKey key, AttestationData att) {
        att.setBlsPubkey(net.bigtangle.server.service.RandaoService.blsPubkey(key));
        att.setSignature(net.bigtangle.server.service.RandaoService.blsSign(key, att.getMessageHash().getBytes()));
    }

    // ========= Slot Tests =========

    @Test
    public void testSlotCalculation() {
        long slot = slotService.getCurrentSlot();
        assertTrue(slot >= 0, "slot must be non-negative");

        long epoch = slotService.getEpochForSlot(slot);
        assertEquals(slot / 32, epoch);

        long epochSlot = slotService.getSlotInEpoch(slot);
        assertEquals(slot % 32, epochSlot);

        long currentEpoch = slotService.getCurrentEpoch();
        assertEquals(slot / 32, currentEpoch);
    }

    @Test
    public void testProposerSelection() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        long proposerIdx = slotService.selectProposer(0, store);
        assertTrue(proposerIdx >= 0, "proposer index must be valid");
    }

    @Test
    public void testProposerSelectionEmptyValidators() throws Exception {
        long proposerIdx = slotService.selectProposer(0, store);
        assertEquals(-1, proposerIdx, "no validators -> no proposer");
    }

    // ========= Stake Tests =========

    @Test
    public void testStakeDeposit() throws Exception {
        assertNotNull(validatorKey);

        BigInteger amount = StakeService.MIN_STAKE;
        assertTrue(amount.compareTo(BigInteger.ZERO) > 0);

        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), amount,
                validatorKey.getPubKeyHash()));

        StakeRecord loaded = store.getStakeDeposit(validatorKey.getPubKey());
        assertNotNull(loaded);
        assertEquals(amount, loaded.getAmount());
        assertFalse(loaded.isSlashed());
        assertEquals(-1, loaded.getActivatedEpoch());
    }

    @Test
    public void testStakeActivateAndSlash() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));

        stakeService.activateValidator(validatorKey.getPubKey(), 1, store);
        StakeRecord activated = store.getStakeDeposit(validatorKey.getPubKey());
        assertEquals(1, activated.getActivatedEpoch());

        stakeService.slashValidator(validatorKey.getPubKey(), store);
        StakeRecord slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(slashed.isSlashed());
        assertTrue(slashed.getWithdrawableEpoch() > 0);
    }

    @Test
    public void testActiveValidatorsQuery() throws Exception {
        PQKey v1 = PQKey.createNew();
        PQKey v2 = PQKey.createNew();

        store.saveStakeDeposit(new StakeRecord(v1.getPubKey(),
                StakeService.MIN_STAKE, v1.getPubKeyHash()));
        store.saveStakeDeposit(new StakeRecord(v2.getPubKey(),
                StakeService.MIN_STAKE, v2.getPubKeyHash()));

        stakeService.activateValidator(v1.getPubKey(), 0, store);
        stakeService.activateValidator(v2.getPubKey(), 0, store);

        List<StakeRecord> active = store.getActiveStakeDeposits();
        assertEquals(2, active.size());

        BigInteger total = stakeService.getTotalActiveStake(store);
        assertEquals(StakeService.MIN_STAKE.multiply(BigInteger.valueOf(2)), total);
    }

    @Test
    public void testActivationDelayExcludesFutureEpochs() throws Exception {
        // A validator whose activation epoch is in the future must NOT be
        // weighted yet (MAX_SEED_LOOKAHEAD delay).
        PQKey v = PQKey.createNew();
        store.saveStakeDeposit(new StakeRecord(v.getPubKey(), StakeService.MIN_STAKE, v.getPubKeyHash()));
        stakeService.activateValidator(v.getPubKey(), 5, store); // future activation

        assertEquals(0L, stakeService.getEffectiveStake(v.getPubKey(), store),
                "not yet active (activation epoch in the future)");
        assertEquals(BigInteger.ZERO, stakeService.getTotalActiveStake(store),
                "no active stake while the only validator is not yet activated");
    }

    @Test
    public void testOnChainAttestationReadsEmbeddedAttestations() throws Exception {
        // Post-fork path: once the activation height is reached, votes are read
        // from the ON-CHAIN embedded attestations, not the gossip view.
        String key = "net.bigtangle.pos.attestationActivation";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "0");

            PQKey v1 = PQKey.createNew();
            long slot = 1;
            long epoch = slot / 32;
            CasperService.Checkpoint base = casperService.getLastFinalizedCheckpoint();
            Sha256Hash target = Sha256Hash.of("ckpt0".getBytes());
            AttestationData att = signedVoteFor(v1, slot, base.getEpoch(), epoch, base.getBlockHash(), target);

            net.bigtangle.core.SlotData sd = new net.bigtangle.core.SlotData(slot, epoch, 0,
                    Sha256Hash.of("parent".getBytes()));
            sd.setAttestations(List.of(att));
            sd.setAttestationRoot(CasperService.computeAttestationRoot(List.of(att)));

            Block prev = UtilGeneseBlock.createGenesis(networkParameters);
            Block b = Block.createBlock(networkParameters, prev, prev);
            b.setBlockType(BlockType.BLOCKTYPE_BEACON);
            Transaction rtx = new Transaction(networkParameters);
            RewardInfo ri = new RewardInfo();
            ri.setChainlength(1);
            ri.setPrevRewardHash(prev.getHash());
            ri.setBlocks(new java.util.HashSet<>());
            rtx.setData(ri.toByteArray());
            b.addTransaction(rtx);
            Transaction slotTx = new Transaction(networkParameters);
            slotTx.setDataClassName("SlotData");
            slotTx.setData(Json.jsonmapper().writeValueAsBytes(sd));
            b.addTransaction(slotTx);

            store.put(b);
            store.insertReward(b.getHash(), prev.getHash(), 1);
            store.updateRewardConfirmed(b.getHash(), true);

            assertTrue(CasperService.onChainAttestationActive(store),
                    "chain-read must be active at/above the (lowered) activation height");
            java.util.Set<String> voters = CasperService.votersForEpoch(epoch, store);
            assertTrue(voters.contains(Utils.HEX.encode(v1.getPubKey())),
                    "embedded attestation must be read from the confirmed chain");
        } finally {
            if (original != null) {
                System.setProperty(key, original);
            } else {
                System.clearProperty(key);
            }
        }
    }

    @Test
    public void testStakeEffectiveStake() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        long effective = stakeService.getEffectiveStake(validatorKey.getPubKey(), store);
        assertEquals(StakeService.MIN_STAKE.longValue(), effective);

        long noStake = stakeService.getEffectiveStake(PQKey.createNew().getPubKey(), store);
        assertEquals(0L, noStake);
    }

    // ========= RANDAO Tests =========

    @Test
    public void testRandaoRevealAndMix() throws Exception {
        byte[] reveal = randaoService.computeReveal(validatorKey, 0);
        assertNotNull(reveal);

        // A missing reveal is rejected; a present reveal folds into the mix.
        byte[] before = randaoService.getRandaoMix(0);
        randaoService.applyReveal(0, null, null);
        assertArrayEquals(before, randaoService.getRandaoMix(0));

        randaoService.applyReveal(0, reveal, null);
        assertFalse(java.util.Arrays.equals(before, randaoService.getRandaoMix(0)),
                "a present reveal must update the mix");
    }

    @Test
    public void testRandaoRevealIsUniqueBlsSignature() throws Exception {
        // The reveal must be a UNIQUE BLS signature over the slot message: for a
        // given (key, slot) there is exactly one valid reveal, so a proposer can
        // never grind a favourable mix by re-rolling signatures.
        byte[] reveal = randaoService.computeReveal(validatorKey, 5);
        assertNotNull(reveal);

        byte[] blsPubkey = net.bigtangle.server.service.RandaoService.blsPubkey(validatorKey);
        assertNotNull(blsPubkey);
        assertTrue(net.bigtangle.server.service.RandaoService.isValidBlsPubkey(blsPubkey),
                "a derived BLS public key must be a valid G1 point");
        assertTrue(net.bigtangle.server.service.RandaoService.verifyReveal(blsPubkey, 5, reveal),
                "reveal must verify as a unique BLS signature over the slot message");

        // Bound to the validator's key: a different key's public key rejects it.
        PQKey other = PQKey.createNew();
        assertFalse(net.bigtangle.server.service.RandaoService.verifyReveal(
                net.bigtangle.server.service.RandaoService.blsPubkey(other), 5, reveal),
                "reveal must not verify under a different validator's BLS key");

        // Unique: the same (key, slot) yields exactly one reveal.
        assertArrayEquals(reveal, randaoService.computeReveal(validatorKey, 5));

        // Garbage / malformed keys and reveals are rejected.
        assertFalse(net.bigtangle.server.service.RandaoService.isValidBlsPubkey(new byte[48]),
                "an all-zero key must not be a valid G1 point");
        assertFalse(net.bigtangle.server.service.RandaoService.isValidBlsPubkey(null));
        assertFalse(net.bigtangle.server.service.RandaoService.verifyReveal(blsPubkey, 5, new byte[96]),
                "a garbage reveal must not verify");
    }

    @Test
    public void testRandaoProofOfPossession() throws Exception {
        byte[] blsPubkey = net.bigtangle.server.service.RandaoService.blsPubkey(validatorKey);
        byte[] pop = net.bigtangle.server.service.RandaoService.blsProofOfPossession(validatorKey);
        assertNotNull(blsPubkey);
        assertNotNull(pop);
        assertTrue(net.bigtangle.server.service.RandaoService.verifyProofOfPossession(
                blsPubkey, validatorKey.getPubKey(), pop),
                "proof of possession must bind the BLS key to the ML-DSA pubkey");

        // A PoP by a different key must not verify against this BLS key.
        PQKey other = PQKey.createNew();
        assertFalse(net.bigtangle.server.service.RandaoService.verifyProofOfPossession(
                blsPubkey, other.getPubKey(), pop),
                "proof of possession must be over the depositor's ML-DSA pubkey");
    }

    @Test
    public void testSelectionMixIsFinalizedSnapshot() throws Exception {
        // Fold a reveal into epoch 3's live mix, then finalize epoch 3 as the
        // snapshot. Even if epoch 3's live mix is subsequently mutated, proposer
        // selection for epoch 5 must keep reading the frozen snapshot.
        byte[] reveal = randaoService.computeReveal(validatorKey, 3 * 32);
        assertNotNull(reveal);
        randaoService.applyReveal(3 * 32, reveal, store);

        byte[] liveAfterFold = store.getPosState("randao", "mix_" + 3);
        assertNotNull(liveAfterFold, "the fold must persist the live mix");

        randaoService.finalizeEpochMix(3, store);
        byte[] snapshot = store.getPosState("randao", "mixfinal_" + 3);
        assertNotNull(snapshot, "finalizeEpochMix must persist the immutable snapshot");
        assertArrayEquals(liveAfterFold, snapshot);

        // Mutating the live mix afterwards must NOT move the snapshot.
        randaoService.applyReveal(3 * 32, new byte[96], store);
        byte[] liveAfterMutation = store.getPosState("randao", "mix_" + 3);
        assertFalse(java.util.Arrays.equals(liveAfterFold, liveAfterMutation),
                "a late reveal must move the live mix");

        assertArrayEquals(snapshot, store.getPosState("randao", "mixfinal_" + 3),
                "the finalized snapshot must be immutable");

        // Selection for epoch 5 reads the frozen snapshot.
        assertArrayEquals(snapshot, randaoService.getSelectionMix(5 * 32, store));
    }

    // ========= GHOST Tests =========

    @Test
    public void testLmdGhostEmpty() throws Exception {
        Sha256Hash root = ghostService.getDagRoot(store);
        assertNotNull(root);
    }

    @Test
    public void testGhostExecuteGhost() throws Exception {
        Sha256Hash root = ghostService.getDagRoot(store);
        Sha256Hash head = ghostService.executeGhost(root, store);
        assertNotNull(head);
        assertEquals(root, head, "with no attestations GHOST returns root");
    }

    @Test
    public void testGhostGetTwoTips() throws Exception {
        List<Sha256Hash> tips = ghostService.getTwoTips(store);
        assertNotNull(tips);
        assertFalse(tips.isEmpty());
        assertTrue(tips.size() <= 2);
    }

    @Test
    public void testGhostProcessAttestation() throws Exception {
        AttestationData att = new AttestationData();
        att.setSlot(1);
        att.setBeaconBlockHash(Sha256Hash.of("testblock".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());

        ghostService.processAttestation(att, store);

        Sha256Hash root = ghostService.getDagRoot(store);
        Sha256Hash head = ghostService.executeGhost(root, store);
        assertNotNull(head);
    }

    // ========= Casper Tests =========

    @Test
    public void testCasperCheckpoint() throws Exception {
        casperService.processSlot(0, Sha256Hash.ZERO_HASH, List.of(), store);
    }

    @Test
    public void testCasperProcessVote() throws Exception {
        long slot = slotService.getCurrentSlot();
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(slot / 32);
        att.setSourceEpoch(0);
        att.setTargetEpoch(slot / 32);
        att.setBeaconBlockHash(Sha256Hash.of("beacon1".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());
        signAttestation(validatorKey, att);

        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        casperService.processVote(att, store);

        List<AttestationData> saved = store.getAttestationsForSlot(att.getSlot());
        assertFalse(saved.isEmpty(), "attestation should be persisted");

        Map<Sha256Hash, Long> votes = store.getSummedAttestationVotes();
        assertTrue(votes.containsKey(att.getBeaconBlockHash()),
                "ghost votes should contain the attested block");
        assertTrue(votes.get(att.getBeaconBlockHash()) > 0,
                "vote weight must be positive");
    }

    @Test
    public void testRejectsUnauthenticatedAttestation() throws Exception {
        AttestationData att = new AttestationData();
        att.setSlot(System.currentTimeMillis() / 12_000L);
        att.setBeaconBlockHash(Sha256Hash.of("beacon1".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());
        // NO signature — must be rejected, not counted.
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        casperService.processVote(att, store);

        assertTrue(store.getSummedAttestationVotes().isEmpty(),
                "unauthenticated attestation must not influence fork choice");
    }

    /**
     * Regression test for the far-future attestation gate when the configured
     * slot interval is shorter than the canonical 12s. The MCMC proposes slots
     * with {@code pos.slotIntervalMs} (2000ms in the docker/remote PoS harness),
     * so its epoch is {@code slot/32}. The gate must compare against a wall
     * epoch computed with the SAME interval: previously {@code SlotService.epochAt}
     * was hardcoded to 12s, making every attestation look 6x ahead and
     * rejecting all of them, so no beacon ever confirmed.
     */
    @Test
    public void testFarFutureGateUsesConfiguredSlotInterval() throws Exception {
        Object original = org.springframework.test.util.ReflectionTestUtils
                .getField(slotService, "slotIntervalMs");
        Object originalCasper = org.springframework.test.util.ReflectionTestUtils
                .getField(casperService, "slotIntervalMs");
        Object originalStake = org.springframework.test.util.ReflectionTestUtils
                .getField(stakeService, "slotIntervalMs");
        try {
            // Simulate the PoS harness config (POS_SLOT_INTERVAL_MS=2000). In
            // production every service reads the same pos.slotIntervalMs, so the
            // slot producer, the far-future gate and the stake activation epoch
            // must all agree on the interval.
            org.springframework.test.util.ReflectionTestUtils
                    .setField(slotService, "slotIntervalMs", 2000L);
            org.springframework.test.util.ReflectionTestUtils
                    .setField(casperService, "slotIntervalMs", 2000L);
            org.springframework.test.util.ReflectionTestUtils
                    .setField(stakeService, "slotIntervalMs", 2000L);

            // The wall-clock epoch must match the slot-derived epoch.
            long slot = slotService.getCurrentSlot();
            long epoch = slotService.getEpochForSlot(slot);
            assertEquals(epoch, slotService.epochAt(System.currentTimeMillis()),
                    "epochAt must use the configured slot interval");

            // A valid attestation for the CURRENT slot must be accepted, not
            // rejected as far-future.
            AttestationData att = new AttestationData();
            att.setSlot(slot);
            att.setEpoch(epoch);
            att.setSourceEpoch(0);
            att.setTargetEpoch(epoch);
            att.setBeaconBlockHash(Sha256Hash.of("beacon-short-slot".getBytes()));
            att.setValidatorPubkey(validatorKey.getPubKey());
            signAttestation(validatorKey, att);

            store.saveStakeDeposit(new StakeRecord(
                    validatorKey.getPubKey(), StakeService.MIN_STAKE,
                    validatorKey.getPubKeyHash()));
            stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

            casperService.processVote(att, store);

            Map<Sha256Hash, Long> votes = store.getSummedAttestationVotes();
            assertTrue(votes.containsKey(att.getBeaconBlockHash()),
                    "current-slot attestation must be accepted, not rejected as far-future");
            assertTrue(votes.get(att.getBeaconBlockHash()) > 0,
                    "vote weight must be positive");

            // A genuinely far-future attestation must STILL be rejected.
            AttestationData farFuture = new AttestationData();
            farFuture.setSlot(slot);
            farFuture.setEpoch(epoch);
            farFuture.setSourceEpoch(0);
            farFuture.setTargetEpoch(epoch + 1000);
            farFuture.setBeaconBlockHash(Sha256Hash.of("beacon-far-future".getBytes()));
            farFuture.setValidatorPubkey(validatorKey.getPubKey());
            signAttestation(validatorKey, farFuture);

            casperService.processVote(farFuture, store);

            assertFalse(store.getSummedAttestationVotes().containsKey(farFuture.getBeaconBlockHash()),
                    "far-future attestation must still be rejected");
        } finally {
            org.springframework.test.util.ReflectionTestUtils
                    .setField(slotService, "slotIntervalMs", original);
            org.springframework.test.util.ReflectionTestUtils
                    .setField(casperService, "slotIntervalMs", originalCasper);
            org.springframework.test.util.ReflectionTestUtils
                    .setField(stakeService, "slotIntervalMs", originalStake);
        }
    }

    @Test
    public void testFinalizeCheckpoint() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        // The source is the highest justified checkpoint (genesis, epoch 0),
        // whatever its block hash is in this node's in-memory Casper state.
        casperService.ensureCheckpoint(0, Sha256Hash.of("checkpoint0".getBytes()));
        casperService.ensureCheckpoint(1, Sha256Hash.of("checkpoint1".getBytes()));
        CasperService.Checkpoint source = casperService.getJustifiedCheckpoint();
        assertNotNull(source, "genesis checkpoint must be justified");
        Sha256Hash c1 = Sha256Hash.of("checkpoint1".getBytes());

        // One active validator with the full MIN_STAKE votes source -> c1 (signed).
        AttestationData att = new AttestationData();
        att.setSlot(64);
        att.setEpoch(2);
        att.setSourceEpoch(source.getEpoch());
        att.setTargetEpoch(1);
        att.setBeaconBlockHash(c1);
        att.setSourceCheckpoint(source.getBlockHash());
        att.setTargetCheckpoint(c1);
        att.setValidatorPubkey(validatorKey.getPubKey());
        signAttestation(validatorKey, att);
        casperService.processVote(att, store);

        casperService.finalizeCheckpoint(1, store);

        assertTrue(casperService.isCheckpointJustified(1),
                "checkpoint 1 must be justified with full stake voting");
        assertTrue(casperService.isCheckpointFinalized(1),
                "checkpoint 1 must be finalized when its parent is finalized");
    }

    // ========= Slashing Tests =========

    @Test
    public void testSlashingDoubleVote() {
        AttestationData att1 = new AttestationData();
        att1.setSlot(1);
        att1.setValidatorPubkey(validatorKey.getPubKey());
        att1.setBeaconBlockHash(Sha256Hash.of("blockA".getBytes()));

        AttestationData att2 = new AttestationData();
        att2.setSlot(1);
        att2.setValidatorPubkey(validatorKey.getPubKey());
        att2.setBeaconBlockHash(Sha256Hash.of("blockB".getBytes()));

        AttestationData evidence1 = slashingService.checkDoubleVote(att1);
        AttestationData evidence2 = slashingService.checkDoubleVote(att2);

        assertNull(evidence1, "first vote not a double");
        assertNotNull(evidence2, "second vote is a double");
    }

    @Test
    public void testSlashingSurroundVote() {
        AttestationData att1 = new AttestationData();
        att1.setSlot(1);
        att1.setValidatorPubkey(validatorKey.getPubKey());
        att1.setBeaconBlockHash(Sha256Hash.of("blockA".getBytes()));
        att1.setSourceCheckpoint(Sha256Hash.ZERO_HASH);
        att1.setTargetCheckpoint(Sha256Hash.of("epoch1".getBytes()));

        AttestationData att2 = new AttestationData();
        att2.setSlot(2);
        att2.setValidatorPubkey(validatorKey.getPubKey());
        att2.setBeaconBlockHash(Sha256Hash.of("blockB".getBytes()));
        att2.setSourceCheckpoint(Sha256Hash.of("epoch1".getBytes()));
        att2.setTargetCheckpoint(Sha256Hash.ZERO_HASH);

        slashingService.checkSurroundVote(att1);
        slashingService.checkSurroundVote(att2);
    }

    @Test
    public void testSlashingProcessSlashing() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        slashingService.processSlashing(validatorKey.getPubKey(), store);

        StakeRecord slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(slashed.isSlashed());
    }

    @Test
    public void testSlashingWithdrawableOverwritesOnReconfirmation() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE, validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        // A SLASHING block for this validator with a valid payload.
        Block slashBlock = new Block(networkParameters);
        slashBlock.setBlockType(BlockType.BLOCKTYPE_SLASHING);
        AttestationData att = new AttestationData();
        att.setSlot(5);
        att.setBeaconBlockHash(Sha256Hash.of("headA".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(StakeService.SLASHING_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(Map.of("attestation1", att)));
        slashBlock.addTransaction(tx);

        // The first confirming beacon sets the withdrawable epoch...
        stakeService.applySlashingConfirmed(slashBlock, 10, store);
        StakeRecord slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(slashed.isSlashed());
        assertEquals(10 + StakeService.WITHDRAWAL_DELAY_EPOCHS, slashed.getWithdrawableEpoch());

        // ...and a re-confirming beacon (e.g. after the first beacon was
        // unconfirmed) OVERWRITES it, so a stale epoch can never be frozen by a
        // keep-first guard.
        stakeService.applySlashingConfirmed(slashBlock, 20, store);
        slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertEquals(20 + StakeService.WITHDRAWAL_DELAY_EPOCHS, slashed.getWithdrawableEpoch());
    }

    @Test
    public void testExitWithdrawableOverwritesOnReconfirmation() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE, validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        Block exitBlock = new Block(networkParameters);
        exitBlock.setBlockType(BlockType.BLOCKTYPE_EXIT);
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(StakeService.EXIT_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(
                Map.of("pubkey", Utils.HEX.encode(validatorKey.getPubKey()))));
        exitBlock.addTransaction(tx);

        stakeService.applyExitConfirmed(exitBlock, 10, store);
        StakeRecord exiting = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(exiting.isExiting());
        assertEquals(10 + StakeService.WITHDRAWAL_DELAY_EPOCHS, exiting.getWithdrawableEpoch());

        stakeService.applyExitConfirmed(exitBlock, 20, store);
        exiting = store.getStakeDeposit(validatorKey.getPubKey());
        assertEquals(20 + StakeService.WITHDRAWAL_DELAY_EPOCHS, exiting.getWithdrawableEpoch());
    }

    @Test
    public void testSlashingBlockConsensus() throws Exception {
        // Seed a validator, then slash it via a BLOCKTYPE_SLASHING block built
        // from two authenticated conflicting attestations.
        StakeRecord seeded = new StakeRecord(validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash());
        seeded.setBlockHash(Sha256Hash.of("stakeblock".getBytes()));
        seeded.setTxHash(Sha256Hash.of("staketx".getBytes()));
        store.saveStakeDeposit(seeded);
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        // Produce a real confirmed beacon so the SLASHING block's parent is a
        // beacon (a genesis parent is stale).
        makeRewardBlock();
        mcmcService.update(store);
        blockGraph.confirmDo(store);

        AttestationData att1 = new AttestationData();
        att1.setSlot(5);
        att1.setBeaconBlockHash(Sha256Hash.of("headA".getBytes()));
        att1.setValidatorPubkey(validatorKey.getPubKey());
        signAttestation(validatorKey, att1);

        AttestationData att2 = new AttestationData();
        att2.setSlot(5);
        att2.setBeaconBlockHash(Sha256Hash.of("headB".getBytes()));
        att2.setValidatorPubkey(validatorKey.getPubKey());
        signAttestation(validatorKey, att2);

        stakeService.submitSlashing(att1, att2, store);

        StakeRecord slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(slashed.isSlashed(),
                "validator must be slashed via the consensus SLASHING block");
    }

    @Test
    public void testVoluntaryExitBlock() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        // Authenticated exit: the validator signs sha256(pubkey || nonce),
        // with the nonce bound to the chain position (max confirmed reward).
        long nonce = store.getMaxConfirmedReward().getChainLength();
        byte[] msg = StakeService.buildExitMessage(validatorKey.getPubKey(), nonce);
        byte[] sig = validatorKey.sign(Sha256Hash.of(msg)).serialize();
        stakeService.submitExit(validatorKey.getPubKey(), sig, store);

        StakeRecord exiting = store.getStakeDeposit(validatorKey.getPubKey());
        assertNotNull(exiting);
        assertTrue(exiting.isExiting(), "validator must be marked exiting");
        assertFalse(exiting.isSlashed(), "voluntary exit must NOT mark slashed");
        // The withdrawable epoch is set at CONFIRMATION (from the confirming
        // beacon's chain epoch), not at save; here it is still pending.
        assertTrue(exiting.getWithdrawableEpoch() < 0, "withdrawable epoch is set at confirmation");
    }

    @Test
    public void testExitRejectsBadSignature() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        // Signature over the WRONG message (the pubkey alone, no nonce).
        byte[] badSig = validatorKey.sign(Sha256Hash.of(validatorKey.getPubKey())).serialize();
        assertThrows(IllegalArgumentException.class,
                () -> stakeService.submitExit(validatorKey.getPubKey(), badSig, store));
    }

    // ========= Fee Tests =========

    @Test
    public void testFeeService() {
        long initialFee = feeService.getBaseFee();
        assertEquals(1000, initialFee);

        feeService.updateBaseFee(FeeService.TARGET_GAS);
        assertEquals(initialFee, feeService.getBaseFee());

        feeService.updateBaseFee(FeeService.GAS_LIMIT);
        assertTrue(feeService.getBaseFee() > initialFee,
                "fee increased when gas > target");

        long highFee = feeService.getBaseFee();
        feeService.updateBaseFee(0);
        assertTrue(feeService.getBaseFee() < highFee,
                "fee decreased when gas < target");
    }

    @Test
    public void testFeeTotalCalculation() {
        long total = feeService.calculateTotalFee(21000, 2);
        long expected = (feeService.getBaseFee() + 2) * 21000;
        assertEquals(expected, total);
    }

    // ========= Block Type Tests =========

    @Test
    public void testBlockTypeStakeAllowed() {
        assertTrue(networkParameters.getAllowedBlockTypes()
                .contains(BlockType.BLOCKTYPE_STAKE));
    }

    @Test
    public void testBlockTypeSlashingAllowed() {
        assertTrue(networkParameters.getAllowedBlockTypes()
                .contains(BlockType.BLOCKTYPE_SLASHING));
    }

    @Test
    public void testL0MintsBIG() throws Exception {
        assertTrue(networkParameters.genesisMintsBIG(),
                "L0 must mint BIG at genesis");
    }

    // ========= Persistence Tests =========

    @Test
    public void testPosStateSaveAndLoad() throws Exception {
        store.savePosState("test", "key1", "value1".getBytes());
        store.savePosState("test", "key2", "value2".getBytes());

        byte[] loaded = store.getPosState("test", "key1");
        assertNotNull(loaded);
        assertEquals("value1", new String(loaded));

        Map<String, byte[]> all = store.getPosStateByService("test");
        assertEquals(2, all.size());
        assertTrue(all.containsKey("key2"));

        store.deletePosState("test", "key1");
        assertNull(store.getPosState("test", "key1"));
    }

    @Test
    public void testAttestationVotesCRUD() throws Exception {
        Sha256Hash blockHash = Sha256Hash.of("epoch1block".getBytes());
        byte[] pubkey = validatorKey.getPubKey();
        long weight = 1000L;
        long slot = slotService.getCurrentSlot();

        store.saveAttestationVote(blockHash, pubkey, weight, slot);

        List<AttestationData> forSlot = store.getAttestationsForSlot(slot);
        boolean found = forSlot.stream()
                .anyMatch(a -> a.getBeaconBlockHash().equals(blockHash));
        assertTrue(found);

        Map<Sha256Hash, Long> summed = store.getSummedAttestationVotes();
        assertTrue(summed.containsKey(blockHash));
        assertTrue(summed.get(blockHash) >= weight);
    }

    @Test
    public void testGetAllStakeDeposits() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));

        List<StakeRecord> all = store.getAllStakeDeposits();
        assertFalse(all.isEmpty());
        assertTrue(all.stream().anyMatch(
                s -> Arrays.equals(s.getPubkey(), validatorKey.getPubKey())));
    }

    @Test
    public void testReleaseStakeDeposit() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);
        stakeService.slashValidator(validatorKey.getPubKey(), store);
        store.releaseStakeDeposit(validatorKey.getPubKey());

        StakeRecord released = store.getStakeDeposit(validatorKey.getPubKey());
        assertNotNull(released, "record should still exist (deactivated)");
        assertEquals(-1, released.getActivatedEpoch());
    }

    // ========= Finality / fork-choice regression tests =========

    /** Builds a synthetic beacon with a RewardInfo (and SlotData when slot != null). */
    private Block makeBeacon(Block prev, Sha256Hash prevRewardHash, long chainlength, Long slot)
            throws Exception {
        Block b = Block.createBlock(networkParameters, prev, prev);
        b.setBlockType(BlockType.BLOCKTYPE_BEACON);
        Transaction rtx = new Transaction(networkParameters);
        RewardInfo ri = new RewardInfo();
        ri.setChainlength(chainlength);
        ri.setPrevRewardHash(prevRewardHash);
        ri.setBlocks(new java.util.HashSet<>());
        rtx.setData(ri.toByteArray());
        b.addTransaction(rtx);
        if (slot != null) {
            SlotData sd = new SlotData(slot, slot / 32, 0, prev.getHash());
            Transaction slotTx = new Transaction(networkParameters);
            slotTx.setDataClassName("SlotData");
            slotTx.setData(Json.jsonmapper().writeValueAsBytes(sd));
            b.addTransaction(slotTx);
        }
        return b;
    }

    @Test
    public void testDescendsFromRewardChain() throws Exception {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        // main chain: genesis <- b1 <- b2 <- b3
        Block b1 = makeBeacon(genesis, genesis.getHash(), 1, 1L);
        store.put(b1);
        Block b2 = makeBeacon(b1, b1.getHash(), 2, 2L);
        store.put(b2);
        Block b3 = makeBeacon(b2, b2.getHash(), 3, 3L);
        store.put(b3);
        // competing fork: genesis <- s1
        Block s1 = makeBeacon(genesis, genesis.getHash(), 1, 4L);
        store.put(s1);

        assertTrue(casperService.descendsFrom(b2.getHash(), genesis.getHash(), store),
                "main chain descends from genesis");
        assertTrue(casperService.descendsFrom(b3.getHash(), b1.getHash(), store),
                "b3 descends from b1");
        assertTrue(casperService.descendsFrom(b1.getHash(), b1.getHash(), store),
                "start == anchor is true");
        assertFalse(casperService.descendsFrom(s1.getHash(), b1.getHash(), store),
                "fork does not descend from main-chain b1");
        assertFalse(casperService.descendsFrom(b1.getHash(), b2.getHash(), store),
                "anchor ahead of start: chainlength bound stops the walk");
        assertFalse(casperService.descendsFrom(
                Sha256Hash.of("not-in-store".getBytes()), genesis.getHash(), store),
                "unstored start hash must return false (callers pass the stored prevRewardHash)");
    }

    @Test
    public void testLmdSameHeadRevoteDoesNotDoubleCount() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        Sha256Hash headA = Sha256Hash.of("headA".getBytes());
        Sha256Hash headB = Sha256Hash.of("headB".getBytes());
        long stake = StakeService.MIN_STAKE.longValue();

        // The confirmed head is commonly unchanged between slots: re-votes for
        // the same head must be idempotent, not additive.
        ghostService.processAttestation(vote(1, headA), store);
        ghostService.processAttestation(vote(2, headA), store);
        ghostService.processAttestation(vote(3, headA), store);
        assertEquals(stake, ghostService.getForkChoiceVotes().get(headA).longValue(),
                "re-votes for the same head must not accumulate weight");

        // Switching the head retracts the old weight completely.
        ghostService.processAttestation(vote(4, headB), store);
        assertNull(ghostService.getForkChoiceVotes().get(headA),
                "retracted head must lose the weight");
        assertEquals(stake, ghostService.getForkChoiceVotes().get(headB).longValue());

        // Persisted state agrees with memory (one latest row per validator).
        Map<Sha256Hash, Long> summed = store.getSummedAttestationVotes();
        assertNull(summed.get(headA), "retracted vote must be deleted from the store");
        assertEquals(stake, summed.get(headB).longValue());
    }

    private AttestationData vote(long slot, Sha256Hash head) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(slot / 32);
        att.setSourceEpoch(0);
        att.setTargetEpoch(slot / 32);
        att.setBeaconBlockHash(head);
        att.setValidatorPubkey(validatorKey.getPubKey());
        return att;
    }

    @Test
    public void testEpochStartClassificationIsSlotBased() throws Exception {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

        // Missed slots make the chainlength lag the slot. Epoch-start must
        // follow the signed slot (slot % 32 == 0), not the chainlength.
        Block drifted = makeBeacon(genesis, genesis.getHash(), 5, 64L);
        RewardInfo ri = new RewardInfo().parseChecked(drifted.getTransactions().get(0).getData());
        assertTrue(SlotService.isEpochStartBeacon(drifted, ri),
                "slot % 32 == 0 with drifted chainlength must still be epoch-start");

        Block midEpoch = makeBeacon(genesis, genesis.getHash(), 33, 65L);
        RewardInfo ri2 = new RewardInfo().parseChecked(midEpoch.getTransactions().get(0).getData());
        assertFalse(SlotService.isEpochStartBeacon(midEpoch, ri2),
                "slot % 32 != 0 is mid-epoch even at chainlength % 32 == 1");

        // Legacy beacons without SlotData keep the chainlength classification.
        Block legacyStart = makeBeacon(genesis, genesis.getHash(), 33, null);
        RewardInfo ri3 = new RewardInfo().parseChecked(legacyStart.getTransactions().get(0).getData());
        assertTrue(SlotService.isEpochStartBeacon(legacyStart, ri3),
                "legacy fallback: chainlength % 32 == 1");
        Block legacyMid = makeBeacon(genesis, genesis.getHash(), 32, null);
        RewardInfo ri4 = new RewardInfo().parseChecked(legacyMid.getTransactions().get(0).getData());
        assertFalse(SlotService.isEpochStartBeacon(legacyMid, ri4));
    }

    @Test
    public void testSlotSequenceToleratesMissedSlots() {
        // Regression: after a missed slot the chainlength lags the slot; the
        // epoch-crossing beacon must remain valid (the old slot==chainlength
        // binding rejected it and halted the chain).
        assertTrue(SlotService.slotSequenceValid(32, 1, 30),
                "missed slot before the boundary must not invalidate the epoch-start beacon");
        assertTrue(SlotService.slotSequenceValid(5, 0, -1),
                "no prev SlotData (legacy/genesis): only self-consistency");
        assertFalse(SlotService.slotSequenceValid(32, 0, 30),
                "epoch must equal slot/32");
        assertFalse(SlotService.slotSequenceValid(30, 0, 30),
                "slots must strictly increase along the reward chain");
        assertFalse(SlotService.slotSequenceValid(29, 0, 30),
                "a slot at/below the prev beacon's slot is rejected");
    }

    // ========= Epoch reward split tests =========

    private String rewardAddress(PQKey key) {
        return net.bigtangle.core.Address
                .fromHash160(networkParameters, Utils.sha256hash160(key.getPubKey())).toBase58();
    }

    @Test
    public void testEpochRewardSplitPlan() {
        PQKey v1 = PQKey.createNew();
        PQKey v2 = PQKey.createNew();
        PQKey v3 = PQKey.createNew();
        List<StakeRecord> vals = List.of(
                new StakeRecord(v1.getPubKey(), BigInteger.valueOf(100), null),
                new StakeRecord(v2.getPubKey(), BigInteger.valueOf(200), null),
                new StakeRecord(v3.getPubKey(), BigInteger.valueOf(300), null));

        Map<String, BigInteger> plan = net.bigtangle.server.service.EpochRewardService
                .planEpochRewards(BigInteger.valueOf(1000), vals, null, networkParameters);

        assertEquals(BigInteger.valueOf(166), plan.get(rewardAddress(v1)), "pro-rata share 100/600");
        assertEquals(BigInteger.valueOf(333), plan.get(rewardAddress(v2)), "pro-rata share 200/600");
        assertEquals(BigInteger.valueOf(501), plan.get(rewardAddress(v3)),
                "last validator receives the rounding remainder");
        assertEquals(BigInteger.valueOf(1000),
                plan.values().stream().reduce(BigInteger.ZERO, BigInteger::add),
                "the split must conserve the pool exactly");

        // Determinism: same inputs -> identical plan (proposer and validators agree).
        assertEquals(plan, net.bigtangle.server.service.EpochRewardService
                .planEpochRewards(BigInteger.valueOf(1000), vals, null, networkParameters));

        // Theft detection: moving a single unit to another validator breaks equality.
        Map<String, BigInteger> stolen = new java.util.LinkedHashMap<>(plan);
        stolen.put(rewardAddress(v1), plan.get(rewardAddress(v1)).add(BigInteger.ONE));
        stolen.put(rewardAddress(v3), plan.get(rewardAddress(v3)).subtract(BigInteger.ONE));
        assertNotEquals(plan, stolen, "a manipulated split must differ from the plan");

        // Empty/zero cases.
        assertTrue(net.bigtangle.server.service.EpochRewardService
                .planEpochRewards(BigInteger.ZERO, vals, null, networkParameters).isEmpty());
        assertTrue(net.bigtangle.server.service.EpochRewardService
                .planEpochRewards(BigInteger.valueOf(1000), List.of(), null, networkParameters).isEmpty());

        // Voter filter: only validators in the voters set are paid.
        java.util.Set<String> voters = java.util.Set.of(Utils.HEX.encode(v1.getPubKey()));
        Map<String, BigInteger> filtered = net.bigtangle.server.service.EpochRewardService
                .planEpochRewards(BigInteger.valueOf(1000), vals, voters, networkParameters);
        assertEquals(1, filtered.size(), "only the voting validator is rewarded");
        assertEquals(BigInteger.valueOf(1000), filtered.get(rewardAddress(v1)),
                "the sole voter receives the entire pool");
        assertNull(filtered.get(rewardAddress(v2)), "non-voter receives nothing");
    }

    // ========= Finality accounting tests =========

    private AttestationData signedVote(long slot, long sourceEpoch, long targetEpoch,
            Sha256Hash source, Sha256Hash target) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(slot / 32);
        att.setSourceEpoch(sourceEpoch);
        att.setTargetEpoch(targetEpoch);
        att.setBeaconBlockHash(target);
        att.setSourceCheckpoint(source);
        att.setTargetCheckpoint(target);
        att.setValidatorPubkey(validatorKey.getPubKey());
        signAttestation(validatorKey, att);
        return att;
    }

    private void registerValidator() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);
    }

    @Test
    public void testLateVoteStillCountsForEarlierEpoch() throws Exception {
        registerValidator();
        // Drive off the LIVE Casper state: checkpoint objects are singletons
        // shared across test methods, so epochs are chosen relative to the
        // current finalized anchor and the ACTUAL planted hashes are used.
        CasperService.Checkpoint base = casperService.getLastFinalizedCheckpoint();
        assertNotNull(base);
        long e1 = base.getEpoch() + 1;
        long e2 = base.getEpoch() + 2;
        CasperService.Checkpoint cp1 = casperService.ensureCheckpoint(e1,
                Sha256Hash.of(("lateVote1-" + e1).getBytes()));
        CasperService.Checkpoint cp2 = casperService.ensureCheckpoint(e2,
                Sha256Hash.of(("lateVote2-" + e2).getBytes()));

        // Vote for epoch e1, THEN for epoch e2. With latest-vote-only accounting
        // the second vote would erase the first; per-epoch records must keep it.
        casperService.processVote(signedVote(e1 * 32, base.getEpoch(), e1, base.getBlockHash(),
                cp1.getBlockHash()), store);
        casperService.processVote(signedVote(e2 * 32, e1, e2, cp1.getBlockHash(),
                cp2.getBlockHash()), store);

        casperService.finalizeCheckpoint(e1, store);
        assertTrue(casperService.isCheckpointJustified(e1),
                "the epoch-e1 vote must still count after the validator voted in epoch e2");
    }

    @Test
    public void testFinalizationResumesAfterSkippedEpoch() throws Exception {
        registerValidator();
        // Leakage-proof: all epochs are relative to the current finalized
        // anchor and all votes use the ACTUAL checkpoint hashes (a previous
        // test's ensureCheckpoint for the same epoch is a no-op).
        CasperService.Checkpoint base = casperService.getLastFinalizedCheckpoint();
        assertNotNull(base);
        long e1 = base.getEpoch() + 1, e3 = base.getEpoch() + 3, e4 = base.getEpoch() + 4;
        CasperService.Checkpoint cp1 = casperService.ensureCheckpoint(e1,
                Sha256Hash.of(("resume1-" + e1).getBytes()));
        CasperService.Checkpoint cp3 = casperService.ensureCheckpoint(e3,
                Sha256Hash.of(("resume3-" + e3).getBytes()));
        CasperService.Checkpoint cp4 = casperService.ensureCheckpoint(e4,
                Sha256Hash.of(("resume4-" + e4).getBytes()));

        // Epoch e1 justifies and finalizes (parent = finalized base).
        casperService.processVote(signedVote(e1 * 32, base.getEpoch(), e1, base.getBlockHash(),
                cp1.getBlockHash()), store);
        casperService.finalizeCheckpoint(e1, store);
        assertTrue(casperService.isCheckpointFinalized(e1));

        // Epoch e2 NEVER justifies (no votes, no checkpoint). Epoch e3
        // justifies (source = e1), but its parent (epoch e2) is not finalized:
        // with a strict finalized-parent rule, finalization would stall here.
        casperService.processVote(signedVote(e3 * 32, e1, e3, cp1.getBlockHash(),
                cp3.getBlockHash()), store);
        casperService.finalizeCheckpoint(e3, store);
        assertTrue(casperService.isCheckpointJustified(e3));
        assertFalse(casperService.isCheckpointFinalized(e3),
                "epoch e3 cannot finalize while its parent never justified");

        // Epoch e4 justifies with source = epoch e3: the consecutive-epoch
        // supermajority link finalizes epoch e3, and epoch e4 follows.
        casperService.processVote(signedVote(e4 * 32, e3, e4, cp3.getBlockHash(),
                cp4.getBlockHash()), store);
        casperService.finalizeCheckpoint(e4, store);
        assertTrue(casperService.isCheckpointJustified(e4));
        assertTrue(casperService.isCheckpointFinalized(e3),
                "the consecutive link must finalize the justified parent (recovery)");
        assertTrue(casperService.isCheckpointFinalized(e4),
                "finality resumes after a skipped epoch");
    }

    @Test
    public void testRejectsMalformedVotes() throws Exception {
        registerValidator();
        long slot = slotService.getCurrentSlot();
        CasperService.Checkpoint source = casperService.getJustifiedCheckpoint();
        assertNotNull(source);
        Sha256Hash target = Sha256Hash.of("malformedTarget".getBytes());

        // epoch field inconsistent with the slot
        AttestationData bad1 = signedVote(slot, 0, slot / 32, source.getBlockHash(), target);
        bad1.setEpoch(slot / 32 + 7);
        signAttestation(validatorKey, bad1);
        casperService.processVote(bad1, store);
        assertTrue(store.getSummedAttestationVotes().isEmpty(),
                "epoch-inconsistent vote must be rejected");

        // far-future target epoch
        long futureSlot = slot + 32 * 10_000L;
        AttestationData bad2 = signedVote(futureSlot, 0, futureSlot / 32, source.getBlockHash(), target);
        casperService.processVote(bad2, store);
        assertTrue(store.getSummedAttestationVotes().isEmpty(),
                "far-future vote must be rejected");
    }

    // ========= Validator snapshot robustness =========

    @Test
    public void testEmptyValidatorSnapshotDoesNotBrickEpoch() throws Exception {
        // Nothing is frozen when the active set is empty at the boundary.
        SlotService.snapshotValidatorsForEpoch(7, store);
        assertNull(SlotService.getValidatorSnapshot(7, store),
                "an empty active set must never be snapshotted");

        // A pre-existing EMPTY snapshot (legacy/broken state) must be treated as
        // missing: selection falls back to the live set instead of finding no
        // proposer for every slot of the epoch.
        store.savePosState("posvalidators", "validators_8", new byte[0]);
        registerValidator();
        List<StakeRecord> sel = SlotService.selectionValidators(320, store); // 320/32 - 2 = 8
        assertFalse(sel.isEmpty(), "empty snapshot must fall back to the live set");
        assertTrue(Arrays.equals(sel.get(0).getPubkey(), validatorKey.getPubKey()));
    }

    // ========= Withdrawal processing =========

    @Test
    public void testProcessWithdrawals() throws Exception {
        registerValidator();
        store.updateStakeExit(validatorKey.getPubKey(), 5);

        stakeService.processWithdrawals(4, store);
        assertNotNull(store.getStakeDeposit(validatorKey.getPubKey()),
                "not yet withdrawable before the withdrawable epoch");

        stakeService.processWithdrawals(5, store);
        assertNull(store.getStakeDeposit(validatorKey.getPubKey()),
                "bond released once the chain reaches the withdrawable epoch");
    }

    // ========= Proposal equivocation slashing =========

    private Block makeSignedBeacon(Block prev, Sha256Hash prevRewardHash, long chainlength, long slot,
            Sha256Hash parentHash) throws Exception {
        return makeSignedBeaconWithKey(prev, prevRewardHash, chainlength, slot, parentHash, validatorKey);
    }

    private Block makeSignedBeaconWithKey(Block prev, Sha256Hash prevRewardHash, long chainlength, long slot,
            Sha256Hash parentHash, PQKey signer) throws Exception {
        Block b = Block.createBlock(networkParameters, prev, prev);
        b.setBlockType(BlockType.BLOCKTYPE_BEACON);
        Transaction rtx = new Transaction(networkParameters);
        RewardInfo ri = new RewardInfo();
        ri.setChainlength(chainlength);
        ri.setPrevRewardHash(prevRewardHash);
        ri.setBlocks(new java.util.HashSet<>());
        rtx.setData(ri.toByteArray());
        b.addTransaction(rtx);
        net.bigtangle.core.SlotData sd = new net.bigtangle.core.SlotData(slot, slot / 32, 0, parentHash);
        sd.setProposerSignature(signer.sign(sd.getMessageHash()).serialize());
        Transaction slotTx = new Transaction(networkParameters);
        slotTx.setDataClassName("SlotData");
        slotTx.setData(Json.jsonmapper().writeValueAsBytes(sd));
        b.addTransaction(slotTx);
        return b;
    }

    private void seedValidatorWithBeaconParent() throws Exception {
        StakeRecord seeded = new StakeRecord(validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash());
        seeded.setBlockHash(Sha256Hash.of("stakeblock".getBytes()));
        seeded.setTxHash(Sha256Hash.of("staketx".getBytes()));
        store.saveStakeDeposit(seeded);
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);
        makeRewardBlock();
        mcmcService.update(store);
        blockGraph.confirmDo(store);
    }

    @Test
    public void testProposalEquivocationSlashing() throws Exception {
        seedValidatorWithBeaconParent();

        long slot = 10_000L;
        net.bigtangle.core.SlotData sd1 = new net.bigtangle.core.SlotData(slot, slot / 32, 0,
                Sha256Hash.of("parentA".getBytes()));
        sd1.setProposerSignature(validatorKey.sign(sd1.getMessageHash()).serialize());
        net.bigtangle.core.SlotData sd2 = new net.bigtangle.core.SlotData(slot, slot / 32, 0,
                Sha256Hash.of("parentB".getBytes()));
        sd2.setProposerSignature(validatorKey.sign(sd2.getMessageHash()).serialize());

        assertTrue(StakeService.isProposalEquivocation(sd1, sd2, validatorKey.getPubKey()),
                "two different signed SlotDatas for one slot are equivocation");

        net.bigtangle.core.SlotData sdOtherSlot = new net.bigtangle.core.SlotData(slot + 1,
                (slot + 1) / 32, 0, Sha256Hash.of("parentA".getBytes()));
        sdOtherSlot.setProposerSignature(validatorKey.sign(sdOtherSlot.getMessageHash()).serialize());
        assertFalse(StakeService.isProposalEquivocation(sd1, sdOtherSlot, validatorKey.getPubKey()),
                "different slots are not equivocation");
        assertFalse(StakeService.isProposalEquivocation(sd1, sd1, validatorKey.getPubKey()),
                "identical content is not equivocation");
        assertFalse(StakeService.isProposalEquivocation(sd1, sd2, PQKey.createNew().getPubKey()),
                "signatures under another key prove nothing");

        stakeService.submitProposalSlashing(sd1, sd2, store);
        StakeRecord slashed = store.getStakeDeposit(validatorKey.getPubKey());
        assertTrue(slashed.isSlashed(),
                "validator must be slashed via the consensus proposal-equivocation block");
    }

    @Test
    public void testSlotSightingDetectsEquivocation() throws Exception {
        seedValidatorWithBeaconParent();
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

        long slot = 10_100L;
        Block b1 = makeSignedBeacon(genesis, genesis.getHash(), 1, slot, Sha256Hash.of("pA".getBytes()));
        store.put(b1);
        stakeService.checkSlotSightingForEquivocation(b1, store);
        assertFalse(store.getStakeDeposit(validatorKey.getPubKey()).isSlashed(),
                "a single beacon for a slot is not an equivocation");

        Block b2 = makeSignedBeacon(genesis, genesis.getHash(), 1, slot, Sha256Hash.of("pB".getBytes()));
        store.put(b2);
        stakeService.checkSlotSightingForEquivocation(b2, store);
        assertTrue(store.getStakeDeposit(validatorKey.getPubKey()).isSlashed(),
                "a second, different beacon for the same slot must slash the proposer");
    }

    @Test
    public void testSightingIgnoresForgedBeaconsAndCaps() throws Exception {
        seedValidatorWithBeaconParent();
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

        // A beacon whose SlotData is signed by a NON-elected key is never recorded.
        long forgedSlot = 20_100L;
        PQKey wrongKey = PQKey.createNew();
        Block forged = makeSignedBeaconWithKey(genesis, genesis.getHash(), 1, forgedSlot,
                Sha256Hash.of("fp".getBytes()), wrongKey);
        store.put(forged);
        stakeService.checkSlotSightingForEquivocation(forged, store);
        assertNull(store.getPosState("pos", "slotsight_" + forgedSlot),
                "a beacon not signed by the elected proposer must never be recorded");

        // Sightings are authenticated against the ACTIVE set: after the
        // equivocation (2nd beacon) slashes the proposer, it is no longer an
        // active validator, so later beacons signed by it are ignored.
        long capSlot = 20_200L;
        for (int i = 1; i <= 5; i++) {
            Block b = makeSignedBeacon(genesis, genesis.getHash(), 1, capSlot,
                    Sha256Hash.of(("cap" + i).getBytes()));
            store.put(b);
            stakeService.checkSlotSightingForEquivocation(b, store);
        }
        String row = new String(store.getPosState("pos", "slotsight_" + capSlot),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2, row.split(",").length,
                "sightings stop once the equivocating proposer is slashed (out of the active set)");
        assertTrue(store.getStakeDeposit(validatorKey.getPubKey()).isSlashed(),
                "the equivocation was detected and slashed");
    }

    // ========= Slashing-protection (duty) tests =========

    @Test
    public void testDutyProtectionAcrossRestart() throws Exception {
        seedValidatorWithBeaconParent();
        validatorDutyService.setValidatorKey(validatorKey);

        validatorDutyService.performDuty();
        long slot = slotService.getCurrentSlot();

        // After duty ran, re-proposing and conflicting re-attestation are refused.
        assertFalse(validatorDutyService.mayPropose(slot), "no second proposal for the same slot");
        assertFalse(validatorDutyService.mayPropose(slot - 1), "no proposal for an older slot");
        assertTrue(validatorDutyService.mayPropose(slot + 32), "a future slot is fine");

        // Simulate a restart: in-memory duty state is reloaded from pos_state.
        validatorDutyService.restoreDutyState();

        assertFalse(validatorDutyService.mayPropose(slot),
                "a restarted validator must not re-propose the same slot (self-slash)");

        Sha256Hash head = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
        Sha256Hash targetCkpt = casperService.ensureCheckpoint(slot / 32, store).getBlockHash();
        assertTrue(validatorDutyService.mayAttest(slot, head, targetCkpt),
                "byte-identical re-vote is safe");
        assertFalse(validatorDutyService.mayAttest(slot, Sha256Hash.of("movedHead".getBytes()), targetCkpt),
                "a restarted validator must not re-attest with a different head (double vote)");
        assertFalse(validatorDutyService.mayAttest(slot - 1, head, targetCkpt),
                "stale slot attestation refused");
    }

    @Test
    public void testSlashingEvidenceSurvivesRestart() {
        AttestationData a1 = new AttestationData();
        a1.setSlot(5);
        a1.setValidatorPubkey(validatorKey.getPubKey());
        a1.setBeaconBlockHash(Sha256Hash.of("headA".getBytes()));
        signAttestation(validatorKey, a1);

        slashingService.checkDoubleVote(a1);

        // Simulate a restart: in-memory history is rebuilt from persisted rows.
        slashingService.restoreState();

        AttestationData a2 = new AttestationData();
        a2.setSlot(5);
        a2.setValidatorPubkey(validatorKey.getPubKey());
        a2.setBeaconBlockHash(Sha256Hash.of("headB".getBytes()));
        signAttestation(validatorKey, a2);

        assertNotNull(slashingService.checkDoubleVote(a2),
                "a double vote is still detected after a restart");
    }

    // ========= Inactivity leak =========

    private void registerValidator(PQKey key) throws Exception {
        store.saveStakeDeposit(new StakeRecord(key.getPubKey(), StakeService.MIN_STAKE,
                key.getPubKeyHash()));
        stakeService.activateValidator(key.getPubKey(), 0, store);
    }

    private AttestationData signedVoteFor(PQKey key, long slot, long sourceEpoch, long targetEpoch,
            Sha256Hash source, Sha256Hash target) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(slot / 32);
        att.setSourceEpoch(sourceEpoch);
        att.setTargetEpoch(targetEpoch);
        att.setBeaconBlockHash(target);
        att.setSourceCheckpoint(source);
        att.setTargetCheckpoint(target);
        att.setValidatorPubkey(key.getPubKey());
        signAttestation(key, att);
        return att;
    }

    @Test
    public void testFinalityRequiresTwoThirdsOfTotalStake() throws Exception {
        // Four validators with equal stake. Justification requires 2/3 of the
        // TOTAL active stake (Ethereum's rule), so once half the validators go
        // offline the remaining half can never justify — regardless of how many
        // epochs it keeps voting. (A real inactivity leak, which PENALIZES the
        // offline validators, is a separate follow-up.)
        PQKey v1 = PQKey.createNew();
        PQKey v2 = PQKey.createNew();
        PQKey v3 = PQKey.createNew();
        PQKey v4 = PQKey.createNew();
        for (PQKey k : List.of(v1, v2, v3, v4)) {
            registerValidator(k);
        }

        CasperService.Checkpoint base = casperService.getLastFinalizedCheckpoint();
        assertNotNull(base);
        long e1 = base.getEpoch() + 1;
        long e2 = e1 + 1;
        CasperService.Checkpoint cp1 = casperService.ensureCheckpoint(e1,
                Sha256Hash.of(("leak1-" + e1).getBytes()));
        CasperService.Checkpoint cp2 = casperService.ensureCheckpoint(e2,
                Sha256Hash.of(("leak2-" + e2).getBytes()));

        // Healthy network: all four vote for e1 -> full-stake 2/3 justification.
        for (PQKey k : List.of(v1, v2, v3, v4)) {
            casperService.processVote(
                    signedVoteFor(k, e1 * 32, base.getEpoch(), e1, base.getBlockHash(),
                            cp1.getBlockHash()),
                    store);
        }
        casperService.finalizeCheckpoint(e1, store);
        assertTrue(casperService.isCheckpointJustified(e1), "all validators voting justifies");
        assertTrue(casperService.isCheckpointFinalized(e1));

        // v3 + v4 go offline: only half the stake votes for e2. This must STALL.
        casperService.processVote(signedVoteFor(v1, e2 * 32, e1, e2, cp1.getBlockHash(),
                cp2.getBlockHash()), store);
        casperService.processVote(signedVoteFor(v2, e2 * 32, e1, e2, cp1.getBlockHash(),
                cp2.getBlockHash()), store);
        casperService.finalizeCheckpoint(e2, store);
        assertFalse(casperService.isCheckpointJustified(e2),
                "half the stake cannot justify");

        // The online validators keep voting for many epochs. With a pure 2/3-of-
        // total threshold the offline half never falls out of the denominator,
        // so justification must stay stalled.
        for (long e = e2 + 1; e <= e2 + CasperService.INACTIVITY_WINDOW_EPOCHS + 1; e++) {
            casperService.processVote(signedVoteFor(v1, e * 32, e1, e, cp1.getBlockHash(),
                    Sha256Hash.of(("leakT1-" + e).getBytes())), store);
            casperService.processVote(signedVoteFor(v2, e * 32, e1, e, cp1.getBlockHash(),
                    Sha256Hash.of(("leakT2-" + e).getBytes())), store);
        }
        casperService.finalizeCheckpoint(e2, store);
        assertFalse(casperService.isCheckpointJustified(e2),
                "2/3 of TOTAL stake is required to justify; half cannot, even after many epochs");
    }

    @Test
    public void testInactivityLeakRestoresFinality() throws Exception {
        // Four validators with equal stake. Once finality stalls past the
        // penalty threshold, validators that stopped voting are drained from the
        // denominator (inactivity leak), so the online half regains 2/3 of the
        // reduced total and finality resumes.
        PQKey v1 = PQKey.createNew();
        PQKey v2 = PQKey.createNew();
        PQKey v3 = PQKey.createNew();
        PQKey v4 = PQKey.createNew();
        for (PQKey k : List.of(v1, v2, v3, v4)) {
            registerValidator(k);
        }

        CasperService.Checkpoint base = casperService.getLastFinalizedCheckpoint();
        assertNotNull(base);
        long e1 = base.getEpoch() + 1;
        CasperService.Checkpoint cp1 = casperService.ensureCheckpoint(e1,
                Sha256Hash.of(("leak1-" + e1).getBytes()));

        // Healthy: all four vote e1 -> justify + finalize.
        for (PQKey k : List.of(v1, v2, v3, v4)) {
            casperService.processVote(
                    signedVoteFor(k, e1 * 32, base.getEpoch(), e1, base.getBlockHash(), cp1.getBlockHash()),
                    store);
        }
        casperService.finalizeCheckpoint(e1, store);
        assertTrue(casperService.isCheckpointJustified(e1), "all validators voting justifies");
        assertTrue(casperService.isCheckpointFinalized(e1));

        // v3 + v4 go offline; v1 + v2 keep voting. Justification must resume
        // once the leak drains the offline half out of the denominator.
        boolean resumed = false;
        for (long e = e1 + 1; e <= e1 + CasperService.INACTIVITY_WINDOW_EPOCHS + 3; e++) {
            CasperService.Checkpoint cp = casperService.ensureCheckpoint(e,
                    Sha256Hash.of(("leak" + e).getBytes()));
            casperService.processVote(signedVoteFor(v1, e * 32, e1, e, cp1.getBlockHash(),
                    cp.getBlockHash()), store);
            casperService.processVote(signedVoteFor(v2, e * 32, e1, e, cp1.getBlockHash(),
                    cp.getBlockHash()), store);
            casperService.finalizeCheckpoint(e, store);
            if (casperService.isCheckpointJustified(e)) {
                resumed = true;
                break;
            }
        }
        assertTrue(resumed,
                "finality resumes once offline stake is drained by the inactivity leak");
    }
}
