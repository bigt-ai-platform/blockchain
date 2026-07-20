package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import net.bigtangle.server.core.BlockWrap;

public class PaiReputationTest extends AbstractIntegrationTest {

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
    public void testChainWithDAGBranches() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();
        Block genesisTip = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block branchA = Block.createBlock(networkParameters, genesisTip, genesisTip);
        blockGraph.addBlock(branchA, true, store);
        addedBlocks.add(branchA);
        Block branchB = Block.createBlock(networkParameters, genesisTip, genesisTip);
        blockGraph.addBlock(branchB, true, store);
        addedBlocks.add(branchB);
        Block merged = Block.createBlock(networkParameters, branchA, branchB);
        blockGraph.addBlock(merged, true, store);
        addedBlocks.add(merged);
        mcmcService.update(store);
        assertNotNull(tipsService.getValidatedBlockPair(store));
        var tips = tipsService.getValidatedBlockPair(store);
        assertNotNull(tips.getLeft());
        assertNotNull(tips.getRight());
    }

    @Test
    public void testReputationEventProcessing() throws Exception {
        Token contract = createContractTokenWithClassname(
                net.bigtangle.layer1.contract.PaiEngine.CLASSNAME_REPUTATION);
        String providerAddr = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        Sha256Hash prevResultHash = Sha256Hash.create("repPrev".getBytes());

        ContractEventRecord event = new ContractEventRecord(
                Sha256Hash.create("repEvt1".getBytes()), prevResultHash, contract.getTokenid(),
                true, false, Sha256Hash.ZERO_HASH,
                Coin.COIN.multiply(50).getValue(), NetworkParameters.BIGTANGLE_TOKENID_STRING, providerAddr);
        store.insertContractEvent(Collections.singletonList(event));

        Contractresult prev = new Contractresult(prevResultHash, true, false, Sha256Hash.ZERO_HASH, null,
                null, contract.getTokenid(), 0, 0, System.currentTimeMillis());
        Block block = Block.createBlock(networkParameters,
                tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                tipsService.getValidatedBlockPair(store).getRight().getBlock());
        ContractExecutionResult result = executePaiContract(block, store, contract,
                prev, new HashSet<>());
        assertNotNull(result);
        assertEquals(1, result.getRemainderRecords().size());
    }
}
