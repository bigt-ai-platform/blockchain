import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.response.GetTransactionStatusResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * MeshBm — HTTP benchmark driver for the hermetic testnodes.sh mesh.
 *
 * The /fundAddresses faucet is gone; wallets are funded AT GENESIS via the
 * distribution CSV (helper/prod/testnodes.sh appends them when BENCH_WALLETS
 * is set). Keys are derived deterministically from an index so the driver can
 * re-derive the same wallets the genesis CSV funded.
 *
 * Modes:
 *   MeshBm genesis <startIndex> <count>        print `address,,value` CSV rows
 *                                              (append into genesis.csv)
 *   MeshBm run <startIndex> <count> <clients> <batch> <nnodes> <urlPrefix>
 *     fetch each wallet's genesis UTXO (getOutputs), build+sign one payment
 *     per wallet, stream them in parallel submitTransactions batches across
 *     the mesh, then (bench.confirm=1) poll until confirmed and report
 *     submit/confirm TPS + latency percentiles.
 *
 * System properties:
 *   bench.fund   satoshis funded per wallet (must match genesis value) 50000
 *   bench.pay    satoshis paid per tx                                   40000
 *   bench.recvIdx  suffix selecting the deterministic recipient key         0
 *   bench.recv   optional fixed base58 recipient (overrides recvIdx)
 *   bench.confirm 1 to poll confirmations                                  1
 *   bench.confirmTimeoutSec confirm poll deadline                          900
 *   bench.rate   optional total submit cap in tx/s (0 = unlimited)           0
 *   bench.progressSec progress line every N seconds                       10
 */
public class MeshBm {
    static NetworkParameters params = MainNetParams.get();
    static final byte[] M_BIG = NetworkParameters.BIGTANGLE_TOKENID;

