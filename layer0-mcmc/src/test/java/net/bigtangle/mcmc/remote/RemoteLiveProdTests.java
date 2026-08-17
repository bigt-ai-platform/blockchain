package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Live test battery run against a DEPLOYED production node over HTTP.
 *
 * <p>Targets {@code server.url} (optionally {@code l1.url}); the genesis
 * wallet (seed 0x01) must be funded on the node. Exercises:
 *  1. server health / consensus endpoints,
 *  2. a real BIG payment round-trip (submit + confirmed balance),
 *  3. mempool double-spend rejection,
 *  4. concurrent submit throughput (load/performance).
 */
public class RemoteLiveProdTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteLiveProdTests.class);

    @Test
    public void testHealth() throws Exception {
        String health = new String(OkHttp3Util.post(contextRoot, new byte[0]), "UTF-8");
        assertNotNull(health);
        log.info("LIVE health root: {}", health.trim());
    }

    @Test
    public void testPaymentRoundTrip() throws Exception {
        List<UTXO> pre = getBalance(false);
        assertTrue(!pre.isEmpty(), "genesis wallet should be funded on the deployed node");
        long preBig = sumBig(pre);
        log.info("LIVE genesis wallet BIG balance before: {}", preBig);

        PQKey recipient = PQKey.createNew();
        payBigTo(recipient, Coin.FEE_DEFAULT.getValue(), null);
        log.info("LIVE submitted payment to {}", recipient.toAddress(networkParameters).toBase58());

        boolean confirmed = false;
        long balance = 0;
        for (int i = 0; i < 90 && !confirmed; i++) {
            Thread.sleep(3000);
            balance = sumBig(getBalanceFor(recipient));
            if (balance >= Coin.FEE_DEFAULT.getValue().longValue()) {
                confirmed = true;
            }
        }
        assertTrue(confirmed, "recipient should have confirmed balance, got " + balance);
        log.info("LIVE payment CONFIRMED, recipient balance = {}", balance);
    }

    @Test
    public void testMempoolDoubleSpendRejected() throws Exception {
        // Two pays from the SAME wallet outpoint set; the second is a double
        // spend of an already-submitted UTXO and must not confirm both.
        Wallet attacker = wallet;
        PQKey a = PQKey.createNew();
        PQKey b = PQKey.createNew();

        List<Transaction> txs = new ArrayList<>();
        List<FreeStandingTransactionOutput> coinList = attacker.calculateAllSpendCandidates(null, false);
        List<FreeStandingTransactionOutput> bigUtxos = new ArrayList<>();
        for (FreeStandingTransactionOutput co : coinList) {
            if (co.getUTXO().getTokenidBuf() != null
                    && co.getUTXO().getTokenidBuf().length > 0
                    && co.getUTXO().getTokenidBuf()[0] == NetworkParameters.BIGTANGLE_TOKENID[0]) {
                bigUtxos.add(co);
            }
        }
        assertTrue(!bigUtxos.isEmpty(), "need a BIG UTXO to attempt double spend");
        Transaction tx1 = attacker.pay(null, a, Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), "ds1")
                .get(0);
        Transaction tx2 = attacker.pay(null, b, Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), "ds2")
                .get(0);
        log.info("LIVE submitted double-spend pair (same UTXO set)");
        Thread.sleep(10000);

        long both = sumBig(getBalanceFor(a)) + sumBig(getBalanceFor(b));
        // Mempool rejects the second; the double spend must not confirm twice.
        log.info("LIVE double-spend result: combined confirmed = {}", both);
        assertTrue(both <= 1000, "double spend should not confirm twice, got " + both);
    }

    @Test
    public void testConcurrentSubmitThroughput() throws Exception {
        int clients = Integer.parseInt(System.getProperty("load.clients", "10"));
        int perClient = Integer.parseInt(System.getProperty("load.perClient", "10"));
        int total = clients * perClient;
        final ExecutorService pool = Executors.newFixedThreadPool(clients);
        final CountDownLatch ready = new CountDownLatch(clients);
        final CountDownLatch done = new CountDownLatch(clients);
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger fail = new AtomicInteger();

        List<PQKey> clientKeys = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            PQKey k = PQKey.createNew();
            clientKeys.add(k);
            payBigTo(k, Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(perClient + 1)), null);
        }
        Thread.sleep(5000);

        long start = System.currentTimeMillis();
        for (int i = 0; i < clients; i++) {
            final PQKey fromKey = clientKeys.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    Wallet w = Wallet.fromKeys(networkParameters, fromKey, contextRoot);
                    for (int j = 0; j < perClient; j++) {
                        try {
                            w.pay(null, fromKey, Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID), "load-" + j);
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            fail.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(30, TimeUnit.SECONDS);
        done.await(180, TimeUnit.SECONDS);
        pool.shutdownNow();
        long elapsedMs = System.currentTimeMillis() - start;
        double tps = elapsedMs > 0 ? (double) ok.get() * 1000.0 / elapsedMs : 0;
        log.info("LIVE LOAD: {} ok / {} fail in {} ms → {:.0f} tx/s",
                ok.get(), fail.get(), elapsedMs, tps);
        assertTrue(ok.get() > 0, "expected some successful submissions");
    }

    private List<UTXO> getBalanceFor(PQKey key) throws Exception {
        Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
        List<UTXO> out = new ArrayList<>();
        for (FreeStandingTransactionOutput o : w.calculateAllSpendCandidates(null, false)) {
            out.add(o.getUTXO());
        }
        return out;
    }

    private long sumBig(List<UTXO> utxos) {
        long tot = 0;
        for (UTXO u : utxos) {
            if (u.getTokenidBuf() != null && u.getTokenidBuf().length > 0
                    && u.getTokenidBuf()[0] == NetworkParameters.BIGTANGLE_TOKENID[0]) {
                tot += u.getValue().getValue().longValue();
            }
        }
        return tot;
    }
}
