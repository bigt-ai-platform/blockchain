package net.bigtangle.mcmc.service.schedule;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import net.bigtangle.server.service.schedule.AbstractScheduleInitService;
import net.bigtangle.kafka.BlockStreamHandler;

@Component("mcmcScheduleInitService")
@EnableAsync
public class ScheduleInitService extends AbstractScheduleInitService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleInitService.class);

    @Autowired(required = false)
    BlockStreamHandler blockStreamHandler;

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Async
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE, timeUnit = TimeUnit.NANOSECONDS)
    public void syncService() {
        if (scheduleConfiguration.isInitSync()) {
            initializeService();
            if (blockStreamHandler != null && serverConfiguration.getRunKafkaStream()) {
                blockStreamHandler.runStream();
            }
        }
    }
}
