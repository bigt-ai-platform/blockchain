package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;

public class PaiFullFlowTest extends AbstractIntegrationTest {

    @Test
    public void testChainWithMultipleBlocks() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
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
    public void testConsecutiveBlockTimestamps() throws Exception {
        List<Block> addedBlocks = new ArrayList<>();
        Block prev = tipsService.getValidatedBlockPair(store).getLeft().getBlock();

        for (int i = 0; i < 20; i++) {
            Block b = Block.createBlock(networkParameters, prev, prev);
            b.setTime(prev.getTimeSeconds() + 1);
            b.solve();
            blockGraph.addBlock(b, true, store);
            addedBlocks.add(b);
            prev = b;
        }

        mcmcService.update(store);
        assertNotNull(tipsService.getValidatedBlockPair(store));
    }
}
