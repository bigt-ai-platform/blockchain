package net.bigtangle.kafka;

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class KafkaMessageProducer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageProducer.class);

    @Autowired
    private KafkaConfiguration kafkaConfiguration;

    @Autowired
    private KafkaSeedDiscovery seedDiscovery;

    private KafkaProducer<String, byte[]> producer;
    private boolean enabled;

    @PostConstruct
    public void init() {
        // Bootstrap from whatever is known now (config, DNS seeds). If nothing
        // is discovered yet, send() retries lazily — the seed refresher and
        // gossip adverts may find brokers later.
        ensureProducer();
    }

    private synchronized boolean ensureProducer() {
        if (producer != null) {
            return true;
        }
        String bs = kafkaConfiguration.getBootstrapServers();
        if (bs == null || bs.isBlank()) {
            bs = seedDiscovery.bootstrapServers();
        }
        if (bs == null || bs.isEmpty()) {
            enabled = false;
            return false;
        }
        Properties props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bs);
        props.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        props.setProperty(ProducerConfig.RETRIES_CONFIG, "3");
        props.setProperty(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "65536");
        props.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producer = new KafkaProducer<>(props);
        enabled = true;
        log.info("Kafka producer initialized, servers={}", bs);
        return true;
    }

    public boolean sendBlock(String blockHash, byte[] data) {
        return send(kafkaConfiguration.getBlockTopic(), blockHash, data);
    }

    public boolean sendTransaction(String txHash, byte[] data) {
        return send(kafkaConfiguration.getTransactionTopic(), txHash, data);
    }

    private boolean send(String topic, String key, byte[] data) {
        if (producer == null && !ensureProducer()) {
            return false; // still no brokers discovered
        }
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, data);
            // Fire-and-forget: the producer's IO thread owns delivery (acks=all,
            // retries=3).  Blocking the caller here serialized every tx ingest
            // behind a broker round-trip.
            producer.send(record, (meta, err) -> {
                if (err != null) {
                    log.warn("Kafka async send failed topic={} key={}", topic, key, err);
                } else {
                    log.trace("Sent to topic={} partition={} offset={}", meta.topic(), meta.partition(), meta.offset());
                }
            });
            return true;
        } catch (Exception e) {
            log.warn("Kafka send failed topic={} key={}", topic, key, e);
            return false;
        }
    }

    @Override
    public void destroy() {
        if (producer != null) {
            producer.close();
            log.info("Kafka producer closed");
        }
    }
}
