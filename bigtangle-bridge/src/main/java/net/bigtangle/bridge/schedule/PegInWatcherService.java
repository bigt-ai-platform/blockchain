package net.bigtangle.bridge.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Scheduled L1-side poll that observes L0 vault locks and issues wrapped
 * tokens, with replay protection (a lock is only minted once). Active only
 * when the bridge is enabled and the node is service-ready.
 */
@Component
@EnableAsync
public class PegInWatcherService {

    private static final Logger logger = LoggerFactory.getLogger(PegInWatcherService.class);

    @Autowired
    private BridgeService bridgeService;

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Scheduled(fixedDelayString = "${bridge.pegInPollMs:15000}")
    public void pollPegIns() {
        if (!bridgeConfiguration.isActive() || !scheduleConfiguration.isChainlength_active()
                || !serverConfiguration.checkService()) {
            return;
        }
        if (anchorConfiguration.getL0Url() == null || anchorConfiguration.getL0Url().isEmpty()) {
            return;
        }
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            bridgeService.processPegInFromL0(store);
        } catch (Exception e) {
            logger.warn("Peg-in polling failed", e);
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception e) {
                    logger.warn("Failed to close store", e);
                }
            }
        }
    }
}
