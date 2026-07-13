package net.bigtangle.mcmc.test.benchmark;

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

import net.bigtangle.core.ECKey;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true" })
public class PaymentBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 50;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    private List<ECKey> userKeys;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        userKeys = createUserkey();
        fundClientKeys();
    }

    private void fundClientKeys() throws Exception {
        for (ECKey key : userKeys) {
            payBigTo(key, BigInteger.valueOf(1000000), null);
        }
        log.info("Funded {} client keys", userKeys.size());
    }

    @Test
    public void testPaymentThroughput() throws Exception {
        List<ECKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(new ECKey());

        AtomicLong totalLatencyNanos = new AtomicLong(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        CompletableFuture<?>[] futures = new CompletableFuture[CLIENTS];

        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            ECKey fromKey = userKeys.get(c % userKeys.size());
            ECKey toKey = recipients.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        long txStart = System.nanoTime();
                        payBigTo(fromKey, BigInteger.valueOf(1), null);
                        totalLatencyNanos.addAndGet(System.nanoTime() - txStart);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
            }, pool);
        }

        CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        pool.shutdownNow();

        long wallTimeMs = (System.nanoTime() - wallStart) / 1_000_000;
        long totalLatencyMs = totalLatencyNanos.get() / 1_000_000;
        int total = successCount.get() + failCount.get();
        double avgLatency = successCount.get() > 0 ? (double) totalLatencyMs / successCount.get() : 0;
        double throughput = wallTimeMs > 0 ? (double) successCount.get() / wallTimeMs * 1000 : 0;

        log.info("");
        log.info("=============================================");
        log.info("  10-Client Payment Benchmark Results");
        log.info("=============================================");
        log.info("Clients:          {}", CLIENTS);
        log.info("Payments/client:  {}", PAYMENTS_PER_CLIENT);
        log.info("Total:            {} ({} OK, {} failed)", total, successCount.get(), failCount.get());
        log.info("Wall time:        {} ms", wallTimeMs);
        log.info("Avg latency/tx:   {:.1f} ms", avgLatency);
        log.info("Throughput:       {:.1f} tx/s", throughput);
        log.info("=============================================");

        assertTrue(successCount.get() > 0, "At least one payment must succeed");
    }
}
