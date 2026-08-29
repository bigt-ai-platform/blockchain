package net.bigtangle.server.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.GetTransactionStatusResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Remote real-time benchmark against the DEPLOYED production PoS network.
 *
 * <p>Unlike the local {@link MaxTpsBenchmarkChain} (in-process Spring context),
 * this test drives a running prod node purely over HTTP:
 * <ol>
 *   <li>fund N wallets via the bootstrap faucet {@code /fundAddresses} (the same
 *       mechanism prodtest uses to fund validators — test/bootstrap only);</li>
 *   <li>fetch the funded UTXOs via {@code /getOutputs};</li>
 *   <li>build + sign one payment per wallet, submit them in parallel batches via
 *       {@code /submitTransactions};</li>
 *   <li>poll {@code /getTransactionsStatusByAddress} until every tx is CONFIRMED
 *       and report submit TPS, end-to-end confirm TPS and latency percentiles.</li>
 * </ol>
 *
 * <p>Funded UTXOs are minted straight into the target node's store (the same
 * bootstrap model prodtest uses). Transfers confirmed on the submit node and its
 * mesh neighbours; the reward chain keeps advancing without wedging (verified on
 * the 3-node prod network).
 *
 * <p>Run via {@code helper/prod/prodbench.sh} (which wraps the maven invocation)
 * or directly:
 * <pre>
 * mvn test -pl bigtangle-servercore \
 *   -Dtest=MaxTpsBenchmarkProd#testProdRealtime \
 *   -Dprod.seed=http://10.8.0.2:8083 \
 *   -Dchain.tx=2000 -Dchain.clients=50 -Dchain.batchSize=250 \
 *   -Dchain.amount=40000 -Dchain.pay=25000 -Dchain.confirmTimeoutSec=600
 * </pre>
 */
public class MaxTpsBenchmarkProd {

    private static final Logger log = LoggerFactory.getLogger(MaxTpsBenchmarkProd.class);

    @Test
    public void testProdRealtime() throws Exception {
        String seed = System.getProperty("prod.seed", "http://10.8.0.2:8083");
        int totalTx = Integer.parseInt(System.getProperty("chain.tx", "2000"));
        int clients = Integer.parseInt(System.getProperty("chain.clients", "50"));
        int batchSize = Integer.parseInt(System.getProperty("chain.batchSize", "250"));
        long fundAmount = Long.parseLong(System.getProperty("chain.amount", "40000"));
        long payAmount = Long.parseLong(System.getProperty("chain.pay", "25000"));
        int confirmTimeoutSec = Integer.parseInt(System.getProperty("chain.confirmTimeoutSec", "600"));
        boolean requireConfirm = Boolean.parseBoolean(System.getProperty("chain.requireConfirm", "true"));

        if (!seed.startsWith("http")) {
            seed = "http://" + seed;
        }
        if (!seed.endsWith("/")) {
            seed = seed + "/";
        }
        final String base = seed;
        NetworkParameters params = MainNetParams.get();

        log.info("==============================================");
        log.info("  PROD Realtime Benchmark -> {}", base);
        log.info("==============================================");
        log.info("Total tx:        {}", totalTx);
        log.info("Clients:         {}", clients);
        log.info("Batch size:      {}", batchSize);
        log.info("Fund per wallet: {}", fundAmount);
        log.info("Pay per tx:      {}", payAmount);
        log.info("Confirm timeout: {} s", confirmTimeoutSec);

        // 1. Generate one wallet per tx + a single final recipient.
        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < totalTx; i++) {
            walletKeys.add(PQKey.createNew());
        }
        PQKey recipient = PQKey.createNew();
        String recvAddr = Address.fromHash160(params, recipient.getPubKeyHash()).toBase58();

