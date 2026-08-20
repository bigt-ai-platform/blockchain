/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreService;

@Component
@EnableAsync
public class UpdateChainService {
    private static final Logger logger = LoggerFactory.getLogger(UpdateChainService.class);

    @Autowired
    ServerConfiguration serverConfiguration;
    @Autowired
    protected BlockStoreService blockGraph;
    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Async("posChainExecutor")
    @Scheduled(fixedDelayString = "${service.schedule.upchainrate:10000}")
    public void updateChain() {
        if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()) {
            // Consensus duty: draw connections from the dedicated pos pool so
            // the block-connection pipeline is never starved by the submit
            // burst (a stale head would make the node propose/attest on a fork).
            StoreService.enterPosContext();
            try {
                blockGraph.updateChain();
            } catch (Exception e) {
                logger.warn("updateConfirmService ", e);
            } finally {
                StoreService.exitPosContext();
            }
        }
    }
}
