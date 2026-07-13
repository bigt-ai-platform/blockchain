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

import net.bigtangle.core.ECKey;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;

/**
 * 10-client payment benchmark using embedded Spring Boot server with MCMC.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true" })
public class EmbeddedBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 100;

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    private List<ECKey> userKeys;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        userKeys = createUserkey();
        for (ECKey key : userKeys) {
            payBigTo(key, BigInteger.valueOf(1000000), null);
        }
        log.info("Funded {} wallets", userKeys.size());
    }

    @Test
    public void testPaymentThroughput() throws Exception {
        List<ECKey> recipients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) recipients.add(new ECKey());

        AtomicLong totalNs = new AtomicLong(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];
        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            ECKey fromKey = userKeys.get(c % userKeys.size());
            ECKey toKey = recipients.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                for (int p = 0; p < PAYMENTS_PER_CLIENT; p++) {
                    try {
                        long start = System.nanoTime();
                        payBigTo(fromKey, BigInteger.valueOf(1), null);
                        totalNs.addAndGet(System.nanoTime() - start);
                        ok.incrementAndGet();
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
        log.info("  Embedded 10-Client Payment Benchmark");
        log.info("==============================================");
        log.info("OK:      {}", ok.get());
        log.info("Fail:    {}", fail.get());
        log.info("Wall:    {} ms", wallMs);
        log.info("Avg lat: {} ms", (long) avg);
        log.info("TPS:     {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