    /** Deterministic per-wallet key seed: SHA-256 of the 8-byte index, so every
     *  index maps to a UNIQUE wallet (the old MeshBench formula had a period of
     *  3968 — indices 3968 apart collided onto one address and minted ~25
     *  duplicate genesis outputs each, bloating getOutputs responses). */
    static byte[] seedFor(long idx) {
        byte[] in = new byte[8];
        for (int i = 0; i < 8; i++) {
            in[i] = (byte) (idx >>> (56 - 8 * i));
        }
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static Address addrFor(byte[] seed) {
        return Address.fromHash160(params, PQKey.fromMLDSA(seed).getPubKeyHash());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        String mode = args[0];
        if ("genesis".equals(mode)) {
            long start = Long.parseLong(args[1]);
            long count = Long.parseLong(args[2]);
            long value = Long.getLong("bench.fund", 50000L);
            StringBuilder sb = new StringBuilder();
            for (long i = 0; i < count; i++) {
                sb.append(addrFor(seedFor(start + i)).toBase58()).append(",,").append(value).append('\n');
            }
            System.out.print(sb);
            return;
        }
        if ("run".equals(mode)) {
            run(args);
            return;
        }
        usage();
    }

    static void usage() {
        System.err.println("usage: MeshBm genesis <startIndex> <count>\n"
                + "       MeshBm run <startIndex> <count> <clients> <batch> <nnodes> <urlPrefix>");
        System.exit(1);
    }

    static void run(String[] args) throws Exception {
        long startIndex = Long.parseLong(args[1]);
        int totalTx = Integer.parseInt(args[2]);
        int clients = Integer.parseInt(args[3]);
        int batch = Integer.parseInt(args[4]);
        int nnodes = Integer.parseInt(args[5]);
        String urlPrefix = args[6];
        long fundAmount = Long.getLong("bench.fund", 50000L);
        long payAmount = Long.getLong("bench.pay", 40000L);
        int recvIdx = Integer.getInteger("bench.recvIdx", 0);
        int confirmNode = Integer.getInteger("bench.confirmNode", 0);
        boolean confirm = Integer.getInteger("bench.confirm", 1) == 1;
        int confirmTimeoutSec = Integer.getInteger("bench.confirmTimeoutSec", 900);
        double ratePerSec = Double.parseDouble(System.getProperty("bench.rate", "0"));
        int progressSec = Integer.getInteger("bench.progressSec", 10);
        String recvOverride = System.getProperty("bench.recv", "");
        String recvAddr = recvOverride.isEmpty()
                ? addrFor(seedFor(1_000_000_000L + recvIdx)).toBase58()
                : recvOverride;

        System.out.println("MESHBM run: start=" + startIndex + " count=" + totalTx + " clients=" + clients
                + " batch=" + batch + " nnodes=" + nnodes + " fund=" + fundAmount + " pay=" + payAmount
                + " recv=" + recvAddr + " rate=" + ratePerSec);

        String base = urlPrefix.endsWith("/")
                ? urlPrefix.substring(0, urlPrefix.length() - 1) : urlPrefix;
        String[] nodeUrls = new String[nnodes];
        for (int i = 0; i < nnodes; i++) {
            nodeUrls[i] = base + (8281 + i) + "/";
        }

        // ---- 1. Fetch the genesis-funded UTXO per wallet (getOutputs, chunked).
        byte[][] seeds = new byte[totalTx][];
        String[] addrs = new String[totalTx];
        List<String> pubKeyHashes = new ArrayList<>(totalTx);
        for (int i = 0; i < totalTx; i++) {
            byte[] seed = seedFor(startIndex + i);
            seeds[i] = seed;
            String addr = addrFor(seed).toBase58();
            addrs[i] = addr;
            pubKeyHashes.add(Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()));
        }
        Map<String, List<UTXO>> addrToUtxos = new HashMap<>();
        int CHUNK = 2000;
        int fetchNode = Integer.getInteger("bench.fetchNode", confirmNode);
        for (int c0 = 0; c0 < totalTx; c0 += CHUNK) {
            List<String> sub = pubKeyHashes.subList(c0, Math.min(totalTx, c0 + CHUNK));
            // Fetch from ONE node (fetchNode) so a flaky/restarting peer never
            // blocks the benchmark behind HTTP timeouts; every node holds the
            // same genesis-funded UTXOs.
            byte[] resp;
            try {
                resp = OkHttp3Util.postString(nodeUrls[fetchNode] + "getOutputs",
                        Json.jsonmapper().writeValueAsString(sub));
            } catch (Exception e) {
                System.err.println("MESHBM getOutputs failed on node-" + fetchNode + ": "
                        + String.valueOf(e.getMessage()).replace('\n', ' '));
                continue;
            }
            GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
            if (gor.getOutputs() != null) {
                for (UTXO u : gor.getOutputs()) {
                    if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount
                            && u.getAddress() != null && !u.isSpent()) {
                        addrToUtxos.computeIfAbsent(u.getAddress(), k -> new ArrayList<>()).add(u);
                    }
                }
            }
        }
        int fundedUtxos = 0;
        for (List<UTXO> l : addrToUtxos.values()) {
            fundedUtxos += l.size();
        }
        System.out.println("MESHBM funded UTXOs fetched: " + fundedUtxos + "/" + totalTx
                + " (distinct wallets " + addrToUtxos.size() + ")");
        if (addrToUtxos.isEmpty()) {
            System.err.println("NO_FUNDED_UTXOS: genesis CSV funding not visible — check BENCH_WALLETS/genesis.csv");
            System.exit(2);
        }

        // ---- 2. Streaming build + timed parallel submit across the mesh.
        AtomicInteger submitted = new AtomicInteger(0);
        AtomicInteger dropped = new AtomicInteger(0);
        ConcurrentLinkedQueue<String> txHashes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[clients];
        int txPerClient = Math.max(1, totalTx / clients);

