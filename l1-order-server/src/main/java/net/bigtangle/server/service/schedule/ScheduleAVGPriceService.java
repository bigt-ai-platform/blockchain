/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

/**
 * Service responsible for scheduled calculation and updating of average prices.
 * Executes daily at 1:00 AM to calculate and update price metrics.
 * Requires chainlength activation and service availability to run.
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.layer1.service.AVGPriceService;

@Component
@EnableAsync
public class ScheduleAVGPriceService {

    /**
     * Logger for tracking service operations and errors
     */
    private static final Logger logger = LoggerFactory.getLogger(ScheduleAVGPriceService.class);

    /**
     * Service for calculating and managing average prices
     */
    @Autowired
    private AVGPriceService aVGPriceService;

    /**
     * Configuration for scheduling parameters and activation flags
     */

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    /**
     * Server configuration containing runtime parameters and service status
     */
    @Autowired
    ServerConfiguration serverConfiguration;

    /**
     * Asynchronously executes the daily average price calculation process.
     * Scheduled to run every day at 1:00 AM.
     * Only executes if:
     * 1. Chainlength activation is enabled
     * 2. Server service is available
     */

    @Async
    @Scheduled(cron = "0 0 1 * * ?")
    public void updatemcmcService() {
        if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()) {
            try {
                // logger.debug(" Start SchedulemcmcService: ");
                aVGPriceService.startSingleProcessCalAdd();

            } catch (Exception e) {
                logger.warn("updatemcmcService ", e);
            }
        }
    }

}
