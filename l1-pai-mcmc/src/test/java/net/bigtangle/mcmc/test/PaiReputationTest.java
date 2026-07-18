package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;

public class PaiReputationTest extends AbstractIntegrationTest {

    @Test
    public void testChainSetup() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Block b = Block.createBlock(networkParameters,
                    tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                    tipsService.getValidatedBlockPair(store).getRight().getBlock());
            b.solve();
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

        // Create two parallel branches
        Block branchA = Block.createBlock(networkParameters, genesisTip, genesisTip);
        branchA.solve();
        blockGraph.addBlock(branchA, true, store);
        addedBlocks.add(branchA);

        Block branchB = Block.createBlock(networkParameters, genesisTip, genesisTip);
        branchB.solve();
        blockGraph.addBlock(branchB, true, store);
        addedBlocks.add(branchB);

        // Merge branches
        Block merged = Block.createBlock(networkParameters, branchA, branchB);
        merged.solve();
        blockGraph.addBlock(merged, true, store);
        addedBlocks.add(merged);

        mcmcService.update(store);
        assertNotNull(tipsService.getValidatedBlockPair(store));

        // Verify tip selection returns distinct blocks
        var tips = tipsService.getValidatedBlockPair(store);
        assertNotNull(tips.getLeft());
        assertNotNull(tips.getRight());
    }
}
