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
import net.bigtangle.core.ECKey;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;

/**
 * Real 10-client payment benchmark. 500 independent wallets, each with 1 UTXO.
 * Each wallet sends 2000 to a recipient (1 + 1999 change — no spendpending
 * lock because wallet is used once).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true" })
public class EmbeddedBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 50;
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
        // Initialize MCMC
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        // Create 500 independent wallets, each will get 2000 coins
        List<ECKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < TOTAL_PAYMENTS; i++) walletKeys.add(new ECKey());

        // Fund all in ONE tx (500 outputs x 12000) — 12000 covers payment + fee
        // Each wallet sends 10000 to recipient. 12000 - 10000 - 1000 fee = 1000 change
        HashMap<String, BigInteger> funding = new HashMap<>();
        for (ECKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(12000));
        }
        Block fb = wallet.payMoneyToECKeyList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund");
        if (fb != null) {
            Block rb = makeRewardBlock(fb);
            blockGraph.updateChain(false);
            log.info("Funding block {} confirmed by reward {}", fb.getHash(), rb.getHash());
        }

        // Re-init MCMC after funding
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        ECKey finalRecipient = new ECKey();
        String finalAddr = finalRecipient.toAddress(networkParameters).toString();

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
                for (int i = 0; i < PAYMENTS_PER_CLIENT; i++) {
                    ECKey walletKey = walletKeys.get(startIdx + i);
                    try {
                        // Each wallet sends 10000 to final recipient
                        long start = System.nanoTime();
                        HashMap<String, BigInteger> pmt = new HashMap<>();
                        pmt.put(finalAddr, BigInteger.valueOf(10000));
                        net.bigtangle.wallet.Wallet w = net.bigtangle.wallet.Wallet.fromKeys(
                                networkParameters, walletKey, contextRoot);
                        Block b = w.payMoneyToECKeyList(null, pmt,
                                NetworkParameters.BIGTANGLE_TOKENID, "pay");
                        if (b != null) {
                            // Confirm the payment
                            makeRewardBlock(b);
                            blockGraph.updateChain(false);
                            totalNs.addAndGet(System.nanoTime() - start);
                            ok.incrementAndGet();
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                    }
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
        log.info("  Real 10-Client Payment Benchmark");
        log.info("==============================================");
        log.info("Clients:         {}", CLIENTS);
        log.info("Payments/client: {}", PAYMENTS_PER_CLIENT);
        log.info("Total:           {} (OK {}, fail {})", ok.get() + fail.get(), ok.get(), fail.get());
        log.info("Wall time:       {} ms", wallMs);
        log.info("Avg latency:     {} ms", (long) avg);
        log.info("Throughput:      {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
