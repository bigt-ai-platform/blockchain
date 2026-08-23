package net.bigtangle.kafka;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    private final ConcurrentLinkedQueue<byte[][]> retryQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean replayThreadStarted;

    @Override
    protected String topic() {
        return kafkaConfiguration.getBlockTopic();
    }

    @Override
    protected void process(KStream<String, byte[]> stream) {
        stream.foreach((key, bytes) -> {
            if (!blockService.addConnectedFromKafka(key.getBytes(), bytes).isPresent()) {
                enqueueRetry(key.getBytes(), bytes);
            }
        });
        startReplayLoop();
    }

    private void enqueueRetry(byte[] key, byte[] bytes) {
        retryQueue.add(new byte[][] { key, bytes });
        while (retryQueue.size() > RETRY_CAPACITY) {
            retryQueue.poll();
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
                Iterator<byte[][]> it = retryQueue.iterator();
                while (it.hasNext() && attempted < 500) {
                    byte[][] kv = it.next();
                    attempted++;
                    if (blockService.addConnectedFromKafka(kv[0], kv[1]).isPresent()) {
                        it.remove();
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
}
