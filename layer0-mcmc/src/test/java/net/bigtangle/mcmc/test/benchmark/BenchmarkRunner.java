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
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
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
    private static final int CLIENTS = 30;
    private static final int PAYMENTS_PER_CLIENT = 500;

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8088/";
        NetworkParameters params = new net.bigtangle.layer0.params.Layer0TestParams();

        log.info("Server: {}", serverUrl);

        List<PQKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) clientKeys.add(PQKey.createNew());
        List<PQKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(PQKey.createNew());

        // Pre-fund wallets via the server's fundAddresses API
        // Each client gets one large UTXO to avoid per-payment UTXO contention
        String apiUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        HashMap<String, Object> fundReq = new HashMap<>();
        List<HashMap<String, Object>> entries = new ArrayList<>();
        long utxoValue = PAYMENTS_PER_CLIENT + Coin.FEE_DEFAULT.getValue().longValue() * 2;
        for (PQKey key : clientKeys) {
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("pubkey", Utils.HEX.encode(key.getPubKey()));
            entry.put("address", Address.fromHash160(params, key.getPubKeyHash()).toBase58());
            entry.put("value", utxoValue);
            entries.add(entry);
        }
        fundReq.put("addresses", entries);
        OkHttp3Util.post(apiUrl + "fundAddresses",
                Json.jsonmapper().writeValueAsString(fundReq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        log.info("Funding done");

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
                try {
                    long start = System.nanoTime();
                    // Batch all payments into one transaction (one tx, multiple recipients)
                    HashMap<String, BigInteger> pmt = new HashMap<>();
                    for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                        pmt.put(Address.fromHash160(params, recipients.get((clientId + p) % CLIENTS).getPubKeyHash()).toBase58(),
                                BigInteger.valueOf(1));
                    }
                    w.payToList(null, pmt, NetworkParameters.BIGTANGLE_TOKENID, "bench");
                    totalNs.addAndGet(System.nanoTime() - start);
                    int done = ok.addAndGet(PAYMENTS_PER_CLIENT);
                } catch (Exception e) {
                    fail.addAndGet(PAYMENTS_PER_CLIENT);
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
        log.info("Total:       {} payments (OK {}, fail {})", CLIENTS * PAYMENTS_PER_CLIENT, ok.get(), fail.get());
        log.info("Wall time:   {} ms", wallMs);
        log.info("Avg latency: {} ms", (long) avg);
        log.info("Throughput:  {} tx/s", (long) tps);
        log.info("==============================================");
        System.exit(ok.get() > 0 ? 0 : 1);
    }
}