        long submitWallStart = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int ci = c;
            futures[c] = CompletableFuture.runAsync(() -> {
                Map<Integer, List<Transaction>> outbox = new HashMap<>();
                long nextAllowed = System.nanoTime();
                try {
                    for (int j = 0; j < txPerClient; j++) {
                        int idx = ci * txPerClient + j;
                        if (idx >= totalTx) {
                            break;
                        }
                        if (ratePerSec > 0) {
                            double share = ratePerSec / Math.max(1, clients);
                            long waitNs = nextAllowed - System.nanoTime();
                            if (waitNs > 0) {
                                Thread.sleep((long) Math.ceil(waitNs / 1e6));
                            }
                            nextAllowed += (long) (1e9 / share);
                        }
                        List<UTXO> utxos = addrToUtxos.get(addrs[idx]);
                        if (utxos == null || utxos.isEmpty()) {
                            dropped.incrementAndGet();
                            continue;
                        }
                        UTXO utxo = utxos.remove(utxos.size() - 1);
                        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
                        Wallet w = Wallet.fromKeys(params, PQKey.fromMLDSA(seeds[idx]));
                        HashMap<String, BigInteger> pay = new HashMap<>();
                        pay.put(recvAddr, BigInteger.valueOf(payAmount));
                        Transaction tx = w.payToListTransaction(null, pay, M_BIG, "meshbm", List.of(coin));
                        if (tx == null) {
                            dropped.incrementAndGet();
                            continue;
                        }
                        int node = idx % nnodes;
                        outbox.computeIfAbsent(node, k -> new ArrayList<>(batch)).add(tx);
                        List<Transaction> ob = outbox.get(node);
                        if (ob.size() >= batch) {
                            flush(nodeUrls, node, ob, submitted, dropped, txHashes);
                            outbox.remove(node);
                        }
                    }
                    for (Map.Entry<Integer, List<Transaction>> e2 : outbox.entrySet()) {
                        flush(nodeUrls, e2.getKey(), e2.getValue(), submitted, dropped, txHashes);
                    }
                } catch (Exception e) {
                    System.err.println("client " + ci + " failed: " + e);
                    dropped.addAndGet(txPerClient);
                }
            }, pool);
        }
        CompletableFuture.allOf(futures).get();
        pool.shutdownNow();
        long submitWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        int ok = submitted.get();
        System.out.println("MESHBM submit done: " + submitWallMs + " ms submitted=" + ok
                + " dropped=" + dropped.get() + " (" + String.format("%.1f", ok * 1000.0 / Math.max(1, submitWallMs))
                + " tx/s)");
        if (ok == 0) {
            System.err.println("NOTHING_SUBMITTED");
            System.exit(3);
        }

        // ---- 3. Confirmation poll (per-wave recipient keeps counting clean).
        int confirmed = 0;
        long peakConfirmed = 0;
        long peakReachedMs = 0;
        long lastReport = System.currentTimeMillis();
        long deadline = confirm ? System.currentTimeMillis() + confirmTimeoutSec * 1000L : 0;
        List<Long> latenciesMs = new ArrayList<>();
        while (confirmed < ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(5000);
            Map<String, GetTransactionStatusResponse> statuses = null;
            for (int n = 0; n < nnodes && statuses == null; n++) {
                try {
                    statuses = fetchStatuses(nodeUrls[(confirmNode + n) % nnodes], recvAddr);
                } catch (Exception e) {
                    // node flapped mid-soak; try the next one
                }
            }
            if (statuses == null) {
                System.out.println("MESHBM confirm poll failed on all nodes");
                continue;
            }
            int c2 = 0;
            latenciesMs.clear();
            for (String h : txHashes) {
                GetTransactionStatusResponse item = statuses.get(h);
                if (item != null && "CONFIRMED".equals(item.getStatus())) {
                    c2++;
                    latenciesMs.add(Math.max(0, item.getUpdatedTime() - item.getCreatedTime()));
                }
            }
            confirmed = c2;
            if (confirmed > peakConfirmed) {
                peakConfirmed = confirmed;
                peakReachedMs = (System.nanoTime() - submitWallStart) / 1_000_000;
            }
            long now = System.currentTimeMillis();
            if (now - lastReport >= progressSec * 1000L) {
                lastReport = now;
                System.out.println("MESHBM progress confirmed=" + confirmed + "/" + ok + " ("
                        + String.format("%.1f", confirmed * 1000.0 / Math.max(1, peakReachedMs)) + " peak tx/s)");
            }
        }
        long confirmWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
        double submitTps = submitWallMs > 0 ? ok * 1000.0 / submitWallMs : 0;
        double confirmTps = confirmWallMs > 0 ? confirmed * 1000.0 / confirmWallMs : 0;
        double peakTps = peakReachedMs > 0 ? peakConfirmed * 1000.0 / peakReachedMs : 0;

        System.out.println("=====MESHBM=====");
        System.out.println("WAVE_START_INDEX=" + startIndex);
        System.out.println("WAVE_COUNT=" + totalTx);
        System.out.println("SUBMITTED=" + ok);
        System.out.println("DROPPED=" + dropped.get());
        System.out.println("CONFIRMED=" + confirmed);
        System.out.println("SUBMIT_WALL_MS=" + submitWallMs);
        System.out.println("CONFIRM_WALL_MS=" + confirmWallMs);
        System.out.println("SUBMIT_TPS=" + String.format("%.1f", submitTps));
        System.out.println("CONFIRM_TPS=" + String.format("%.1f", confirmTps));
        System.out.println("PEAK_TPS=" + String.format("%.1f", peakTps));
        System.out.println("P50_MS=" + percentile(latenciesMs, 50));
        System.out.println("P95_MS=" + percentile(latenciesMs, 95));
        System.out.println("P99_MS=" + percentile(latenciesMs, 99));
        System.out.println("=====MESHBM_END=====");
        System.exit(confirmed > 0 ? 0 : 4);
    }

    static void flush(String[] nodeUrls, int firstNode, List<Transaction> txs, AtomicInteger submitted,
            AtomicInteger dropped, ConcurrentLinkedQueue<String> hashes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            for (Transaction t : txs) {
                byte[] b = t.bitcoinSerialize();
                dos.writeInt(b.length);
                dos.write(b);
            }
            dos.close();
        } catch (Exception ignore) {
        }
        byte[] payload = baos.toByteArray();
        String[] batchHashes = new String[txs.size()];
        for (int i = 0; i < txs.size(); i++) {
            batchHashes[i] = txs.get(i).getHash().toString();
        }
        int tries = 0;
        int node = firstNode;
        int maxTries = 30 * nodeUrls.length;
        while (tries < maxTries) {
            try {
                OkHttp3Util.post(nodeUrls[node] + "submitTransactions", payload);
                submitted.addAndGet(txs.size());
                for (String h : batchHashes) {
                    hashes.add(h);
                }
                return;
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("UTXO not found")) {
                    dropped.addAndGet(txs.size());
                    return;
                }
                // The node counts duplicate resubmissions of an already-pending
                // tx as NEW pending entries, so re-posting an already-pending
                // batch would inflate the mempool. Verify first: if the batch
                // is already pending anywhere, count it submitted and move on.
                if (allPending(nodeUrls, batchHashes)) {
                    submitted.addAndGet(txs.size());
                    for (String h : batchHashes) {
                        hashes.add(h);
                    }
                    return;
                }
                // Fail over to the next node on connection/service errors.
                if (msg.contains("service is not ready") || msg.contains("ConnectException")
                        || msg.contains("Connection refused") || msg.contains("UTXO lookup failed")) {
                    node = (node + 1) % nodeUrls.length;
                }
                tries++;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        dropped.addAndGet(txs.size());
        System.err.println("MESHBM batch dropped after " + tries + " tries: "
                + (txs.isEmpty() ? "" : nodeUrls[node] + " last err"));
    }

    /** True when every one of the given tx hashes is present in any node's mempool. */
    static boolean allPending(String[] nodeUrls, String[] hashes) {
        for (String u : nodeUrls) {
            if (allPendingNode(u, hashes)) {
                return true;
            }
        }
        return false;
    }

    static boolean allPendingNode(String nodeUrl, String[] hashes) {
        try {
            byte[] r = OkHttp3Util.postString(nodeUrl + "getPendingTransactions", "{}");
            Map<String, Object> map = Json.jsonmapper().readValue(r, Map.class);
            Object listObj = map.get("transactionlist");
            if (!(listObj instanceof List)) {
                return false;
            }
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Object o : (List<?>) listObj) {
                try {
                    byte[] txBytes = java.util.Base64.getDecoder().decode(String.valueOf(o));
                    present.add(net.bigtangle.core.Sha256Hash.create(txBytes).toString());
                } catch (Exception ignore) {
                }
            }
            for (String h : hashes) {
                if (!present.contains(h)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Map<String, GetTransactionStatusResponse> fetchStatuses(String nodeUrl, String address) throws Exception {
        byte[] r = OkHttp3Util.postString(nodeUrl + "getTransactionsStatusByAddress",
                Json.jsonmapper().writeValueAsString(Map.of("address", address)));
        GetTransactionStatusResponse.GetTransactionsStatusResponse resp = Json.jsonmapper().readValue(r,
                GetTransactionStatusResponse.GetTransactionsStatusResponse.class);
        Map<String, GetTransactionStatusResponse> out = new HashMap<>();
        if (resp.getTransactions() != null) {
            for (GetTransactionStatusResponse item : resp.getTransactions()) {
                if (item.getTxHash() != null) {
                    out.put(item.getTxHash(), item);
                }
            }
        }
        return out;
    }

    static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
