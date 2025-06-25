/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Transaction;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Threading;

@Component
@EnableAsync
public class BlockBatchService {

    private static final Logger logger = LoggerFactory.getLogger(BlockBatchService.class);

    @Autowired
    protected StoreService storeService;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private BlockService blockService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;
	@Autowired
	private BlockSaveService blockSaveService;
    @Autowired
    ServerConfiguration serverConfiguration;
	@Autowired
	protected CacheBlockPrototypeService cacheBlockPrototypeService;
    /*
     * create solved block from other not resolved blocks together as service
     */
    @Scheduled(fixedDelayString = "${service.schedule.blockbatchrate:50000}")
    public void batch() {
        if (scheduleConfiguration.isBlockBatchService_active() && serverConfiguration.checkService()) {
            startSingleProcess();
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
            batchBlocks();
        } catch (Exception e) {
            logger.info("BlockBatchService error", e);
        } finally {
            lock.unlock();
        }

    }

    private void batchBlocks() throws BlockStoreException, Exception {
        BlockStoreInterface store = storeService.getStore();
        try {
            List<BatchBlock> batchBlocks = store.getBatchBlockList();
            if (batchBlocks.isEmpty()) {
                return;
            }
            Block block = cacheBlockPrototypeService.getBlockPrototype(store);
            for (BatchBlock batchBlock : batchBlocks) {
                byte[] payloadBytes = batchBlock.getBlock();
                Block putBlock = this.networkParameters.getDefaultSerializer().makeBlock(payloadBytes);
                for (Transaction transaction : putBlock.getTransactions()) {
                    block.addTransaction(transaction);
                }
            }
            if (block.getTransactions().size() == 0) {
                return;
            }
            block.solve();
            blockSaveService.saveBlock(block, store);
            for (BatchBlock batchBlock : batchBlocks) {
                store.deleteBatchBlock(batchBlock.getHash());
            }
        } finally {
            store.close();
        }

    }
}
