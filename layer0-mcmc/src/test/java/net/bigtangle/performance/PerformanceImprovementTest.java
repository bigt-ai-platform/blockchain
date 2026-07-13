package net.bigtangle.performance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.utils.Json;

public class PerformanceImprovementTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceImprovementTest.class);

    // Matches the fastExp implementation in TipsService
    private static final int EXP_TABLE_MIN = -200;
    private static final int EXP_TABLE_MAX = 200;
    private static final int EXP_TABLE_SIZE = EXP_TABLE_MAX - EXP_TABLE_MIN + 1;
    private static volatile double[] expTable;
    private static volatile double expTableAlpha;

    private static double fastExp(double alpha, long diff) {
        if (diff >= EXP_TABLE_MIN && diff <= EXP_TABLE_MAX) {
            double[] table = expTable;
            if (table == null || expTableAlpha != alpha) {
                table = new double[EXP_TABLE_SIZE];
                for (int i = EXP_TABLE_MIN; i <= EXP_TABLE_MAX; i++) {
                    table[i - EXP_TABLE_MIN] = Math.exp(alpha * i);
                }
                expTable = table;
                expTableAlpha = alpha;
            }
            return table[(int) diff - EXP_TABLE_MIN];
        }
        return Math.exp(alpha * diff);
    }

    @Test
    public void testJsonMapperIsSingleton() {
        ObjectMapper first = Json.jsonmapper();
        ObjectMapper second = Json.jsonmapper();
        ObjectMapper third = Json.jsonmapper();
        assertSame(first, second, "Json.jsonmapper() must return the same singleton instance");
        assertSame(second, third, "Json.jsonmapper() must return the same singleton instance");
    }

    @Test
    public void testJsonMapperConfiguration() {
        ObjectMapper mapper = Json.jsonmapper();
        assertNotNull(mapper);
        assertTrue(mapper.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT));
    }

    @Test
    public void testBlockMCMCSerializationRoundTrip() throws Exception {
        Sha256Hash hash = Sha256Hash.of("test-data".getBytes());
        BlockMCMC original = new BlockMCMC(hash, 75, 10, 100);

        ObjectMapper mapper = Json.jsonmapper();
        byte[] bytes = mapper.writeValueAsBytes(original);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        BlockMCMC deserialized = mapper.readValue(bytes, BlockMCMC.class);
        assertEquals(original.getBlockHash(), deserialized.getBlockHash());
        assertEquals(original.getRating(), deserialized.getRating());
        assertEquals(original.getDepth(), deserialized.getDepth());
        assertEquals(original.getCumulativeWeight(), deserialized.getCumulativeWeight());
    }

    @Test
    public void testSharedExecutorService() throws Exception {
        ExecutorService shared = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        int taskCount = 20;
        CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
        for (int i = 0; i < taskCount; i++) {
            int taskId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, shared);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        shared.shutdownNow();
        assertTrue(shared.isShutdown());
    }

    @Test
    public void testBlockMCMCDefaultInstance() {
        Sha256Hash hash = Sha256Hash.of("test-block".getBytes());
        BlockMCMC def = BlockMCMC.defaultBlockMCMC(hash);
        assertEquals(hash, def.getBlockHash());
        assertEquals(0, def.getRating());
        assertEquals(0, def.getDepth());
        assertEquals(1, def.getCumulativeWeight());
    }

    @Test
    public void testPerformanceDirectObjectVsJson() throws Exception {
        Sha256Hash hash = Sha256Hash.of("perf-test-data".getBytes());
        BlockMCMC original = new BlockMCMC(hash, 50, 5, 200);
        ObjectMapper mapper = Json.jsonmapper();
        byte[] serialized = mapper.writeValueAsBytes(original);

        int iterations = 10000;

        long jsonStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            @SuppressWarnings("unused")
            BlockMCMC result = mapper.readValue(serialized, BlockMCMC.class);
        }
        long jsonDuration = System.nanoTime() - jsonStart;

        long directStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            new BlockMCMC(original.getBlockHash(), original.getRating(),
                    original.getDepth(), original.getCumulativeWeight());
        }
        long directDuration = System.nanoTime() - directStart;

        log.info("JSON deserialization {} iterations: {} ms", iterations, jsonDuration / 1_000_000);
        log.info("Direct object access {} iterations: {} ms", iterations, directDuration / 1_000_000);

        assertTrue(directDuration < jsonDuration,
                "Direct object access should be faster than JSON deserialization. Direct: "
                        + directDuration / 1_000_000 + "ms, JSON: " + jsonDuration / 1_000_000 + "ms");
    }

    @Test
    public void testCompletableFutureTimeoutEquivalent() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }, executor);

        String result = future.orTimeout(5, TimeUnit.SECONDS).get();
        assertEquals("done", result);
        executor.shutdownNow();
    }

    @Test
    public void testFastExpMatchesMathExp() {
        double alpha = -0.05;
        long[] testDiffs = {-200, -100, -50, -10, -5, -1, 0, 1, 5, 10, 50, 100, 200};
        for (long diff : testDiffs) {
            double expected = Math.exp(alpha * diff);
            double actual = fastExp(alpha, diff);
            assertEquals(expected, actual, 1e-15, "fastExp(" + diff + ") should match Math.exp");
        }
    }

    @Test
    public void testFastExpTableRebuildsOnAlphaChange() {
        double expectedDefault = fastExp(-0.05, 10);
        double mathExp = Math.exp(-0.05 * 10);
        assertEquals(mathExp, expectedDefault, 1e-15);

        double expectedNew = fastExp(-0.1, 10);
        double mathExpNew = Math.exp(-0.1 * 10);
        assertEquals(mathExpNew, expectedNew, 1e-15);
    }

    @Test
    public void testFastExpOutsideTableRange() {
        double alpha = -0.05;
        assertEquals(Math.exp(alpha * 500), fastExp(alpha, 500), 1e-15);
        assertEquals(Math.exp(alpha * (-500)), fastExp(alpha, -500), 1e-15);
    }

    @Test
    public void testFastExpPerformance() {
        double alpha = -0.05;
        int iterations = 100000;
        long[] diffs = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            diffs[i] = (long) (Math.random() * 200 - 100);
        }

        long mathExpStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            @SuppressWarnings("unused")
            double val = Math.exp(alpha * diffs[i]);
        }
        long mathExpDuration = System.nanoTime() - mathExpStart;

        long fastExpStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            @SuppressWarnings("unused")
            double val = fastExp(alpha, diffs[i]);
        }
        long fastExpDuration = System.nanoTime() - fastExpStart;

        log.info("Math.exp * {} iterations: {} ms", iterations, mathExpDuration / 1_000_000);
        log.info("fastExp   * {} iterations: {} ms", iterations, fastExpDuration / 1_000_000);

        assertTrue(fastExpDuration < mathExpDuration,
                "fastExp should be faster than Math.exp. fastExp: "
                        + fastExpDuration / 1_000_000 + "ms, Math.exp: " + mathExpDuration / 1_000_000 + "ms");
    }

    @Test
    public void testBatchCollectionOperations() {
        Set<Sha256Hash> hashes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            hashes.add(Sha256Hash.of(("hash-" + i).getBytes()));
        }

        assertEquals(100, hashes.size());

        Set<Sha256Hash> known = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            known.add(Sha256Hash.of(("hash-" + i).getBytes()));
        }

        Set<Sha256Hash> missing = new HashSet<>(hashes);
        missing.removeAll(known);
        assertEquals(50, missing.size());
    }
}
