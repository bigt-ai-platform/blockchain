package net.bigtangle.server.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Mass double-spend attack verification against the DEPLOYED prod PoS node.
 *
 * <p>Funds {@code attack.tx} wallets, builds {@code attack.tx} LEGITIMATE
 * payments (pay the merchant) plus {@code attack.tx} DOUBLE-SPEND attempts
 * that re-spend the SAME UTXOs (pay the attacker) — 2&times;N transactions.
 * Every transaction is submitted individually over HTTP, then the network's
 * protection is verified:
 * <ol>
 *   <li>the mempool/consensus must reject the second spend of each UTXO
 *       (ConflictPossibleException at submit, or non-confirmation),</li>
 *   <li>at most ONE of each {valid, double-spend} pair may confirm — zero
 *       double redemption,</li>
 *   <li>the node must stay up and confirm the legitimate payments.</li>
 * </ol>
 *
 * <p>Run (see also {@code helper/prod/prodbench.sh} pattern):
 * <pre>
 * mvn test -pl bigtangle-servercore \
 *   -Dtest=DoubleSpendAttackBenchmark#massDoubleSpend \
 *   -Dattack.seed=http://10.8.0.2:8083 \
 *   -Dattack.tx=5000 -Dattack.clients=20 -Dattack.confirmTimeoutSec=600
 * </pre>
 */
public class DoubleSpendAttackBenchmark {

    private static final Logger log = LoggerFactory.getLogger(DoubleSpendAttackBenchmark.class);

    @Test
    public void massDoubleSpend() throws Exception {
        String seed = System.getProperty("attack.seed", "http://10.8.0.2:8083/");
        int pairs = Integer.parseInt(System.getProperty("attack.tx", "5000"));
        int clients = Integer.parseInt(System.getProperty("attack.clients", "20"));
        int confirmTimeoutSec = Integer.parseInt(System.getProperty("attack.confirmTimeoutSec", "600"));
        long fundAmount = Long.parseLong(System.getProperty("attack.fund", "30000"));
        long payAmount = Long.parseLong(System.getProperty("attack.pay", "20000"));

        if (!seed.startsWith("http")) seed = "http://" + seed;
        if (!seed.endsWith("/")) seed = seed + "/";
        final String base = seed;
        NetworkParameters params = MainNetParams.get();

        log.info("==============================================");
        log.info("  MASS DOUBLE-SPEND ATTACK -> {}", base);
        log.info("  {} pairs ({} legit + {} double-spend) = {} tx", pairs, pairs, pairs, pairs * 2);
        log.info("==============================================");

        // 1. Wallets: one funded UTXO per pair, plus merchant + attacker addresses.
        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < pairs; i++) walletKeys.add(PQKey.createNew());
        PQKey merchant = PQKey.createNew();
        PQKey attacker = PQKey.createNew();
        String merchantAddr = Address.fromHash160(params, merchant.getPubKeyHash()).toBase58();
        String attackerAddr = Address.fromHash160(params, attacker.getPubKeyHash()).toBase58();

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
        requireGenesisBootstrap();
        log.info("Funded {} wallets", walletKeys.size());

