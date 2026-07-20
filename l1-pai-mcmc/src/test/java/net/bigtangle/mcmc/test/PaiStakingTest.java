package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.Contractresult;

public class PaiStakingTest extends AbstractIntegrationTest {

    @Test
    public void testChainSetup() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Block b = Block.createBlock(networkParameters,
                    tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                    tipsService.getValidatedBlockPair(store).getRight().getBlock());
            blockGraph.addBlock(b, true, store);
            addedBlocks.add(b);
        }
        mcmcService.update(store);
        Sha256Hash prevRewardHash = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
        Block reward = rewardService.createReward(prevRewardHash, store);
        if (reward != null) {
            blockGraph.updateChain(false);
            addedBlocks.add(reward);
        }
        assertNotNull(tipsService.getValidatedBlockPair(store));
    }

    @Test
    public void testStakeEventProcessing() throws Exception {
        Token contract = createContractTokenWithClassname(
                net.bigtangle.layer1.contract.PaiEngine.CLASSNAME_STAKING);
        String providerAddr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash prevResultHash = Sha256Hash.create("stakePrev".getBytes());

        ContractEventRecord stakeEvent = new ContractEventRecord(
                Sha256Hash.create("stakeEvt1".getBytes()), prevResultHash, contract.getTokenid(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, providerAddr);
        store.insertContractEvent(Collections.singletonList(stakeEvent));

        Contractresult prev = new Contractresult(prevResultHash, true, false, Sha256Hash.ZERO_HASH, null,
                null, contract.getTokenid(), 0, 0, System.currentTimeMillis());

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ContractExecutionResult result = executePaiContract(block, store, contract,
                prev, new HashSet<>());

        assertNotNull(result);
        assertEquals(1, result.getRemainderRecords().size());
        assertTrue(result.getRemainderRecords().contains(stakeEvent.getBlockHash()));
    }

    @Test
    public void testMultipleStakes() throws Exception {
        Token contract = createContractTokenWithClassname(
                net.bigtangle.layer1.contract.PaiEngine.CLASSNAME_STAKING);
        String providerAddr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash prevResultHash = Sha256Hash.create("multiPrev".getBytes());

        ContractEventRecord event1 = new ContractEventRecord(
                Sha256Hash.create("stk1".getBytes()), prevResultHash, contract.getTokenid(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(10).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, providerAddr);
        ContractEventRecord event2 = new ContractEventRecord(
                Sha256Hash.create("stk2".getBytes()), prevResultHash, contract.getTokenid(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(20).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, providerAddr);
        store.insertContractEvent(List.of(event1, event2));

        Contractresult prev = new Contractresult(prevResultHash, true, false, Sha256Hash.ZERO_HASH, null,
                null, contract.getTokenid(), 0, 0, System.currentTimeMillis());

        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ContractExecutionResult result = executePaiContract(block, store, contract,
                prev, new HashSet<>());

        assertNotNull(result);
        assertEquals(2, result.getRemainderRecords().size());
    }

    @Test
    public void testEmptyContractReturnsEmpty() throws Exception {
        Token contract = createContractTokenWithClassname(
                net.bigtangle.layer1.contract.PaiEngine.CLASSNAME_STAKING);
        Contractresult prev = Contractresult.firstContractresult();
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ContractExecutionResult result = executePaiContract(block, store, contract,
                prev, new HashSet<>());

        assertNotNull(result);
        assertEquals(0, result.getRemainderRecords().size());
        assertEquals(0, result.getCancelRecords().size());
    }
}
