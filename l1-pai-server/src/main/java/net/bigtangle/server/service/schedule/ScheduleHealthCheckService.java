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
        // Not gated on checkService(): the health check sets the server DOWN
        // on a DB outage, so that gate self-disables the recovery watchdog
        // (attackvector 29). Always run the cheap DB ping.
        if (scheduleConfiguration.isChainlength_active()) {
            heathCheckService.startSingleProcess();
        }
    }
}
