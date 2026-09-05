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
     *
     * <p>The loop only ever CREATES a client when none exists or the previous
     * one reached a terminal state. A live client in CREATED/REBALANCING/
     * RUNNING heals itself — Kafka Streams re-joins its group autonomously.
     * Recreating a live client orphans the old one (close() does not reap a
     * member stuck in rebalance), and each orphan keeps churning the SAME
     * consumer group: observed as a permanent rebalance storm (~150 leaked
     * threads per handler per node) whose starving groups never delivered
     * attestations — hence zero finality — at ~100% CPU.
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
                    if (streams == null || isTerminal(streams.state())) {
                        runStream();
                    }
                    // Wait on THIS client; a slow/REBALANCING client must be
                    // waited out, not replaced next round.
                    if (awaitRunning(600_000)) {
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
        // Post-ready watchdog: startWhenReady RETURNS once the client first
        // reaches RUNNING, so a consumer that dies LATER (e.g. a DB outage
        // mid-record throws out of the processor and kills the stream thread —
        // attackvector §29) is never restarted. This daemon loops for the life
        // of the bean and resurrects any terminal/dead client via the same
        // idempotent ensureStarted() path the scheduler uses.
        Thread w = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (!isRunning()) {
                        log.warn("{} consumer not RUNNING (state={}) — restarting",
                                getClass().getSimpleName(), streams == null ? "null" : streams.state());
                        ensureStarted();
                    }
                } catch (Exception e) {
                    log.warn("{} watchdog restart failed: {}", getClass().getSimpleName(), e.getMessage());
                }
            }
        }, "stream-watchdog-" + getClass().getSimpleName());
        w.setDaemon(true);
        w.start();
    }

    private static boolean isTerminal(KafkaStreams.State s) {
        return s == KafkaStreams.State.ERROR || s == KafkaStreams.State.NOT_RUNNING
                || s == KafkaStreams.State.PENDING_SHUTDOWN;
    }

    /** Bounded wait for the current client to reach RUNNING; false on fatal states or timeout. */
    private boolean awaitRunning(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            KafkaStreams s = streams;
            if (s == null) return false;
            KafkaStreams.State st = s.state();
            if (st == KafkaStreams.State.RUNNING) return true;
            if (isTerminal(st)) return false;
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Idempotent kick for external schedulers: starts a client ONLY when none
     * is alive. Schedulers calling {@link #runStream()} directly recreated a
     * healthy client every tick and caused the orphan/rebalance storm above.
     */
    public void ensureStarted() {
        if (streams == null || isTerminal(streams.state())) {
            runStream();
        }
    }

    protected abstract String topic();

    protected abstract void process(KStream<String, byte[]> stream);

    /**
     * True when the record's key is stamped with a foreign chain id
     * ("chainId:payload-key", see KafkaMessageProducer.send). Such records are
     * skipped: one shared broker must never leak another chain's blocks/txs/
     * attestations into this node's consensus. Unprefixed keys (legacy
     * producers) are accepted.
     */
    protected boolean foreignChainRecord(String key) {
        // A null key carries no chain stamp and cannot be routed — drop it.
        // Without this guard a single null-keyed record (trivial for anyone
        // with produce access to post) NPEs the stream thread and permanently
        // disables that consumer: the failed offset never commits and the
        // thread is never replaced.
        if (key == null)
            return true;
        int i = key.indexOf(':');
        return i > 0 && !networkParameters.getChainId().equals(key.substring(0, i));
    }

    /** The payload key without the chain-id stamp (null stays null). */
    protected String recordKey(String key) {
        if (key == null)
            return null;
        int i = key.indexOf(':');
        return i > 0 ? key.substring(i + 1) : key;
    }

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
        // Batch blocks are multi-MB (a full batch.txPerBlock of PQ-signed
        // transactions). The default 1 MB fetch limits silently drop them,
        // so peers never receive the block and the mesh forks permanently.
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
                String.valueOf(KafkaMessageProducer.KAFKA_MAX_MESSAGE_BYTES));
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG,
                String.valueOf(KafkaMessageProducer.KAFKA_MAX_MESSAGE_BYTES));
        props.put("consumer.max.partition.fetch.bytes",
                String.valueOf(KafkaMessageProducer.KAFKA_MAX_MESSAGE_BYTES));
        props.put("consumer.fetch.max.bytes",
                String.valueOf(KafkaMessageProducer.KAFKA_MAX_MESSAGE_BYTES));

        StreamsBuilder builder = new StreamsBuilder();
        try {
            KStream<String, byte[]> stream = builder.stream(topic());
            process(stream);
        } catch (Exception e) {
            log.error("Failed to build stream topology", e);
            return;
        }

        // Close any previous client first: an unclosed KafkaStreams keeps its
        // whole thread family (admin, producer, coordinator, stream threads).
        if (streams != null) {
            try {
                streams.close(java.time.Duration.ofSeconds(10));
            } catch (Exception e) {
                log.debug("previous stream client close failed", e);
            }
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
        // Broadcast semantics: EVERY validator must consume EVERY record. A
        // shared suffix across nodes puts them in one consumer group and the
        // partition is served to only ONE member — peers then starve for votes
        // and blocks (observed as permanent head divergence). Always append a
        // per-node identity (API port is unique per node).
        return applicationId(getClass(), suffix, serverConfiguration.getPort());
    }

    /**
     * Kafka Streams application id for one of this node's stream handlers.
     *
     * <p>The id is the consumer-group id: every member that shares it competes
     * for the topic partitions, so TWO NODES MUST NEVER RESOLVE THE SAME ID.
     * A duplicate id silently splits the group — with a single-partition chain
     * topic one member is served and the others starve for votes/blocks, which
     * shows up only later as permanent head divergence and zero finality (the
     * deployed L0 pair wedged exactly this way when both ran on the same API
     * port with the same default CONSUMERIDSUFFIX).
     *
     * <p>Uniqueness therefore requires EACH node to configure a distinct
     * {@code kafka.consumerIdSuffix} (CONSUMERIDSUFFIX) — the API port is NOT
     * a sufficient discriminator when nodes bind the same port on different
     * hosts. The prod deploy scripts derive it per host from the advertised
     * domain (eu1/eu2, socialeu1/socialeu2) and refuse to start with a
     * duplicate.
     */
    static String applicationId(Class<?> handler, String suffix, String port) {
        return handler.getCanonicalName() + "_" + suffix + "-node" + port;
    }

    public boolean isRunning() {
        return streams != null && KafkaStreams.State.RUNNING.equals(streams.state());
    }
}
