package net.bigtangle.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.p2p.DnsDiscoveryResolver;
import net.bigtangle.p2p.NodeRecord;
import net.bigtangle.params.NetworkParameters;

/**
 * Live registry of Kafka bootstrap endpoints, filled from three sources in
 * increasing freshness:
 * <ol>
 * <li>static {@code kafka.bootstrapServers} config</li>
 * <li>DNS seeds (enrtree) — re-resolved periodically so a node started before
 * any broker exists still finds one later (the old code resolved once at init
 * and stayed empty forever)</li>
 * <li>gossip advertisements from peers ({@link #addDiscovered})</li>
 * </ol>
 * Consumers: {@link KafkaMessageProducer}, {@link AbstractStreamHandler},
 * {@code GossipProtocol} (advert ingestion).
 */
@Component
public class KafkaSeedDiscovery implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaSeedDiscovery.class);

    private static final int DEFAULT_KAFKA_PORT = 9092;
    private static final long REFRESH_INTERVAL_SEC = 60;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private KafkaConfiguration kafkaConfiguration;

    private final Set<String> brokers = ConcurrentHashMap.newKeySet();
    private Thread refresher;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        String staticCfg = kafkaConfiguration.getBootstrapServers();
        if (staticCfg != null && !staticCfg.isBlank()) {
            addDiscovered(staticCfg.split(","));
            log.info("Kafka discovery seeded from config: {}", staticCfg);
        }
        refreshFromDns();
        refresher = new Thread(this::refreshLoop, "kafka-seed-discovery");
        refresher.setDaemon(true);
        refresher.start();
    }

    private void refreshLoop() {
        while (running) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(REFRESH_INTERVAL_SEC));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            refreshFromDns();
        }
    }

    /** Resolve DNS seeds and merge candidates into the live set. */
    public void refreshFromDns() {
        String[] seeds = networkParameters.getDnsSeeds();
        if (seeds == null || seeds.length == 0) {
            return;
        }
        List<String> found = new ArrayList<>();
        for (String seed : seeds) {
            try {
                List<NodeRecord> records = DnsDiscoveryResolver.resolve(seed);
                for (NodeRecord r : records) {
                    int port = r.getTcpPort() > 0 ? r.getTcpPort() : DEFAULT_KAFKA_PORT;
                    found.add(r.getHost() + ":" + port);
                }
            } catch (Exception e) {
                log.debug("DNS seed {} resolution failed: {}", seed, e.getMessage());
            }
        }
        if (!found.isEmpty()) {
            addDiscovered(found.toArray(new String[0]));
            log.info("Kafka discovery: {} broker endpoint(s) known", brokers.size());
        }
    }

    /** Merge gossip-advertised (or config) endpoints into the live set. */
    public void addDiscovered(String... endpoints) {
        for (String s : endpoints) {
            if (s != null && !s.isBlank()) {
                brokers.add(s.trim());
            }
        }
    }

    /**
     * Current bootstrap list as a comma-joined string, or {@code null} when
     * nothing has been discovered yet.
     */
    public String bootstrapServers() {
        if (brokers.isEmpty()) {
            return null;
        }
        // Sorted for stable ordering across calls/nodes.
        List<String> sorted = new ArrayList<>(brokers);
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }

    @Override
    public void destroy() {
        running = false;
        if (refresher != null) {
            refresher.interrupt();
        }
    }
}
