/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.service;

import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.health.KafkaHealthIndicator;
import net.bigtangle.kafka.BlockStreamHandler;
import net.bigtangle.lifecycle.StatusCollector;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Threading;

/**
 * <p>
 * Provides services for check of system components. if the database is down,
 * then it will close the kafka streams and set server is down. If kafka stream
 * is down, then it set the server down.
 * </p>
 */
@Service
public class HeathCheckService {
    private static final Logger log = LoggerFactory.getLogger(HeathCheckService.class);

    protected static final String DATABASE_NAME = "database";

    @Autowired
    ServerConfiguration serverConfiguration;

    @Autowired
    private StoreService storeService;
    @Autowired
    protected NetworkParameters networkParameters;

    @Autowired
    protected BlockStreamHandler blockStreamHandler;

    @Autowired
    protected KafkaHealthIndicator kafkaHealthIndicator;

    protected final ReentrantLock lock = Threading.lock("HeathCheckService");

    /** True after the DB outage shut the server down; cleared on recovery. */
    private volatile boolean serviceDownForDb;

    public void startSingleProcess() {
        if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
            log.debug(this.getClass().getName() + "  HeathCheckService running. Returning...");
            return;
        }

        try {
            if (!checkDB()) {
                // DB down: close the kafka stream and set the server down so no
                // consensus duty runs against a dead store (it would throw every
                // tick). This is now REVERSIBLE — see the recovery branch below.
                if (!serviceDownForDb) {
                    serviceDownForDb = true;
                    try {
                        blockStreamHandler.closeStream();
                    } catch (Exception e) {
                        log.warn("HeathCheckService close stream failed: {}", e.getMessage());
                    }
                    serverConfiguration.setServiceWait();
                    log.error(" Database is down. Closed kafka stream and set server down.");
                }
            } else if (serviceDownForDb) {
                // DB RECOVERED after an outage (attackvector §29): the server
                // was set down and its duties gated off; nothing ever brought it
                // back. Restore service and (re)start the kafka consumers.
                serviceDownForDb = false;
                serverConfiguration.setServiceReady(true);
                try {
                    // Idempotent: no-op when the consumer is already RUNNING,
                    // restarts it when closed/terminal (the watchdog also does,
                    // but recover immediately here).
                    blockStreamHandler.ensureStarted();
                } catch (Exception e) {
                    log.warn("HeathCheckService stream restart failed: {}", e.getMessage());
                }
                log.info("Database recovered — server set ready and kafka stream restarted.");
            }
        } catch (Exception e) {
            log.warn("HeathCheckService ", e);
        } finally {
            lock.unlock();

        }
    }

    private boolean checkDB() {
        StatusCollector statusCollector = new StatusCollector();

        if (!checkDB(statusCollector).isStatus()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            return checkDB(statusCollector).isStatus();
        }
        return true;
    }

    private StatusCollector checkDB(StatusCollector status) {

        try {
            BlockStoreInterface store = storeService.getStore();
            try {
                store.getSettingValue("version");
                status.setOkMessage(DATABASE_NAME);
            } finally {
                store.close();
            }

        } catch (Exception e) {
            log.error("database is down:" + e.getMessage(), e);
            status.setFailedMessage(DATABASE_NAME);
        }
        return status;
    }

    private boolean checkKafka() {
        StatusCollector statusCollector = new StatusCollector();

        if (!checkKafka(statusCollector).isStatus()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            return checkKafka(statusCollector).isStatus();
        }
        return true;
    }

    private StatusCollector checkKafka(StatusCollector status) {
        try {
            if (!kafkaHealthIndicator.checkTopic()) {
                status.setFailedMessage("Kafka cluster node down");
            } else {
                status.setOkMessage("kafka");
            }
        } catch (Exception e) {
            log.error("Kafka down:" + e.getMessage(), e);
            status.setFailedMessage("kafka");
        }
        return status;
    }

}
