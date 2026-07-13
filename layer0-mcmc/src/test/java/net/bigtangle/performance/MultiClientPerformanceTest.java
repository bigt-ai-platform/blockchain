package net.bigtangle.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.utils.Json;

public class MultiClientPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(MultiClientPerformanceTest.class);

    // Matches implementation in TipsService
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

    // Simulates per-request ExecutorService (old approach)
    private static <T> T executeWithNewExecutor(Callable<T> task, long timeout, TimeUnit unit) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get(timeout, unit);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final ExecutorService sharedExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

    // Simulates shared executor (new approach)
    private static <T> T executeWithSharedExecutor(Callable<T> task, long timeout, TimeUnit unit) throws Exception {
        return sharedExecutor.submit(task).get(timeout, unit);
    }

    // --- Concurrent ObjectMapper Tests ---

    @Test
    public void testConcurrentObjectMapperSingletonUnderLoad() throws Exception {
        int clientCount = 50;
        int operationsPerClient = 2000;
        AtomicLong oldMapperTotalTime = new AtomicLong(0);
        AtomicLong singletonMapperTotalTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            int clientId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    // Simulate old approach: create new ObjectMapper each time
                    long oldStart = System.nanoTime();
                    for (int j = 0; j < operationsPerClient; j++) {
                        ObjectMapper m = new ObjectMapper();
                        m.configure(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, true);
                        m.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
                        @SuppressWarnings("unused")
                        byte[] bytes = m.writeValueAsBytes(new BlockMCMC(
                                Sha256Hash.of(("client-" + clientId + "-" + j).getBytes()),
                                50, 5, 100));
                    }
                    long oldTime = System.nanoTime() - oldStart;

                    // Simulate new approach: use singleton
                    long newStart = System.nanoTime();
                    ObjectMapper mapper = Json.jsonmapper();
                    for (int j = 0; j < operationsPerClient; j++) {
                        @SuppressWarnings("unused")
                        byte[] bytes = mapper.writeValueAsBytes(new BlockMCMC(
                                Sha256Hash.of(("client-" + clientId + "-" + j).getBytes()),
                                50, 5, 100));
                    }
                    long newTime = System.nanoTime() - newStart;

                    oldMapperTotalTime.addAndGet(oldTime);
                    singletonMapperTotalTime.addAndGet(newTime);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get(), "No errors during concurrent ObjectMapper test");

        long oldMs = oldMapperTotalTime.get() / 1_000_000;
        long newMs = singletonMapperTotalTime.get() / 1_000_000;
        double speedup = (double) oldMs / Math.max(newMs, 1);

        log.info("=== Concurrent ObjectMapper Performance ===");
        log.info("Clients: {}, ops/client: {}, total ops: {}", clientCount, operationsPerClient,
                clientCount * operationsPerClient);
        log.info("Old (new mapper each call): {} ms total", oldMs);
        log.info("New (singleton mapper):      {} ms total", newMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(newMs < oldMs,
                "Singleton ObjectMapper should be faster than per-call creation. Old: "
                        + oldMs + "ms, New: " + newMs + "ms");
    }

    // --- Concurrent Math.exp vs fastExp Tests ---

    @Test
    public void testConcurrentFastExpUnderLoad() throws Exception {
        int clientCount = 50;
        int operationsPerClient = 5000;
        AtomicLong mathExpTotalTime = new AtomicLong(0);
        AtomicLong fastExpTotalTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        double alpha = -0.05;
        long[][] diffsByClient = new long[clientCount][operationsPerClient];
        Random rnd = new Random(42);
        for (int i = 0; i < clientCount; i++) {
            for (int j = 0; j < operationsPerClient; j++) {
                diffsByClient[i][j] = (long) (rnd.nextDouble() * 200 - 100);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            int clientId = i;
            long[] diffs = diffsByClient[i];
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    long mathExpStart = System.nanoTime();
                    for (int j = 0; j < operationsPerClient; j++) {
                        @SuppressWarnings("unused")
                        double val = Math.exp(alpha * diffs[j]);
                    }
                    mathExpTotalTime.addAndGet(System.nanoTime() - mathExpStart);

                    long fastExpStart = System.nanoTime();
                    for (int j = 0; j < operationsPerClient; j++) {
                        @SuppressWarnings("unused")
                        double val = fastExp(alpha, diffs[j]);
                    }
                    fastExpTotalTime.addAndGet(System.nanoTime() - fastExpStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long mathExpMs = mathExpTotalTime.get() / 1_000_000;
        long fastExpMs = fastExpTotalTime.get() / 1_000_000;
        double speedup = (double) mathExpMs / Math.max(fastExpMs, 1);

        log.info("=== Concurrent Math.exp vs fastExp ===");
        log.info("Clients: {}, ops/client: {}, total ops: {}", clientCount, operationsPerClient,
                clientCount * operationsPerClient);
        log.info("Math.exp: {} ms total", mathExpMs);
        log.info("fastExp:  {} ms total", fastExpMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(fastExpMs < mathExpMs,
                "fastExp should be faster than Math.exp under concurrent load. Math.exp: "
                        + mathExpMs + "ms, fastExp: " + fastExpMs + "ms");
    }

    // --- BlockMCMC: JSON round-trip vs Direct Object Access ---

    private static class BlockMCMCCacheBenchmark {
        private final ConcurrentHashMap<Sha256Hash, byte[]> jsonCache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Sha256Hash, BlockMCMC> objectCache = new ConcurrentHashMap<>();
        private final ObjectMapper mapper = Json.jsonmapper();

        BlockMCMC getViaJson(Sha256Hash hash) {
            byte[] data = jsonCache.get(hash);
            if (data == null) return null;
            try {
                return mapper.readValue(data, BlockMCMC.class);
            } catch (Exception e) {
                return null;
            }
        }

        BlockMCMC getViaObject(Sha256Hash hash) {
            return objectCache.get(hash);
        }

        void put(Sha256Hash hash, BlockMCMC mcmc) {
            try {
                jsonCache.put(hash, mapper.writeValueAsBytes(mcmc));
            } catch (Exception e) {
                // ignore
            }
            objectCache.put(hash, mcmc);
        }
    }

    @Test
    public void testConcurrentBlockMCMCAccess() throws Exception {
        int clientCount = 30;
        int lookupsPerClient = 5000;
        int cacheSize = 500;
        AtomicLong jsonTotalTime = new AtomicLong(0);
        AtomicLong objectTotalTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        BlockMCMCCacheBenchmark cache = new BlockMCMCCacheBenchmark();
        List<Sha256Hash> hashes = new ArrayList<>();
        for (int i = 0; i < cacheSize; i++) {
            Sha256Hash h = Sha256Hash.of(("cache-hash-" + i).getBytes());
            hashes.add(h);
            cache.put(h, new BlockMCMC(h, ThreadLocalRandom.current().nextLong(0, 100),
                    ThreadLocalRandom.current().nextLong(0, 20),
                    ThreadLocalRandom.current().nextLong(1, 500)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    long jsonStart = System.nanoTime();
                    for (int j = 0; j < lookupsPerClient; j++) {
                        Sha256Hash h = hashes.get(j % cacheSize);
                        BlockMCMC m = cache.getViaJson(h);
                        if (m == null) errors.incrementAndGet();
                    }
                    jsonTotalTime.addAndGet(System.nanoTime() - jsonStart);

                    long objectStart = System.nanoTime();
                    for (int j = 0; j < lookupsPerClient; j++) {
                        Sha256Hash h = hashes.get(j % cacheSize);
                        BlockMCMC m = cache.getViaObject(h);
                        if (m == null) errors.incrementAndGet();
                    }
                    objectTotalTime.addAndGet(System.nanoTime() - objectStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long jsonMs = jsonTotalTime.get() / 1_000_000;
        long objectMs = objectTotalTime.get() / 1_000_000;
        double speedup = (double) jsonMs / Math.max(objectMs, 1);

        log.info("=== Concurrent BlockMCMC Cache Access ===");
        log.info("Clients: {}, lookups/client: {}, total: {}", clientCount, lookupsPerClient,
                clientCount * lookupsPerClient);
        log.info("JSON round-trip: {} ms total", jsonMs);
        log.info("Direct object:   {} ms total", objectMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(objectMs < jsonMs,
                "Direct object access should be faster than JSON round-trip under concurrent load. JSON: "
                        + jsonMs + "ms, Object: " + objectMs + "ms");
    }

    // --- Concurrent Batch Collection Operations ---

    @Test
    public void testConcurrentBatchHashOperations() throws Exception {
        int clientCount = 40;
        int batchSize = 1000;
        AtomicLong sequentialTime = new AtomicLong(0);
        AtomicLong batchTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Pre-create test data
        List<Set<Sha256Hash>> allHashes = new ArrayList<>();
        List<Set<Sha256Hash>> allKnown = new ArrayList<>();
        for (int c = 0; c < clientCount; c++) {
            Set<Sha256Hash> hashes = new HashSet<>();
            for (int i = 0; i < batchSize; i++) {
                hashes.add(Sha256Hash.of(("batch-hash-" + c + "-" + i).getBytes()));
            }
            allHashes.add(hashes);
            Set<Sha256Hash> known = new HashSet<>();
            for (int i = 0; i < batchSize / 2; i++) {
                known.add(Sha256Hash.of(("batch-hash-" + c + "-" + i).getBytes()));
            }
            allKnown.add(known);
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            int clientId = i;
            Set<Sha256Hash> hashes = allHashes.get(i);
            Set<Sha256Hash> known = allKnown.get(i);
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    // Simulate N+1: individual lookups (like old subUpdateWeightAndDepth)
                    long sequentialStart = System.nanoTime();
                    for (Sha256Hash h : hashes) {
                        if (!known.contains(h)) {
                            @SuppressWarnings("unused")
                            boolean found = known.contains(h);
                        }
                    }
                    sequentialTime.addAndGet(System.nanoTime() - sequentialStart);

                    // Simulate batch: set operations (like new batch-load)
                    long batchStart = System.nanoTime();
                    Set<Sha256Hash> missing = new HashSet<>(hashes);
                    missing.removeAll(known);
                    for (Sha256Hash h : missing) {
                        @SuppressWarnings("unused")
                        boolean found = known.contains(h);
                    }
                    batchTime.addAndGet(System.nanoTime() - batchStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long seqMs = sequentialTime.get() / 1_000_000;
        long batchMs = batchTime.get() / 1_000_000;
        double speedup = (double) seqMs / Math.max(batchMs, 1);

        log.info("=== Concurrent Batch Collection Operations ===");
        log.info("Clients: {}, batch size: {}", clientCount, batchSize);
        log.info("Sequential (N+1): {} ms total", seqMs);
        log.info("Batch set ops:    {} ms total", batchMs);
        log.info("Speedup: {:.1f}x", speedup);
    }

    // --- Simulated MCMC Walk with Cached Approvers ---

    private static class MCMCSimulatedWalk {
        private static final double ALPHA = -0.05;
        private final Random rnd = new Random();

        // Old: no approver cache, calls Math.exp each time
        double walkNoCache(int steps, List<Long> weights) {
            double result = 0;
            long currentWeight = weights.get(0);
            for (int s = 0; s < steps; s++) {
                double totalWeight = 0;
                for (long w : weights) {
                    totalWeight += Math.exp(ALPHA * (currentWeight - w));
                }
                double pick = rnd.nextDouble() * totalWeight;
                for (int i = 0; i < weights.size(); i++) {
                    pick -= Math.exp(ALPHA * (currentWeight - weights.get(i)));
                    if (pick <= 0) {
                        currentWeight = weights.get(i);
                        result += currentWeight;
                        break;
                    }
                }
            }
            return result;
        }

        // New: cached transition weights, uses fastExp
        double walkWithCache(int steps, List<Long> weights, HashMap<Long, Double> transitionCache) {
            double result = 0;
            long currentWeight = weights.get(0);
            for (int s = 0; s < steps; s++) {
                double totalWeight = 0;
                double[] probs = new double[weights.size()];
                for (int i = 0; i < weights.size(); i++) {
                    long diff = currentWeight - weights.get(i);
                    Double cached = transitionCache.get(diff);
                    if (cached == null) {
                        cached = fastExp(ALPHA, diff);
                        transitionCache.put(diff, cached);
                    }
                    probs[i] = cached;
                    totalWeight += cached;
                }
                double pick = rnd.nextDouble() * totalWeight;
                for (int i = 0; i < weights.size(); i++) {
                    pick -= probs[i];
                    if (pick <= 0) {
                        currentWeight = weights.get(i);
                        result += currentWeight;
                        break;
                    }
                }
            }
            return result;
        }
    }

    @Test
    public void testSimulatedMCMCWALkPerformance() throws Exception {
        int clientCount = 20;
        int walksPerClient = 200;
        int stepsPerWalk = 50;
        int candidateCount = 10;
        AtomicLong oldTotalTime = new AtomicLong(0);
        AtomicLong newTotalTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Pre-generate weight sequences for reproducibility
        List<List<Long>> allWeights = new ArrayList<>();
        Random rnd = new Random(1234);
        for (int c = 0; c < clientCount; c++) {
            List<Long> weights = new ArrayList<>();
            for (int i = 0; i < candidateCount; i++) {
                weights.add((long) (rnd.nextDouble() * 500));
            }
            allWeights.add(weights);
        }

        MCMCSimulatedWalk walker = new MCMCSimulatedWalk();
        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            int clientId = i;
            List<Long> weights = allWeights.get(i);
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    long oldStart = System.nanoTime();
                    for (int w = 0; w < walksPerClient; w++) {
                        @SuppressWarnings("unused")
                        double r = walker.walkNoCache(stepsPerWalk, weights);
                    }
                    oldTotalTime.addAndGet(System.nanoTime() - oldStart);

                    long newStart = System.nanoTime();
                    HashMap<Long, Double> transitionCache = new HashMap<>();
                    for (int w = 0; w < walksPerClient; w++) {
                        @SuppressWarnings("unused")
                        double r = walker.walkWithCache(stepsPerWalk, weights, transitionCache);
                    }
                    newTotalTime.addAndGet(System.nanoTime() - newStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long oldMs = oldTotalTime.get() / 1_000_000;
        long newMs = newTotalTime.get() / 1_000_000;
        double speedup = (double) oldMs / Math.max(newMs, 1);

        log.info("=== Simulated MCMC Walk Performance ===");
        log.info("Clients: {}, walks/client: {}, steps/walk: {}, candidates: {}",
                clientCount, walksPerClient, stepsPerWalk, candidateCount);
        log.info("Old (exp each step): {} ms total", oldMs);
        log.info("New (cached+table):  {} ms total", newMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(newMs < oldMs,
                "Optimized MCMC walk should be faster. Old: " + oldMs + "ms, New: " + newMs + "ms");
    }

    // --- Shared Executor vs Per-Request Executor ---

    @Test
    public void testExecutorOverheadComparison() throws Exception {
        int requestCount = 500;
        AtomicLong perRequestTotal = new AtomicLong(0);
        AtomicLong sharedTotal = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CompletableFuture<?>[] futures = new CompletableFuture[10];

        for (int i = 0; i < 10; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    long perRequestStart = System.nanoTime();
                    for (int r = 0; r < requestCount; r++) {
                        executeWithNewExecutor(() -> {
                            Thread.sleep(1);
                            return "";
                        }, 30, TimeUnit.SECONDS);
                    }
                    perRequestTotal.addAndGet(System.nanoTime() - perRequestStart);

                    long sharedStart = System.nanoTime();
                    for (int r = 0; r < requestCount; r++) {
                        executeWithSharedExecutor(() -> {
                            Thread.sleep(1);
                            return "";
                        }, 30, TimeUnit.SECONDS);
                    }
                    sharedTotal.addAndGet(System.nanoTime() - sharedStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long perRequestMs = perRequestTotal.get() / 1_000_000;
        long sharedMs = sharedTotal.get() / 1_000_000;
        double speedup = (double) perRequestMs / Math.max(sharedMs, 1);

        log.info("=== Executor Overhead Comparison ===");
        log.info("Requests/client: {}, clients: {}, total requests: {}",
                requestCount, 10, requestCount * 10);
        log.info("Per-request executor: {} ms total", perRequestMs);
        log.info("Shared executor:      {} ms total", sharedMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(sharedMs < perRequestMs,
                "Shared executor should be faster than per-request. Per-request: "
                        + perRequestMs + "ms, Shared: " + sharedMs + "ms");
    }

    @Test
    public void testExecutorThroughputUnderHighConcurrency() throws Exception {
        int totalRequests = 2000;
        int concurrency = 50;

        // Per-request executor
        long perRequestWallTime = measureThroughput(totalRequests, concurrency, () -> {
            try {
                executeWithNewExecutor(() -> {
                    Thread.sleep(2);
                    return "";
                }, 30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Shared executor
        long sharedWallTime = measureThroughput(totalRequests, concurrency, () -> {
            try {
                executeWithSharedExecutor(() -> {
                    Thread.sleep(2);
                    return "";
                }, 30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        double throughputRatio = (double) perRequestWallTime / Math.max(sharedWallTime, 1);

        log.info("=== Executor Throughput ({} requests, {} concurrency) ===", totalRequests, concurrency);
        log.info("Per-request executor wall time: {} ms", perRequestWallTime);
        log.info("Shared executor wall time:      {} ms", sharedWallTime);
        log.info("Speedup: {:.1f}x", throughputRatio);
        assertTrue(sharedWallTime < perRequestWallTime,
                "Shared executor should have higher throughput. Per-request: "
                        + perRequestWallTime + "ms, Shared: " + sharedWallTime + "ms");
    }

    private long measureThroughput(int totalRequests, int concurrency, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.nanoTime();
        CompletableFuture<?>[] futures = new CompletableFuture[totalRequests];
        for (int i = 0; i < totalRequests; i++) {
            futures[i] = CompletableFuture.runAsync(task, pool);
        }
        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        long duration = System.nanoTime() - start;
        pool.shutdownNow();
        return duration / 1_000_000;
    }

    // --- Concurrent Cache Eviction Performance ---

    @Test
    public void testBulkVsSelectiveEviction() throws Exception {
        int cacheSize = 5000;
        int evictionBatchSize = 500;
        int iterations = 100;

        ConcurrentHashMap<Sha256Hash, BlockMCMC> fullCache = new ConcurrentHashMap<>();
        List<Sha256Hash> allHashes = new ArrayList<>();
        for (int i = 0; i < cacheSize; i++) {
            Sha256Hash h = Sha256Hash.of(("evict-" + i).getBytes());
            allHashes.add(h);
            fullCache.put(h, new BlockMCMC(h, 50, 5, 100));
        }

        long bulkTotal = 0;
        long selectiveTotal = 0;

        for (int iter = 0; iter < iterations; iter++) {
            // Pick a random subset to "change"
            Set<Sha256Hash> changed = new HashSet<>();
            for (int i = 0; i < evictionBatchSize; i++) {
                changed.add(allHashes.get((iter * evictionBatchSize + i) % cacheSize));
            }

            // Bulk eviction (old approach): clear everything
            long bulkStart = System.nanoTime();
            fullCache.clear();
            for (Sha256Hash h : allHashes) {
                fullCache.put(h, new BlockMCMC(h, 50, 5, 100));
            }
            bulkTotal += System.nanoTime() - bulkStart;

            // Selective eviction (new approach): only clear changed hashes
            long selectiveStart = System.nanoTime();
            for (Sha256Hash h : changed) {
                fullCache.remove(h);
            }
            for (Sha256Hash h : changed) {
                fullCache.put(h, new BlockMCMC(h, 50, 5, 100));
            }
            selectiveTotal += System.nanoTime() - selectiveStart;
        }

        long bulkMs = bulkTotal / 1_000_000;
        long selectiveMs = selectiveTotal / 1_000_000;
        double speedup = (double) bulkMs / Math.max(selectiveMs, 1);

        log.info("=== Bulk vs Selective Eviction ({} iterations, {} changed/iter) ===",
                iterations, evictionBatchSize);
        log.info("Bulk evict (clear all):     {} ms", bulkMs);
        log.info("Selective evict (changed):  {} ms", selectiveMs);
        log.info("Speedup: {:.1f}x", speedup);
        assertTrue(selectiveMs < bulkMs,
                "Selective eviction should be faster than bulk when changes are a subset. Bulk: "
                        + bulkMs + "ms, Selective: " + selectiveMs + "ms");
    }

    // --- MCMC Weight Transition Full Simulation ---

    @Test
    public void testFullMCMMCTransitionSimulation() throws Exception {
        int clientCount = 20;
        int transitionsPerClient = 1000;
        int candidatesPerTransition = 8;
        AtomicLong oldSimTotal = new AtomicLong(0);
        AtomicLong newSimTotal = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CompletableFuture<?>[] futures = new CompletableFuture[clientCount];

        for (int i = 0; i < clientCount; i++) {
            int clientId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    Random rnd = new Random(42 + clientId);
                    long oldSimStart = System.nanoTime();
                    for (int t = 0; t < transitionsPerClient; t++) {
                        long currentWeight = (long) (rnd.nextDouble() * 500);
                        double[] weights = new double[candidatesPerTransition];
                        double total = 0;
                        for (int c = 0; c < candidatesPerTransition; c++) {
                            long candWeight = (long) (rnd.nextDouble() * 500);
                            weights[c] = Math.exp(-0.05 * (currentWeight - candWeight));
                            total += weights[c];
                        }
                        double pick = rnd.nextDouble() * total;
                        for (int c = 0; c < candidatesPerTransition; c++) {
                            pick -= weights[c];
                            if (pick <= 0) break;
                        }
                    }
                    oldSimTotal.addAndGet(System.nanoTime() - oldSimStart);

                    // New: use fastExp
                    long newSimStart = System.nanoTime();
                    for (int t = 0; t < transitionsPerClient; t++) {
                        long currentWeight = (long) (rnd.nextDouble() * 500);
                        double[] weights = new double[candidatesPerTransition];
                        double total = 0;
                        for (int c = 0; c < candidatesPerTransition; c++) {
                            long candWeight = (long) (rnd.nextDouble() * 500);
                            weights[c] = fastExp(-0.05, currentWeight - candWeight);
                            total += weights[c];
                        }
                        double pick = rnd.nextDouble() * total;
                        for (int c = 0; c < candidatesPerTransition; c++) {
                            pick -= weights[c];
                            if (pick <= 0) break;
                        }
                    }
                    newSimTotal.addAndGet(System.nanoTime() - newSimStart);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, errors.get());

        long oldMs = oldSimTotal.get() / 1_000_000;
        long newMs = newSimTotal.get() / 1_000_000;

        log.info("=== MCMC Transition Full Simulation ===");
        log.info("Clients: {}, transitions/client: {}, candidates/transition: {}",
                clientCount, transitionsPerClient, candidatesPerTransition);
        log.info("Old (Math.exp): {} ms total", oldMs);
        log.info("New (fastExp):   {} ms total", newMs);
        log.info("Speedup: {:.1f}x", (double) oldMs / Math.max(newMs, 1));
    }

    // --- Incremental Processing Simulation ---

    @Test
    public void testIncrementalVsFullRecomputation() throws Exception {
        int totalBlocks = 5000;
        int newBlocksPerCycle = 10;
        int incrementalCycles = 100;
        AtomicLong fullTime = new AtomicLong(0);
        AtomicLong incrementalTime = new AtomicLong(0);

        // Pre-create block hashes and weights simulating a stable DAG
        List<Sha256Hash> allHashes = new ArrayList<>();
        for (int i = 0; i < totalBlocks; i++) {
            allHashes.add(Sha256Hash.of(("dag-block-" + i).getBytes()));
        }
        List<Long> weights = new ArrayList<>();
        for (int i = 0; i < totalBlocks; i++) {
            weights.add((long) (totalBlocks - i));
        }

        // Full recomputation: process ALL blocks each cycle
        long fullStart = System.nanoTime();
        for (int cycle = 0; cycle < incrementalCycles; cycle++) {
            for (int i = 0; i < totalBlocks; i++) {
                long w = weights.get(i);
                for (int j = Math.max(0, i - 2); j < i; j++) {
                    weights.set(j, weights.get(j) + 1);
                }
            }
        }
        fullTime.addAndGet(System.nanoTime() - fullStart);

        // Incremental: only process new blocks each cycle
        long incStart = System.nanoTime();
        for (int cycle = 0; cycle < incrementalCycles; cycle++) {
            int startIdx = Math.max(0, totalBlocks - (cycle + 1) * newBlocksPerCycle);
            for (int i = startIdx; i < totalBlocks; i++) {
                long w = weights.get(i);
                for (int j = Math.max(0, i - 2); j < i; j++) {
                    weights.set(j, weights.get(j) + 1);
                }
            }
        }
        incrementalTime.addAndGet(System.nanoTime() - incStart);

        long fullMs = fullTime.get() / 1_000_000;
        long incMs = incrementalTime.get() / 1_000_000;

        log.info("=== Incremental vs Full Recomputation ===");
        log.info("Total blocks: {}, new/cycle: {}, cycles: {}", totalBlocks, newBlocksPerCycle, incrementalCycles);
        log.info("Full recomputation: {} ms", fullMs);
        log.info("Incremental:        {} ms", incMs);
        log.info("Speedup: {:.1f}x", (double) fullMs / Math.max(incMs, 1));
    }

    // --- Parallel Walk Simulation ---

    @Test
    public void testSequentialVsParallelWalk() throws Exception {
        int walkSteps = 100;
        int iterations = 200;
        int candidatesPerStep = 8;

        Random rnd = new Random(1234);
        List<long[][]> walkData = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long[][] steps = new long[walkSteps][candidatesPerStep];
            for (int s = 0; s < walkSteps; s++) {
                for (int c = 0; c < candidatesPerStep; c++) {
                    steps[s][c] = (long) (rnd.nextDouble() * 500);
                }
            }
            walkData.add(steps);
        }

        // Sequential: walk left then right (old approach)
        long seqStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long[][] steps = walkData.get(i);
            @SuppressWarnings("unused")
            double leftResult = simulateWalk(steps);
            @SuppressWarnings("unused")
            double rightResult = simulateWalk(steps);
        }
        long seqDuration = System.nanoTime() - seqStart;

        // Parallel: walk both simultaneously (new approach)
        long parStart = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < iterations; i++) {
            long[][] steps = walkData.get(i);
            java.util.concurrent.Future<Double> leftFuture = executor.submit(() -> simulateWalk(steps));
            java.util.concurrent.Future<Double> rightFuture = executor.submit(() -> simulateWalk(steps));
            try {
                @SuppressWarnings("unused")
                double leftResult = leftFuture.get(30, TimeUnit.SECONDS);
                @SuppressWarnings("unused")
                double rightResult = rightFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        long parDuration = System.nanoTime() - parStart;
        executor.shutdownNow();

        long seqMs = seqDuration / 1_000_000;
        long parMs = parDuration / 1_000_000;

        log.info("=== Sequential vs Parallel Walk ===");
        log.info("Iterations: {}, steps/walk: {}, candidates/step: {}", iterations, walkSteps, candidatesPerStep);
        log.info("Sequential: {} ms", seqMs);
        log.info("Parallel:   {} ms", parMs);
        log.info("Speedup: {:.1f}x", (double) seqMs / Math.max(parMs, 1));
        assertTrue(parMs < seqMs,
                "Parallel walk should be faster than sequential. Seq: " + seqMs + "ms, Par: " + parMs + "ms");
    }

    private double simulateWalk(long[][] steps) {
        double result = 0;
        long currentWeight = steps[0][0];
        for (int s = 0; s < steps.length; s++) {
            double total = 0;
            double[] probs = new double[steps[s].length];
            for (int c = 0; c < steps[s].length; c++) {
                probs[c] = fastExp(-0.05, currentWeight - steps[s][c]);
                total += probs[c];
            }
            double pick = ThreadLocalRandom.current().nextDouble() * total;
            for (int c = 0; c < probs.length; c++) {
                pick -= probs[c];
                if (pick <= 0) {
                    currentWeight = steps[s][c];
                    result += currentWeight;
                    break;
                }
            }
        }
        return result;
    }

    // --- Summary Test: Reports all speedups ---

    @Test
    public void testSummaryReport() {
        log.info("");
        log.info("===========================================");
        log.info("  Performance Improvement Summary");
        log.info("===========================================");
        log.info("");
        log.info("Improvement              | Expected Speedup | Status");
        log.info("-------------------------|------------------|--------");
        log.info("ObjectMapper singleton   | 10-50x          | Implemented");
        log.info("Shared executor          | 5-20x           | Implemented");
        log.info("BlockMCMC object cache   | 2-5x            | Implemented");
        log.info("Math.exp lookup table    | 2-8x            | Implemented");
        log.info("Batch DB load (N+1 fix)  | 10-100x         | Implemented");
        log.info("Lightweight topology     | 2-5x            | Implemented");
        log.info("Approver cache           | 2-5x            | Implemented");
        log.info("Selective cache eviction | 2-10x           | Implemented");
        log.info("");
        log.info("Run each @Test method individually to see");
        log.info("actual measured speedups on your hardware.");
        log.info("===========================================");
    }
}
