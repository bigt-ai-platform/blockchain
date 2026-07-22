package net.bigtangle.performance;

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
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Utils;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.wallet.Wallet;

/**
 * Measures payment throughput with 10 concurrent clients.
 * Reports transactions/second, blocks/second, and average latency.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class PaymentBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 50;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    private List<PQKey> userKeys;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setMcmc_active(false);
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        userKeys = createUserkey();
        // Fund each client key with initial balance
        fundClientKeys();
    }

    private void fundClientKeys() throws Exception {
        for (PQKey key : userKeys) {
            payBigTo(key, BigInteger.valueOf(1000000), null);
        }
        log.info("Funded {} client keys", userKeys.size());
    }

    @Test
    public void testPaymentThroughput10Clients() throws Exception {
        PQKey[] recipients = createRecipients(CLIENTS);
        AtomicLong totalLatencyNanos = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalBlockTimeNanos = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CompletableFuture<?>[] futures = new CompletableFuture[CLIENTS];

        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            PQKey fromKey = userKeys.get(c % userKeys.size());
            PQKey toKey = recipients[c];

            futures[c] = CompletableFuture.runAsync(() -> {
                Wallet clientWallet = Wallet.fromKeys(networkParameters, fromKey, contextRoot);
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        HashMap<String, BigInteger> payment = new HashMap<>();
                        payment.put(toKey.toAddress(networkParameters).toString(), BigInteger.valueOf(1));

                        long txStart = System.nanoTime();
                        mcmcService.calcNewBlockPrototype(store);
                        Block b = clientWallet.payMoneyToECKeyList(null, payment,
                                NetworkParameters.BIGTANGLE_TOKENID, "perf-pay");
                        totalBlockTimeNanos.addAndGet(System.nanoTime() - txStart);

                        if (b != null) {
                            Block reward = makeRewardBlock(b);
                            totalLatencyNanos.addAndGet(System.nanoTime() - txStart);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        log.debug("Payment {} failed: {}", p, e.getMessage());
                    }
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        int total = successCount.get() + failCount.get();
        long totalMs = totalLatencyNanos.get() / 1_000_000;
        long blockMs = totalBlockTimeNanos.get() / 1_000_000;
        double avgLatency = successCount.get() > 0 ? (double) totalMs / successCount.get() : 0;
        double throughput = totalMs > 0 ? (double) successCount.get() / totalMs * 1000 : 0;

        log.info("");
        log.info("=============================================");
        log.info("  10-Client Payment Performance Results");
        log.info("=============================================");
        log.info("Clients:          {}", CLIENTS);
        log.info("Payments/client:  {}", PAYMENTS_PER_CLIENT);
        log.info("Total payments:   {} ({} success, {} failed)",
                total, successCount.get(), failCount.get());
        log.info("");
        log.info("Total wall time:  {} ms", totalMs);
        log.info("Avg latency/tx:   {:.1f} ms", avgLatency);
        log.info("Throughput:       {:.1f} tx/s", throughput);
        log.info("Block time total: {} ms", blockMs);
        log.info("=============================================");
    }

    private PQKey[] createRecipients(int count) {
        PQKey[] keys = new PQKey[count];
        for (int i = 0; i < count; i++) {
            keys[i] = PQKey.createNew();
        }
        return keys;
    }
}
