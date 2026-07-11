package net.bigtangle.bridge.schedule;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceVerifyReward;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * Scheduled service on the L1 node that watches L0 for confirmed anchors
 * targeting this chain. When a newer anchored L1 tip is found, the L1 node
 * reorgs to follow the L0-finalized branch (Phase 2 fork resolution).
 *
 * This implements the canonical-chain rule from LAYERING-PLAN.md §3.3:
 * "Once an anchor is L0-confirmed, the anchored branch is canonical — all
 * L1 nodes MUST switch to it."
 */
@Component
@EnableAsync
public class AnchorWatcherService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorWatcherService.class);

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private BlockSaveService blockSaveService;

    @Autowired
    private ObjectMapper jsonmapper;

    @Scheduled(fixedDelayString = "${anchor.watchIntervalMs:60000}")
    public void watchAnchors() {
        if (!anchorConfiguration.isActive() || !scheduleConfiguration.isMilestone_active()
                || !serverConfiguration.checkService()) {
            return;
        }
        String l0Url = anchorConfiguration.getL0Url();
        if (l0Url == null || l0Url.isEmpty()) {
            return;
        }
        BlockStoreInterface store = null;
        try {
            store = storeService.getStore();
            Sha256Hash latestConfirmedReward = cacheBlockService.getMaxConfirmedReward(store).getBlockHash();
            long localHeight = cacheBlockService.getMaxConfirmedReward(store).getChainLength();

            AnchorRecord latestLocalAnchor = store.getLatestAnchorByChainId(networkParameters.getChainId());
            long anchoredHeight = (latestLocalAnchor != null) ? latestLocalAnchor.getL1Height() : -1;

            if (anchoredHeight >= localHeight) {
                return;
            }

            List<AnchorRecord> l0Anchors = fetchAnchorsFromL0(l0Url, anchoredHeight + 1);
            for (AnchorRecord l0Anchor : l0Anchors) {
                if (!l0Anchor.isConfirmed()) {
                    continue;
                }
                if (l0Anchor.getL1Height() <= anchoredHeight) {
                    continue;
                }
                logger.info("Found L0-confirmed anchor for chain {} at height {} (local: {})",
                        l0Anchor.getChainId(), l0Anchor.getL1Height(), localHeight);

                store.saveAnchor(l0Anchor);
                anchoredHeight = l0Anchor.getL1Height();

                if (l0Anchor.getL1Height() > localHeight) {
                    reorgToAnchoredTip(l0Anchor, store);
                }
            }
        } catch (Exception e) {
            logger.warn("Anchor watcher failed", e);
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

    private List<AnchorRecord> fetchAnchorsFromL0(String l0Url, long sinceHeight) throws Exception {
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("chainId", networkParameters.getChainId());
        params.put("sinceHeight", sinceHeight);
        params.put("limit", 100);
        byte[] resp = OkHttp3Util.post(l0Url + "/" + ReqCmd.getAnchors.name(),
                jsonmapper.writeValueAsString(params).getBytes());
        return jsonmapper.readValue(resp, new TypeReference<List<AnchorRecord>>() {});
    }

    private void reorgToAnchoredTip(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        logger.info("Reorging L1 to follow L0-anchored tip at height {}", anchor.getL1Height());

        ServiceBaseConnect connect = new ServiceBaseConnect(serverConfiguration, networkParameters,
                cacheBlockService, jsonmapper);
        ServiceVerifyReward verifyReward = new ServiceVerifyReward(serverConfiguration, networkParameters,
                cacheBlockService, jsonmapper);

        Block anchoredHead = store.get(anchor.getL1RewardHeadHash());
        if (anchoredHead == null) {
            logger.warn("Anchored head block {} not found locally, need to sync from peers",
                    anchor.getL1RewardHeadHash());
            return;
        }

        Block currentHead = store.get(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
        if (currentHead.getHeight() >= anchor.getL1Height()) {
            return;
        }

        verifyReward.handleNewBestChain(anchoredHead, store);
        logger.info("Reorg complete: L1 now following anchored tip at height {}", anchor.getL1Height());
    }
}
