/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SyncBlockService;

@Component
@EnableAsync
public class ScheduleSyncBlockService {
    
    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private SyncBlockService syncBlockService;
    @Autowired
    ServerConfiguration serverConfiguration;
    /*
     * Sync the chain and block data direct via p2p 
     */
    @Async
    @Scheduled(fixedDelayString = "${service.schedule.syncrate:50000}")
    public void syncService() throws BlockStoreException {
        if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()
        		&& !scheduleConfiguration.isInitSync() ) {
            syncBlockService.startSingleProcess();
        }

    }

}
