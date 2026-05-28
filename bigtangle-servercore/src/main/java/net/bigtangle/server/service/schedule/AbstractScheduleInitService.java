/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service.schedule;

import org.bitcoin.Secp256k1Context;
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

            Secp256k1Context.getContext();

            if (scheduleConfiguration.isMilestone_active()) {
                try {
                    getLogger().debug("syncBlockService startInit");
                    syncBlockService.startInit();
                } catch (Exception e) {
                    getLogger().error("startInit failed", e);
                }
            }

            serverConfiguration.setServiceReady(true);
            getLogger().debug("serviceReady = true");

        } catch (Exception e) {
            getLogger().error("AbstractScheduleInitService failed", e);
        }
    }
}
