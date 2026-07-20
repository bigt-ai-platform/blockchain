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

    @Scheduled(fixedRate = 2000)
    public void checkService() {
        if (scheduleConfiguration.isMilestone_active() && serverConfiguration.checkService()) {
            heathCheckService.startSingleProcess();
        }
    }
}
