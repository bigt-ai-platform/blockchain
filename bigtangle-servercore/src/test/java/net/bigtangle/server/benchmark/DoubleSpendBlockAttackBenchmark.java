package net.bigtangle.server.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
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
 * Double-spend attack via CREATED TRANSFER BLOCKS — bypasses the mempool
 * entirely. The attacker funds {@code attack.tx} wallets, submits the
 * LEGITIMATE payments to the mempool, then for every double-spend CRAFTS a
 * {@code BLOCKTYPE_TRANSFER} block (template fetched from {@code /getTip},
 * double-spend tx appended) and injects it via {@code /batchBlock}.
 *
 * <p>Verifies the CONSENSUS layer rejects a double-spend that arrives inside a
 * block: the crafted block's tx must never confirm, no outpoint may be spent
 * twice, and the legit payments must still confirm.
 *
 * <pre>
 * mvn test -pl bigtangle-servercore \
 *   -Dtest=DoubleSpendBlockAttackBenchmark#doubleSpendViaTransferBlocks \
 *   -Dattack.seed=http://10.8.0.2:8083 \
 *   -Dattack.tx=1000 -Dattack.clients=10 -Dattack.confirmTimeoutSec=600
 * </pre>
 */
public class DoubleSpendBlockAttackBenchmark {

    private static final Logger log = LoggerFactory.getLogger(DoubleSpendBlockAttackBenchmark.class);

    @Test
    public void doubleSpendViaTransferBlocks() throws Exception {
        String seed = System.getProperty("attack.seed", "http://10.8.0.2:8083/");
        int pairs = Integer.parseInt(System.getProperty("attack.tx", "1000"));
        int clients = Integer.parseInt(System.getProperty("attack.clients", "10"));
        int confirmTimeoutSec = Integer.parseInt(System.getProperty("attack.confirmTimeoutSec", "600"));
        long fundAmount = Long.parseLong(System.getProperty("attack.fund", "30000"));
        long payAmount = Long.parseLong(System.getProperty("attack.pay", "20000"));

        if (!seed.startsWith("http")) seed = "http://" + seed;
        if (!seed.endsWith("/")) seed = seed + "/";
        final String base = seed;
        NetworkParameters params = MainNetParams.get();

        log.info("==============================================");
        log.info("  DOUBLE-SPEND VIA CREATED TRANSFER BLOCKS -> {}", base);
        log.info("  {} pairs = {} legit (mempool) + {} double-spend (in crafted blocks)", pairs, pairs, pairs);
        log.info("==============================================");

        // 1. Wallets + recipients. Fund CONTROL extra wallets whose LEGIT txs
        //    are injected as crafted blocks to prove the crafted-block path is
        //    valid (only the double-spend blocks must be rejected).
        int control = Integer.parseInt(System.getProperty("attack.control", "20"));
        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < pairs + control; i++) walletKeys.add(PQKey.createNew());
        PQKey merchant = PQKey.createNew();
        PQKey attacker = PQKey.createNew();
        String merchantAddr = Address.fromHash160(params, merchant.getPubKeyHash()).toBase58();
        String attackerAddr = Address.fromHash160(params, attacker.getPubKeyHash()).toBase58();

