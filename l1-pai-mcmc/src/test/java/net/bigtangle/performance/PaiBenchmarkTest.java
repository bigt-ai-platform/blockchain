package net.bigtangle.performance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import net.bigtangle.core.Block;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;

public class PaiBenchmarkTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaiBenchmarkTest.class);

    @Test
    public void testBlockSubmissionThroughput() throws Exception {
        int blockCount = 50;
        List<Block> addedBlocks = new ArrayList<>();

        Stopwatch watch = Stopwatch.createStarted();

        for (int i = 0; i < blockCount; i++) {
            Block b = Block.createBlock(networkParameters,
                    tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                    tipsService.getValidatedBlockPair(store).getRight().getBlock());
            blockGraph.addBlock(b, true, store);
            addedBlocks.add(b);
        }

        watch.stop();
        long elapsedMs = watch.elapsed(java.util.concurrent.TimeUnit.MILLISECONDS);
        double tps = blockCount / (elapsedMs / 1000.0);

        log.info("Submitted {} blocks in {} ms ({:.1f} blocks/sec)", blockCount, elapsedMs, tps);

        assertTrue(tps > 0);
        assertNotNull(tipsService.getValidatedBlockPair(store));
    }

    @Test
    public void testMCMCStress() throws Exception {
        // Build up chain
        for (int i = 0; i < 30; i++) {
            Block b = Block.createBlock(networkParameters,
                    tipsService.getValidatedBlockPair(store).getLeft().getBlock(),
                    tipsService.getValidatedBlockPair(store).getRight().getBlock());
            blockGraph.addBlock(b, true, store);
        }

        Stopwatch watch = Stopwatch.createStarted();
        mcmcService.update(store);
        watch.stop();

        long elapsedMs = watch.elapsed(java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("MCMC update on {} blocks took {} ms", 30, elapsedMs);

        assertNotNull(tipsService.getValidatedBlockPair(store));
        assertTrue(elapsedMs < 30000, "MCMC update should complete within 30s");
    }
}
