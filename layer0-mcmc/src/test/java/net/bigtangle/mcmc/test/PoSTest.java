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
import net.bigtangle.core.ECKey;
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

    private ECKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        validatorKey = new ECKey();
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
        ECKey v1 = new ECKey();
        ECKey v2 = new ECKey();

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

        long noStake = stakeService.getEffectiveStake(new ECKey().getPubKey(), store);
        assertEquals(0L, noStake);
    }

    // ========= RANDAO Tests =========

    @Test
    public void testRandaoCommitAndReveal() throws Exception {
        byte[] commit = randaoService.commit(validatorKey.getPubKey(), 0);
        assertNotNull(commit);
        assertEquals(32, commit.length);

        byte[] reveal = randaoService.computeReveal(validatorKey.getPubKey(), 0);
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
        att.setBeaconBlockHash(Sha256Hash.of("beacon1".getBytes()));
        att.setValidatorPubkey(validatorKey.getPubKey());

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

        boolean r1 = slashingService.checkDoubleVote(att1);
        boolean r2 = slashingService.checkDoubleVote(att2);

        assertFalse(r1, "first vote not a double");
        assertTrue(r2, "second vote is a double");
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
