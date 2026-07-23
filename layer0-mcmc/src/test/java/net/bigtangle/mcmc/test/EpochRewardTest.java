package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.service.EpochRewardService;
import net.bigtangle.server.service.StakeService;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true" })
public class EpochRewardTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EpochRewardTest.class);

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired
    protected EpochRewardService epochRewardService;

    @Autowired
    protected StakeService stakeService;

    private PQKey validator1;
    private PQKey validator2;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        validator1 = PQKey.createNew();
    }

    @Test
    public void testEpochRewardDistribution() throws Exception {
        log.info("=== Test: Epoch reward distribution ===");

        // Fund and register two validators with different stakes
        fundAndStake(validator1, StakeService.MIN_STAKE);
        mempoolService.drainAll();
        fundAndStake(validator2, StakeService.MIN_STAKE.multiply(BigInteger.valueOf(2)));

        stakeService.activateValidator(validator1.getPubKey(), 0, store);
        stakeService.activateValidator(validator2.getPubKey(), 0, store);

        BigInteger totalStake = stakeService.getTotalActiveStake(store);
        BigInteger expectedStake = StakeService.MIN_STAKE.multiply(BigInteger.valueOf(3));
        assertEquals(expectedStake, totalStake, "Total stake should be 3x MIN_STAKE");
        log.info("Validators registered: v1={}, v2={}, total={}",
                StakeService.MIN_STAKE,
                StakeService.MIN_STAKE.multiply(BigInteger.valueOf(2)),
                totalStake);

        // Distribute epoch rewards
        long epoch = 1;
        long rewardPool = 32L * 31709791L;
        Sha256Hash rewardBlockHash = epochRewardService.distributeEpochRewards(epoch,
                BigInteger.valueOf(rewardPool), store);
        assertNotNull(rewardBlockHash, "distributeEpochRewards should return a block hash");

        // Verify total stake unchanged (rewards are new UTXOs, not stake increases)
        BigInteger postRewardStake = stakeService.getTotalActiveStake(store);
        assertEquals(expectedStake, postRewardStake,
                "Stake should be unchanged after reward distribution");

        // Verify reward block
        Block rewardBlock = store.get(rewardBlockHash);
        assertNotNull(rewardBlock, "Reward block should exist in store");
        assertEquals(BlockType.BLOCKTYPE_BEACON, rewardBlock.getBlockType(),
                "Reward block must be BLOCKTYPE_BEACON");

        int bigOutputs = 0;
        long totalOutput = 0;
        for (net.bigtangle.core.Transaction tx : rewardBlock.getTransactions()) {
            for (net.bigtangle.core.TransactionOutput out : tx.getOutputs()) {
                if (java.util.Arrays.equals(
                        NetworkParameters.BIGTANGLE_TOKENID,
                        out.getValue().getTokenid())) {
                    bigOutputs++;
                    totalOutput += out.getValue().getValue().longValue();
                }
            }
        }
        assertTrue(bigOutputs >= 1, "Reward block should have BIG outputs");
        assertTrue(totalOutput <= rewardPool,
                "Total output should not exceed reward pool: " + totalOutput
                + " <= " + rewardPool);
        log.info("Reward block: {} outputs, {} total, pool={}",
                bigOutputs, totalOutput, rewardPool);

        log.info("=== TEST PASSED ===");
    }

    private void fundAndStake(PQKey key, BigInteger amount) throws Exception {
        Block fb = payBigTo(key, amount.add(BigInteger.valueOf(100000)), null);
        if (fb != null) {
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }

        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block depositBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        depositBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(depositBlock);
        store.saveStakeDeposit(new StakeRecord(key.getPubKey(), amount, null));
    }
}
