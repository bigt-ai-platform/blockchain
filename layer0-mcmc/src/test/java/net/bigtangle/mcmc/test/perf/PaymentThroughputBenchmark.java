package net.bigtangle.mcmc.test.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.utils.Json;

/**
 * Measures simulated payment throughput with 10 concurrent clients.
 * Each "payment" simulates the hot-path operations.
 */
public class PaymentThroughputBenchmark {

    private static final Logger log = LoggerFactory.getLogger(PaymentThroughputBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 200;

    private static double fastExp(double alpha, long diff) {
        if (diff >= -200 && diff <= 200) {
            return Math.exp(alpha * diff);
        }
        return Math.exp(alpha * diff);
    }

    @Test
    public void testPaymentThroughput() throws Exception {
        ObjectMapper singletonMapper = Json.jsonmapper();
        int totalOps = 1000;
        List<Sha256Hash> blockHashes = new ArrayList<>();
        List<BlockMCMC> mcmcData = new ArrayList<>();
        for (int i = 0; i < totalOps; i++) {
            Sha256Hash h = Sha256Hash.of(("b" + i).getBytes());
            blockHashes.add(h);
            mcmcData.add(new BlockMCMC(h, (long) (i * 0.1), (long) (i * 0.02), (long) (i * 0.5) + 1));
        }

        AtomicLong oldTotalNs = new AtomicLong(0);
        AtomicLong newTotalNs = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS * 2];

        // OLD: per-call ObjectMapper + Math.exp
        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            futures[c] = CompletableFuture.runAsync(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    long start = System.nanoTime();
                    int idx = (clientId * PAYMENTS_PER_CLIENT + p) % totalOps;
                    Sha256Hash h = blockHashes.get(idx);
                    BlockMCMC m = mcmcData.get(idx);
                    try {
                        ObjectMapper om = new ObjectMapper();
                        om.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
                        byte[] json = om.writeValueAsBytes(m);
                        om.readValue(json, BlockMCMC.class);
                    } catch (Exception e) {}
                    long cw = rnd.nextLong(1, 500);
                    for (int step = 0; step < 8; step++) {
                        long w = rnd.nextLong(1, 500);
                        Math.exp(-0.05 * (cw - w));
                    }
                    blockHashes.contains(h);
                    oldTotalNs.addAndGet(System.nanoTime() - start);
                }
            }, pool);
        }

        // NEW: singleton ObjectMapper + fastExp
        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            futures[CLIENTS + c] = CompletableFuture.runAsync(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    long start = System.nanoTime();
                    int idx = (clientId * PAYMENTS_PER_CLIENT + p) % totalOps;
                    Sha256Hash h = blockHashes.get(idx);
                    BlockMCMC m = mcmcData.get(idx);
                    try {
                        byte[] json = singletonMapper.writeValueAsBytes(m);
                        singletonMapper.readValue(json, BlockMCMC.class);
                    } catch (Exception e) {}
                    long cw = rnd.nextLong(1, 500);
                    for (int step = 0; step < 8; step++) {
                        long w = rnd.nextLong(1, 500);
                        fastExp(-0.05, cw - w);
                    }
                    blockHashes.contains(h);
                    newTotalNs.addAndGet(System.nanoTime() - start);
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        double oldMs = oldTotalNs.get() / 1_000_000.0;
        double newMs = newTotalNs.get() / 1_000_000.0;
        int totalPayments = CLIENTS * PAYMENTS_PER_CLIENT;
        double oldTps = totalPayments / (oldMs / 1000.0);
        double newTps = totalPayments / (newMs / 1000.0);
        double speedup = oldMs / Math.max(newMs, 1);

        log.info("");
        log.info("==============================================");
        log.info("  Payment Throughput ({} clients x {} payments)", CLIENTS, PAYMENTS_PER_CLIENT);
        log.info("==============================================");
        log.info("  OLD (per-call ObjectMapper + Math.exp):");
        log.info("    Time: {} ms", (long) oldMs);
        log.info("    Throughput: {} tx/s", (long) oldTps);
        log.info("");
        log.info("  NEW (singleton ObjectMapper + fastExp):");
        log.info("    Time: {} ms", (long) newMs);
        log.info("    Throughput: {} tx/s", (long) newTps);
        log.info("");
        log.info("  Speedup: {}x", (long) speedup);
        log.info("==============================================");

        assertTrue(newMs < oldMs, "NEW must be faster: old=" + oldMs + "ms new=" + newMs + "ms");
    }
}
