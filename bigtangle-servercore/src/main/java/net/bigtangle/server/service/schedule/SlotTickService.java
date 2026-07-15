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
import net.bigtangle.server.service.SlotService;
import net.bigtangle.server.service.StoreService;

@Component
@EnableAsync
public class SlotTickService {

    private static final Logger log = LoggerFactory.getLogger(SlotTickService.class);

    @Autowired
    private SlotService slotService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    private long lastProcessedEpoch = -1;

    @Async
    @Scheduled(fixedDelayString = "${pos.slotIntervalMs:12000}")
    public void tick() {
        if (!scheduleConfiguration.isMilestone_active() || !serverConfiguration.checkService()) {
            return;
        }

        try {
            long slot = slotService.getCurrentSlot();
            long epoch = slotService.getEpochForSlot(slot);

            var store = storeService.getStore();
            try {
                slotService.proposeBeaconBlock(slot, store);

                if (epoch != lastProcessedEpoch) {
                    slotService.processEpoch(epoch, store);
                    lastProcessedEpoch = epoch;
                }
            } finally {
                store.close();
            }
        } catch (Exception e) {
            log.debug("Slot tick error", e);
        }
    }
}
