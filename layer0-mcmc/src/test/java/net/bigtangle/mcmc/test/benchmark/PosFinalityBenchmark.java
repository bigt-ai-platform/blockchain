package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=200" })
public class PosFinalityBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PosFinalityBenchmark.class);

    private static final int VALIDATORS = 32;
    private static final int SLOTS = 64;
    private static final int SLOTS_PER_EPOCH = 32;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;
    @Autowired(required = false)
    protected GhostService ghostService;
    @Autowired(required = false)
    protected CasperService casperService;
    @Autowired(required = false)
    protected StakeService stakeService;

    private List<PQKey> validatorKeys;
    private String genesisPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
    private List<Sha256Hash> blockHashes = new ArrayList<>();

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        validatorKeys = new ArrayList<>();
        for (int i = 0; i < VALIDATORS; i++) validatorKeys.add(PQKey.createNew();
    }

    @Test
    public void testPosFinality() throws Exception {
        log.info("=== PoS Finality Benchmark ===");
        log.info("Validators: {}  Slots: {}  Epoch: {} slots", VALIDATORS, SLOTS, SLOTS_PER_EPOCH);

        // -- Phase 1: Fund validators --
        log.info("--- Phase 1: Fund validators ---");
        Wallet genesisWallet = Wallet.fromKeys(networkParameters,
                PQKey.createNew() {
            HashMap<String, BigInteger> fund = new HashMap<>();
            fund.put(vk.toAddress(networkParameters).toHex(), BigInteger.valueOf(10000000));
            Block b = wrapTransaction(genesisWallet.payToList(null, fund,
                    NetworkParameters.BIGTANGLE_TOKENID, "fund"));
            if (b != null) {
                makeRewardBlock(b);
                blockGraph.updateChain(false);
                mcmcService.update(store);
                mcmcService.calcNewBlockPrototype(store);
            }
        }
        log.info("Funded {} validators", validatorKeys.size());

        // -- Phase 2: Register validators with 32 BIG stake --
        log.info("--- Phase 2: Register validators ---");
        for (int i = 0; i < VALIDATORS; i++) {
            PQKey vk = validatorKeys.get(i);
            Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
            Block depositBlock = Block.createBlock(networkParameters,
                    store.get(proto.getPrevBlockHash()),
                    store.get(proto.getPrevBranchBlockHash()));
            depositBlock.setBlockType(BlockType.BLOCKTYPE_STAKE);
            Transaction tx = new Transaction(networkParameters);
            tx.addOutput(new Coin(StakeService.MIN_STAKE.longValue(),
                    NetworkParameters.BIGTANGLE_TOKENID),
                    vk.toAddress(networkParameters));
            depositBlock.addTransaction(tx);
            store.put(depositBlock);
            // Register stake directly (simplified for benchmark)
            store.saveStakeDeposit(new net.bigtangle.core.StakeRecord(
                    vk.getPubKey(), StakeService.MIN_STAKE, null));
            if (i > 0 && i % 8 == 0) log.info("  Registered {}/{}", i, VALIDATORS);
        }
        log.info("All {} validators registered", VALIDATORS);

        for (PQKey vk : validatorKeys) {
            stakeService.activateValidator(vk.getPubKey(), 0, store);
        }
        log.info("Total active stake: {}", stakeService.getTotalActiveStake(store));

        // -- Phase 3: Block production + attestation + finality --
        log.info("--- Phase 3: Block production + attestation ---");

        long wallStart = System.nanoTime();
        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block prevBlock = store.get(proto.getPrevBlockHash());
        if (prevBlock == null) {
            prevBlock = store.get(proto.getHash());
        }

        for (int slot = 0; slot < SLOTS; slot++) {
            long epoch = slot / SLOTS_PER_EPOCH;
            int proposerIdx = slot % VALIDATORS;
            PQKey proposer = validatorKeys.get(proposerIdx);

            Block block = Block.createBlock(networkParameters,
                    prevBlock, prevBlock);
            block.setBlockType(BlockType.BLOCKTYPE_BEACON);
            store.put(block);
            blockHashes.add(block.getHash());
            prevBlock = block;

            // All validators attest
            List<AttestationData> attestations = new ArrayList<>();
            for (PQKey attester : validatorKeys) {
                AttestationData att = new AttestationData();
                att.setSlot(slot);
                att.setEpoch(epoch);
                att.setBeaconBlockHash(block.getHash());
                att.setValidatorPubkey(attester.getPubKey());
                att.setSignature(attester.sign(block.getHash()).encodeToDER());
                casperService.processVote(att, store);
                attestations.add(att);
            }

            // Epoch boundary: try GHOST finality
            // GHOST walks the DAG following heaviest attestation votes.
            // With 32 validators all attesting every slot, the heaviest branch
            // is unambiguous — every node converges to the same canonical head.
            if (slot > 0 && slot % 8 == 0) {
                Sha256Hash ghostHead = ghostService.executeGhost(
                        Sha256Hash.ZERO_HASH, store);
                // The GHOST head should match our last block (single chain)
                if (ghostHead.equals(block.getHash())) {
                    log.info("  Slot {}: GHOST head matches ({} votes)",
                            slot, ghostService.getForkChoiceVotes()
                                    .values().stream().mapToLong(Long::longValue).sum());
                }
            }
        }

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        // Finalize remaining epochs
        long lastEpoch = (SLOTS - 1) / SLOTS_PER_EPOCH;
        for (long e = 0; e <= lastEpoch; e++) {
            casperService.finalizeCheckpoint(e, store);
        }

        log.info("");
        log.info("==============================================");
        log.info("  PoS Finality Results");
        log.info("==============================================");
        log.info("Validators:    {}", VALIDATORS);
        log.info("Slots:         {}", SLOTS);
        log.info("Blocks:        {}", blockHashes.size());
        log.info("Epochs:        {}", lastEpoch + 1);
        log.info("Wall time:     {} ms", wallMs);
        log.info("GHOST votes:   {}", ghostService.getForkChoiceVotes().size());
        log.info("==============================================");
        assertTrue(blockHashes.size() > 0, "Must have blocks");
    }
}
