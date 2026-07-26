package net.bigtangle.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.p2p.DnsDiscoveryResolver;
import net.bigtangle.p2p.NodeRecord;
import net.bigtangle.params.NetworkParameters;

@Component
public class KafkaMessageProducer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageProducer.class);

    private static final int DEFAULT_KAFKA_PORT = 9092;

    @Autowired
    private KafkaConfiguration kafkaConfiguration;

    @Autowired
    private NetworkParameters networkParameters;

    private KafkaProducer<String, byte[]> producer;
    private boolean enabled;

    @PostConstruct
    public void init() {
        String bs = kafkaConfiguration.getBootstrapServers();
        if (bs == null || bs.isEmpty()) {
            bs = discoverBootstrapServers();
        }
        if (bs == null || bs.isEmpty()) {
            log.info("Kafka disabled: no bootstrap servers configured or discovered");
            enabled = false;
            return;
        }
        enabled = true;
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
        log.info("Kafka producer initialized, servers={}", bs);
    }

    private String discoverBootstrapServers() {
        String[] seeds = networkParameters.getDnsSeeds();
        if (seeds == null || seeds.length == 0) {
            log.info("No DNS seeds configured for Kafka discovery");
            return null;
        }
        List<String> servers = new ArrayList<>();
        for (String seed : seeds) {
            try {
                List<NodeRecord> records = DnsDiscoveryResolver.resolve(seed);
                for (NodeRecord r : records) {
                    String addr = r.getHost() + ":" + DEFAULT_KAFKA_PORT;
                    if (!servers.contains(addr)) {
                        servers.add(addr);
                    }
                }
                log.info("DNS seed {} resolved to {} Kafka candidates", seed, records.size());
            } catch (Exception e) {
                log.warn("Failed to resolve DNS seed {} for Kafka discovery: {}", seed, e.getMessage());
            }
        }
        if (servers.isEmpty()) return null;
        return String.join(",", servers);
    }

    public boolean sendBlock(String blockHash, byte[] data) {
        return send(kafkaConfiguration.getBlockTopic(), blockHash, data);
    }

    public boolean sendTransaction(String txHash, byte[] data) {
        return send(kafkaConfiguration.getTransactionTopic(), txHash, data);
    }

    private boolean send(String topic, String key, byte[] data) {
        if (!enabled) return false;
        try {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, data);
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata meta = future.get();
            log.trace("Sent to topic={} partition={} offset={}", meta.topic(), meta.partition(), meta.offset());
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
