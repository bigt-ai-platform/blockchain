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
import net.bigtangle.server.service.OrderExecutionService;

@Component
@EnableAsync
public class ScheduleOrdermatchService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleOrdermatchService.class);

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private OrderExecutionService orderExecutionService;

    @Autowired
    ServerConfiguration serverConfiguration;
    
    @Async
    @Scheduled(fixedDelayString = "${service.schedule.mcmcrate:500}")
    public void orderExecutionService() {
    	  if (scheduleConfiguration.isMilestone_active() && serverConfiguration.checkService()) {
            try {
             //   logger.debug(" Start schedule orderExecutionService: ");
                orderExecutionService.startSingleProcess();
            } catch (Exception e) {
                logger.warn("orderExecutionService ", e);
            }
        }
    }

}
