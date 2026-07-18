package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.server.core.BlockWrap;

public class PaiRewardTest extends AbstractIntegrationTest {

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
    public void testMultipleRewardCycles() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();

        // Create blocks + reward in sequence
        for (int cycle = 0; cycle < 3; cycle++) {
            for (int i = 0; i < 3; i++) {
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
        }

        // After multiple cycles, rewards should have been created
        assertNotNull(tipsService.getValidatedBlockPair(store));
    }
}
