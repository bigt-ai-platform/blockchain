package net.bigtangle.mcmc.test.perf;

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

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
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
                       "spring.datasource.hikari.maximum-pool-size=100" })
public class MaxTPSBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MaxTPSBenchmark.class);

    private static final int CLIENTS = 50;
    private static final int PAYMENTS_PER_CLIENT = 1000;
    private static final int TOTAL_PAYMENTS = CLIENTS * PAYMENTS_PER_CLIENT;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired
    protected BlockSaveService blockSaveService;

    @Autowired
    protected MempoolService mempoolService;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
    }

    @Test
    public void testMaxTPS() throws Exception {

        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        List<ECKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < TOTAL_PAYMENTS; i++) walletKeys.add(new ECKey());

        HashMap<String, BigInteger> funding = new HashMap<>();
        for (ECKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(20000));
        }
        Block fb = wallet.payMoneyToECKeyList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund");
        if (fb != null) {
            Block rb = makeRewardBlock(fb);
            blockGraph.updateChain(false);
            log.info("Funded {} wallets", walletKeys.size());
        }

        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        Sha256Hash fundBlockHash = fb.getHash();
        Sha256Hash fundTxHash = fb.getTransactions().get(0).getHash();

        Map<String, FreeStandingTransactionOutput> addrToCandidate = new HashMap<>();
        for (int i = 0; i < fb.getTransactions().get(0).getOutputs().size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            if (utxo != null) {
                addrToCandidate.put(utxo.getAddress(),
                        new FreeStandingTransactionOutput(networkParameters, utxo));
            }
        }
        List<FreeStandingTransactionOutput> allCandidates = new ArrayList<>();
        for (ECKey k : walletKeys) {
            String addr = k.toAddress(networkParameters).toString();
            FreeStandingTransactionOutput c = addrToCandidate.get(addr);
            if (c != null) {
                allCandidates.add(c);
            }
        }
        log.info("Pre-fetched {} UTXOs in-memory (matched {} by address)",
                allCandidates.size(), addrToCandidate.size());

        ECKey finalRecipient = new ECKey();
        String finalAddr = finalRecipient.toAddress(networkParameters).toString();

        Block tipProto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block tipParent = store.get(tipProto.getPrevBlockHash());
        Block tipBranchParent = store.get(tipProto.getPrevBranchBlockHash());
        byte[] tipMinerAddr = tipProto.getMinerAddress();

        AtomicLong totalEcdsaNs = new AtomicLong(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];
        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            int startIdx = c * PAYMENTS_PER_CLIENT;
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    long t0 = System.nanoTime();
                    List<Transaction> txs = new ArrayList<>();

                    for (int i = 0; i < PAYMENTS_PER_CLIENT; i++) {
                        int idx = startIdx + i;
                        ECKey wk = walletKeys.get(idx);
                        FreeStandingTransactionOutput candidate = allCandidates.get(idx);

                        Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                        Transaction tx = w.payToListTransaction(null,
                                new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                                NetworkParameters.BIGTANGLE_TOKENID, "pay",
                                List.of(candidate));
                        if (tx != null) txs.add(tx);
                    }
                    totalEcdsaNs.addAndGet(System.nanoTime() - t0);

                    Block tip = Block.createBlock(networkParameters, tipParent, tipBranchParent);
                    tip.setMinerAddress(tipMinerAddr);
                    for (Transaction tx : txs) {
                        tip.addTransaction(tx);
                    }
                    blockService.batchBlockToMempool(tip);
                    ok.addAndGet(PAYMENTS_PER_CLIENT);
                } catch (Exception e) {
                    fail.addAndGet(PAYMENTS_PER_CLIENT);
                    log.error("Client submit failed", e);
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long submitWallMs = (System.nanoTime() - wallStart) / 1_000_000;
        long ecdsaMs = totalEcdsaNs.get() / 1_000_000;
        log.info("Submit phase: {} tx (ECDSA: {} ms, {} us/tx)",
                ok.get(), ecdsaMs, ok.get() > 0 ? totalEcdsaNs.get() / ok.get() / 1000 : 0);

        long batchStart = System.nanoTime();
        int batched = blockSaveService.batchBlocksFromMempool();
        long batchMs = (System.nanoTime() - batchStart) / 1_000_000;
        log.info("Batched {} transactions in {} ms", batched, batchMs);

        long mcmcStart = System.nanoTime();
        if (ok.get() > 0) mcmcService.update(store);
        long mcmcMs = (System.nanoTime() - mcmcStart) / 1_000_000;
        log.info("MCMC update:   {} ms", mcmcMs);

        long protoStart = System.nanoTime();
        if (ok.get() > 0) mcmcService.calcNewBlockPrototype(store);
        long protoMs = (System.nanoTime() - protoStart) / 1_000_000;
        log.info("New prototype: {} ms", protoMs);

        long chainStart = System.nanoTime();
        if (ok.get() > 0) blockGraph.updateChain(false);
        long chainMs = (System.nanoTime() - chainStart) / 1_000_000;
        log.info("Chain update:  {} ms", chainMs);

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        double tps = wallMs > 0 ? (double) ok.get() / wallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  Max TPS Benchmark (Zero-HTTP Submit)");
        log.info("==============================================");
        log.info("Clients:         {}", CLIENTS);
        log.info("Payments/client: {}", PAYMENTS_PER_CLIENT);
        log.info("Total:           {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Submit wall:     {} ms", submitWallMs);
        log.info("  ECDSA signing:   {} ms", ecdsaMs);
        log.info("Batch wall:      {} ms", batchMs);
        log.info("MCMC update:     {} ms", mcmcMs);
        log.info("New prototype:   {} ms", protoMs);
        log.info("Chain update:    {} ms", chainMs);
        log.info("Total wall:      {} ms", wallMs);
        log.info("Throughput:      {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
