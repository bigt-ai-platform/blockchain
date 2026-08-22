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

/**
 * One-time init for the payment L1: marks the service ready once the initial
 * sync has run, then hands continuous syncing to ScheduleSyncBlockService.
 * Mirrors l1-order-server's ScheduleInitService.
 */
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
            if (serverConfiguration.getRunKafkaStream()) {
                blockStreamHandler.runStream();
                transactionStreamHandler.runStream();
            }
        }
    }
}
