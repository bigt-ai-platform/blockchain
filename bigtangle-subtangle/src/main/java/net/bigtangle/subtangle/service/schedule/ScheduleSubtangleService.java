package net.bigtangle.subtangle.service.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.subtangle.SubtangleConfiguration;
import net.bigtangle.subtangle.service.SubtangleService;

@Component
@EnableAsync
public class ScheduleSubtangleService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleSubtangleService.class);

    @Autowired
    private SubtangleConfiguration subtangleConfiguration;

    @Scheduled(fixedRateString = "${subtangle.rate:10000}")
    public void updateSubtangleService() throws BlockStoreException {
        if (subtangleConfiguration.isActive()) {

            BlockStoreInterface store = storeService.getStore();
            try {
                logger.debug(" Start ScheduleSubtangleService: ");

                subtangleService.giveMoneyToTargetAccount(store);
            } catch (Exception e) {
                logger.warn("ScheduleSubtangleService ", e);
            } finally {
                store.close();

            }
        }
    }

    @Autowired
    protected StoreService storeService;

    @Autowired
    private SubtangleService subtangleService;
}
