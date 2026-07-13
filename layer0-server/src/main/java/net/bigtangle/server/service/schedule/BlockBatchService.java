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
        if (mempoolService.size() == 0) {
            return;
        }
        try {
            int batched = blockSaveService.batchBlocksFromMempool();
            if (batched > 0) {
                logger.debug("Micro-batched {} transactions", batched);
            }
        } catch (Exception e) {
            logger.debug("Micro-batch error", e);
        }
    }

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
