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

    // Resolved lazily to avoid bean-init cycles (CasperService and
    // SyncBlockService are heavy, mutually interconnected services).
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.CasperService> casperServiceProvider;
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<net.bigtangle.server.service.SyncBlockService> syncBlockServiceProvider;

    private long lastProcessedEpoch = -1;

    /** Guards against concurrent ticks (the scheduled task can overlap). */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Guards against concurrent epoch ticks. */
    private final AtomicBoolean epochRunning = new AtomicBoolean(false);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private final AtomicLong epochConsecutiveFailures = new AtomicLong(0);

    // Duty tick and epoch tick are SEPARATE schedules on separate executors:
    // epoch finality (Casper vote evaluation over the whole attestation
    // lookback, pruning) can run for seconds at an epoch boundary, and while it
    // hogs the duty thread the next slot's proposal is lost (observed as 20-33s
    // beacon gaps under load). Proposing/attesting is wall-clock bound and must
    // never wait behind chain bookkeeping.
    //
    // The duty tick runs FASTER than the slot interval and relies on
    // ValidatorDutyService.performDuty's once-per-slot guard (lastDutySlot) for
    // dedup, so a tick that overruns or is rejected cannot skip a whole slot —
    // the next wakeup retries within the same slot. The guard exits before any
    // DB access, so the extra wakeups are nearly free.
    @Async("posExecutor")
    @Scheduled(fixedDelayString = "${pos.slotTickRateMs:1000}")
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

    /**
     * Epoch finality tick: drives Casper checkpoint justification/finalization
     * and pos_state pruning from the CONFIRMED chain. Chain-derived and
     * idempotent, so a slow cycle costs nothing but latency — it must simply
     * never run on the duty thread.
     *
     * <p>Runs on its OWN executor: when it shared posChainExecutor, a backed-up
     * connect queue rejected the submission outright (TaskRejectedException in
     * the scheduler) and finality evaluation silently stopped —
     * finalizedChainLength pinned at 0 while blocks kept confirming.
     */
    @Async("posEpochExecutor")
    @Scheduled(fixedDelayString = "${pos.epochTickRateMs:5000}")
    public void epochTick() {
        if (!epochRunning.compareAndSet(false, true)) {
            return;
        }
        StoreService.enterPosContext();
        try {
            if (!scheduleConfiguration.isChainlength_active() || !serverConfiguration.checkService()) {
                return;
            }
            if (epochConsecutiveFailures.get() >= FAILURE_BACKOFF_LIMIT) {
                if (epochConsecutiveFailures.get() >= FAILURE_BACKOFF_LIMIT + FAILURE_BACKOFF_TICKS) {
                    epochConsecutiveFailures.set(0);
                } else {
                    return;
                }
            }
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
                long chainEpoch = SlotService.currentChainEpoch(store, slotService.slotsPerEpoch());
                // Cold-start finality bootstrap: a node whose Casper state
                // begins empty (fresh rejoin) can never justify from genesis —
                // the mesh's votes for past epochs are gone (embedded votes
                // live only ATTESTATION_LOOKBACK_EPOCHS deep). Adopt the
                // peers' finalized checkpoint as the anchor once; live
                // justification resumes from it (~2 epochs). The anchor hash
                // is verified against our own chain-derived boundary inside
                // adoptFinalizedAnchor, and a node already at/near the peers'
                // finality (or a young chain) skips the query entirely.
                if (chainEpoch > 2) {
                    net.bigtangle.server.service.CasperService casper = casperServiceProvider.getIfAvailable();
                    net.bigtangle.server.service.CasperService.Checkpoint fin =
                            casper == null ? null : casper.getLastFinalizedCheckpoint(store);
                    long finEpoch = fin == null ? -1 : fin.getEpoch();
                    if (finEpoch < chainEpoch - 2 && casper != null) {
                        net.bigtangle.server.service.SyncBlockService sync =
                                syncBlockServiceProvider.getIfAvailable();
                        net.bigtangle.response.GetTXRewardResponse peer =
                                sync == null ? null : sync.getBestPeerFinalizedCheckpoint();
                        if (peer != null && peer.getFinalizedEpoch() != null
                                && peer.getFinalizedEpoch() > finEpoch
                                && peer.getFinalizedBlockHash() != null) {
                            casper.adoptFinalizedAnchor(peer.getFinalizedEpoch(),
                                    net.bigtangle.core.Sha256Hash.wrap(peer.getFinalizedBlockHash()), store);
                        }
                    }
                }
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
                epochConsecutiveFailures.set(0);
            } catch (Throwable t) {
                long failures = epochConsecutiveFailures.incrementAndGet();
                log.warn("Epoch tick error (consecutive failures={})", failures, t);
            } finally {
                store.close();
            }
        } catch (Throwable t) {
            log.warn("Epoch tick setup error", t);
        } finally {
            StoreService.exitPosContext();
            epochRunning.set(false);
        }
    }
}
