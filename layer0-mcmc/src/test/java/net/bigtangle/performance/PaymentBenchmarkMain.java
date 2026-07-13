package net.bigtangle.performance;

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

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Utils;
import net.bigtangle.wallet.Wallet;

/**
 * Standalone 10-client payment benchmark. Launches its own server and measures
 * throughput. Run from Maven with:
 * mvn exec:java -pl layer0-mcmc -Dexec.mainClass=net.bigtangle.performance.PaymentBenchmarkMain
 */
public class PaymentBenchmarkMain {

    private static final Logger log = LoggerFactory.getLogger(PaymentBenchmarkMain.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 50;

    public static void main(String[] args) throws Exception {
        log.info("Starting 10-client payment benchmark...");

        // Use the test server that's started externally or via Spring Boot
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8088/";
        NetworkParameters params = NetworkParameters.testNet();

        // Create genesis wallet (has all the money on testnet)
        String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
        Wallet genesisWallet = Wallet.fromKeys(params,
                ECKey.fromPrivate(Utils.HEX.decode(testPriv)), serverUrl);

        // Create 10 client wallets and fund them
        List<ECKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            clientKeys.add(new ECKey());
        }
        for (ECKey key : clientKeys) {
            HashMap<String, BigInteger> funding = new HashMap<>();
            funding.put(key.toAddress(params).toString(), BigInteger.valueOf(100000));
            genesisWallet.payToList(null, funding, NetworkParameters.BIGTANGLE_TOKENID, "fund");
        }
        log.info("Funded {} client wallets", clientKeys.size());

        // Create recipient wallets
        List<ECKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            recipients.add(new ECKey());
        }

        // Run benchmark
        AtomicLong totalLatencyNanos = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CompletableFuture<?>[] futures = new CompletableFuture[CLIENTS];

        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            ECKey fromKey = clientKeys.get(c);
            Wallet clientWallet = Wallet.fromKeys(params, fromKey, serverUrl);

            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        HashMap<String, BigInteger> payment = new HashMap<>();
                        payment.put(recipients.get(clientId).toAddress(params).toString(), BigInteger.valueOf(1));

                        long txStart = System.nanoTime();
                        Block b = clientWallet.payToList(null, payment,
                                NetworkParameters.BIGTANGLE_TOKENID, "bench");
                        if (b != null) {
                            totalLatencyNanos.addAndGet(System.nanoTime() - txStart);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long wallTimeMs = (System.nanoTime() - wallStart) / 1_000_000;
        long totalLatencyMs = totalLatencyNanos.get() / 1_000_000;
        int total = successCount.get() + failCount.get();
        double avgLatency = successCount.get() > 0 ? (double) totalLatencyMs / successCount.get() : 0;
        double throughput = totalLatencyMs > 0 ? (double) successCount.get() / totalLatencyMs * 1000 : 0;

        log.info("");
        log.info("=============================================");
        log.info("  10-Client Payment Benchmark Results");
        log.info("=============================================");
        log.info("Server:           {}", serverUrl);
        log.info("Clients:          {}", CLIENTS);
        log.info("Payments/client:  {}", PAYMENTS_PER_CLIENT);
        log.info("Total:            {} ({} OK, {} failed)",
                total, successCount.get(), failCount.get());
        log.info("Wall time:        {} ms", wallTimeMs);
        log.info("Avg latency/tx:   {:.1f} ms", avgLatency);
        log.info("Throughput:       {:.1f} tx/s", throughput);
        log.info("=============================================");
    }
}
