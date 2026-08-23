/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SyncBlockService;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Base class providing common initialization logic for all modules:
 * cryptographic context setup, blockchain sync, and service readiness.
 */
public abstract class AbstractScheduleInitService {

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired
    protected ServerConfiguration serverConfiguration;

    @Autowired
    protected SyncBlockService syncBlockService;

    protected abstract Logger getLogger();

    protected void initializeService() {
        try {
            getLogger().debug("AbstractScheduleInitService starting: " + scheduleConfiguration.toString());


            if (scheduleConfiguration.isChainlength_active()) {
                try {
                    getLogger().debug("syncBlockService startInit");
                    syncBlockService.startInit();
                } catch (Exception e) {
                    getLogger().error("startInit failed", e);
                }
                // Readiness = executed through the peers' latest FINALIZED
                // checkpoint, not through the moving head. At the finalized
                // height the UTXO state is provably identical on every node
                // (deterministic replay of an irreversible prefix), so serving
                // reads from there is correct; chasing the head tip may never
                // terminate under load. Bounded by a timeout: availability
                // beats an eternal 'service is not ready' — a timeout emits a
                // WARN and goes ready anyway, duties stay vote-gated as usual.
                long target = -1;
                try {
                    target = syncBlockService.getMaxPeerFinalizedChainLength();
                } catch (Exception e) {
                    getLogger().debug("peer finalized length probe failed", e);
                }
                if (target > 0) {
                    long timeoutMs = 1000L * 60 * Long.getLong("bigtangle.readinessTimeoutMinutes", 30);
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    long lastLog = 0;
                    while (System.currentTimeMillis() < deadline) {
                        long local = -1;
                        BlockStoreInterface store = null;
                        try {
                            store = syncBlockService.getStore();
                            local = syncBlockService.getLocalConfirmedChainLength(store);
                        } catch (Exception e) {
                            getLogger().debug("local confirmed length: {}", e.getMessage());
                        } finally {
                            if (store != null) try { store.close(); } catch (Exception ignore) {}
                        }
                        if (local >= target) {
                            getLogger().info("readiness: confirmed tip " + local
                                    + " reached peers' finalized checkpoint " + target);
                            break;
                        }
                        if (System.currentTimeMillis() - lastLog > 30000) {
                            getLogger().info("readiness: executing to finalized checkpoint, local="
                                    + local + " target=" + target);
                            lastLog = System.currentTimeMillis();
                        }
                        // keep pulling while waiting (async, lock-guarded)
                        try {
                            syncBlockService.startSingleProcess(-1L, false);
                        } catch (Exception e) {
                            getLogger().debug("sync tick during readiness wait", e);
                        }
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    long remaining = -1;
                    BlockStoreInterface store = null;
                    try {
                        store = syncBlockService.getStore();
                        remaining = syncBlockService.getLocalConfirmedChainLength(store);
                    } catch (Exception e) {
                        getLogger().debug("final local length: {}", e.getMessage());
                    } finally {
                        if (store != null) try { store.close(); } catch (Exception ignore) {}
                    }
                    if (remaining < target) {
                        getLogger().warn("readiness timeout: going ready at local=" + remaining
                                + " < finalized " + target + "; reads may be stale until catch-up completes");
                    }
                }
            }

            serverConfiguration.setServiceReady(true);
            // The one-time initial sync has run; let the periodic
            // ScheduleSyncBlockService take over so new blocks are
            // continuously pulled from the requester.
            scheduleConfiguration.setInitSync(false);
            getLogger().debug("serviceReady = true, initSync -> false");

        } catch (Exception e) {
            getLogger().error("AbstractScheduleInitService failed", e);
        }
    }
}