        // 2. Fund.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PQKey k : walletKeys) {
            Map<String, Object> e = new HashMap<>();
            e.put("address", Address.fromHash160(params, k.getPubKeyHash()).toBase58());
            e.put("value", fundAmount);
            e.put("pubkey", Utils.HEX.encode(k.getPubKey()));
            entries.add(e);
        }
        OkHttp3Util.postString(base + "fundAddresses", Json.jsonmapper().writeValueAsString(Map.of("addresses", entries)));

        // 3. Fetch UTXOs.
        List<String> hashes = new ArrayList<>();
        for (PQKey k : walletKeys) hashes.add(Utils.HEX.encode(k.getPubKeyHash()));
        GetOutputsResponse gor = Json.jsonmapper().readValue(
                OkHttp3Util.postString(base + "getOutputs", Json.jsonmapper().writeValueAsString(hashes)),
                GetOutputsResponse.class);
        Map<String, UTXO> addrToUtxo = new HashMap<>();
        if (gor.getOutputs() != null) {
            for (UTXO u : gor.getOutputs()) {
                if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount && u.getAddress() != null) {
                    addrToUtxo.put(u.getAddress(), u);
                }
            }
        }
        log.info("Fetched {}/{} UTXOs", addrToUtxo.size(), walletKeys.size());
        assertTrue(addrToUtxo.size() > 0, "No funded UTXOs");

        // 4. Build pairs + control legit txs.
        Transaction[] legit = new Transaction[pairs];
        Transaction[] ds = new Transaction[pairs];
        List<Transaction> controlTxs = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int client = c;
            futures.add(pool.submit(() -> {
                for (int i = client; i < pairs; i += clients) {
                    try {
                        PQKey wk = walletKeys.get(i);
                        UTXO utxo = addrToUtxo.get(Address.fromHash160(params, wk.getPubKeyHash()).toBase58());
                        if (utxo == null) continue;
                        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, wk);
                        legit[i] = w.payToListTransaction(null, new HashMap<>(Map.of(merchantAddr,
                                BigInteger.valueOf(payAmount))), NetworkParameters.BIGTANGLE_TOKENID, "legit",
                                List.of(coin));
                        ds[i] = w.payToListTransaction(null, new HashMap<>(Map.of(attackerAddr,
                                BigInteger.valueOf(payAmount))), NetworkParameters.BIGTANGLE_TOKENID, "double-spend",
                                List.of(coin));
                    } catch (Exception e) {
                        log.error("build pair {} failed", i, e);
                    }
                }
                for (int j = client; j < control; j += clients) {
                    try {
                        int idx = pairs + j;
                        PQKey wk = walletKeys.get(idx);
                        UTXO utxo = addrToUtxo.get(Address.fromHash160(params, wk.getPubKeyHash()).toBase58());
                        if (utxo == null) continue;
                        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, wk);
                        synchronized (controlTxs) {
                            controlTxs.add(w.payToListTransaction(null, new HashMap<>(Map.of(merchantAddr,
                                    BigInteger.valueOf(payAmount))), NetworkParameters.BIGTANGLE_TOKENID,
                                    "control-legit", List.of(coin)));
                        }
                    } catch (Exception e) {
                        log.error("build control {} failed", client, e);
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) f.get(10, java.util.concurrent.TimeUnit.MINUTES);
        pool.shutdownNow();
        log.info("Built {} pairs + {} control txs", pairs, controlTxs.size());

        // 5. Submit legit to the mempool.
        java.util.concurrent.atomic.AtomicInteger legitSubmitted = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger legitRejected = new java.util.concurrent.atomic.AtomicInteger(0);
        pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        futures = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int client = c;
            futures.add(pool.submit(() -> {
                for (int i = client; i < pairs; i += clients) {
                    if (legit[i] == null) continue;
                    if (submitTx(base, legit[i])) {
                        legitSubmitted.incrementAndGet();
                    } else {
                        legitRejected.incrementAndGet();
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) f.get(10, java.util.concurrent.TimeUnit.MINUTES);
        pool.shutdownNow();
        log.info("Legit submitted to mempool: {} (rejected {})", legitSubmitted.get(), legitRejected.get());

        // 6. Inject double-spends AND control-legit txs via CRAFTED TRANSFER
        //    BLOCKS (/batchBlock). The control blocks must be ACCEPTED (proving
        //    the crafted-block path is valid); the double-spend blocks must not.
        java.util.concurrent.atomic.AtomicInteger blockAccepted = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger blockRejected = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger controlAccepted = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger controlRejected = new java.util.concurrent.atomic.AtomicInteger(0);
        pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        futures = new ArrayList<>();
        long injectStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int client = c;
            futures.add(pool.submit(() -> {
                for (int i = client; i < pairs; i += clients) {
                    if (ds[i] == null) continue;
                    try {
                        Block block = craftTransferBlock(base, params, ds[i]);
                        if (submitBlock(base, block)) blockAccepted.incrementAndGet();
                        else blockRejected.incrementAndGet();
                    } catch (Exception e) {
                        blockRejected.incrementAndGet();
                    }
                }
                for (int j = client; j < controlTxs.size(); j += clients) {
                    try {
                        Block block = craftTransferBlock(base, params, controlTxs.get(j));
                        if (submitBlock(base, block)) controlAccepted.incrementAndGet();
                        else controlRejected.incrementAndGet();
                    } catch (Exception e) {
                        controlRejected.incrementAndGet();
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) f.get(15, java.util.concurrent.TimeUnit.MINUTES);
        pool.shutdownNow();
        long injectMs = (System.nanoTime() - injectStart) / 1_000_000;
        log.info("Crafted-block inject done: {} ms — double-spend accepted {}, rejected {}; control accepted {}, rejected {}",
                injectMs, blockAccepted.get(), blockRejected.get(), controlAccepted.get(), controlRejected.get());

        // 7. Poll confirmation.
        int legitConfirmed = 0;
        int dsConfirmed = 0;
        long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            legitConfirmed = countConfirmedByAddress(base, merchantAddr);
            dsConfirmed = countConfirmedByAddress(base, attackerAddr);
            log.info("  confirmed legit {}/{}  double-spend {}/{}", legitConfirmed, legitSubmitted.get(),
                    dsConfirmed, blockAccepted.get());
            if (legitConfirmed >= legitSubmitted.get() && dsConfirmed == 0) break;
            Thread.sleep(5000);
        }

        log.info("");
        log.info("==============================================");
        log.info("  DOUBLE-SPEND VIA CREATED BLOCKS — RESULTS");
        log.info("==============================================");
        log.info("Pairs:              {}", pairs);
        log.info("Legit submitted:    {}", legitSubmitted.get());
        log.info("Legit confirmed:    {}", legitConfirmed);
        log.info("Double-spend blocks accepted: {}", blockAccepted.get());
        log.info("Double-spend blocks rejected: {}", blockRejected.get());
        log.info("Control blocks accepted:      {}", controlAccepted.get());
        log.info("Control blocks rejected:      {}", controlRejected.get());
        log.info("Double-spend CONFIRMED:       {}", dsConfirmed);
        log.info("==============================================");

        assertEquals(0, dsConfirmed, "A double-spend inside a crafted TRANSFER block was confirmed (double redemption!)");
        assertTrue(controlAccepted.get() > 0, "No crafted CONTROL block was accepted — the crafted-block path itself is broken");
        assertTrue(blockRejected.get() > 0, "No crafted double-spend block was rejected — double-spend protection not exercised");
        assertTrue(legitConfirmed > 0, "No legitimate payment confirmed");
        log.info("BLOCK-LEVEL ATTACK DEFLECTED: {} crafted double-spend blocks rejected (control {} accepted), 0 double-redemption",
                blockRejected.get(), controlAccepted.get());
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

    /**
     * Builds a valid BLOCKTYPE_TRANSFER block approving the CURRENT DAG tips
     * (template from /getTip, parents fetched by hash) with the given
     * (double-spend) transaction attached, then POSTs it to /batchBlock.
     */
    private static boolean submitBlock(String base, Block block) {
        try {
            OkHttp3Util.post(base + "batchBlock", block.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Block fetchBlock(String base, NetworkParameters params, net.bigtangle.core.Sha256Hash hash)
            throws Exception {
        byte[] r = OkHttp3Util.postString(base + "getBlockByHash",
                Json.jsonmapper().writeValueAsString(Map.of("hashHex", hash.toString(), "text", "false")));
        Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
        String dataHex = (String) m.get("dataHex");
        return params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
    }

    private static Block craftTransferBlock(String base, NetworkParameters params, Transaction tx) throws Exception {
        byte[] tipResp = OkHttp3Util.postString(base + "getTip", "{}");
        Map<?, ?> m = Json.jsonmapper().readValue(tipResp, Map.class);
        String dataHex = (String) m.get("dataHex");
        Block proto = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
        Block trunk = fetchBlock(base, params, proto.getPrevBlockHash());
        Block branch = fetchBlock(base, params, proto.getPrevBranchBlockHash());
        Block block = Block.createBlock(params, trunk, branch);
        block.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
        block.addTransaction(tx);
        return block;
    }

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
}
