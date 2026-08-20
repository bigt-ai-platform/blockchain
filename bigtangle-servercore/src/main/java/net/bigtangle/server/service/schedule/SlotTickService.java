package net.bigtangle.server.service.schedule;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SlotService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.ValidatorDutyService;

@Component
@EnableAsync
public class SlotTickService {

    private static final Logger log = LoggerFactory.getLogger(SlotTickService.class);

    /** Consecutive failures before backing off (skip ticks for a while). */
    private static final int FAILURE_BACKOFF_LIMIT = 5;
    /** Backoff: skip this many ticks after repeated failures. */
    private static final long FAILURE_BACKOFF_TICKS = 10;

    @Autowired
    private SlotService slotService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private ValidatorDutyService validatorDutyService;

    private long lastProcessedEpoch = -1;

    /** Guards against concurrent ticks (the scheduled task can overlap). */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    @Async("posExecutor")
    @Scheduled(fixedDelayString = "${pos.slotIntervalMs:12000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.trace("Slot tick already running; skipping");
            return;
        }
        StoreService.enterPosContext();
        try {
            if (!scheduleConfiguration.isChainlength_active() || !serverConfiguration.checkService()) {
                return;
            }

            // Backoff after repeated failures: skip ticks so a stuck store does
            // not spin an error hot-loop.
            if (consecutiveFailures.get() >= FAILURE_BACKOFF_LIMIT) {
                if (consecutiveFailures.get() >= FAILURE_BACKOFF_LIMIT + FAILURE_BACKOFF_TICKS) {
                    consecutiveFailures.set(0); // reset and retry
                } else {
                    return;
                }
            }

            try {
                long slot = slotService.getCurrentSlot();
                long epoch = slotService.getEpochForSlot(slot);

                var store = storeService.getStore();
                try {
                    // Finality is driven by the CHAIN epoch (confirmed
                    // chainlength / 32), NOT the wall-clock epoch. On a young
                    // chain the wall-clock epoch runs far ahead of the chain
                    // (the beacon chain confirms ~1 block per proposer slot), so
                    // finalizeCheckpoint(wallEpoch) targets an epoch boundary
                    // that does not exist on-chain and can never justify.
                    // Driving it from the confirmed chain makes every node
                    // evaluate the SAME chain epoch and converge on the same
                    // checkpoint.
                    long chainEpoch = SlotService.currentChainEpoch(store);
                    if (chainEpoch != lastProcessedEpoch) {
                        // Evaluate the just-COMPLETED chain epoch: only its
                        // attestations are complete. Evaluating the current
                        // epoch (which has no votes yet at its first tick) would
                        // never justify anything — finality would never advance.
                        if (chainEpoch > 0) {
                            slotService.processEpoch(chainEpoch - 1, store);
                        }
                        lastProcessedEpoch = chainEpoch;
                    }
                } finally {
                    store.close();
                }

                validatorDutyService.performDuty();
                consecutiveFailures.set(0);
            } catch (Throwable t) {
                // Never let a tick exception kill the scheduler thread; back off
                // on repeated failures.
                long failures = consecutiveFailures.incrementAndGet();
                if (log.isDebugEnabled() || failures == 1) {
                    log.warn("Slot tick error (consecutive failures={})", failures, t);
                } else {
                    log.debug("Slot tick error (consecutive failures={})", failures, t);
                }
            }
        } finally {
            StoreService.exitPosContext();
            running.set(false);
        }
    }
}
