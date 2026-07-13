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

import net.bigtangle.core.ECKey;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

/**
 * Standalone 10-client payment benchmark.
 * Connect to a running test server (e.g., layer0-server on port 8081).
 *
 * Start server:
 *   mvn spring-boot:run -pl layer0-server -Dspring-boot.run.arguments="--server.net=test"
 *
 * Run benchmark:
 *   mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test \
 *     -Dexec.mainClass=net.bigtangle.mcmc.test.benchmark.BenchmarkRunner \
 *     -Dexec.args="http://localhost:8081/"
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 50;

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8088/";
        NetworkParameters params = new net.bigtangle.layer0.params.Layer0TestParams();
        String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

        log.info("Connecting to {} as genesis wallet", serverUrl);
        Wallet genesisWallet = Wallet.fromKeys(params,
                ECKey.fromPrivate(Utils.HEX.decode(testPriv)), serverUrl);

        List<ECKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) clientKeys.add(new ECKey());

        log.info("Funding {} client wallets...", clientKeys.size());
        for (ECKey key : clientKeys) {
            HashMap<String, BigInteger> funding = new HashMap<>();
            funding.put(key.toAddress(params).toString(), BigInteger.valueOf(100000));
            genesisWallet.payToList(null, funding, NetworkParameters.BIGTANGLE_TOKENID, "fund");
        }
        log.info("Funding complete");

        List<ECKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(new ECKey());

        AtomicLong totalLatencyNanos = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CompletableFuture<?>[] futures = new CompletableFuture[CLIENTS];
        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            ECKey fromKey = clientKeys.get(c);
            Wallet w = Wallet.fromKeys(params, fromKey, serverUrl);
            ECKey toKey = recipients.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        HashMap<String, BigInteger> pmt = new HashMap<>();
                        pmt.put(toKey.toAddress(params).toString(), BigInteger.valueOf(1));
                        long txStart = System.nanoTime();
                        w.payToList(null, pmt, NetworkParameters.BIGTANGLE_TOKENID, "bench");
                        totalLatencyNanos.addAndGet(System.nanoTime() - txStart);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        long latMs = totalLatencyNanos.get() / 1_000_000;
        int total = successCount.get() + failCount.get();
        double avgLat = successCount.get() > 0 ? (double) latMs / successCount.get() : 0;
        double tps = wallMs > 0 ? (double) successCount.get() / wallMs * 1000 : 0;

        log.info("");
        log.info("=============================================");
        log.info("  10-Client Payment Benchmark Results");
        log.info("=============================================");
        log.info("Server:           {}", serverUrl);
        log.info("Clients:          {}", CLIENTS);
        log.info("Payments/client:  {}", PAYMENTS_PER_CLIENT);
        log.info("Total:            {} ({} OK, {} failed)", total, successCount.get(), failCount.get());
        log.info("Wall time:        {} ms", wallMs);
        log.info("Avg latency/tx:   {:.1f} ms", avgLat);
        log.info("Throughput:       {:.1f} tx/s", tps);
        log.info("=============================================");

        System.exit(successCount.get() > 0 ? 0 : 1);
    }
}
