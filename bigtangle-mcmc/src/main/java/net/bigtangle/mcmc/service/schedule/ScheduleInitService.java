/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.service.schedule;

import java.util.concurrent.TimeUnit;

import org.bitcoin.Secp256k1Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SyncBlockService;

@Component
@EnableAsync
public class ScheduleInitService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleInitService.class);

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private SyncBlockService syncBlockService;

    @Async
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE, timeUnit = TimeUnit.NANOSECONDS)
    public void syncService() {
        try {
            logger.debug("MCMCStart ScheduleInitService: " + scheduleConfiguration.toString());

            Secp256k1Context.getContext();

            if (scheduleConfiguration.isMilestone_active()) {
                try {
                    logger.debug("syncBlockService startInit");
                    syncBlockService.startInit();
                } catch (Exception e) {
                    logger.error("startInit failed", e);
                }
            }

            serverConfiguration.setServiceReady(true);
            logger.debug("MCMCStart serviceReady = true");

        } catch (Exception e) {
            logger.error("ScheduleInitService failed", e);
        }
    }
}
