/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SyncBlockService;

/**
 * Base class providing common initialization logic for all modules:
 * cryptographic context setup, blockchain sync, and service readiness.
 */
public abstract class AbstractScheduleInitService {

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired
    protected ServerConfiguration serverConfiguration;

    @Autowired
    protected SyncBlockService syncBlockService;

    protected abstract Logger getLogger();

    protected void initializeService() {
        try {
            getLogger().debug("AbstractScheduleInitService starting: " + scheduleConfiguration.toString());

            
            if (scheduleConfiguration.isChainlength_active()) {
                try {
                    getLogger().debug("syncBlockService startInit");
                    syncBlockService.startInit();
                } catch (Exception e) {
                    getLogger().error("startInit failed", e);
                }
            }

            serverConfiguration.setServiceReady(true);
            // The one-time initial sync has run; let the periodic
            // ScheduleSyncBlockService take over so new blocks are
            // continuously pulled from the requester.
            scheduleConfiguration.setInitSync(false);
            getLogger().debug("serviceReady = true, initSync -> false");

        } catch (Exception e) {
            getLogger().error("AbstractScheduleInitService failed", e);
        }
    }
}
