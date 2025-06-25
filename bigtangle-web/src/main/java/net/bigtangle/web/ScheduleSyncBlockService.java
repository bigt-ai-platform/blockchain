/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.web;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.exception.BlockStoreException;

@Component
@EnableAsync
public class ScheduleSyncBlockService {
    
  
    @Autowired
    private SyncBlockService syncBlockService;
   
    /*
     * Sync the chain and block data direct via p2p 
     */
    @Async
    @Scheduled(fixedDelayString = "${service.schedule.syncrate:50000}")
    public void syncService() throws BlockStoreException, JsonProcessingException, IOException {
        
            syncBlockService.startSingleProcess();
       

    }

}
