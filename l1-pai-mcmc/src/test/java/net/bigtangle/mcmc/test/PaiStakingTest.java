package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.UTXO;
import net.bigtangle.server.core.BlockWrap;

public class PaiStakingTest extends AbstractIntegrationTest {

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
}
