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
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.Wallet;

/**
 * 10-client payment benchmark.
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
        String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

        log.info("Server: {}", serverUrl);

        Wallet genesisWallet = Wallet.fromKeys(params,
                ECKey.fromPrivate(Utils.HEX.decode(testPriv)), serverUrl);

        List<ECKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) clientKeys.add(new ECKey());

        log.info("Funding wallets...");
        for (ECKey key : clientKeys) {
            HashMap<String, BigInteger> funding = new HashMap<>();
            funding.put(key.toAddress(params).toString(), BigInteger.valueOf(100000));
            genesisWallet.payToList(null, funding, NetworkParameters.BIGTANGLE_TOKENID, "fund");
        }
        log.info("Funding done");

        List<ECKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(new ECKey());

        AtomicLong totalNs = new AtomicLong(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];

        long wallStart = System.nanoTime();
        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            ECKey fromKey = clientKeys.get(c);
            Wallet w = Wallet.fromKeys(params, fromKey, serverUrl);
            ECKey toKey = recipients.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        long start = System.nanoTime();
                        HashMap<String, BigInteger> pmt = new HashMap<>();
                        pmt.put(toKey.toAddress(params).toString(), BigInteger.valueOf(1));
                        w.payToList(null, pmt, NetworkParameters.BIGTANGLE_TOKENID, "bench");
                        totalNs.addAndGet(System.nanoTime() - start);
                        ok.incrementAndGet();
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
