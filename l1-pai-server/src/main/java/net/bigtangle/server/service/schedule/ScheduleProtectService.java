package net.bigtangle.server.service.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.UserDataService;

@Component
@EnableAsync
public class ScheduleProtectService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleProtectService.class);

    @Autowired
    ServerConfiguration serverConfiguration;
    @Autowired
    private UserDataService userDataService;

    @Async
    @Scheduled(fixedDelayString = "${service.schedule.protect:2000}")
    public void protect() {
        if (serverConfiguration.getIpcheck()) {
            try {
                logger.debug(" Start schedule userDataService.calcDenied(): ");
                userDataService.calcDenied();
            } catch (Exception e) {
                logger.warn("userDataService.calcDenied() ", e);
            }
        }
    }
}