        // 3. Fetch the funded UTXOs.
        List<String> pubKeyHashes = new ArrayList<>();
        for (PQKey k : walletKeys) pubKeyHashes.add(Utils.HEX.encode(k.getPubKeyHash()));
        byte[] resp = OkHttp3Util.postString(base + "getOutputs", Json.jsonmapper().writeValueAsString(pubKeyHashes));
        GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
        Map<String, UTXO> addrToUtxo = new HashMap<>();
        if (gor.getOutputs() != null) {
            for (UTXO u : gor.getOutputs()) {
                if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount && u.getAddress() != null) {
                    addrToUtxo.put(u.getAddress(), u);
                }
            }
        }
        log.info("Fetched {}/{} UTXOs", addrToUtxo.size(), walletKeys.size());
        assertTrue(addrToUtxo.size() > 0, "No funded UTXOs available");

        // 4. Pre-build pairs: legit tx (pay merchant) + double-spend tx (pay
        //    attacker) spending the SAME UTXO. Indexed by outpoint.
        Transaction[] legit = new Transaction[pairs];
        Transaction[] ds = new Transaction[pairs];
        int txPerClient = Math.max(1, pairs / clients);
        ExecutorService buildPool = Executors.newFixedThreadPool(clients);
        List<ConcurrentLinkedQueue<Integer>> buildWork = new ArrayList<>();
        for (int c = 0; c < clients; c++) buildWork.add(new ConcurrentLinkedQueue<>());
        for (int i = 0; i < pairs; i++) buildWork.get(i % clients).add(i);
        List<java.util.concurrent.Future<?>> buildFutures = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int client = c;
            buildFutures.add(buildPool.submit(() -> {
                for (Integer i : buildWork.get(client)) {
                    try {
                        PQKey wk = walletKeys.get(i);
                        UTXO utxo = addrToUtxo.get(Address.fromHash160(params, wk.getPubKeyHash()).toBase58());
                        if (utxo == null) continue;
                        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, wk);
                        HashMap<String, BigInteger> payMerchant = new HashMap<>();
                        payMerchant.put(merchantAddr, BigInteger.valueOf(payAmount));
                        legit[i] = w.payToListTransaction(null, payMerchant, NetworkParameters.BIGTANGLE_TOKENID,
                                "legit", List.of(coin));
                        HashMap<String, BigInteger> payAttacker = new HashMap<>();
                        payAttacker.put(attackerAddr, BigInteger.valueOf(payAmount));
                        ds[i] = w.payToListTransaction(null, payAttacker, NetworkParameters.BIGTANGLE_TOKENID,
                                "double-spend", List.of(coin));
                    } catch (Exception e) {
                        log.error("build pair {} failed", i, e);
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : buildFutures) f.get(10, TimeUnit.MINUTES);
        buildPool.shutdownNow();
        int built = 0;
        for (int i = 0; i < pairs; i++) if (legit[i] != null && ds[i] != null) built++;
        log.info("Pre-built {}/{} pairs", built, pairs);

        // 5. Submit every tx individually: legit FIRST (wins the outpoint), then
        //    the double-spend (must be rejected). Track submit success/failure.
        ConcurrentLinkedQueue<String> legitHashes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> dsHashes = new ConcurrentLinkedQueue<>();
        AtomicInteger legitSubmitted = new AtomicInteger(0);
        AtomicInteger dsRejected = new AtomicInteger(0);
        AtomicInteger dsSubmitted = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        long submitStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int client = c;
            futures.add(pool.submit(() -> {
                for (int i = client; i < pairs; i += clients) {
                    if (legit[i] == null) continue;
                    if (submitTx(base, legit[i])) {
                        legitSubmitted.incrementAndGet();
                        legitHashes.add(legit[i].getHash().toString());
                    }
                    if (submitTx(base, ds[i])) {
                        dsSubmitted.incrementAndGet();
                        dsHashes.add(ds[i].getHash().toString());
                    } else {
                        dsRejected.incrementAndGet();
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) f.get(10, TimeUnit.MINUTES);
        pool.shutdownNow();
        long submitMs = (System.nanoTime() - submitStart) / 1_000_000;
        log.info("Submit done: {} ms — legit ok {}, double-spend rejected {}, double-spend ACCEPTED {}",
                submitMs, legitSubmitted.get(), dsRejected.get(), dsSubmitted.get());

        // 6. Poll confirmation via per-address status (one call per round:
        //    legit txs pay the merchant, double-spends pay the attacker).
        long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
        int legitConfirmed = 0;
        int dsConfirmed = 0;
        while (System.currentTimeMillis() < deadline) {
            legitConfirmed = countConfirmedByAddress(base, merchantAddr);
            dsConfirmed = countConfirmedByAddress(base, attackerAddr);
            log.info("  confirmed legit {}/{}  double-spend {}/{}", legitConfirmed, legitSubmitted.get(),
                    dsConfirmed, dsSubmitted.get());
            if (legitConfirmed >= legitSubmitted.get() && dsConfirmed == 0) break;
            Thread.sleep(5000);
        }
        long confirmMs = (System.nanoTime() - submitStart) / 1_000_000;

        // 7. VERIFY protection: no double-spend may confirm (zero double
        //    redemption), the protection must actually have been exercised
        //    (some double-spend rejected), and the legit payments confirm.
        log.info("");
        log.info("==============================================");
        log.info("  MASS DOUBLE-SPEND ATTACK RESULTS");
        log.info("==============================================");
        log.info("Pairs:           {} ({} legit + {} double-spend tx)", pairs, pairs, pairs);
        log.info("Legit submitted: {}", legitSubmitted.get());
        log.info("Legit confirmed: {}", legitConfirmed);
        log.info("Double-spend rejected at submit: {}", dsRejected.get());
        log.info("Double-spend accepted:           {}", dsSubmitted.get());
        log.info("Double-spend CONFIRMED:          {}", dsConfirmed);
        log.info("Submit wall:     {} ms", submitMs);
        log.info("Confirm wall:    {} ms", confirmMs);
        log.info("==============================================");

        assertEquals(0, dsConfirmed, "A double-spend transaction was confirmed (double redemption!)");
        assertTrue(dsRejected.get() > 0, "No double-spend was rejected — protection not exercised");
        assertTrue(legitConfirmed > 0, "No legitimate payment confirmed");
        log.info("ATTACK DEFLECTED: {} double-spend attempts rejected, {} legit payments confirmed, 0 double-redemption",
                dsRejected.get(), legitConfirmed);
    }

    /** POST a single raw tx; true if the node accepted it into the mempool. */
    private static boolean submitTx(String base, Transaction tx) {
        try {
            OkHttp3Util.post(base + "submitTransaction", tx.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Count CONFIRMED transactions paying {@code address} (one HTTP round). */
    private static int countConfirmedByAddress(String base, String address) {
        try {
            byte[] r = OkHttp3Util.postString(base + "getTransactionsStatusByAddress",
                    Json.jsonmapper().writeValueAsString(Map.of("address", address)));
            net.bigtangle.response.GetTransactionStatusResponse.GetTransactionsStatusResponse resp =
                    Json.jsonmapper().readValue(r,
                            net.bigtangle.response.GetTransactionStatusResponse.GetTransactionsStatusResponse.class);
            int n = 0;
            if (resp.getTransactions() != null) {
                for (net.bigtangle.response.GetTransactionStatusResponse item : resp.getTransactions()) {
                    if ("CONFIRMED".equals(item.getStatus())) n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The coin-minting faucet was removed; bootstrap must come from a genesis CSV. */
    private static void requireGenesisBootstrap() {
        throw new RuntimeException(
                "fundAddresses faucet removed — bootstrap the node via a genesis CSV "
                        + "that funds the benchmark wallets (see helper/test/TestGenesisOutput.csv)");
    }
}
