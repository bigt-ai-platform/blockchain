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

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.health.KafkaHealthIndicator;
import net.bigtangle.kafka.BlockStreamHandler;
import net.bigtangle.lifecycle.StatusCollector;
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

    public void startSingleProcess() {
        if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
            log.debug(this.getClass().getName() + "  HeathCheckService running. Returning...");
            return;
        }

        try {
            if (!checkDB()) {
                blockStreamHandler.closeStream();
                serverConfiguration.setServiceWait();
                log.error(" Database is down. Close the kafka stream and set server down.  ");
            }
            // if (!checkKafka()) {
            // TODO blockStreamHandler.closeStream();
            // serverConfiguration.setServiceWait();
            // log.warn(" Kafka is down. Close the kafka stream and set server
            // down. ");
            // }

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
