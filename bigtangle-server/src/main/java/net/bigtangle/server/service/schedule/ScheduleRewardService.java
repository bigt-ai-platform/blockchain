/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
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
import net.bigtangle.server.service.RewardService;

@Component
@EnableAsync
public class ScheduleRewardService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleRewardService.class);

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private RewardService rewardService;

    @Autowired
    ServerConfiguration serverConfiguration;
    
    @Async
    @Scheduled(fixedDelayString = "${service.schedule.miningrate:50000}")
    public void updateReward() {
        try {
        if (scheduleConfiguration.isMining() && serverConfiguration.checkService()) {

                logger.debug(" Start schedule updateReward: ");
                rewardService.startSingleProcess();
        }
            } catch (Exception e) {
                logger.warn("performRewardVoting ", e);
            }

    }

}
