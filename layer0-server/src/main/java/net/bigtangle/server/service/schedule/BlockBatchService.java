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
import net.bigtangle.store.BlockStoreInterface;
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

    @Autowired
    private net.bigtangle.server.service.ValidatorDutyService validatorDutyService;

    @Autowired
    private net.bigtangle.server.service.StoreService storeService;

    @Scheduled(fixedDelayString = "${service.schedule.blockbatchrate:50000}")
    public void batch() {
        if (!scheduleConfiguration.isBlockBatchService_active() || !serverConfiguration.checkService()) {
            return;
        }
        // SINGLE-BUILDER GATE: publish queued batches only while this node
        // holds proposal rights. Every node's mempool receives every tx via
        // kafka; ungated publishers wrap identical spends into competing
        // blocks that conflict pairwise and starve each other forever.
        if (!builderGateWithWarmup()) {
            return;
        }
        startSingleProcess();
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
        // SINGLE-BUILDER GATE (see batch()): only the current proposer drains
        // the mempool into batch blocks. Other nodes keep their mempools until
        // their turn — they must NOT wrap the same spends into competing
        // blocks.
        if (!builderGateWithWarmup()) {
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
            if (connectQueueSaturated()) {
                logger.debug("Micro-batch paused: chain-connect queue at depth {}", maxConnectQueueDepth);
                return;
            }
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
    /**
     * INGEST BACKPRESSURE: stop creating new batch blocks while the
     * chain-connect queue is at least this deep. Under a submit burst far
     * above the confirm rate (~390 tx/s on a 5-node mesh), batch blocks were
     * manufactured faster than beacons could reference and confirm them — a
     * million transactions piled up as unconfirmed DAG weight, block rates
     * halved, and the epoch tick starved behind connection contention.
     * Pausing the PRODUCER (not the chain) lets confirmation catch up; the
     * mempool absorbs the difference up to server.mempoolMaxTx and further
     * submits are rejected with MempoolFullException. Tune via
     * batch.maxConnectQueueDepth (0 disables).
     */
    private static final int maxConnectQueueDepth = Integer.getInteger("batch.maxConnectQueueDepth", 2000);
    private volatile long lastDrain = System.currentTimeMillis();

    /** True when the local chain-connect backlog reached the pause threshold. */
    private boolean connectQueueSaturated() {
        if (maxConnectQueueDepth <= 0) {
            return false;
        }
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            return store.selectChainblockqueue(false, maxConnectQueueDepth).size() >= maxConnectQueueDepth;
        } catch (Exception e) {
            // Advisory guard only: on lookup failure keep producing rather
            // than stall the pipeline because the probe itself failed.
            return false;
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    protected final ReentrantLock lock = Threading.lock("BlockBatchService");

    /**
     * Cheap memo for the builder gate: the election answer is stable within a
     * slot, and the gate runs on a 100 ms tick. Recomputed whenever the slot
     * rolls over (or on first use).
     */
    private volatile long gatedSlot = Long.MIN_VALUE;
    private volatile boolean gatedAnswer;

    private boolean builderGateWithWarmup() {
        try {
            long slot = validatorDutyService.getCurrentSlotPublic();
            if (slot != gatedSlot) {
                gatedAnswer = validatorDutyService.isCurrentBlockBuilder();
                gatedSlot = slot;
            }
            return gatedAnswer;
        } catch (Exception e) {
            // Fail-open would resurrect competing builders; fail-closed just
            // delays batching by one tick.
            return false;
        }
    }

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
