package net.bigtangle.bridge.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.AnchorService;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;

@Component
@EnableAsync
public class AnchorPostService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorPostService.class);

    @Autowired
    private AnchorService anchorService;

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private NetworkParameters networkParameters;

    @Scheduled(fixedDelayString = "${anchor.postIntervalMs:30000}")
    public void postAnchor() {
        if (!anchorConfiguration.isActive() || !scheduleConfiguration.isChainlength_active()
                || !serverConfiguration.checkService()) {
            return;
        }
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            TXReward latestReward = cacheBlockService.getMaxConfirmedReward(store);
            Sha256Hash latestRewardHash = latestReward.getBlockHash();
            long latestRewardHeight = latestReward.getChainLength();

            AnchorRecord latestAnchor = store.getLatestAnchorByChainId(networkParameters.getChainId());
            if (latestAnchor != null) {
                long rewardHeightDiff = latestRewardHeight - latestAnchor.getL1Height();
                if (rewardHeightDiff < anchorConfiguration.getPostInterval()) {
                    return;
                }
            }
            anchorService.postAnchor(store);
        } catch (Exception e) {
            logger.warn("Anchor posting failed", e);
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
