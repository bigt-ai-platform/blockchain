package net.bigtangle.mcmc.test.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * Layer0 performance improvement tests — validates all optimizations
 * implemented for the layer0-server and layer0-mcmc modules.
 */
public class Layer0PerformanceTest {

	private static final Logger log = LoggerFactory.getLogger(Layer0PerformanceTest.class);

	// fastExp implementation matching TipsService
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

	// ========================
	// 1. ObjectMapper Singleton
	// ========================

	@Test
	public void testObjectMapperSingleton() {
		ObjectMapper a = Json.jsonmapper();
		ObjectMapper b = Json.jsonmapper();
		ObjectMapper c = Json.jsonmapper();
		assertEquals(System.identityHashCode(a), System.identityHashCode(b));
		assertEquals(System.identityHashCode(b), System.identityHashCode(c));
	}

	@Test
	public void testObjectMapperConcurrentAccess() throws Exception {
		int threads = 20;
		int ops = 5000;
		AtomicLong oldTime = new AtomicLong(0);
		AtomicLong newTime = new AtomicLong(0);

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CompletableFuture<?>[] futures = new CompletableFuture[threads];
		for (int t = 0; t < threads; t++) {
			futures[t] = CompletableFuture.runAsync(() -> {
				long oldStart = System.nanoTime();
				for (int i = 0; i < ops; i++) {
					ObjectMapper m = new ObjectMapper();
					m.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
					try { m.writeValueAsBytes(new BlockMCMC(Sha256Hash.of(("o" + i).getBytes()), 0, 0, 1)); } catch (Exception e) {}
				}
				oldTime.addAndGet(System.nanoTime() - oldStart);

				long newStart = System.nanoTime();
				ObjectMapper singleton = Json.jsonmapper();
				for (int i = 0; i < ops; i++) {
					try { singleton.writeValueAsBytes(new BlockMCMC(Sha256Hash.of(("n" + i).getBytes()), 0, 0, 1)); } catch (Exception e) {}
				}
				newTime.addAndGet(System.nanoTime() - newStart);
			}, pool);
		}
		CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
		pool.shutdownNow();

		double speedup = (double) oldTime.get() / Math.max(newTime.get(), 1);
		log.info("ObjectMapper: {} threads x {} ops — old={}ms new={}ms speedup={:.1f}x",
				threads, ops, oldTime.get() / 1_000_000, newTime.get() / 1_000_000, speedup);
		assertTrue(speedup > 2, "ObjectMapper singleton should be >2x faster, got " + speedup + "x");
	}

	// ========================
	// 2. fastExp Lookup Table
	// ========================

	@Test
	public void testFastExpMatchesMathExpForAllDiffs() {
		double alpha = -0.05;
		for (long diff = -200; diff <= 200; diff++) {
			assertEquals(Math.exp(alpha * diff), fastExp(alpha, diff), 1e-15, "diff=" + diff);
		}
	}

	@Test
	public void testFastExpOutOfRange() {
		assertEquals(Math.exp(-0.05 * 300), fastExp(-0.05, 300), 1e-15);
		assertEquals(Math.exp(-0.05 * (-300)), fastExp(-0.05, -300), 1e-15);
	}

	@Test
	public void testFastExpPerformance() {
		double alpha = -0.05;
		int iterations = 50000;
		long[] diffs = new long[iterations];
		Random rnd = new Random(42);
		for (int i = 0; i < iterations; i++) diffs[i] = (long) (rnd.nextDouble() * 200 - 100);

		// Warmup
		for (int warmup = 0; warmup < 2; warmup++) {
			for (int i = 0; i < iterations; i++) Math.exp(alpha * diffs[i]);
			for (int i = 0; i < iterations; i++) fastExp(alpha, diffs[i]);
		}

		long mathExpNs = measureNanos(() -> { for (int i = 0; i < iterations; i++) Math.exp(alpha * diffs[i]); });
		long fastExpNs = measureNanos(() -> { for (int i = 0; i < iterations; i++) fastExp(alpha, diffs[i]); });

		double speedup = (double) mathExpNs / Math.max(fastExpNs, 1);
		log.info("fastExp {} iterations: Math.exp={}ms fastExp={}ms speedup={:.1f}x",
				iterations, mathExpNs / 1_000_000, fastExpNs / 1_000_000, speedup);
	}

	// ==============================
	// 3. BlockMCMC Object vs JSON
	// ==============================

	@Test
	public void testBlockMCMCDirectVsJson() throws Exception {
		Sha256Hash hash = Sha256Hash.of("test".getBytes());
		BlockMCMC original = new BlockMCMC(hash, 50, 5, 200);
		ObjectMapper mapper = Json.jsonmapper();
		byte[] serialized = mapper.writeValueAsBytes(original);

		int iterations = 20000;

		long jsonNs = measureNanos(() -> {
			for (int i = 0; i < iterations; i++) {
				try { mapper.readValue(serialized, BlockMCMC.class); } catch (Exception e) {}
			}
		});
		long directNs = measureNanos(() -> {
			for (int i = 0; i < iterations; i++) {
				new BlockMCMC(original.getBlockHash(), original.getRating(),
						original.getDepth(), original.getCumulativeWeight());
			}
		});

		double speedup = (double) jsonNs / Math.max(directNs, 1);
		log.info("BlockMCMC access {} iterations: JSON={}ms Direct={}ms speedup={:.1f}x",
				iterations, jsonNs / 1_000_000, directNs / 1_000_000, speedup);
		assertTrue(speedup > 2, "Direct object access should be >2x faster, got " + speedup + "x");
	}

