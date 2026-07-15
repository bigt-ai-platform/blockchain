package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=100" })
public class MaxTpsBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MaxTpsBenchmark.class);

    private static final int CLIENTS = 50;
    private static final int TX_PER_CLIENT = 1000;
    private static final int TOTAL_TX = CLIENTS * TX_PER_CLIENT;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    private String genesisPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
    }

    @Test
    public void testMempoolTps() throws Exception {
        List<ECKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < TOTAL_TX; i++) walletKeys.add(new ECKey());

        Wallet genesisWallet = Wallet.fromKeys(networkParameters,
                ECKey.fromPrivate(Utils.HEX.decode(genesisPriv)), contextRoot);
        HashMap<String, BigInteger> funding = new HashMap<>();
        for (ECKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(20000));
        }
        Block fb = genesisWallet.payToList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund");
        if (fb != null) {
            makeRewardBlock(fb);
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }
        log.info("Funded {} wallets", walletKeys.size());

        Sha256Hash fundBlockHash = fb.getHash();
        Sha256Hash fundTxHash = fb.getTransactions().get(0).getHash();
        Map<String, FreeStandingTransactionOutput> addrToCoin = new HashMap<>();
        for (int i = 0; i < fb.getTransactions().get(0).getOutputs().size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            if (utxo != null) {
                addrToCoin.put(utxo.getAddress(),
                        new FreeStandingTransactionOutput(networkParameters, utxo));
            }
        }
        List<FreeStandingTransactionOutput> allCoins = new ArrayList<>();
        for (ECKey k : walletKeys) {
            String addr = k.toAddress(networkParameters).toString();
            FreeStandingTransactionOutput c = addrToCoin.get(addr);
            if (c != null) allCoins.add(c);
        }
        log.info("Pre-fetched {} UTXOs", allCoins.size());

        ECKey finalRecipient = new ECKey();
        String finalAddr = finalRecipient.toAddress(networkParameters).toString();

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];

        // Warm up MCMC for later batch processing
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        mcmcService.calcNewBlockPrototype(store);

        long wallStart = System.nanoTime();
        for (int c = 0; c < CLIENTS; c++) {
            int startIdx = c * TX_PER_CLIENT;
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < TX_PER_CLIENT; i++) {
                        int idx = startIdx + i;
                        ECKey wk = walletKeys.get(idx);
                        FreeStandingTransactionOutput coin = allCoins.get(idx);
                        Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                        Transaction tx = w.payToListTransaction(null,
                                new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                                NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(coin));
                        if (tx != null) {
                            // Submit raw transaction to mempool via HTTP
                            OkHttp3Util.post(contextRoot + "submitTransaction",
                                    tx.bitcoinSerialize());
                        }
                    }
                    ok.addAndGet(TX_PER_CLIENT);
                } catch (Exception e) {
                    fail.addAndGet(TX_PER_CLIENT);
                    log.error("Client failed", e);
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long submitWallMs = (System.nanoTime() - wallStart) / 1_000_000;

        // Server-side batch: drain mempool into blocks
        long batchStart = System.nanoTime();
        int batched = blockSaveService.batchBlocksFromMempool();
        long batchMs = (System.nanoTime() - batchStart) / 1_000_000;
        log.info("Batched {} transactions in {} ms", batched, batchMs);

        // MCMC update + prototype
        long mcmcStart = System.nanoTime();
        if (ok.get() > 0) mcmcService.update(store);
        long mcmcMs = (System.nanoTime() - mcmcStart) / 1_000_000;

        long protoStart = System.nanoTime();
        if (ok.get() > 0) mcmcService.calcNewBlockPrototype(store);
        long protoMs = (System.nanoTime() - protoStart) / 1_000_000;

        // Chain update
        long chainStart = System.nanoTime();
        if (ok.get() > 0) blockGraph.updateChain(false);
        long chainMs = (System.nanoTime() - chainStart) / 1_000_000;

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        double tps = wallMs > 0 ? (double) ok.get() / wallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  Mempool TPS (submitTransaction via HTTP)");
        log.info("==============================================");
        log.info("Clients:      {}", CLIENTS);
        log.info("Tx/client:    {}", TX_PER_CLIENT);
        log.info("Total tx:     {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Submit wall:  {} ms", submitWallMs);
        log.info("Batch wall:   {} ms", batchMs);
        log.info("MCMC update:  {} ms", mcmcMs);
        log.info("Prototype:    {} ms", protoMs);
        log.info("Chain update: {} ms", chainMs);
        log.info("Total wall:   {} ms", wallMs);
        log.info("Throughput:   {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
