package net.bigtangle.kafka;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.server.service.BlockService;

/**
 * Consumes blocks from Kafka with an out-of-order retry buffer.
 *
 * <p>Blocks can arrive before their predecessors (Kafka and gossip race, or a
 * peer publishes a child first). The first ingest attempt of such a block
 * fails with MissingPredecessor — silently dropping it (and committing the
 * offset) permanently lost it for this node while peers stored it, diverging
 * confirmed lengths. Failed attempts are re-queued and replayed every few
 * seconds until they store or expire.
 */
@Service
public class BlockStreamHandler extends AbstractStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(BlockStreamHandler.class);

    /** Max buffered out-of-order blocks. Oldest entries are dropped beyond this. */
    private static final int RETRY_CAPACITY = 5_000;
    /** Give up on a block after this many failed attempts. */
    private static final int MAX_ATTEMPTS = 30;

    @Autowired
    BlockService blockService;

    private final ConcurrentLinkedQueue<RetryEntry> retryQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean replayThreadStarted;

    /** Buffered failed ingest with its attempt counter. */
    private static final class RetryEntry {
        final String keyHex;
        final byte[] key;
        final byte[] bytes;
        int attempts;

        RetryEntry(String keyHex, byte[] key, byte[] bytes) {
            this.keyHex = keyHex;
            this.key = key;
            this.bytes = bytes;
        }
    }

    @Override
    protected String topic() {
        return kafkaConfiguration.getBlockTopic();
    }

    @Override
    protected void process(KStream<String, byte[]> stream) {
        stream.foreach((key, bytes) -> {
            if (foreignChainRecord(key)) return;
            String payloadKey = recordKey(key);
            if (!blockService.addConnectedFromKafka(payloadKey.getBytes(), bytes).isPresent()) {
                enqueueRetry(payloadKey, bytes);
            }
        });
        startReplayLoop();
    }

    private void enqueueRetry(String key, byte[] bytes) {
        retryQueue.add(new RetryEntry(key, key.getBytes(), bytes));
        while (retryQueue.size() > RETRY_CAPACITY) {
            RetryEntry dropped = retryQueue.poll();
            if (dropped != null) {
                log.warn("retry buffer overflow: dropping block {} (oldest)", dropped.keyHex);
            }
        }
    }

    /** Periodically replays failed ingests; runs until the handler dies. */
    private void startReplayLoop() {
        if (replayThreadStarted) {
            return;
        }
        replayThreadStarted = true;
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int attempted = 0;
                Iterator<RetryEntry> it = retryQueue.iterator();
                while (it.hasNext() && attempted < 500) {
                    RetryEntry entry = it.next();
                    attempted++;
                    entry.attempts++;
                    if (blockService.addConnectedFromKafka(entry.key, entry.bytes).isPresent()) {
                        it.remove();
                    } else if (entry.attempts >= MAX_ATTEMPTS) {
                        // Give up LOUDLY: the block was received but could
                        // never be stored locally. Peers still hold their
                        // copies (broadcast is mesh-wide), so sync can fetch
                        // it later — but silently keeping a dead entry in the
                        // buffer hid permanent ingest failures until now.
                        it.remove();
                        log.warn("retry buffer: block {} dropped after {} failed attempts",
                                entry.keyHex, entry.attempts);
                    }
                }
                if (!retryQueue.isEmpty()) {
                    log.info("Block retry buffer: {} pending", retryQueue.size());
                }
            }
        }, "block-retry-replay");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Raw bytes of buffered failed ingests whose hash is wanted. Lets
     * {@code SyncBlockService} self-recover blocks this node's own consumer
     * failed to store before declaring them unservable by any peer.
     *
     * @param wanted hex block hashes to look up
     * @return matching key-hex → serialized block bytes (entries stay buffered)
     */
    public java.util.Map<String, byte[]> retryBytesFor(Set<Sha256Hash> wanted) {
        java.util.Map<String, byte[]> out = new java.util.HashMap<>();
        if (wanted == null || wanted.isEmpty()) {
            return out;
        }
        Set<String> hexWanted = new java.util.HashSet<>();
        for (Sha256Hash h : wanted) {
            hexWanted.add(h.toString());
        }
        for (RetryEntry e : retryQueue) {
            if (hexWanted.contains(e.keyHex)) {
                out.put(e.keyHex, e.bytes);
            }
        }
        return out;
    }
}