	// ==============================
	// 4. MCMC Walk Transition
	// ==============================

	@Test
	public void testMCMCWalkingPerformance() {
		int candidates = 10;
		int iterations = 5000;
		Random rnd = new Random(1234);
		List<Long> weights = new ArrayList<>();
		for (int i = 0; i < candidates; i++) weights.add((long) (rnd.nextDouble() * 500));

		// Warmup
		for (int w = 0; w < 2; w++) {
			mcmcWalkOld(iterations, weights, rnd);
			mcmcWalkNew(iterations, weights, rnd);
		}

		long oldNs = measureNanos(() -> mcmcWalkOld(iterations, weights, rnd));
		long newNs = measureNanos(() -> mcmcWalkNew(iterations, weights, rnd));

		double speedup = (double) oldNs / Math.max(newNs, 1);
		log.info("MCMC walk {} iterations: old={}ms new={}ms speedup={:.1f}x",
				iterations, oldNs / 1_000_000, newNs / 1_000_000, speedup);
	}

	private void mcmcWalkOld(int iterations, List<Long> weights, Random rnd) {
		for (int i = 0; i < iterations; i++) {
			long cw = weights.get(i % weights.size());
			double total = 0;
			for (long w : weights) total += Math.exp(-0.05 * (cw - w));
			double pick = rnd.nextDouble() * total;
			for (long w : weights) {
				pick -= Math.exp(-0.05 * (cw - w));
				if (pick <= 0) break;
			}
		}
	}

	private void mcmcWalkNew(int iterations, List<Long> weights, Random rnd) {
		double alpha = -0.05;
		for (int i = 0; i < iterations; i++) {
			long cw = weights.get(i % weights.size());
			double total = 0;
			for (long w : weights) total += fastExp(alpha, cw - w);
			double pick = rnd.nextDouble() * total;
			for (long w : weights) {
				pick -= fastExp(alpha, cw - w);
				if (pick <= 0) break;
			}
		}
	}

	// ==============================
	// 5. Batch Collection Ops
	// ==============================

	@Test
	public void testBatchHashOperations() {
		Set<Sha256Hash> hashes = new HashSet<>();
		for (int i = 0; i < 10000; i++) hashes.add(Sha256Hash.of(("h" + i).getBytes()));
		Set<Sha256Hash> known = new HashSet<>();
		for (int i = 0; i < 5000; i++) known.add(Sha256Hash.of(("h" + i).getBytes()));

		long sequentialNs = measureNanos(() -> {
			Set<Sha256Hash> missing = new HashSet<>();
			for (Sha256Hash h : hashes) { if (!known.contains(h)) missing.add(h); }
		});

		long batchNs = measureNanos(() -> {
			Set<Sha256Hash> missing = new HashSet<>(hashes);
			missing.removeAll(known);
		});

		double speedup = (double) sequentialNs / Math.max(batchNs, 1);
		log.info("Batch ops 10000 hashes: sequential={}ms batch={}ms speedup={:.1f}x",
				sequentialNs / 1_000_000, batchNs / 1_000_000, speedup);
	}

	// ==============================
	// 6. Shared Executor
	// ==============================

	@Test
	public void testSharedExecutorOverhead() throws Exception {
		int requests = 100;
		int threads = 5;

		// Use AtomicLong to safely accumulate from multiple threads
		java.util.concurrent.atomic.AtomicLong perRequestNs = new java.util.concurrent.atomic.AtomicLong(0);
		java.util.concurrent.atomic.AtomicLong sharedNs = new java.util.concurrent.atomic.AtomicLong(0);

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CompletableFuture<?>[] futures = new CompletableFuture[threads];
		for (int t = 0; t < threads; t++) {
			futures[t] = CompletableFuture.runAsync(() -> {
				long prStart = System.nanoTime();
				for (int r = 0; r < requests; r++) {
					ExecutorService es = Executors.newSingleThreadExecutor();
					try { es.submit((Callable<String>) () -> "").get(30, TimeUnit.SECONDS); }
					catch (Exception e) {}
					finally { es.shutdownNow(); }
				}
				perRequestNs.addAndGet(System.nanoTime() - prStart);

				ExecutorService shared = Executors.newFixedThreadPool(4);
				long shStart = System.nanoTime();
				for (int r = 0; r < requests; r++) {
					try { shared.submit((Callable<String>) () -> "").get(30, TimeUnit.SECONDS); }
					catch (Exception e) {}
				}
				sharedNs.addAndGet(System.nanoTime() - shStart);
				shared.shutdownNow();
			}, pool);
		}
		CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
		pool.shutdownNow();

		double speedup = (double) perRequestNs.get() / Math.max(sharedNs.get(), 1);
		log.info("Executor overhead: per-request={}ms shared={}ms speedup={:.1f}x",
				perRequestNs.get() / 1_000_000, sharedNs.get() / 1_000_000, speedup);
	}

	// ==============================
	// Helpers
	// ==============================

	private long measureNanos(Runnable r) {
		System.gc();
		long start = System.nanoTime();
		r.run();
		return System.nanoTime() - start;
	}
}
