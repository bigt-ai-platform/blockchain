/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreService;

/**
 * Drives the reward-chain connection loop ({@code blockGraph.updateChain}) on
 * a SELF-OWNED resilient daemon thread instead of the Spring
 * {@code @Scheduled/@Async} path.
 *
 * <p>Why: a DB outage (postgres stop/restart) permanently silenced the old
 * {@code @Async("posChainExecutor") @Scheduled} method on the affected node —
 * the duty thread went quiet mid-outage and never resumed, freezing the
 * confirmed chain while the process stayed healthy and gossips continued
 * (attackvector §29, reproduced by MeshChaos V67; the node only recovered on a
 * process restart). A single-threaded {@code @Scheduled} driver can die with
 * the boot/shared scheduler and nothing restarts it.
 *
 * <p>This loop cannot silently stop: it owns its thread, wraps every iteration
 * in {@code catch (Throwable)} (a DB outage logs and continues — the pool
 * re-establishes, §29 pool fix), and keeps looping at the configured cadence.
 * Only an interrupted flag stops it (shutdown).
 */
@Component
public class UpdateChainService {
    private static final Logger logger = LoggerFactory.getLogger(UpdateChainService.class);

    @Autowired
    ServerConfiguration serverConfiguration;
    @Autowired
    protected BlockStoreService blockGraph;
    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    /** Cadence (ms) between chain-connect passes. */
    @Value("${service.schedule.upchainrate:10000}")
    private long upchainRate = 10000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::loop, "pos-chain-driver");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        worker = t;
        t.start();
        logger.info("UpdateChainService driver started (cadence {} ms)", upchainRate);
    }

    private void loop() {
        while (running.get()) {
            try {
                if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()) {
                    // Consensus duty: chain connection on the dedicated pos
                    // context so it is never starved by the submit burst (a
                    // stale head would make the node propose/attest on a fork).
                    StoreService.enterPosContext();
                    try {
                        blockGraph.updateChain();
                    } catch (Throwable e) {
                        // A DB outage throws here (connection lost mid-pass).
                        // LOG AND CONTINUE — never exit the loop. The next pass
                        // retries once the pool re-establishes (§29 fix:
                        // connection-test-query + short max-lifetime cycle dead
                        // connections). An uncaught Error here would kill the
                        // duty forever (the pre-fix silent freeze).
                        logger.warn("pos-chain pass failed (DB outage?) — continuing: {}", e.toString());
                    } finally {
                        StoreService.exitPosContext();
                    }
                }
            } catch (Throwable e) {
                logger.warn("pos-chain driver iteration error — continuing: {}", e.toString());
            }
            try {
                Thread.sleep(upchainRate);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        Thread w = worker;
        if (w != null) {
            w.interrupt();
        }
    }
}
