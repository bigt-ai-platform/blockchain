/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.store.BlockStoreService;
import net.bigtangle.store.BlockStoreInterface;

/**
 * <p>
 * A RewardService provides service for create and validate the reward chain.
 * </p>
 */
@Service
public class AVGPriceService {

    @Autowired
    protected BlockStoreService blockGraph;
    @Autowired
    protected ServerConfiguration serverConfiguration;

    @Autowired
    protected NetworkParameters networkParameters;
    @Autowired
    private StoreService storeService;
    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private  final   String  LOCKID = this.getClass().getName();

    /**
     * Scheduled update function that updates the Tangle
     *
     */

    // createReward is time boxed and can run parallel.
    public void startSingleProcessCalAdd() throws BlockStoreException {

        BlockStoreInterface store = storeService.getStore();

        try {
            // log.info("create Reward started");
            LockObject lock = store.selectLockobject(LOCKID);
            boolean canrun = false;
            if (lock == null) {
                store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
                canrun = true;
            } else if (lock.getLocktime() < System.currentTimeMillis() - scheduleConfiguration.getMiningrate()) {
                store.deleteLockobject(LOCKID);
                store.insertLockobject(new LockObject(LOCKID, System.currentTimeMillis()));
                canrun = true;
            } else {
                log.info("AVGPriceService running return:  {}", Utils.dateTimeFormat(lock.getLocktime()));
            }
            if (canrun) {
                store.batchAddAvgPrice();
                store.deleteLockobject(LOCKID);
            }

        } catch (Exception e) {
            log.error("create Reward end  ", e);
            store.deleteLockobject(LOCKID);
        } finally {
            store.close();
        }

    }

}
