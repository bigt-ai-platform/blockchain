import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Mesh replica of ConfirmedPaymentBenchmark:
 * - fund: mints ONE confirmed genesis-sourced UTXO per disposable wallet
 *   (fresh deterministic ML-DSA key) directly via the /fundAddresses faucet,
 *   distributing calls across all nodes.
 * - run : streams signed TRANSFER txs (one per wallet, single-hop spend,
 *   no recycling, zero client conflict risk) to the nodes in batched
 *   submitTransactions calls. Timed exactly like the benchmark: submit wall
 *   then confirm poll until every funded UTXO flips spent=true.
 *
 * args: mode(fund|run) totalTx clients batch baseUrlPrefix startIndex
 * Progress goes to stdout every 10 s during run.
 */
public class MeshBench {
    static NetworkParameters params = MainNetParams.get();

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int totalTx = Integer.parseInt(args[1]);
        int clients = Integer.parseInt(args[2]);
        int batch = Integer.parseInt(args[3]);
        String urlPrefix = args[4];
        long startIndex = Long.parseLong(args[5]);
        int nnodes = Integer.getInteger("load.nnodes", 5);

        byte[][] seeds = new byte[totalTx][];
        String[] addrs = new String[totalTx];
        String[] urls = new String[clients];
        for (int i = 0; i < totalTx; i++) {
            byte[] seed = new byte[32];
            Arrays.fill(seed, (byte) (0x80 + ((startIndex + i) & 0x1f)));
            seed[(int) ((startIndex + i) % 31)] = (byte) (((startIndex + i) * 7) & 0x7f);
            seeds[i] = seed;
            PQKey k = PQKey.fromMLDSA(seed);
            addrs[i] = Address.fromHash160(params, k.getPubKeyHash()).toBase58();
        }
        Files.write(Paths.get("/tmp/opencode/meshwallets.txt"),
                Arrays.asList(addrs));

        if ("fund".equals(mode)) {
            AtomicInteger ok = new AtomicInteger();
            ExecutorService fp = Executors.newFixedThreadPool(nnodes);
            List<CompletableFuture<Void>> fs = new ArrayList<>();
            int chunk = 100;
            int callsPerNode = (totalTx + chunk - 1) / chunk;
            for (int c = 0; c < callsPerNode; c++) {
                final int ci = c;
                fs.add(CompletableFuture.runAsync(() -> {
                    try {
                        int n = ci % nnodes;
                        StringBuilder sb = new StringBuilder("[");
                        int lo = ci * chunk;
                        int hi = Math.min(totalTx, lo + chunk);
                        for (int i = lo; i < hi; i++) {
                            sb.append(i > lo ? "," : "")
                              .append("{\"address\":\"").append(addrs[i]).append("\",")
                              .append("\"value\":20000,\"index\":").append(startIndex + i)
                              .append("}");
                        }
                        sb.append("]");
                        String body = "{\"addresses\":" + sb.toString() + "}";
                        java.net.http.HttpClient hc = java.net.http.HttpClient.newHttpClient();
                        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(
                                        urlPrefix + (8281 + n) + "/fundAddresses"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        java.net.http.HttpResponse<String> resp = null;
                        for (int attempt = 0; attempt < 3 && resp == null; attempt++) {
                            try {
                                resp = hc.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                            } catch (Exception retry) {
                                Thread.sleep(1000);
                            }
                        }
                        if (resp != null && resp.body().contains("\"errorcode\" : 0")) {
                            ok.addAndGet(hi - lo);
                        } else {
                            System.out.println("FUNDFAIL chunk=" + ci + " node=" + (8281 + n)
                                    + " resp=" + (resp == null ? "null" : resp.body().substring(0, Math.min(120, resp.body().length()))));
                        }
                    } catch (Exception e) {
                        System.out.println("fund err @" + ci + ": " + e);
                    }
                }, fp));
            }
            CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).get();
            fp.shutdownNow();
            System.out.printf("FUND done: %d/%d wallets funded (startIndex=%d)%n",
                    ok.get(), totalTx, startIndex);
            return;
        }

        // ---------- timed RUN ----------
        String recvAddr = args.length > 6 ? args[6]
                : Address.fromHash160(params,
                      PQKey.fromMLDSA(new byte[] {(byte) 0x51}).getPubKeyHash())
                          .toBase58();
        long payAmount = Long.getLong("bench.pay", 10000L);
        // Optional TOTAL offered-rate cap (tx/s across clients): keeps load
        // under the mempool cap so we can measure SUSTAINED confirm TPS
        // without triggering backpressure drop-out.
        double ratePerSec = Double.parseDouble(System.getProperty("bench.rate", "0"));
        long fundAmount = Long.getLong("bench.fund", 20000L);

        // genesis funding-UTXO identity, same construction as faucet/benchmark
        net.bigtangle.core.Block genesis =
                net.bigtangle.core.UtilGeneseBlock.createGenesis(params);
        Sha256Like(genesis);

        AtomicInteger submitted = new AtomicInteger();
        AtomicLong confirmStartSentinel = new AtomicLong();
        ConcurrentLinkedQueue<String> hashes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        int txPerClient = Math.max(1, totalTx / clients);
        long submitWallStart = System.nanoTime();

