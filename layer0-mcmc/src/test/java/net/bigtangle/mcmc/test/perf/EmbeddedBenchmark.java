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
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;

/**
 * 10-client payment benchmark using embedded Spring Boot server with MCMC.
 * 
 * Each client sends ONE transaction with MANY outputs (like the existing
 * PerformanceRemote pattern). Avoids spendpending lock by using single
 * large transactions per client.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true" })
public class EmbeddedBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedBenchmark.class);
    private static final int CLIENTS = 10;
    private static final int PAYMENTS_PER_CLIENT = 500;
    private static final int OUTPUTS_PER_TX = 100;

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

        // Create recipient keys
        List<List<ECKey>> recipientGroups = new ArrayList<>();
        for (int c = 0; c < CLIENTS; c++) {
            List<ECKey> group = new ArrayList<>();
            for (int p = 0; p < Math.min(OUTPUTS_PER_TX, PAYMENTS_PER_CLIENT); p++) {
                group.add(new ECKey());
            }
            recipientGroups.add(group);
        }

        int txCount = (PAYMENTS_PER_CLIENT + OUTPUTS_PER_TX - 1) / OUTPUTS_PER_TX;

        // Fund genesis wallet with small UTXOs by splitting the genesis coin
        long totalNeeded = (long) CLIENTS * PAYMENTS_PER_CLIENT + CLIENTS;
        log.info("Splitting genesis coin into {} UTXOs...", totalNeeded);
        HashMap<String, BigInteger> splitTx = new HashMap<>();
        for (int i = 0; i < Math.min(1000, totalNeeded); i++) {
            ECKey dummy = new ECKey();
            splitTx.put(dummy.toAddress(networkParameters).toString(), BigInteger.valueOf(1));
        }
        wallet.payToList(null, splitTx, NetworkParameters.BIGTANGLE_TOKENID, "split");
        log.info("Split done");

        // Wait for MCMC to refresh tip after split
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        AtomicLong totalNs = new AtomicLong(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CLIENTS];
        long wallStart = System.nanoTime();

        for (int c = 0; c < CLIENTS; c++) {
            int clientId = c;
            List<ECKey> recipients = recipientGroups.get(c);
            futures[c] = CompletableFuture.runAsync(() -> {
                try {
                    long start = System.nanoTime();
                    HashMap<String, BigInteger> batchTx = new HashMap<>();
                    for (ECKey r : recipients) {
                        batchTx.put(r.toAddress(networkParameters).toString(), BigInteger.valueOf(1));
                    }
                    Block b = wallet.payToList(null, batchTx, NetworkParameters.BIGTANGLE_TOKENID,
                            "batch-" + clientId);
                    if (b != null) {
                        totalNs.addAndGet(System.nanoTime() - start);
                        ok.addAndGet(recipients.size());
                    }
                } catch (Exception e) {
                    fail.addAndGet(recipients.size());
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
        log.info("Clients:        {}", CLIENTS);
        log.info("Outputs/client: {}", OUTPUTS_PER_TX);
        log.info("Total outputs:  {}", ok.get() + fail.get());
        log.info("OK:             {}", ok.get());
        log.info("Fail:           {}", fail.get());
        log.info("Wall time:      {} ms", wallMs);
        log.info("Avg latency:    {} ms", (long) avg);
        log.info("Throughput:     {} tx/s", (long) tps);
        log.info("==============================================");
        assertTrue(ok.get() > 0, "Must have successful payments");
    }
}
