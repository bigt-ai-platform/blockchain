/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.HeathCheckService;

@Component
@EnableAsync
public class ScheduleHealthCheckService {
    
    @Autowired
    private ScheduleConfiguration scheduleConfiguration;


    @Autowired
    ServerConfiguration serverConfiguration;
    @Autowired
    HeathCheckService heathCheckService;
    
    /*
     * check the heath of the system, database and kafka stream 
     */
   @Scheduled(fixedRate = 2000)
    public void checkService() {
        // Deliberately NOT gated on serverConfiguration.checkService(): the
        // health check sets the server DOWN when the DB drops, so gating on
        // that flag self-disables the very watchdog that must detect the DB
        // coming back (attackvector §29 — a node stayed down forever). The DB
        // ping is cheap; always run.
        if (scheduleConfiguration.isChainlength_active()) {
            heathCheckService.startSingleProcess();
        }

    }

}
