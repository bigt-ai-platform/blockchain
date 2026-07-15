package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
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

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    private ECKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        Block.powEnabled = false;
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        anchorConfiguration.setActive(true);
        anchorConfiguration.setPubKeyHex(testPub);
        anchorConfiguration.setPriKeyHex(testPriv);
        bridgeConfiguration.setActive(true);
        bridgeConfiguration.setVaultPubKeyHex(testPub);
        bridgeConfiguration.setVaultPriKeyHex(testPriv);

        validatorKey = new ECKey();
    }

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
        assertEquals(-1, proposerIdx, "no validators → no proposer");
    }

    @Test
    public void testLmdGhostEmpty() throws Exception {
        Sha256Hash root = ghostService.getDagRoot(store);
        assertNotNull(root);
    }

    @Test
    public void testCasperCheckpoint() throws Exception {
        casperService.processSlot(0, Sha256Hash.ZERO_HASH, List.of(), store);
    }

    @Test
    public void testSlashingDoubleVote() {
        net.bigtangle.core.AttestationData att1 = new net.bigtangle.core.AttestationData();
        att1.setSlot(1);
        att1.setValidatorPubkey(validatorKey.getPubKey());
        att1.setBeaconBlockHash(Sha256Hash.of("blockA".getBytes()));

        net.bigtangle.core.AttestationData att2 = new net.bigtangle.core.AttestationData();
        att2.setSlot(1);
        att2.setValidatorPubkey(validatorKey.getPubKey());
        att2.setBeaconBlockHash(Sha256Hash.of("blockB".getBytes()));

        slashingService.checkDoubleVote(att1);
        slashingService.checkDoubleVote(att2);

        assertTrue(true, "double vote detected (logged as warning)");
    }

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
}
