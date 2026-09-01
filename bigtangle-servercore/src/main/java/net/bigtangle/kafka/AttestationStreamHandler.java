package net.bigtangle.kafka;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.server.service.CasperService;

/**
 * Consumes validator attestations from Kafka and feeds them through the exact
 * same {@link CasperService#processVote} validation path as the HTTP endpoint.
 *
 * <p>Rationale: the gossip HTTP path for attestations proved lossy under load
 * (bursts + slow peers ⇒ dropped posts ⇒ no 2/3 quorum ⇒ global confirmation
 * stall). Kafka's durable ordered log guarantees every validator sees every
 * vote; per-validator slot ordering is preserved by keying on the validator
 * pubkey. Delivery is at-least-once — processVote is idempotent via its
 * latest-slot dedup.
 */
@Service
public class AttestationStreamHandler extends AbstractStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(AttestationStreamHandler.class);

    @Autowired
    private CasperService casperService;

    @Autowired
    private net.bigtangle.server.service.StoreService storeService;

    @Override
    protected String topic() {
        return kafkaConfiguration.getAttestationTopic();
    }

    @Override
    protected void process(KStream<String, byte[]> stream) {
        stream.foreach((key, bytes) -> {
            if (foreignChainRecord(key)) return;
            BlockStoreInterface store = null;
            try {
                AttestationData att = Json.jsonmapper().readValue(bytes, AttestationData.class);
                store = storeService.getStore();
                casperService.processVote(att, store);
            } catch (Exception e) {
                log.debug("attestation consume failed: {}", e.getMessage());
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
    }

    /** The stream thread must not die when the broker is briefly unreachable: retry. */
}
