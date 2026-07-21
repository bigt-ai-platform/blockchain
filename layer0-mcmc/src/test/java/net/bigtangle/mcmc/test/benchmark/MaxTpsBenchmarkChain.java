package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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
import net.bigtangle.store.BlockStoreInterface;
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
                       "spring.datasource.hikari.maximum-pool-size=200" })
public class MaxTpsBenchmarkChain extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MaxTpsBenchmarkChain.class);

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
    public void testChainRealtime() throws Exception {
        int totalTx = Integer.parseInt(System.getProperty("chain.tx", "50000"));
        int clients = Integer.parseInt(System.getProperty("chain.clients", "200"));
        int batchSize = Integer.parseInt(System.getProperty("chain.batchSize", "250"));
        int mcmcInterval = Integer.parseInt(System.getProperty("chain.mcmcInterval", "10"));

        log.info("==============================================");
        log.info("  Real-Time Chain Benchmark");
        log.info("==============================================");
        log.info("Total tx:     {}", totalTx);
        log.info("Clients:      {}", clients);
        log.info("Batch size:   {}", batchSize);
        log.info("MCMC every:   {} blocks", mcmcInterval);

        List<ECKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < totalTx; i++) walletKeys.add(new ECKey());

        Wallet genesisWallet = Wallet.fromKeys(networkParameters,
                ECKey.fromPrivate(Utils.HEX.decode(genesisPriv)), contextRoot);
        HashMap<String, BigInteger> funding = new HashMap<>();
        for (ECKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(20000));
        }
        Transaction fundingTx = genesisWallet.payToList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund");
        log.info("Funding sent to {} wallets", walletKeys.size());

        Sha256Hash fundBlockHash;
        {
            BlockStoreInterface bs = storeService.getStore();
            try {
                Block proto = cacheBlockPrototypeService.getBlockPrototype(bs);
                proto.addTransaction(fundingTx);
                blockSaveService.saveBatchBlock(proto, bs);
                fundBlockHash = proto.getHash();
            } finally {
                bs.close();
            }
            blockGraph.updateChain(false);
        }

        Sha256Hash fundTxHash = fundingTx.getHash();
        Map<String, FreeStandingTransactionOutput> addrToCoin = new HashMap<>();
        for (int i = 0; i < fundingTx.getOutputs().size(); i++) {
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

        // Phase 1: Parallel submit all tx to mempool (like real usage)
        log.info("Submitting {} transactions via {} parallel clients...", totalTx, clients);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[clients];
        int txPerClient = totalTx / clients;

        long submitWallStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            int startIdx = c * txPerClient;
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    List<Transaction> txs = new ArrayList<>();
                    for (int i = 0; i < txPerClient; i++) {
                        int idx = startIdx + i;
                        ECKey wk = walletKeys.get(idx);
                        FreeStandingTransactionOutput coin = allCoins.get(idx);
                        Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                        Transaction tx = w.payToListTransaction(null,
                                new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                                NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(coin));
                        if (tx != null) txs.add(tx);

                        if (txs.size() == batchSize || i == txPerClient - 1) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            DataOutputStream dos = new DataOutputStream(baos);
                            for (Transaction bt : txs) {
                                byte[] btBytes = bt.bitcoinSerialize();
                                dos.writeInt(btBytes.length);
                                dos.write(btBytes);
                            }
                            dos.close();
                            OkHttp3Util.post(contextRoot + "submitTransactions", baos.toByteArray());
                            txs.clear();
                        }
                    }
                    ok.addAndGet(txPerClient);
                } catch (Exception e) {
                    fail.addAndGet(txPerClient);
                    log.error("Client failed", e);
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();
        long submitWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        log.info("Submit done: {} ms (OK {}, fail {})", submitWallMs, ok.get(), fail.get());

        // Phase 2: Drain mempool into blocks, MCMC every mcmcInterval blocks
        log.info("Draining mempool into blocks, MCMC every {} blocks...", mcmcInterval);
        long cumBatchNs = 0, cumMcmcNs = 0, cumProtoNs = 0, cumChainNs = 0;
        int blocksCreated = 0;

        long drainStart = System.nanoTime();
        while (true) {
            long t1 = System.nanoTime();
            int batched = blockSaveService.batchBlocksFromMempool();
            cumBatchNs += System.nanoTime() - t1;
            if (batched == 0) break;
            blocksCreated++;

            long t2 = System.nanoTime();
            blockGraph.updateChain(false);
            cumChainNs += System.nanoTime() - t2;

            if (blocksCreated % mcmcInterval == 0) {
                long t3 = System.nanoTime();
                mcmcService.update(store);
                cumMcmcNs += System.nanoTime() - t3;

                long t4 = System.nanoTime();
                mcmcService.calcNewBlockPrototype(store);
                cumProtoNs += System.nanoTime() - t4;
            }
        }
        long drainWallMs = (System.nanoTime() - drainStart) / 1_000_000;
        long totalWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        double tps = totalWallMs > 0 ? (double) ok.get() / totalWallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  Real-Time Chain Results");
        log.info("==============================================");
        log.info("Total tx:      {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Blocks:        {}", blocksCreated);
        log.info("MCMC interval: {} blocks", mcmcInterval);
        log.info("Submit wall:   {} ms", submitWallMs);
        log.info("Drain wall:    {} ms", drainWallMs);
        log.info("Total wall:    {} ms", totalWallMs);
        log.info("Throughput:    {} tx/s", (long) tps);
        log.info("");
        log.info("  Phase            Total (ms)");
        log.info("  ---------------  -----------");
        log.info("  Submit           {}", submitWallMs);
        log.info("  Batch (cum)      {}", cumBatchNs / 1_000_000);
        log.info("  MCMC (cum)       {}", cumMcmcNs / 1_000_000);
        log.info("  Prototype (cum)  {}", cumProtoNs / 1_000_000);
        log.info("  Chain (cum)      {}", cumChainNs / 1_000_000);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
