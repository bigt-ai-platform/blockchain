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
import net.bigtangle.server.service.CacheBlockService;

/**
 * Periodically invalidates the local in-memory block/output caches.
 *
 * The HTTP server and the MCMC process share the same database but run in
 * separate JVMs with separate in-memory caches. When the MCMC confirms a
 * block (e.g. a token creation or order block), it only evicts its own JVM's
 * caches; the HTTP server would keep serving stale confirmed-outputs data.
 * This scheduled full eviction keeps the HTTP-facing caches in sync with the
 * shared database at the cost of a cheap re-read.
 */
@Component
@EnableAsync
public class ScheduleCacheEvictService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleCacheEvictService.class);

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Async
    @Scheduled(fixedDelayString = "${service.schedule.cacheevict:5000}")
    public void evict() {
        if (!serverConfiguration.checkService()) {
            return;
        }
        try {
            cacheBlockService.evictOutputs();
            cacheBlockService.evictAccountBalance();
        } catch (Exception e) {
            logger.debug("cache eviction skipped: {}", e.getMessage());
        }
    }
}
