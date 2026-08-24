/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.kafka.BlockStreamHandler;
import net.bigtangle.kafka.TransactionStreamHandler;

@Component
@EnableAsync
public class ScheduleInitService extends AbstractScheduleInitService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleInitService.class);

    @Autowired
    BlockStreamHandler blockStreamHandler;
    @Autowired
    TransactionStreamHandler transactionStreamHandler;

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Async
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE, timeUnit = TimeUnit.NANOSECONDS)
    public void syncService() {
        if (scheduleConfiguration.isInitSync()) {
            initializeService();
        }
    }

    private volatile boolean streamsStarted = false;

    /**
     * Kafka stream consumers start RETRYING instead of one-shot: the previous
     * single-shot starter died with the boot-time init-sync exception and
     * never retried, leaving nodes silently without a consumer for their
     * whole lifetime (observed as confirmation stalls while a benchmark burst
     * ran). A short retry loop tolerates brokers/peers that are not ready at
     * T+5s.
     */
    @Scheduled(initialDelay = 15000, fixedDelay = 30000, timeUnit = TimeUnit.MILLISECONDS)
    public void startKafkaStreams() {
        if (streamsStarted || !serverConfiguration.getRunKafkaStream()
                || !scheduleConfiguration.isInitSync()) {
            return;
        }
        try {
            // ensureStarted only creates a client when none is alive; calling
            // runStream() here unconditionally killed the PostConstruct
            // starter's healthy client every 30s (rebalance storm, leaked
            // threads, starved attestation groups).
            blockStreamHandler.ensureStarted();
            transactionStreamHandler.ensureStarted();
            streamsStarted = true;
            logger.info("Kafka stream consumers started");
        } catch (Exception e) {
            logger.warn("Kafka stream start failed, will retry: {}", e.getMessage());
        }
    }
}
