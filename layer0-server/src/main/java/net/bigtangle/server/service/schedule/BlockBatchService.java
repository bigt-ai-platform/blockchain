/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.utils.Threading;

@Component
@EnableAsync
public class BlockBatchService {

    private static final Logger logger = LoggerFactory.getLogger(BlockBatchService.class);

    @Autowired
    private BlockSaveService blockSaveService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    ServerConfiguration serverConfiguration;

    @Autowired
    private MempoolService mempoolService;

    @Scheduled(fixedDelayString = "${service.schedule.blockbatchrate:50000}")
    public void batch() {
        if (scheduleConfiguration.isBlockBatchService_active() && serverConfiguration.checkService()) {
            startSingleProcess();
        }
    }

    @Async
    @Scheduled(fixedDelayString = "${service.schedule.microbatchrate:100}")
    public void microBatch() {
        if (!scheduleConfiguration.isMicroBatch_active() || !serverConfiguration.checkService()) {
            return;
        }
        int size = mempoolService.size();
        if (size == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // Accumulate txs into FEWER, LARGER batch blocks instead of creating a
        // tiny DAG block per 100 ms tick. A huge DAG of 1-10 tx blocks chokes
        // the beacon's block-reference walk (dagBlockHashesFrom) and confirmation
        // collapses under load. Drain when the mempool reaches minBatchTx OR the
        // oldest pending tx has waited maxBatchAgeMs (so a low trickle still
        // batches within one slot). Tune via batch.minTx / batch.maxBatchAgeMs.
        if (size < minBatchTx && (now - lastDrain) < maxBatchAgeMs) {
            return;
        }
        // One drain at a time: each batchBlocksFromMempool run validates and
        // writes multi-thousand-tx blocks; overlapping 100ms ticks stack those
        // multi-second jobs on the DB exactly when ingest already saturates it
        // (observed as 10s mempool->block lag and a whole slot lost).
        if (!lock.tryLock()) {
            return;
        }
        lastDrain = now;
        try {
            int batched = blockSaveService.batchBlocksFromMempool();
            if (batched > 0) {
                logger.debug("Micro-batched {} transactions", batched);
            }
        } catch (Exception e) {
            logger.debug("Micro-batch error", e);
        } finally {
            lock.unlock();
        }
    }

    /** Min mempool size before the micro-batch drains it into a block. */
    private static final int minBatchTx = Integer.getInteger("batch.minTx", 2000);
    /** Max age of the oldest pending tx before the micro-batch force-drains. */
    private static final long maxBatchAgeMs = Long.getLong("batch.maxBatchAgeMs", 2000);
    private volatile long lastDrain = System.currentTimeMillis();

    protected final ReentrantLock lock = Threading.lock("BlockBatchService");

    public void startSingleProcess() {
        if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
            logger.debug(this.getClass().getName() + "  Update already running. Returning...");
            return;
        }

        logger.info("BlockBatchService start");
        try {
            blockSaveService.batchBlocks();
        } catch (Exception e) {
            logger.info("BlockBatchService error", e);
        } finally {
            lock.unlock();
        }
    }
}
