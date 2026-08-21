package net.bigtangle.server.benchmark;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

/**
 * PoS-era payment throughput benchmark (HTTP to a live {@code layer0-server}).
 *
 * <p>Each client is pre-funded with one large UTXO via the test-only
 * {@code /fundAddresses} faucet ({@code FUND_ENABLED=true} on the node), then
 * submits a batched multi-recipient payment through the mempool
 * ({@code Wallet.payToList} → {@code /submitTransaction}).
 *
 * <p>Run (see {@code helper/fulltest/benchmark.sh}):
 * <pre>
 * mvn test-compile -pl bigtangle-servercore -q
 * mvn exec:java -pl bigtangle-servercore -Dexec.classpathScope=test \
 *   -Dexec.mainClass=net.bigtangle.server.benchmark.PaymentBenchmark \
 *   -Dexec.args="http://localhost:8081/" \
 *   -Dbenchmark.clients=30 -Dbenchmark.payments=2000
 * </pre>
 */
public class PaymentBenchmark {

    private static final Logger log = LoggerFactory.getLogger(PaymentBenchmark.class);

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8081/";
        int clients = Integer.parseInt(System.getProperty("benchmark.clients", "30"));
        int paymentsPerClient = Integer.parseInt(System.getProperty("benchmark.payments", "2000"));

        NetworkParameters params = "main".equals(System.getProperty("benchmark.net", "test"))
                ? MainNetParams.get() : new Layer0TestParams();
        log.info("Server: {}", serverUrl);

        List<PQKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            clientKeys.add(PQKey.createNew());
        }
        List<PQKey> recipients = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            recipients.add(PQKey.createNew());
        }

        // Pre-fund each client with one large UTXO to avoid per-payment
        // UTXO contention (fundAddresses mints confirmed coins directly).
        String apiUrl = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
        HashMap<String, Object> fundReq = new HashMap<>();
        List<HashMap<String, Object>> entries = new ArrayList<>();
        long utxoValue = paymentsPerClient + Coin.FEE_DEFAULT.getValue().longValue() * 2;
        for (PQKey key : clientKeys) {
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("pubkey", Utils.HEX.encode(key.getPubKey()));
            entry.put("address", Address.fromHash160(params, key.getPubKeyHash()).toBase58());
            entry.put("value", utxoValue);
            entries.add(entry);
        }
        fundReq.put("addresses", entries);
        OkHttp3Util.post(apiUrl + "fundAddresses",
                Json.jsonmapper().writeValueAsString(fundReq).getBytes(StandardCharsets.UTF_8));
        log.info("Funding done");

        List<Wallet> wallets = new ArrayList<>();
        for (PQKey key : clientKeys) {
            wallets.add(Wallet.fromKeys(params, key, apiUrl));
        }

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicLong totalNs = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(clients);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[clients];

        long wallStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int clientId = c;
            Wallet w = wallets.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    long start = System.nanoTime();
                    HashMap<String, BigInteger> pmt = new HashMap<>();
                    for (int p = 0; p < paymentsPerClient; p++) {
                        pmt.put(Address
                                .fromHash160(params, recipients.get((clientId + p) % clients).getPubKeyHash())
                                .toBase58(), BigInteger.ONE);
                    }
                    w.payToList(null, pmt, NetworkParameters.BIGTANGLE_TOKENID, "bench");
                    totalNs.addAndGet(System.nanoTime() - start);
                    ok.addAndGet(paymentsPerClient);
                } catch (Exception e) {
                    log.warn("client {} payment failed: {}", clientId, e.toString());
                    fail.addAndGet(paymentsPerClient);
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
        log.info("Server:     {}", serverUrl);
        log.info("Clients:    {}", clients);
        log.info("Payments/client: {}", paymentsPerClient);
        log.info("Total:      {} payments (OK {}, fail {})",
                clients * paymentsPerClient, ok.get(), fail.get());
        log.info("Wall time:  {} ms", wallMs);
        log.info("Avg latency: {} ms", (long) avg);
        log.info("Throughput:  {} tx/s", (long) tps);
        log.info("==============================================");
        System.exit(ok.get() > 0 ? 0 : 1);
    }
}