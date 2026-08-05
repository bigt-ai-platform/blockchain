package net.bigtangle.bridge.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Scheduled retry for failed peg-outs (F7): a peg-out is only attempted when an
 * anchor confirms on L0, and a transient failure there is swallowed. This task
 * periodically re-attempts every confirmed anchor with an embedded burn;
 * {@link BridgeService#processPegOut} is idempotent (already-spent vaults are
 * skipped), so a retry can never double-release. Runs only on the node that
 * holds the vault private key (the L0 peg-out operator).
 */
@Component
@EnableAsync
public class PegOutRetryService {

    private static final Logger logger = LoggerFactory.getLogger(PegOutRetryService.class);

    @Autowired
    private BridgeService bridgeService;

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Scheduled(fixedDelayString = "${bridge.pegOutRetryMs:30000}")
    public void retryPendingPegOuts() {
        if (!bridgeConfiguration.isActive() || !scheduleConfiguration.isChainlength_active()
                || !serverConfiguration.checkService()) {
            return;
        }
        // Only the node holding the vault private key (the peg-out signer) may
        // release vault funds.
        if (bridgeConfiguration.getVaultPriKeyHex() == null || bridgeConfiguration.getVaultPriKeyHex().isEmpty()) {
            return;
        }
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            for (AnchorRecord anchor : store.getAllAnchors()) {
                if (anchor.isConfirmed() && anchor.getBurnJson() != null && !anchor.getBurnJson().isEmpty()) {
                    bridgeService.processPegOut(anchor, store);
                }
            }
        } catch (Exception e) {
            logger.warn("Peg-out retry failed", e);
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
