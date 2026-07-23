package net.bigtangle.mcmc.test.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Max-throughput benchmark. Each client batches all payments into ONE block.
 * ONE reward block at the end confirms everything. HikariCP pool = 100.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=100" })
public class EmbeddedBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBenchmark.class);
    private static final int CLIENTS = 50;
    private static final int PAYMENTS_PER_CLIENT = 100;
    private static final int TOTAL_PAYMENTS = CLIENTS * PAYMENTS_PER_CLIENT;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
    }

    @Test
    public void testPaymentThroughput() throws Exception {
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        // Create wallet keys — one per payment
        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < TOTAL_PAYMENTS; i++) walletKeys.add(PQKey.createNew());

        // Fund all in ONE transaction
        List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
        List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
        for (FreeStandingTransactionOutput co : candidates) {
            if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
                tokenUtxos.add(co);
            }
        }
        Coin total = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
        Coin totalSend = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
        Transaction fundingTx = new Transaction(networkParameters);
        for (PQKey k : walletKeys) {
            Coin amount = Coin.valueOf(20000, NetworkParameters.BIGTANGLE_TOKENID);
            fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx, amount, k));
            totalSend = totalSend.add(amount);
        }
        Coin sendWithFee = totalSend.add(Coin.FEE_DEFAULT);
        for (FreeStandingTransactionOutput co : tokenUtxos) {
            fundingTx.addInput(co.getUTXO().getBlockHash(), co);
            total = total.add(co.getValue());
            if (total.getValue().compareTo(sendWithFee.getValue()) >= 0) {
                Coin change = total.subtract(sendWithFee);
                if (!change.isNegative() && !change.isZero()) {
                    PQKey changeKey = wallet.walletKeys(null).get(0);
                    fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx, change, changeKey));
                }
                break;
            }
        }
        if (total.getValue().compareTo(totalSend.getValue()) < 0) {
            throw new InsufficientMoneyException(totalSend + " outputs size= " + tokenUtxos.size());
        }
        wallet.signTransaction(fundingTx, null);
        Block fb = wrapTransaction(fundingTx);
        if (fb != null) {
            Block rb = makeRewardBlock(fb);
            blockGraph.updateChain(false);
            log.info("Funded {} wallets", walletKeys.size());
        }

        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        PQKey finalRecipient = PQKey.createNew();
        AtomicLong totalNs = new AtomicLong(0);
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
                    Wallet firstW = Wallet.fromKeys(networkParameters, walletKeys.get(startIdx), contextRoot);
                    List<Transaction> txs = new ArrayList<>();
                    for (int i = 0; i < PAYMENTS_PER_CLIENT; i++) {
                        PQKey wk = walletKeys.get(startIdx + i);
                        Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                        List<FreeStandingTransactionOutput> localCandidates = w.calculateAllSpendCandidates(null, false);
                        List<FreeStandingTransactionOutput> filtered = new ArrayList<>();
                        for (FreeStandingTransactionOutput co : localCandidates) {
                            if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
                                filtered.add(co);
                            }
                        }
                        Coin localTotalSend = Coin.valueOf(15000, NetworkParameters.BIGTANGLE_TOKENID);
                        Transaction tx = new Transaction(networkParameters);
                        tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, localTotalSend, finalRecipient));
                        Coin restAmount = localTotalSend.negate().subtract(Coin.FEE_DEFAULT);
                        PQKey beneficiary = null;
                        for (FreeStandingTransactionOutput co : filtered) {
                            beneficiary = wk;
                            restAmount = co.getValue().add(restAmount);
                            tx.addInput(co.getUTXO().getBlockHash(), co);
                            if (!restAmount.isNegative()) {
                                if (restAmount.isPositive()) {
                                    tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, restAmount, beneficiary));
                                }
                                break;
                            }
                        }
                        w.signTransaction(tx, null);
                        if (tx != null) txs.add(tx);
                    }

                    long start = System.nanoTime();
                    for (Transaction txb : txs) firstW.submitTransaction(txb);
                    totalNs.addAndGet(System.nanoTime() - start);
                    ok.addAndGet(PAYMENTS_PER_CLIENT);
                } catch (Exception e) {
                    fail.addAndGet(PAYMENTS_PER_CLIENT);
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        // Final reward + chain update to confirm all blocks
        if (ok.get() > 0) {
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
            blockGraph.updateChain(false);
        }

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        double tps = wallMs > 0 ? (double) ok.get() / wallMs * 1000 : 0;
        double avg = ok.get() > 0 ? (double) totalNs.get() / ok.get() / 1_000_000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  Max-Throughput Benchmark");
        log.info("==============================================");
        log.info("Clients:         {}", CLIENTS);
        log.info("Payments/client: {}", PAYMENTS_PER_CLIENT);
        log.info("Pool size:       100");
        log.info("Total:           {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Wall time:       {} ms", wallMs);
        log.info("Avg latency:     {} ms", (long) avg);
        log.info("Throughput:      {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