        // 2. (Faucet removed) bootstrap wallets via genesis CSV instead.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PQKey k : walletKeys) {
            Map<String, Object> e = new HashMap<>();
            e.put("address", Address.fromHash160(params, k.getPubKeyHash()).toBase58());
            e.put("value", fundAmount);
            e.put("pubkey", Utils.HEX.encode(k.getPubKey()));
            entries.add(e);
        }
        Map<String, Object> fundBody = new HashMap<>();
        fundBody.put("addresses", entries);
        long fundStart = System.nanoTime();
        requireGenesisBootstrap();
        log.info("Funded {} wallets ({} ms)", walletKeys.size(),
                (System.nanoTime() - fundStart) / 1_000_000);

        // 3. Fetch the funded UTXOs (one getOutputs call, keyed by address).
        List<String> pubKeyHashes = new ArrayList<>();
        for (PQKey k : walletKeys) {
            pubKeyHashes.add(Utils.HEX.encode(k.getPubKeyHash()));
        }
        byte[] resp = OkHttp3Util.postString(base + "getOutputs", Json.jsonmapper().writeValueAsString(pubKeyHashes));
        GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
        Map<String, UTXO> addrToUtxo = new HashMap<>();
        if (gor.getOutputs() != null) {
            for (UTXO u : gor.getOutputs()) {
                if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount
                        && u.getAddress() != null) {
                    addrToUtxo.put(u.getAddress(), u);
                }
            }
        }
        log.info("Fetched {}/{} UTXOs", addrToUtxo.size(), walletKeys.size());

        // 4. Pre-build + sign all transactions (NOT timed — signing is client
        //    CPU on the benchmark host and would skew the ingest measurement).
        int txPerClient = Math.max(1, totalTx / clients);
        Transaction[] allTxs = new Transaction[totalTx];
        AtomicInteger built = new AtomicInteger(0);
        AtomicInteger buildFail = new AtomicInteger(0);
        ExecutorService buildPool = Executors.newFixedThreadPool(clients);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] buildFutures = new CompletableFuture[clients];
        long buildStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            int startIdx = c * txPerClient;
            buildFutures[c] = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < txPerClient; i++) {
                        int idx = startIdx + i;
                        if (idx >= totalTx) {
                            break;
                        }
                        PQKey wk = walletKeys.get(idx);
                        UTXO utxo = addrToUtxo.get(Address.fromHash160(params, wk.getPubKeyHash()).toBase58());
                        if (utxo == null) {
                            continue;
                        }
                        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, wk);
                        HashMap<String, BigInteger> pay = new HashMap<>();
                        pay.put(recvAddr, BigInteger.valueOf(payAmount));
                        Transaction tx = w.payToListTransaction(null, pay, NetworkParameters.BIGTANGLE_TOKENID,
                                "bench", List.of(coin));
                        if (tx != null) {
                            allTxs[idx] = tx;
                            built.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    buildFail.incrementAndGet();
                    log.error("Build client failed", e);
                }
            }, buildPool);
        }
        CompletableFuture.allOf(buildFutures).get(10, TimeUnit.MINUTES);
        buildPool.shutdownNow();
        log.info("Pre-built {}/{} transactions ({} ms, fail {})", built.get(), totalTx,
                (System.nanoTime() - buildStart) / 1_000_000, buildFail.get());

        // 5. Timed parallel submit of the pre-built transactions.
        AtomicInteger submitted = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        ConcurrentLinkedQueue<String> txHashes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[clients];

        long submitWallStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            int startIdx = c * txPerClient;
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    List<Transaction> txs = new ArrayList<>();
                    for (int i = 0; i < txPerClient; i++) {
                        int idx = startIdx + i;
                        if (idx >= totalTx) {
                            break;
                        }
                        Transaction tx = allTxs[idx];
                        if (tx == null) {
                            continue;
                        }
                        txs.add(tx);
                        if (txs.size() == batchSize) {
                            submitted.addAndGet(submitBatch(base, txs));
                            for (Transaction t : txs) {
                                txHashes.add(t.getHash().toString());
                            }
                            txs.clear();
                        }
                    }
                    if (!txs.isEmpty()) {
                        submitted.addAndGet(submitBatch(base, txs));
                        for (Transaction t : txs) {
                            txHashes.add(t.getHash().toString());
                        }
                    }
                } catch (Exception e) {
                    failed.addAndGet(txPerClient);
                    log.error("Submit client failed", e);
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();
        long submitWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        int ok = submitted.get();
        log.info("Submit done: {} ms (submitted {}, fail {})", submitWallMs, ok, failed.get());

        if (ok == 0) {
            assertTrue(ok > 0, "No transactions were submitted to the prod node");
        }

        // 5. Confirmation polling (statuses by recipient address, one call/round).
        int confirmed = 0;
        List<Long> latenciesMs = new ArrayList<>();
        long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
        while (confirmed < ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(5000);
            Map<String, GetTransactionStatusResponse> statuses = fetchStatuses(base, recvAddr);
            confirmed = 0;
            latenciesMs.clear();
            for (String h : txHashes) {
                GetTransactionStatusResponse item = statuses.get(h);
                if (item != null && "CONFIRMED".equals(item.getStatus())) {
                    confirmed++;
                    latenciesMs.add(Math.max(0, item.getUpdatedTime() - item.getCreatedTime()));
                }
            }
            log.info("  confirmed {}/{}", confirmed, ok);
        }
        long confirmWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;

        double submitTps = submitWallMs > 0 ? (double) ok / submitWallMs * 1000 : 0;
        double confirmTps = confirmWallMs > 0 ? (double) ok / confirmWallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  PROD Real-Time Results ({} -> {})", base, confirmWallMs);
        log.info("==============================================");
        log.info("Total tx:        {} (submitted {}, confirmed {})", totalTx, ok, confirmed);
        log.info("Submit wall:     {} ms", submitWallMs);
        log.info("Confirm wall:    {} ms", confirmWallMs);
        log.info("Submit TPS:      {} tx/s", String.format("%.1f", submitTps));
        log.info("Confirm TPS:     {} tx/s", String.format("%.1f", confirmTps));
        log.info("Confirm p50:     {} ms", percentile(latenciesMs, 50));
        log.info("Confirm p95:     {} ms", percentile(latenciesMs, 95));
        log.info("Confirm p99:     {} ms", percentile(latenciesMs, 99));
        log.info("==============================================");

        if (requireConfirm) {
            assertTrue(confirmed > 0, "No transaction confirmed within the timeout");
        }
    }

    private static int submitBatch(String seed, List<Transaction> txs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        for (Transaction bt : txs) {
            byte[] b = bt.bitcoinSerialize();
            dos.writeInt(b.length);
            dos.write(b);
        }
        dos.close();
        OkHttp3Util.post(seed + "submitTransactions", baos.toByteArray());
        return txs.size();
    }

    private static Map<String, GetTransactionStatusResponse> fetchStatuses(String seed, String address)
            throws Exception {
        byte[] r = OkHttp3Util.postString(seed + "getTransactionsStatusByAddress",
                Json.jsonmapper().writeValueAsString(Map.of("address", address)));
        GetTransactionStatusResponse.GetTransactionsStatusResponse resp = Json.jsonmapper().readValue(r,
                GetTransactionStatusResponse.GetTransactionsStatusResponse.class);
        Map<String, GetTransactionStatusResponse> out = new HashMap<>();
        if (resp.getTransactions() != null) {
            for (GetTransactionStatusResponse item : resp.getTransactions()) {
                if (item.getTxHash() != null) {
                    out.put(item.getTxHash(), item);
                }
            }
        }
        return out;
    }

    private static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    /** The coin-minting faucet was removed; bootstrap must come from a genesis CSV. */
    private static void requireGenesisBootstrap() {
        throw new RuntimeException(
                "fundAddresses faucet removed — bootstrap the node via a genesis CSV "
                        + "that funds the benchmark wallets (see helper/test/TestGenesisOutput.csv)");
    }
}