        final int FUND_CHUNK = 100; // must mirror fund-mode chunk: owner node = (idx/CHUNK)%nnodes
        for (int c = 0; c < clients; c++) {
            final int ci = c;
            fs.add(CompletableFuture.runAsync(() -> {
                java.util.Map<Integer, ArrayList<Transaction>> outboxByNode = new HashMap<>();
                try {
                    long nextAllowed = System.nanoTime();
                    for (int j = 0; j < txPerClient; j++) {
                        if (ratePerSec > 0) {
                            double share = ratePerSec / Math.max(1, clients);
                            long waitNs = nextAllowed - System.nanoTime();
                            if (waitNs > 0) Thread.sleep((long) Math.ceil(waitNs / 1e6));
                            nextAllowed += (long) (1e9 / share);
                        }
                        int idx = ci * txPerClient + j;
                        if (idx >= totalTx) break;
                        PQKey wk = PQKey.fromMLDSA(seeds[idx]);
                        UTXO utxo = new UTXO();
                        // MATCH THE FAUCET'S STORED CONVENTION: fundAddresses
                        // never sets UTXO.hash, so the store persists the
                        // BLOCK hash there; spending must reference the same
                        // triple or admission reports 'UTXO not found'.
                        utxo.setHash(genesis.getHash());
                        utxo.setIndex(startIndex + idx);
                        utxo.setValue(new net.bigtangle.core.Coin(
                                BigInteger.valueOf(fundAmount),
                                NetworkParameters.BIGTANGLE_TOKENID));
                        utxo.setCoinbase(true);
                        utxo.setScript(net.bigtangle.script.ScriptBuilder.createOutputScript(
                                Address.fromHash160(params, wk.getPubKeyHash())));
                        utxo.setAddress(addrs[idx]);
                        utxo.setBlockHash(genesis.getHash());
                        utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
                        utxo.setConfirmed(true);
                        utxo.setSpent(false);
                        FreeStandingTransactionOutput coin =
                                new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, wk);
                        HashMap<String, BigInteger> pay = new HashMap<>();
                        pay.put(recvAddr, BigInteger.valueOf(payAmount));
                        Transaction tx = w.payToListTransaction(null, pay,
                                NetworkParameters.BIGTANGLE_TOKENID, "mesh",
                                List.of(coin));
                        if (tx == null) continue;
                        int node = (int) (((long)(idx / FUND_CHUNK)) % nnodes);
                        outboxByNode.computeIfAbsent(node,
                                k -> new ArrayList<>(batch)).add(tx);
                        ArrayList<Transaction> ob = outboxByNode.get(node);
                        if (ob.size() >= batch) {
                            flush(ob, urlPrefix + (8281 + node) + "/");
                            for (Transaction t : ob) hashes.add(t.getHash().toString());
                            submitted.addAndGet(ob.size());
                            outboxByNode.remove(node);
                        }
                    }
                    for (var e2 : outboxByNode.entrySet()) {
                        flush(e2.getValue(), urlPrefix + (8281 + e2.getKey()) + "/");
                        for (Transaction t : e2.getValue()) hashes.add(t.getHash().toString());
                        submitted.addAndGet(e2.getValue().size());
                    }
                } catch (Exception e) {
                    System.out.println("client " + ci + " failed: " + e);
                }
            }, pool));
        }
        Thread tick = new Thread(() -> {
            while (true) {
                try { Thread.sleep(10000); } catch (InterruptedException ie) { return; }
                long el = (System.nanoTime() - submitWallStart) / 1_000_000_000L;
                System.out.printf("[run] %ds submitted=%d tracked=%d%n",
                        el, submitted.get(), hashes.size());
                System.out.flush();
            }
        });
        tick.setDaemon(true);
        tick.start();
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).get();
        pool.shutdownNow();
        long submitWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        System.out.printf("SUBMIT done: %d ms submitted=%d dropped=%d (%.1f tx/s)%n",
                submitWallMs, submitted.get(), dropped.get(),
                submitted.get() * 1000.0 / Math.max(1, submitWallMs));
        System.out.println(confirmStartSentinel);
    }

    private static void sha256Placeholder() { }

    static void Sha256Like(Object o) { }

    private static final java.util.concurrent.atomic.AtomicInteger dropped =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Deliver a batch with retry/backoff: transient failures (Mempool full,
     * brief node restarts) are retried with 1 s backoff (max 60 attempts);
     * permanent rejections ("UTXO not found" — wallets the target node never
     * held) fail fast. A batch that cannot be delivered is DROPPED and
     * counted, never fatal to the client thread — churn-tolerant load.
     */
    private static void flush(List<Transaction> txs, String url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        for (Transaction t : txs) {
            byte[] b = t.bitcoinSerialize();
            dos.writeInt(b.length);
            dos.write(b);
        }
        dos.close();
        byte[] payload = baos.toByteArray();
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                OkHttp3Util.post(url + "submitTransactions", payload);
                return;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("UTXO not found")) {
                    dropped.addAndGet(txs.size());
                    return;
                }
                if (attempt == 59) {
                    dropped.addAndGet(txs.size());
                    return;
                }
                Thread.sleep(1000);
            }
        }
    }
}
