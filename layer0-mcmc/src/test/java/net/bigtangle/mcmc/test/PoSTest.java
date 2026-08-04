package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.UTXO;
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

    private PQKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        validatorKey = PQKey.createNew();
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

        stakeService.activateValidator(v1.getPubKey(), 1, store);
        stakeService.activateValidator(v2.getPubKey(), 1, store);

        List<StakeRecord> active = store.getActiveStakeDeposits();
        assertEquals(2, active.size());

        BigInteger total = stakeService.getTotalActiveStake(store);
        assertEquals(StakeService.MIN_STAKE.multiply(BigInteger.valueOf(2)), total);
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
    public void testRandaoCommitAndReveal() throws Exception {
        byte[] commit = randaoService.commit(validatorKey, 0);
        assertNotNull(commit);
        assertEquals(32, commit.length);

        byte[] reveal = randaoService.computeReveal(validatorKey, 0);
        assertNotNull(reveal);

        randaoService.reveal(validatorKey.getPubKey(), 0, reveal);

        byte[] mix = randaoService.getRandaoMix(0);
        assertNotNull(mix);
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
        AttestationData att = new AttestationData();
        att.setSlot(System.currentTimeMillis() / 12_000L);
        att.setEpoch(0);
        att.setSourceEpoch(0);
        att.setTargetEpoch(0);
        att.setBeaconBlockHash(Sha256Hash.of("beacon1".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());
        att.setSignature(validatorKey.sign(att.getMessageHash()).serialize());

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
        att.setSignature(validatorKey.sign(att.getMessageHash()).serialize());
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
    public void testSlashingBlockConsensus() throws Exception {        // Seed a validator, then slash it via a BLOCKTYPE_SLASHING block built
        // from two authenticated conflicting attestations.
        StakeRecord seeded = new StakeRecord(validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash());
        seeded.setBlockHash(Sha256Hash.of("stakeblock".getBytes()));
        seeded.setTxHash(Sha256Hash.of("staketx".getBytes()));
        store.saveStakeDeposit(seeded);
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        AttestationData att1 = new AttestationData();
        att1.setSlot(5);
        att1.setBeaconBlockHash(Sha256Hash.of("headA".getBytes()));
        att1.setValidatorPubkey(validatorKey.getPubKey());
        att1.setSignature(validatorKey.sign(att1.getMessageHash()).serialize());

        AttestationData att2 = new AttestationData();
        att2.setSlot(5);
        att2.setBeaconBlockHash(Sha256Hash.of("headB".getBytes()));
        att2.setValidatorPubkey(validatorKey.getPubKey());
        att2.setSignature(validatorKey.sign(att2.getMessageHash()).serialize());

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
        assertTrue(exiting.getWithdrawableEpoch() > 0, "withdrawable epoch must be set");
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

        store.saveAttestationVote(blockHash, pubkey, weight);

        List<AttestationData> forSlot = store.getAttestationsForSlot(
                System.currentTimeMillis() / 12_000L);
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
}
