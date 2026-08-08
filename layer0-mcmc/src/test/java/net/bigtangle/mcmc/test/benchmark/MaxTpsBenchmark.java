package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.MempoolService;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=200" })
public class MaxTpsBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MaxTpsBenchmark.class);

    private static final int CLIENTS = 20;
    private static final int TX_PER_CLIENT = 250;
    private static final int BATCH_SIZE = 250;
    private static final int TOTAL_TX = Math.min(Integer.getInteger("bench.totalTx", CLIENTS * TX_PER_CLIENT), 50000);

    private static final File KEY_FILE = new File(System.getProperty("user.dir")
            .replace("/layer0-mcmc", "").replace("/l1-pai-mcmc", ""), "helper/testpq.json");
    private static List<PQKey> PRELOADED_KEYS;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;
    @Autowired
    protected MempoolService mempoolService;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
    }

    @Test
    public void testMempoolTps() throws Exception {
        // Load pre-generated PQKeys (avoids slow SLH-DSA keygen during benchmark)
        if (PRELOADED_KEYS == null) {
            PRELOADED_KEYS = PQKeyStore.load(KEY_FILE);
            log.info("Loaded {} pre-generated PQKeys from {}", PRELOADED_KEYS.size(), KEY_FILE);
        }
        List<PQKey> walletKeys = new ArrayList<>(PRELOADED_KEYS.subList(0, Math.min(TOTAL_TX, PRELOADED_KEYS.size())));

        // Fund each wallet by creating a P2PK transaction and confirming with reward block
        Transaction fundingTx = new Transaction(networkParameters);
        for (PQKey k : walletKeys) {
            fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx,
                    new Coin(BigInteger.valueOf(20000), NetworkParameters.BIGTANGLE_TOKENID), k));
        }
        List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
        Coin totalOut = Coin.valueOf(20000L * walletKeys.size(), NetworkParameters.BIGTANGLE_TOKENID);
        Coin need = totalOut.add(Coin.FEE_DEFAULT);
        Coin totalIn = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
        PQKey walletKey = wallet.walletKeys(null).get(0);
        for (FreeStandingTransactionOutput co : candidates) {
            if (!java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) continue;
            fundingTx.addInput(co.getUTXO().getBlockHash(), co);
            totalIn = totalIn.add(co.getValue());
            if (totalIn.getValue().compareTo(need.getValue()) >= 0) {
                Coin change = totalIn.subtract(need);
                if (!change.isNegative() && !change.isZero()) {
                    fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx, change, walletKey));
                }
                break;
            }
        }
        wallet.signTransaction(fundingTx, null);
        // Save the funding transaction in a block directly, capture block hash
        BlockStoreInterface ss = storeService.getStore();
        Sha256Hash fundBlockHash;
        try {
            Block proto = cacheBlockPrototypeService.getBlockPrototype(ss);
            proto.addTransaction(fundingTx);
            blockSaveService.saveBatchBlock(proto, ss);
            fundBlockHash = proto.getHash();
        } finally {
            ss.close();
        }
        blockGraph.updateChain(false);
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        log.info("Funded {} wallets", walletKeys.size());

        Sha256Hash fundTxHash = fundingTx.getHash();
        List<FreeStandingTransactionOutput> allCoins = new ArrayList<>();
        for (int i = 0; i < fundingTx.getOutputs().size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            if (utxo != null) {
                allCoins.add(new FreeStandingTransactionOutput(networkParameters, utxo));
            }
        }
        log.info("Pre-fetched {} UTXOs", allCoins.size());

        PQKey finalRecipient = PRELOADED_KEYS.get(PRELOADED_KEYS.size() - 1);
        String finalAddr = Address.fromHash160(networkParameters, finalRecipient.getPubKeyHash()).toBase58();

        // Pre-create all transactions (includes SLH-DSA signing, not timed)
        log.info("Pre-creating {} transactions (SLH-DSA signing)...", TOTAL_TX);
        List<Transaction> allTxs = new ArrayList<>(TOTAL_TX);
        for (int i = 0; i < TOTAL_TX; i++) {
            PQKey wk = walletKeys.get(i);
            Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
            Transaction tx = w.payToListTransaction(null,
                    new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                    NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(allCoins.get(i)));
            if (tx != null) allTxs.add(tx);
            if ((i + 1) % 200 == 0) log.info("  Pre-created {}/{} transactions", i + 1, TOTAL_TX);
        }
        log.info("Pre-created {} transactions", allTxs.size());

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        // Warm up MCMC for later batch processing
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        mcmcService.calcNewBlockPrototype(store);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];

        long wallStart = System.nanoTime();
        for (int c = 0; c < CLIENTS; c++) {
            int startIdx = c * TX_PER_CLIENT;
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < TX_PER_CLIENT; i++) {
                        int idx = startIdx + i;
                        mempoolService.submitTransaction(allTxs.get(idx));
                    }
                    ok.addAndGet(TX_PER_CLIENT);
                } catch (Exception e) {
                    fail.addAndGet(TX_PER_CLIENT);
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long submitWallMs = (System.nanoTime() - wallStart) / 1_000_000;

        // Server-side batch: drain mempool into blocks (parallel groups)
        // 1000 tx/block → 5000 tx splits into 5 parallel groups
        BlockSaveService.BATCH_TX_PER_BLOCK = Integer.getInteger("batch.txperblock", 1000);
        BlockSaveService.BATCH_PARALLELISM = Integer.getInteger("batch.parallelism",
                Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
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
