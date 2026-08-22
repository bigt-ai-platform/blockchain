package net.bigtangle.kafka;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PreDestroy;
import net.bigtangle.p2p.DnsDiscoveryResolver;

public abstract class AbstractStreamHandler {

    @Autowired
    protected KafkaConfiguration kafkaConfiguration;

    @Autowired
    protected net.bigtangle.params.NetworkParameters networkParameters;

    @Autowired
    protected KafkaSeedDiscovery seedDiscovery;

    @Autowired
    protected net.bigtangle.server.config.ServerConfiguration serverConfiguration;

    protected KafkaStreams streams;
    private static final Logger log = LoggerFactory.getLogger(AbstractStreamHandler.class);

    private volatile boolean started;
    private final java.util.concurrent.atomic.AtomicBoolean startScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Self-scheduling bootstrap: starts the retrying starter thread after
     * bean wiring, gated only by {@code server.runKafkaStream}. Deliberately
     * independent of the shared @Scheduled infrastructure, whose one-shot
     * starter died with early-boot failures and left nodes without consumers.
     */
    @jakarta.annotation.PostConstruct
    public void startWhenReady() {
        if (!serverConfiguration.getRunKafkaStream()) {
            return;
        }
        if (!startScheduled.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 120 && !started; attempt++) {
                try {
                    runStream();
                    if (streams != null && KafkaStreams.State.RUNNING.equals(streams.state())) {
                        started = true;
                        return;
                    }
                } catch (Exception e) {
                    log.warn("stream start attempt {} failed: {}", attempt, e.getMessage());
                }
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "stream-starter-" + getClass().getSimpleName());
        t.setDaemon(true);
        t.start();
    }

    protected abstract String topic();

    protected abstract void process(KStream<String, byte[]> stream);

    public void runStream() {
        String bs = kafkaConfiguration.getBootstrapServers();
        if (bs == null || bs.isEmpty()) {
            bs = discoverBootstrapServers();
        }
        if (bs == null || bs.isEmpty()) return;
        log.info("Starting Kafka stream handler: {} topic={}", getClass().getSimpleName(), topic());
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bs);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.Serdes$StringSerde");
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.Serdes$ByteArraySerde");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        StreamsBuilder builder = new StreamsBuilder();
        try {
            KStream<String, byte[]> stream = builder.stream(topic());
            process(stream);
        } catch (Exception e) {
            log.error("Failed to build stream topology", e);
            return;
        }

        streams = new KafkaStreams(builder.build(), props);
        streams.setUncaughtExceptionHandler((thread, ex) -> {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            log.error("Kafka stream uncaught exception: {}", ex.getMessage());
            log.error(sw.toString());
        });
        streams.start();
        log.info("Kafka stream handler started: {}", getClass().getSimpleName());
    }

    private String discoverBootstrapServers() {
        // Delegates to the shared live registry (config + DNS + gossip adverts).
        String bs = seedDiscovery.bootstrapServers();
        if (bs == null) {
            String[] seeds = networkParameters.getDnsSeeds();
            if (seeds != null && seeds.length > 0 && !dnsWarned) {
                dnsWarned = true;
                log.info("No Kafka brokers discovered yet; DNS seeds will be retried by the discovery service");
            }
        }
        return bs;
    }

    private volatile boolean dnsWarned;

    @PreDestroy
    public void closeStream() {
        if (streams != null) {
            streams.close();
            log.info("Kafka stream handler closed: {}", getClass().getSimpleName());
        }
    }

    private String getApplicationId() {
        String suffix = kafkaConfiguration.getConsumerIdSuffix();
        if (suffix == null || suffix.isEmpty()) {
            suffix = "mjWvzPZz4YJtWqb7ux7cdgq5G7rzkg3bXG";
        }
        return getClass().getCanonicalName() + "_" + suffix;
    }

    public boolean isRunning() {
        return streams != null && KafkaStreams.State.RUNNING.equals(streams.state());
    }
}
