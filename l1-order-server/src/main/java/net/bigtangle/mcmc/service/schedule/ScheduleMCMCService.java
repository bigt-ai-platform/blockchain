/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.mcmc.service.schedule;

/**
 * Service responsible for scheduled execution of MCMC (Markov Chain Monte Carlo) processes.
 * Runs at a configurable fixed delay (default: 500ms) for continuous validation and consensus operations.
 * Requires chainlength activation and service availability to execute.
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.mcmc.service.MCMCService;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;

@Component
@EnableAsync
public class ScheduleMCMCService {

    /**
     * Logger for tracking service operations and errors
     */
    private static final Logger logger = LoggerFactory.getLogger(ScheduleMCMCService.class);

    /**
     * Service handling core MCMC operations and calculations
     */
    @Autowired
    private MCMCService mcmcService;

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
     * Asynchronously executes the MCMC process at a fixed delay.
     * The delay is configurable through application properties (service.schedule.mcmcrate)
     * with a default of 500ms if not specified.
     * Only executes if:
     * 1. Chainlength activation is enabled
     * 2. Server service is available
     */

    @Async
    @Scheduled(fixedDelayString = "${service.schedule.mcmcrate:500}")
    public void updatemcmcService() {
        if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()) {
            try {
            //    logger.debug(" Start SchedulemcmcService: ");
                mcmcService.startSingleProcess();

            } catch (Exception e) {
                logger.warn("updatemcmcService ", e);
            }
        }
    }

}
