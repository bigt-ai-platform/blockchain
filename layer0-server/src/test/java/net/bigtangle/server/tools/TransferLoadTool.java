import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

/**
 * Real chain-TPS load: signed TRANSFER txs over HTTP per node key.
 * args: seedsFile urlPrefix durationSec amountSat
 * seedsFile: one ML-DSA seed hex per line; worker i -> node i (port 8281+i).
 */
public class TransferLoadTool {
    public static void main(String[] args) throws Exception {
        List<String> seeds = Files.readAllLines(Paths.get(args[0]));
        String urlPrefix = args[1];
        long durationSec = Long.parseLong(args[2]);
        double ratePerSec = args.length > 4 && Double.parseDouble(args[4]) > 0
                ? Double.parseDouble(args[4]) : 0;
        long amountSat = Long.parseLong(args[3]);
        NetworkParameters params = MainNetParams.get();

        int n = seeds.size();
        Wallet[] wallets = new Wallet[n];
        String[] urls = new String[n];
        int nnodes = Integer.getInteger("load.nnodes", 5);
        for (int i = 0; i < n; i++) {
            wallets[i] = Wallet.fromKeys(params,
                    PQKey.fromMLDSA(Utils.HEX.decode(seeds.get(i).trim())),
                    "/tmp/opencode/walletctx" + i);
            // Worker i signs for seed i but queries node (i % nnodes): allows
            // more parallel signers than nodes.
            urls[i] = urlPrefix + (8281 + (i % nnodes)) + "/";
            wallets[i].setServerURL(urls[i]);
        }
        String[] recipients = new String[8];
        for (int r = 0; r < recipients.length; r++) {
            byte[] rs = new byte[32];
            Arrays.fill(rs, (byte) (0x50 + r));
            recipients[r] = Address.fromHash160(params, PQKey.fromMLDSA(rs).getPubKeyHash()).toBase58();
        }
        Files.write(Paths.get("/tmp/opencode/recipients.txt"), Arrays.asList(recipients));

        AtomicLong ok = new AtomicLong(), err = new AtomicLong();
        long deadline = System.currentTimeMillis() + durationSec * 1000;
        long t0 = System.currentTimeMillis();
        Thread progress = new Thread(() -> {
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(15000); } catch (InterruptedException ie) { return; }
                long el = System.currentTimeMillis() - t0;
                System.out.printf("[tps] %ds submitted=%d err=%d rate=%.1f/s%n",
                        el / 1000, ok.get(), err.get(),
                        ok.get() / Math.max(1.0, el / 1000.0));
                System.out.flush();
            }
        });
        progress.setDaemon(true);
        progress.start();
        Thread[] ts = new Thread[n];
        for (int w = 0; w < n; w++) {
            final int idx = w;
            ts[w] = new Thread(() -> {
                Wallet wallet = wallets[idx];
                int rot = 0;
                java.util.List<Transaction> outbox = new java.util.ArrayList<>();
                // BATCHED SUBMISSION (mirrors ConfirmedPaymentBenchmark):
                // sign streaming, flush BATCH txs per HTTP call on
                // submitTransactions so per-tx HTTP round trips never cap the
                // offered load. Candidate fetches are throttled to every
                // CAND_REFRESH_MS: change outputs recycle through
                // confirmations, and polling them faster only adds DB load.
                final long CAND_REFRESH_MS = Long.getLong("load.candRefreshMs", 4000L);
                long nextCandFetch = 0;
                java.util.List<net.bigtangle.wallet.FreeStandingTransactionOutput> cands =
                        new java.util.ArrayList<>();
                // Optional total-rate pacing across all workers: worker w
                // submits at rate/n so the OFFER side never outruns intent.
                final double share = ratePerSec > 0 ? (double) ratePerSec / n : 0;
                long nextAllowed = System.nanoTime();
                while (System.currentTimeMillis() < deadline) {
                    try {
                        if (share > 0) {
                            long waitNs = nextAllowed - System.nanoTime();
                            if (waitNs > 0) Thread.sleep((long) Math.ceil(waitNs / 1e6));
                            nextAllowed += (long) (1e9 / share);
                        }
                        if (cands.isEmpty() && System.currentTimeMillis() >= nextCandFetch) {
                            cands = wallet.calculateAllSpendCandidates(null, false);
                            nextCandFetch = System.currentTimeMillis() + CAND_REFRESH_MS;
                        }
                        if (cands.isEmpty()) {
                            if (err.incrementAndGet() <= 2) {
                                System.out.println("w" + idx + ": no candidates; wallet addr="
                                    + Address.fromHash160(params,
                                        wallets[idx].walletKeys(null).get(0).getPubKeyHash()).toBase58()
                                   );
                            }
                            Thread.sleep(500);
                            continue;
                        }
                        HashMap<String, BigInteger> give = new HashMap<>();
                        give.put(recipients[(idx + rot++) % recipients.length],
                                BigInteger.valueOf(amountSat));
                        Transaction tx = wallet.payToListTransaction(null, give,
                                NetworkParameters.BIGTANGLE_TOKENID, "tps",
                                java.util.Collections.singletonList(cands.remove(0)));
                        if (tx == null) {
                            err.incrementAndGet();
                            continue;
                        }
                        outbox.add(tx);
                        ok.incrementAndGet();
                        if (outbox.size() >= BATCH_SUBMIT) {
                            submitBatch(outbox, urls[idx]);
                            outbox.clear();
                        }
                    } catch (Exception e) {
                        long en = err.incrementAndGet();
                        if (en <= 3) { System.out.println("ERR: " + e); }
                        try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
                    }
                }
                if (!outbox.isEmpty()) {
                    try { submitBatch(outbox, urls[idx]); } catch (Exception ignore) { }
                }
            });
            ts[w].start();
        }
        for (Thread th : ts) th.join();
        System.out.printf("DONE submitted=%d err=%d avg=%.1f tx/s%n",
                ok.get(), err.get(),
                ok.get() / Math.max(1.0, durationSec));
    }

    /** Sign-off-the-clock batched submit: length-prefixed stream of serialized txs. */
    private static void submitBatch(java.util.List<Transaction> txs, String url) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        for (Transaction t : txs) {
            byte[] b = t.bitcoinSerialize();
            dos.writeInt(b.length);
            dos.write(b);
        }
        dos.close();
        OkHttp3Util.post(url + "submitTransactions", baos.toByteArray());
    }

    private static final int BATCH_SUBMIT = Integer.getInteger("load.batchSubmit", 250);
}
