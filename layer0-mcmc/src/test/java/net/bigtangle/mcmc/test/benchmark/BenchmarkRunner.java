package net.bigtangle.mcmc.test.benchmark;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

/**
 * 10-client payment benchmark.
 * Performance measurement only — assumes wallets are pre-funded.
 *
 * Start server:
 *   mvn spring-boot:run -pl layer0-server \
 *     -Dspring-boot.run.jvmArguments="-Dservice.schedule.mcmc=true" \
 *     -Dspring-boot.run.arguments="--server.net=Test --server.port=8089 \
 *       --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4"
 *
 * Run:
 *   mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.bigtangle.mcmc.test.benchmark.BenchmarkRunner \
 *     -Dexec.args="http://localhost:8089/"
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 200;

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8088/";
        NetworkParameters params = new net.bigtangle.layer0.params.Layer0TestParams();

        log.info("Server: {}", serverUrl);

        List<PQKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) clientKeys.add(PQKey.createNew());
        List<PQKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(PQKey.createNew());

        List<Wallet> wallets = new ArrayList<>();
        for (PQKey key : clientKeys) {
            wallets.add(Wallet.fromKeys(params, key, serverUrl));
        }

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicLong totalNs = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];

        long wallStart = System.nanoTime();
        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            Wallet w = wallets.get(c);
            PQKey toKey = recipients.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        long start = System.nanoTime();
                        HashMap<String, BigInteger> pmt = new HashMap<>();
                        pmt.put(Address.fromHash160(params, toKey.getPubKeyHash()).toBase58(), BigInteger.valueOf(1));
                        w.payToList(null, pmt, NetworkParameters.BIGTANGLE_TOKENID, "bench");
                        totalNs.addAndGet(System.nanoTime() - start);
                        int done = ok.incrementAndGet();
                        if (done % 200 == 0) log.info("Progress: {} / {} payments", done, CLIENTS * PAYMENTS_PER_CLIENT);
                    } catch (Exception e) {
                        fail.incrementAndGet();
                    }
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        double tps = wallMs > 0 ? (double) ok.get() / wallMs * 1000 : 0;
        double avg = ok.get() > 0 ? (double) totalNs.get() / ok.get() / 1_000_000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("Server:       {}", serverUrl);
        log.info("Clients:     {}", CLIENTS);
        log.info("Payments/client: {}", PAYMENTS_PER_CLIENT);
        log.info("Total:       {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Wall time:   {} ms", wallMs);
        log.info("Avg latency: {} ms", (long) avg);
        log.info("Throughput:  {} tx/s", (long) tps);
        log.info("==============================================");
        System.exit(ok.get() > 0 ? 0 : 1);
    }
}
