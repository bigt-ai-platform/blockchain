/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

/**
 * Service responsible for initializing and synchronizing the blockchain system.
 * Handles critical startup tasks including:
 * - Cryptographic context initialization
 * - Blockchain milestone synchronization
 * - Service readiness signaling
 * - Kafka stream processing initialization
 */

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.bitcoin.Secp256k1Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.kafka.BlockStreamHandler;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SyncBlockService;

@Component
@EnableAsync
public class ScheduleInitService {

    /**
     * Configuration for scheduling parameters and initialization flags
     */

	@Autowired
	private ScheduleConfiguration scheduleConfiguration;

    /**
     * Server configuration containing runtime parameters and settings
     */

	@Autowired
	private ServerConfiguration serverConfiguration;

    /**
     * Network parameters defining blockchain configuration
     */
	@Autowired
	NetworkParameters networkParameters;

    /**
     * Data source for database connectivity
     */
	@Autowired
	protected transient DataSource dataSource;

    /**
     * Service for synchronizing blockchain data
     */

	@Autowired
	private SyncBlockService syncBlockService;

    /**
     * Handler for Kafka block streaming operations
     */
	@Autowired
	BlockStreamHandler blockStreamHandler;

    /**
     * Logger for tracking service operations and errors
     */
	private static final Logger logger = LoggerFactory.getLogger(ScheduleInitService.class);

    /**
     * Asynchronously executes the initialization sequence for the blockchain system.
     * This method is scheduled to run once after a 5 second delay during application startup.
     * Performs the following operations:
     * 1. Initializes cryptographic context
     * 2. Starts milestone synchronization if enabled
     * 3. Sets service readiness flag
     * 4. Starts Kafka stream processing if configured
     * 
     * @throws BlockStoreException If there is an error during blockchain synchronization
     */

	@Async
	@Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE, timeUnit = TimeUnit.NANOSECONDS)
	public void syncService() throws BlockStoreException {
		if (scheduleConfiguration.isInitSync()) {
			try {
			 
				logger.debug("Schedule: " + scheduleConfiguration.toString());

				Secp256k1Context.getContext();

				Secp256k1Context.getContext();
				if (scheduleConfiguration.isMilestone_active()) {
					try {
						logger.debug("syncBlockService startInit");
						syncBlockService.startInit();
					} catch (Exception e) {
						logger.error("", e);
						// TODO sync checkpoint System.exit(-1);
					}
				}
				serverConfiguration.setServiceReady(true);
				if (serverConfiguration.getRunKafkaStream()) {
					blockStreamHandler.runStream();
				}

			} catch (Exception e) {
				logger.error("", e);
				// TODO sync checkpoint System.exit(-1);
			}
		}
	}

}
