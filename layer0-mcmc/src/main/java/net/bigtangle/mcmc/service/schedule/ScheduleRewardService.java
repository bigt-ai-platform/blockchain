package net.bigtangle.mcmc.service.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.mcmc.service.RewardService;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;

@Component
@EnableAsync
public class ScheduleRewardService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleRewardService.class);

    @Autowired
    private RewardService rewardService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    ServerConfiguration serverConfiguration;

    @Async
    @Scheduled(fixedDelayString = "${service.schedule.rewardrate:5000}")
    public void updateRewardService() {
        if (scheduleConfiguration.isChainlength_active() && serverConfiguration.checkService()) {
            try {
                rewardService.startSingleProcess();
            } catch (Exception e) {
                logger.warn("updateRewardService ", e);
            }
        }
    }
}
