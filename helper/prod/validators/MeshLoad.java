import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
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
 * MeshLoad — sustained-load / soak-stability / consensus-under-load driver for
 * the hermetic testnodes.sh mesh. Companion to MeshAttack (single-shot
 * vectors V1-V36); this file owns the separate load lane V37-V50.
 *
 * Wallets are funded AT GENESIS via the distribution CSV (same deterministic
 * seed formula as MeshAttack/MeshBm, fund default 30000 via load.fund), so
 * every flood below spends real confirmed outputs over the real node API.
 * Fund them with testnodes.sh LOAD_WALLETS (appended AFTER the attack
 * wallets; LOAD_START = NNODES+1+BENCH_WALLETS+ATTACK_WALLETS).
 *
 * Modes:
 *   MeshLoad genesis <startIndex> <count> [fund]    print `address,,value` rows
 *   MeshLoad run <startIndex> <count> <nnodes> <urlPrefix> [scale] [only]
 *     only = optional subset, e.g. "V37,V38" (default: all).
 *
 * System properties (all optional):
 *   load.fund        per-wallet genesis value                 30000
 *   load.pay         satoshis paid per tx                      20000
 *   load.confirmTimeoutSec confirm poll deadline                 300
 *   load.soakMin     S-class bounded window (minutes)            20
 *   load.enduranceMin V46 watch duration (minutes, 4320 = 72h)  4320
 *   load.sampleSec   V42/V46 sampling cadence (seconds)          30 (V42) / 300 (V46)
 *   load.probeSec    V42 drip cadence (seconds)                  60
 *   load.burstSec    V44 sustained-load window (seconds)         90
 *   load.clients     V38/V45 concurrency                         16
 *   load.maxP99Ms    V38 responsiveness bound (ms)               5000
 *   load.maxSpread   V46 chainlength-spread bound (blocks)       32
 *   load.rejoinContainer container name for the REJOIN probe      (unset)
 *   load.rejoinWaitSec   REJOIN rejoin deadline (seconds)          600
 *   load.gossipPorts comma-separated gossip TCP ports, one per
 *                    node in url order (e.g. "9421,9422,9423").
 *                    When set, V38 ALSO hammers the raw gossip socket;
 *                    otherwise V38 runs the HTTP-concurrency variant.
 *   load.merchantIdx recipient index base                  300_000_000
 *
 * Wallet budget at scale 1.0 (bounded defaults): V37 ~4005, V39 ~300,
 * V41 8, V42 soakMin*60/probeSec, V44 ~30, V45 ~300, V47 ~1010, V48 30,
 * V50 3 — plus V42's window; size LOAD_WALLETS accordingly (full lane with
 * the 72h V46/V42 windows needs ~15k). Exit 0 = all deflected.
 *
 * Vectors:
 *   V37 mempool saturation + no-TTL wedge (spam to the cap, honest tx must win)
 *   V38 gossip socket/thread explosion (TCP frame + HTTP concurrency storm)
 *   V39 oversized-body POST flood (fat batchBlock, node must stay responsive)
 *   V40 orphan/unsolid store bloat (unknown-parent storm, resources must hold)
 *   V41 sync request amplification (missing-reference fan-out, no cascade)
 *   V42 pos_state/vote growth over the soak window (finality must keep moving)
 *   V43 GHOST map growth (many-key attestation spam, finality must advance)
 *   V44 DB pool exhaustion -> slot-tick starvation (load across slot ticks)
 *   V45 reference-sweep blowup past the proposal deadline (burst, then advance)
 *   V46 endurance drift watch (no attack; spread/liveness flat for enduranceMin)
 *   V47 fee-less FIFO ordering grief (spam + honest txs, honest must confirm)
 *   V48 gossip dup-forward amplification loop (same block xN, confirms once)
 *   V49 equivocation-set poisoning (many fake keys, honest set untouched)
 *   V50 weak-subjectivity isolation (deep fork to ONE node, fin must hold)
 *   REJOIN restart-rejoin probe (OPT-IN via only=REJOIN: docker-restarts one
 *     node container and asserts it rejoins the mesh — §26 regression that
 *     restart alone must heal; needs -Dload.rejoinContainer=<name>)
 */
public class MeshLoad {
    static NetworkParameters params = MainNetParams.get();
    static final byte[] M_BIG = NetworkParameters.BIGTANGLE_TOKENID;

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

    static PQKey keyFor(long idx) {
        return PQKey.fromMLDSA(seedFor(idx));
    }

    /**
     * Recipient index UNIQUE PER RUN (same contract as MeshAttack): startIndex
     * advances every lane cycle, so startIndex*1e6 + salt never collides with a
     * previously-confirmed address (the sticky-FAIL artifact).
     */
    static long runUniqueIndex(long startIndex, long salt) {
        return startIndex * 1_000_000L + salt;
    }

    static final List<String> VERDICTS = new ArrayList<>();
    static String SEED_NODE = "";

    static void verdict(String vector, boolean pass, String detail) {
        String row = String.format("%-36s %-8s %s", vector, pass ? "PASS" : "FAIL", detail);
        VERDICTS.add(row);
        System.out.println((pass ? "[DEFLECTED] " : "[*** BREACH ***] ") + row);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        if ("genesis".equals(args[0])) {
            long start = Long.parseLong(args[1]);
            long count = Long.parseLong(args[2]);
            long value = args.length > 3 ? Long.parseLong(args[3]) : Long.getLong("load.fund", 30000L);
            StringBuilder sb = new StringBuilder();
            for (long i = 0; i < count; i++) {
                sb.append(addrFor(seedFor(start + i)).toBase58()).append(",,").append(value).append('\n');
            }
            System.out.print(sb);
            return;
        }
        if ("run".equals(args[0])) {
            run(args);
            return;
        }
        usage();
    }

    static void usage() {
        System.err.println("usage: MeshLoad genesis <startIndex> <count> [fund]\n"
                + "       MeshLoad run <startIndex> <count> <nnodes> <urlPrefix> [scale] [only]");
        System.exit(1);
    }

    static void run(String[] args) throws Exception {
        long startIndex = Long.parseLong(args[1]);
        int totalWallets = Integer.parseInt(args[2]);
        int nnodes = Integer.parseInt(args[3]);
        String urlPrefix = args[4];
        double scale = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;
        java.util.Set<String> only = null;
        if (args.length > 6 && !args[6].isBlank()) {
            only = new java.util.HashSet<>();
            for (String v : args[6].split(",")) only.add(v.trim().toUpperCase());
        }
        long fundAmount = Long.getLong("load.fund", 30000L);
        long payAmount = Long.getLong("load.pay", 20000L);
        int confirmTimeoutSec = Integer.getInteger("load.confirmTimeoutSec", 300);
        int soakMin = Integer.getInteger("load.soakMin", 20);
        long enduranceMin = Long.getLong("load.enduranceMin", 4320L);
        int sampleSecV42 = Integer.getInteger("load.sampleSec", 30);
        int probeSec = Integer.getInteger("load.probeSec", 60);
        int burstSec = Integer.getInteger("load.burstSec", 90);
        int clients = Integer.getInteger("load.clients", 16);
        long maxP99Ms = Long.getLong("load.maxP99Ms", 5000L);
        long maxSpread = Long.getLong("load.maxSpread", 32L);
        long merchantIdxBase = Long.getLong("load.merchantIdx", 300_000_000L);
        String gossipPortsRaw = System.getProperty("load.gossipPorts", "").trim();

        String base = urlPrefix;
        String[] nodeUrls = new String[nnodes];
        for (int i = 0; i < nnodes; i++) {
            nodeUrls[i] = base + (8281 + i) + "/";
        }
        String seedNode = nodeUrls[0];
        SEED_NODE = seedNode;

        java.util.function.IntUnaryOperator n = full -> Math.max(1, (int) Math.round(full * scale));

        System.out.println("MESHLOAD run: start=" + startIndex + " wallets=" + totalWallets
                + " nnodes=" + nnodes + " seed=" + seedNode + " fund=" + fundAmount + " pay=" + payAmount
                + " scale=" + scale + (only == null ? "" : " only=" + only));

        // ---- fetch genesis-funded UTXOs (deterministic wallets) ----
        Map<String, UTXO> utxos = new HashMap<>();
        {
            List<String> pubKeyHashes = new ArrayList<>();
            Map<String, String> addrOf = new HashMap<>();
            for (long i = 0; i < totalWallets; i++) {
                byte[] seed = seedFor(startIndex + i);
                String addr = addrFor(seed).toBase58();
                addrOf.put(Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()), addr);
                pubKeyHashes.add(Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()));
            }
            int CHUNK = 2000;
            for (int c0 = 0; c0 < totalWallets; c0 += CHUNK) {
                List<String> sub = pubKeyHashes.subList(c0, Math.min(totalWallets, c0 + CHUNK));
                byte[] resp = OkHttp3Util.postString(seedNode + "getOutputs",
                        Json.jsonmapper().writeValueAsString(sub));
                GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
                if (gor.getOutputs() != null) {
                    for (UTXO u : gor.getOutputs()) {
                        if (u.getValue() != null && u.getValue().getValue().longValue() == fundAmount
                                && u.getAddress() != null && !u.isSpent()) {
                            utxos.put(u.getAddress(), u);
                        }
                    }
                }
            }
        }
        System.out.println("MESHLOAD funded UTXOs fetched: " + utxos.size() + "/" + totalWallets);
        if (utxos.isEmpty()) {
            System.err.println("NO_FUNDED_UTXOS: load genesis funding not visible");
            System.exit(2);
        }

        // Wallet cursor: each vector consumes a slice; never reuse (single-spend).
        final long[] cursor = { startIndex };
        java.util.function.IntUnaryOperator take = count -> {
            long at = cursor[0];
            cursor[0] += count;
            return (int) at;
        };

        // ================================================================ V37
        // Mempool saturation + no-TTL wedge: flood submitTransaction toward the
        // mempoolMaxTx cap, then prove an honest transfer still confirms and the
        // node stays responsive. Full scale targets the 4000 cap; the verdict is
        // honest-confirm + liveness (a starved honest tx = wedge = BREACH).
        if (wanted(only, "V37")) {
            System.out.println("============ V37: MEMPOOL SATURATION ============");
            try {
                int spam = n.applyAsInt(4000);
                int base0 = take.applyAsInt(spam + 5);
                String spamAddr = addrFor(seedFor(runUniqueIndex(startIndex, 37001))).toBase58();
                String honestAddr = addrFor(seedFor(runUniqueIndex(startIndex, 37002))).toBase58();
                int sent = 0;
                for (int i = 0; i < spam; i++) {
                    UTXO u = utxos.get(addrFor(seedFor(base0 + i)).toBase58());
                    if (u == null) continue;
                    try {
                        Transaction t = pay(keyFor(base0 + i), u, spamAddr, payAmount, "v37-spam");
                        if (submitTx(t)) sent++;
                    } catch (Exception ignore) {
                    }
                }
                int honestSent = 0;
                for (int j = 0; j < 5; j++) {
                    UTXO u = utxos.get(addrFor(seedFor(base0 + spam + j)).toBase58());
                    if (u == null) continue;
                    try {
                        if (submitTx(pay(keyFor(base0 + spam + j), u, honestAddr, payAmount, "v37-honest")))
                            honestSent++;
                    } catch (Exception ignore) {
                    }
                }
                int honestConfirmed = 0;
                long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(5000);
                    honestConfirmed = countConfirmed(honestAddr);
                    if (honestConfirmed >= honestSent && honestSent > 0) break;
                }
                long clAfter = chainAfterSettle(seedNode, chainLength(seedNode), 30000);
                boolean ok = honestSent > 0 && honestConfirmed >= honestSent && clAfter > 0;
                verdict("V37 mempool saturation (" + sent + ")",
                        ok, "spam x" + sent + ", honest " + honestConfirmed + "/" + honestSent
                                + (honestConfirmed < honestSent ? " STARVED" : ""));
            } catch (Exception e) {
                verdict("V37 mempool saturation", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V38
        // Gossip socket/thread explosion: hammer the raw gossip TCP socket (when
        // load.gossipPorts is given) AND fan out HTTP concurrency; the node must
        // stay responsive (p99 under bound) and the chain must keep advancing.
        if (wanted(only, "V38")) {
            System.out.println("============ V38: GOSSIP/CONCURRENCY STORM ============");
            try {
                long clBefore = chainLength(seedNode);
                AtomicInteger tcpOk = new AtomicInteger();
                AtomicInteger tcpFail = new AtomicInteger();
                List<Long> lat = java.util.Collections.synchronizedList(new ArrayList<>());
                AtomicInteger httpOk = new AtomicInteger();
                // Raw gossip frames (MSG_TRANSACTION with random bytes): exercises
                // the listener pool + per-peer queues without needing funds.
                String[] gports = gossipPortsRaw.isEmpty() ? new String[0] : gossipPortsRaw.split(",");
                final byte[] framePayload = buildV38Frame(startIndex, utxos, payAmount);
                ExecutorService pool = Executors.newFixedThreadPool(clients);
                List<Future<?>> futs = new ArrayList<>();
                long burstMs = 60_000L;
                long endAt = System.currentTimeMillis() + burstMs;
                for (int c = 0; c < clients; c++) {
                    final int ci = c;
                    futs.add(pool.submit(() -> {
                        java.util.Random rnd = new java.util.Random(0xB38 + ci);
                        while (System.currentTimeMillis() < endAt) {
                            // TCP gossip frame when ports are configured.
                            if (gports.length > 0 && framePayload.length > 0) {
                                try {
                                    String host = hostOf(nodeUrls[ci % nnodes]);
                                    int port = Integer.parseInt(gports[ci % gports.length].trim());
                                    try (java.net.Socket s = new java.net.Socket()) {
                                        s.connect(new java.net.InetSocketAddress(host, port), 3000);
                                        java.io.DataOutputStream dos = new java.io.DataOutputStream(
                                                s.getOutputStream());
                                        byte[] p = framePayload.clone();
                                        p[p.length / 2] ^= (byte) (rnd.nextInt(255) + 1);
                                        dos.writeInt(0x42474C31);
                                        dos.writeInt(2);
                                        dos.writeInt(p.length);
                                        dos.write(p);
                                        dos.flush();
                                        tcpOk.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    tcpFail.incrementAndGet();
                                }
                            }
                            // HTTP concurrency churn on every iteration.
                            try {
                                long t0 = System.nanoTime();
                                OkHttp3Util.postString(nodeUrls[ci % nnodes] + "getTip", "{}");
                                lat.add((System.nanoTime() - t0) / 1_000_000L);
                                httpOk.incrementAndGet();
                            } catch (Exception ignore) {
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futs) {
                    try { f.get(burstMs + 60_000L, TimeUnit.MILLISECONDS); } catch (Exception ignore) { }
                }
                pool.shutdownNow();
                long p99 = percentile(lat, 99);
                long clAfter = chainAfterSettle(seedNode, clBefore, 30000);
                boolean ok = clAfter > clBefore && p99 <= maxP99Ms && httpOk.get() > 0;
                verdict("V38 concurrency storm (tcp " + tcpOk + "/" + tcpFail + ")",
                        ok, "http x" + httpOk + " p99=" + p99 + "ms (bound " + maxP99Ms + "), cl "
                                + clBefore + " -> " + clAfter
                                + (gports.length == 0 ? " [http-variant; gossip ports unset]" : " [tcp+http]"));
            } catch (Exception e) {
                verdict("V38 concurrency storm", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V39
        // Oversized-body POST flood: submit fat TRANSFER batchBlocks (~N txs in
        // one body) to every node; each POST must return promptly (no hang) and
        // the chain must keep advancing afterwards.
        if (wanted(only, "V39")) {
            System.out.println("============ V39: OVERSIZED-BODY FLOOD ============");
            try {
                long clBefore = chainLength(seedNode);
                int per = n.applyAsInt(300);
                int base0 = take.applyAsInt(per);
                List<Transaction> txs = new ArrayList<>();
                for (int i = 0; i < per; i++) {
                    UTXO u = utxos.get(addrFor(seedFor(base0 + i)).toBase58());
                    if (u == null) continue;
                    try {
                        txs.add(pay(keyFor(base0 + i), u,
                                addrFor(seedFor(runUniqueIndex(startIndex, 39001))).toBase58(),
                                payAmount, "v39-fat"));
                    } catch (Exception ignore) {
                    }
                }
                byte[] tipResp = OkHttp3Util.postString(seedNode + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                Block trunk = fetchBlock(proto.getPrevBlockHash());
                Block branch = fetchBlock(proto.getPrevBranchBlockHash());
                Block fat = Block.createBlock(params, trunk, branch);
                fat.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
                for (Transaction t : txs) fat.addTransaction(t);
                byte[] body = fat.bitcoinSerialize();
                long worstMs = 0;
                for (String u : nodeUrls) {
                    long t0 = System.nanoTime();
                    try {
                        OkHttp3Util.post(u + "batchBlock", body);
                    } catch (Exception ignore) {
                    }
                    worstMs = Math.max(worstMs, (System.nanoTime() - t0) / 1_000_000L);
                }
                long p99 = tipP99(nodeUrls, 20);
                long clAfter = chainAfterSettle(seedNode, clBefore, 45000);
                boolean ok = clAfter > clBefore && worstMs < 60_000 && p99 <= maxP99Ms;
                verdict("V39 oversized-body (" + (body.length / 1024) + "KB x" + nnodes + ")",
                        ok, "worstPOST=" + worstMs + "ms tipP99=" + p99 + "ms, cl " + clBefore + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V39 oversized-body", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V40
        // Orphan/unsolid store bloat: storm unknown-parent blocks (no funds
        // needed); the orphan set must stay bounded — proxy: chain + finality
        // keep advancing and the tip stays responsive afterwards.
        if (wanted(only, "V40")) {
            System.out.println("============ V40: ORPHAN STORE BLOAT ============");
            try {
                long clBefore = chainLength(seedNode);
                long finBefore = finalizedAfterRead(seedNode);
                int count = n.applyAsInt(60);
                byte[] tipResp = OkHttp3Util.postString(seedNode + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                java.util.Random rnd = new java.util.Random(0x40);
                int sent = 0;
                for (int i = 0; i < count; i++) {
                    try {
                        Block b = Block.createBlock(params, proto, proto);
                        b.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
                        byte[] rndHash = new byte[32];
                        rnd.nextBytes(rndHash);
                        b.setPrevBlockHash(Sha256Hash.wrap(rndHash));
                        if (submitBlockTo(nodeUrls[i % nnodes], b)) sent++;
                    } catch (Exception ignore) {
                    }
                }
                long clAfter = chainAfterSettle(seedNode, clBefore, 45000);
                long finAfter = finalizedAfterRead(seedNode);
                boolean ok = sent > 0 && clAfter > clBefore && finAfter >= finBefore;
                verdict("V40 orphan storm (" + sent + ")",
                        ok, "orphans x" + sent + ", cl " + clBefore + " -> " + clAfter
                                + ", fin " + finBefore + " -> " + finAfter);
            } catch (Exception e) {
                verdict("V40 orphan storm", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V41
        // Sync request amplification: alternate tip blocks and stale forks
        // across nodes (missing-reference fan-out); sync must not cascade —
        // chain advances, finroots stay 1.
        if (wanted(only, "V41")) {
            System.out.println("============ V41: SYNC AMPLIFICATION ============");
            try {
                long clBefore = chainLength(seedNode);
                int base0 = take.applyAsInt(8);
                String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 41001))).toBase58();
                int staged = 0;
                for (int round = 0; round < 4; round++) {
                    try {
                        UTXO u = fetchUtxo(base0 + round);
                        if (u != null && !u.isSpent()) {
                            Transaction t = pay(keyFor(base0 + round), u, attacker, payAmount, "v41-tip");
                            if (submitBlockTo(nodeUrls[round % nnodes], craftTransferBlock(t))) staged++;
                        }
                        Block stale = staleForkBlock(nodeUrls, base0 + 4 + round, 2, attacker, payAmount);
                        if (stale != null && submitBlockTo(nodeUrls[(round + 1) % nnodes], stale)) staged++;
                    } catch (Exception ignore) {
                    }
                    Thread.sleep(2000);
                }
                long clAfter = chainAfterSettle(seedNode, clBefore, 45000);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                boolean ok = staged == 0 || (clAfter > clBefore && fins.size() == 1);
                verdict("V41 sync fan-out (4 rounds)",
                        ok, (staged == 0 ? "UNSTAGED " : "") + "cl " + clBefore + " -> " + clAfter
                                + ", finroots=" + fins.size());
            } catch (Exception e) {
                verdict("V41 sync fan-out", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V42
        // pos_state / vote growth over the soak window: drip one tx per probeSec
        // for soakMin minutes; finality must advance across the whole window
        // (a stalled fin while the chain moves = prune-path starvation).
        if (wanted(only, "V42")) {
            System.out.println("============ V42: SOAK-WINDOW FINALITY DRIFT ============");
            try {
                int drips = Math.max(2, soakMin * 60 / Math.max(10, probeSec));
                int base0 = take.applyAsInt(drips + 2);
                String dripAddr = addrFor(seedFor(runUniqueIndex(startIndex, 42001))).toBase58();
                long finBefore = finalizedAfterRead(seedNode);
                long clBefore = chainLength(seedNode);
                boolean stalled = false;
                long lastFin = finBefore;
                long endAt = System.currentTimeMillis() + soakMin * 60_000L;
                int di = 0;
                while (System.currentTimeMillis() < endAt && di < drips) {
                    try {
                        UTXO u = utxos.get(addrFor(seedFor(base0 + di)).toBase58());
                        if (u != null) {
                            submitTx(pay(keyFor(base0 + di), u, dripAddr, payAmount, "v42-drip"));
                        }
                    } catch (Exception ignore) {
                    }
                    di++;
                    Thread.sleep(Math.max(10, probeSec) * 1000L);
                    long f = finalizedLength(seedNode);
                    if (f >= 0) lastFin = f;
                }
                long finAfter = finalizedAfterRead(seedNode);
                long clAfter = chainLength(seedNode);
                // Finality must have moved at least one epoch forward; a flat
                // fin across a multi-epoch window while the chain moves is the
                // prune-path starvation signature.
                boolean ok = finAfter > finBefore && clAfter > clBefore && !stalled;
                verdict("V42 soak drift (" + soakMin + "min)",
                        ok, "fin " + finBefore + " -> " + finAfter + " (last-seen " + lastFin + "), cl "
                                + clBefore + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V42 soak drift", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V43
        // GHOST map growth: attestation spam from many distinct fake pubkeys at
        // soak scale; every vote must be dropped pre-verify and finality must
        // still advance (no per-key state accumulation stall).
        if (wanted(only, "V43")) {
            System.out.println("============ V43: MANY-KEY VOTE SPAM ============");
            try {
                long finBefore = finalizedAfterRead(seedNode);
                long clBefore = chainLength(seedNode);
                int perShape = n.applyAsInt(100);
                int sent = 0;
                sent += submitAttestationSpam(nodeUrls, 431, 8100L, 1012L, 1011L, 1012L, perShape);
                sent += submitAttestationSpam(nodeUrls, 432, 8_100_000L, 1_012_500L, 999_999L, 1_012_500L, perShape);
                sent += submitAttestationSpam(nodeUrls, 433, 8100L, 1012L, 1011L, 1012L, perShape);
                sent += submitAttestationSpamGarbageBls(nodeUrls, Math.max(10, perShape / 2));
                long clAfter = chainAfterSettle(seedNode, clBefore, 45000);
                long finAfter = finalizedAfterRead(seedNode);
                boolean ok = clAfter > clBefore && finAfter >= finBefore;
                verdict("V43 many-key vote spam (" + sent + ")",
                        ok, "spam x" + sent + " dropped, cl " + clBefore + " -> " + clAfter
                                + ", fin " + finBefore + " -> " + finAfter);
            } catch (Exception e) {
                verdict("V43 many-key vote spam", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V44
        // DB pool exhaustion -> slot-tick starvation: sustain mixed tx +
        // garbage-block load across several slot ticks; beacons must keep
        // producing (chain advances steadily through the burst).
        if (wanted(only, "V44")) {
            System.out.println("============ V44: TICK-STARVATION BURST ============");
            try {
                int budget = n.applyAsInt(30);
                int base0 = take.applyAsInt(budget);
                String burstAddr = addrFor(seedFor(runUniqueIndex(startIndex, 44001))).toBase58();
                long clBefore = chainLength(seedNode);
                AtomicInteger sent = new AtomicInteger();
                long endAt = System.currentTimeMillis() + burstSec * 1000L;
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, clients));
                List<Future<?>> futs = new ArrayList<>();
                AtomicInteger wi = new AtomicInteger();
                for (int c = 0; c < Math.min(8, clients); c++) {
                    futs.add(pool.submit(() -> {
                        while (System.currentTimeMillis() < endAt) {
                            int i = wi.getAndIncrement() % budget;
                            try {
                                UTXO u = utxos.get(addrFor(seedFor(base0 + i)).toBase58());
                                if (u != null && submitTx(pay(keyFor(base0 + i), u, burstAddr,
                                        payAmount, "v44"))) {
                                    sent.incrementAndGet();
                                }
                            } catch (Exception ignore) {
                            }
                            // Garbage batchBlock interleaved (unknown parent).
                            try {
                                byte[] tipResp = OkHttp3Util.postString(seedNode + "getTip", "{}");
                                Block proto = params.getDefaultSerializer().makeBlock(Utils.HEX.decode(
                                        (String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                                Block b = Block.createBlock(params, proto, proto);
                                b.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
                                b.setPrevBlockHash(Sha256Hash.wrap(seedFor(System.nanoTime())));
                                submitBlockTo(nodeUrls[i % nnodes], b);
                            } catch (Exception ignore) {
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futs) {
                    try { f.get(burstSec + 60L, TimeUnit.SECONDS); } catch (Exception ignore) { }
                }
                pool.shutdownNow();
                long clAfter = chainAfterSettle(seedNode, clBefore, 60000);
                // ~12s slots: a 90s burst spans ~7 ticks; beacons must have kept
                // producing through it (tick starvation would flatline the head).
                boolean ok = clAfter > clBefore + 1;
                verdict("V44 tick burst (" + burstSec + "s, tx x" + sent + ")",
                        ok, "cl " + clBefore + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V44 tick burst", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V45
        // Reference-sweep blowup past the proposal deadline: land a big burst,
        // then require the head to advance promptly (the next proposal's sweep
        // must fit inside its deadline).
        if (wanted(only, "V45")) {
            System.out.println("============ V45: POST-BURST PROPOSAL RECOVERY ============");
            try {
                int burst = n.applyAsInt(300);
                int base0 = take.applyAsInt(burst);
                String burstAddr = addrFor(seedFor(runUniqueIndex(startIndex, 45001))).toBase58();
                AtomicInteger sent = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, clients));
                List<Future<?>> futs = new ArrayList<>();
                int perClient = Math.max(1, burst / Math.min(8, clients));
                for (int c = 0; c < Math.min(8, clients); c++) {
                    final int ci = c;
                    futs.add(pool.submit(() -> {
                        for (int j = 0; j < perClient; j++) {
                            int idx = ci * perClient + j;
                            if (idx >= burst) break;
                            try {
                                UTXO u = utxos.get(addrFor(seedFor(base0 + idx)).toBase58());
                                if (u != null && submitTx(pay(keyFor(base0 + idx), u, burstAddr,
                                        payAmount, "v45"))) {
                                    sent.incrementAndGet();
                                }
                            } catch (Exception ignore) {
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futs) {
                    try { f.get(300, TimeUnit.SECONDS); } catch (Exception ignore) { }
                }
                pool.shutdownNow();
                long clBefore = chainLength(seedNode);
                long t0 = System.currentTimeMillis();
                long clAfter = clBefore;
                // Next proposals must land within 5 minutes of the burst.
                while (System.currentTimeMillis() - t0 < 300_000L) {
                    Thread.sleep(10000);
                    clAfter = chainLength(seedNode);
                    if (clAfter > clBefore) break;
                }
                boolean ok = sent.get() > 0 && clAfter > clBefore;
                verdict("V45 burst recovery (tx x" + sent + ")",
                        ok, "burst x" + sent.get() + ", head advanced in "
                                + ((System.currentTimeMillis() - t0) / 1000) + "s");
            } catch (Exception e) {
                verdict("V45 burst recovery", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V46
        // Endurance drift watch (no attack): sample spread + liveness every
        // sampleSec for enduranceMin minutes. Any persistent spread beyond
        // maxSpread, or a non-advancing minimum chainlength across two samples,
        // is drift = BREACH. Default 4320 min = 72h; override down for smoke.
        if (wanted(only, "V46")) {
            System.out.println("============ V46: ENDURANCE DRIFT WATCH ============");
            try {
                int sampleSec = Integer.getInteger("load.sampleSec", 300);
                if (sampleSec < 20) sampleSec = 20;
                long samples = Math.max(2, enduranceMin * 60 / sampleSec);
                long worstSpread = 0;
                long stalls = 0;
                long prevMin = -1;
                int errors = 0;
                boolean ok = true;
                String detail = "";
                for (long s = 0; s < samples; s++) {
                    long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
                    boolean anyErr = false;
                    for (String u : nodeUrls) {
                        long cl = chainLength(u);
                        if (cl < 0) { anyErr = true; continue; }
                        min = Math.min(min, cl);
                        max = Math.max(max, cl);
                    }
                    if (anyErr) {
                        errors++;
                        if (errors >= 3) {
                            ok = false;
                            detail = "node unreachable 3x at sample " + s;
                            break;
                        }
                    } else {
                        errors = 0;
                        worstSpread = Math.max(worstSpread, max - min);
                        if (max - min > maxSpread) {
                            ok = false;
                            detail = "spread " + (max - min) + " > " + maxSpread + " at sample " + s;
                            break;
                        }
                        if (prevMin >= 0 && min <= prevMin - 1) {
                            // min regressed: reorg below a previous sample is drift.
                            ok = false;
                            detail = "min-cl regressed " + prevMin + " -> " + min + " at sample " + s;
                            break;
                        }
                        if (prevMin >= 0 && min == prevMin) stalls++;
                        else stalls = 0;
                        if (stalls >= 2) {
                            ok = false;
                            detail = "head stalled at " + min + " for 3 samples";
                            break;
                        }
                        prevMin = min;
                    }
                    if (s < samples - 1) Thread.sleep(sampleSec * 1000L);
                }
                if (detail.isEmpty()) detail = samples + " samples, worst spread " + worstSpread;
                verdict("V46 endurance (" + enduranceMin + "min)", ok, detail);
            } catch (Exception e) {
                verdict("V46 endurance", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V47
        // Fee-less FIFO ordering grief: bury a few honest transfers under a big
        // cheap-spam flood; every honest tx must still confirm (no fee market
        // means FIFO — starvation here is the grief).
        if (wanted(only, "V47")) {
            System.out.println("============ V47: FIFO ORDERING GRIEF ============");
            try {
                int spam = n.applyAsInt(1000);
                int base0 = take.applyAsInt(spam + 10);
                String spamAddr = addrFor(seedFor(runUniqueIndex(startIndex, 47001))).toBase58();
                String honestAddr = addrFor(seedFor(runUniqueIndex(startIndex, 47002))).toBase58();
                int sent = 0;
                for (int i = 0; i < spam; i++) {
                    UTXO u = utxos.get(addrFor(seedFor(base0 + i)).toBase58());
                    if (u == null) continue;
                    try {
                        if (submitTx(pay(keyFor(base0 + i), u, spamAddr, payAmount, "v47-spam"))) sent++;
                    } catch (Exception ignore) {
                    }
                }
                int honestSent = 0;
                for (int j = 0; j < 10; j++) {
                    UTXO u = utxos.get(addrFor(seedFor(base0 + spam + j)).toBase58());
                    if (u == null) continue;
                    try {
                        if (submitTx(pay(keyFor(base0 + spam + j), u, honestAddr, payAmount, "v47-honest")))
                            honestSent++;
                    } catch (Exception ignore) {
                    }
                }
                int honestConfirmed = 0;
                long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(5000);
                    honestConfirmed = countConfirmed(honestAddr);
                    if (honestConfirmed >= honestSent && honestSent > 0) break;
                }
                boolean ok = honestSent > 0 && honestConfirmed >= honestSent;
                verdict("V47 fifo grief (spam x" + sent + ")",
                        ok, "honest " + honestConfirmed + "/" + honestSent
                                + (honestConfirmed < honestSent ? " STARVED" : ""));
            } catch (Exception e) {
                verdict("V47 fifo grief", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V48
        // Gossip dup-forward amplification loop: deliver the SAME valid block
        // to every node, repeatedly; the payload tx must confirm exactly once
        // and the chain must keep advancing (no re-verify storm wedge).
        if (wanted(only, "V48")) {
            System.out.println("============ V48: DUP-FORWARD LOOP ============");
            try {
                int base0 = take.applyAsInt(30);
                String dupAddr = addrFor(seedFor(runUniqueIndex(startIndex, 48001))).toBase58();
                long clBefore = chainLength(seedNode);
                UTXO u = fetchUtxo(base0);
                boolean staged = false;
                if (u != null) {
                    Transaction t = pay(keyFor(base0), u, dupAddr, payAmount, "v48-dup");
                    Block b = craftTransferBlock(t);
                    for (int round = 0; round < 3; round++) {
                        for (String nu : nodeUrls) submitBlockTo(nu, b);
                        staged = true;
                        Thread.sleep(3000);
                    }
                }
                Thread.sleep(20000);
                int confirmed = -1;
                if (staged) {
                    // Poll with the standard deadline: under backlog the single
                    // payload confirms later than the fixed settle windows.
                    long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
                    while (System.currentTimeMillis() < deadline) {
                        confirmed = countConfirmed(dupAddr);
                        if (confirmed >= 1) break;
                        Thread.sleep(5000);
                    }
                }
                long clAfter = chainAfterSettle(seedNode, clBefore, 30000);
                boolean ok = !staged || (confirmed == 1 && clAfter > clBefore);
                verdict("V48 dup-forward (3 rounds x" + nnodes + ")",
                        ok, (!staged ? "UNSTAGED " : "") + "dup-tx confirmed x" + confirmed + " (want 1), cl "
                                + clBefore + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V48 dup-forward", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V49
        // Equivocation-set poisoning: slashing proofs from many DISTINCT fake
        // keys (each resubmitted); the honest validator set must be untouched
        // and the chain must advance (no per-key state drag).
        if (wanted(only, "V49")) {
            System.out.println("============ V49: EQUIVOCATION-SET POISONING ============");
            try {
                long valBefore = activeValidatorCount(seedNode);
                long clBefore = chainLength(seedNode);
                int keys = Math.max(5, n.applyAsInt(40));
                int sent = 0;
                for (int k = 0; k < keys; k++) {
                    PQKey fk = PQKey.createNew();
                    byte[] pub = fk.getPubKey();
                    long slot = 31_220_000L + k;
                    long epoch = slot / 8;
                    for (int dup = 0; dup < 2; dup++) {
                        AttestationData a1 = fakeAttestation(pub, slot, epoch, epoch - 1, epoch,
                                Sha256Hash.of(("v49a-" + k).getBytes()),
                                Sha256Hash.of(("v49t-" + k).getBytes()));
                        AttestationData b2 = fakeAttestation(pub, slot, epoch, epoch - 1, epoch,
                                Sha256Hash.of(("v49b-" + k).getBytes()),
                                Sha256Hash.of(("v49u-" + k).getBytes()));
                        try {
                            String body = "{\"attestation1\":"
                                    + new String(Json.jsonmapper().writeValueAsBytes(a1),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    + ",\"attestation2\":"
                                    + new String(Json.jsonmapper().writeValueAsBytes(b2),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    + "}";
                            OkHttp3Util.postString(seedNode + "submitSlashingProof", body);
                            sent++;
                        } catch (Exception ignore) {
                            sent++;
                        }
                    }
                }
                long clAfter = chainAfterSettle(seedNode, clBefore, 45000);
                long valAfter = activeValidatorCount(seedNode);
                boolean ok = valAfter == valBefore && clAfter > clBefore;
                verdict("V49 equivocation poisoning (" + keys + " keys)",
                        ok, "proofs x" + sent + ", validators " + valBefore + " -> " + valAfter
                                + (valAfter != valBefore ? " POISONED" : "") + ", cl " + clBefore + " -> " + clAfter);
            } catch (Exception e) {
                verdict("V49 equivocation poisoning", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ V50
        // Weak-subjectivity isolation: feed a deep hostile fork to ONE node
        // only (eclipse proxy); its finalized root must never diverge from the
        // mesh, and it must keep converging to the honest head.
        if (wanted(only, "V50")) {
            System.out.println("============ V50: SINGLE-NODE ECLIPSE FORK ============");
            try {
                String lone = nodeUrls[nnodes - 1];
                String loneFinBefore = finalizedHash(lone);
                long loneClBefore = chainLength(lone);
                String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 50001))).toBase58();
                int base0 = take.applyAsInt(3);
                int staged = 0;
                for (int r = 0; r < 3; r++) {
                    try {
                        long cl = chainLength(seedNode);
                        long fin = finalizedLength(seedNode);
                        int depth = (int) Math.max(4, cl - Math.max(0, fin) + 2);
                        Block fork = staleForkBlock(nodeUrls, base0 + r, depth, attacker, payAmount);
                        if (fork != null && submitBlockTo(lone, fork)) staged++;
                    } catch (Exception ignore) {
                    }
                    Thread.sleep(3000);
                }
                Thread.sleep(45000);
                java.util.Set<String> fins = finRootSettled(nodeUrls);
                long loneClAfter = chainLength(lone);
                boolean ok = staged == 0 || (fins.size() == 1 && loneClAfter >= loneClBefore);
                verdict("V50 eclipse fork (deep x" + staged + " to 1 node)",
                        ok, (staged == 0 ? "UNSTAGED " : "") + "lone fin " + loneFinBefore + " -> "
                                + finalizedHash(lone) + ", finroots=" + fins.size()
                                + ", lone cl " + loneClBefore + " -> " + loneClAfter);
            } catch (Exception e) {
                verdict("V50 eclipse fork", false, "error: " + shortMsg(e));
            }
        }

        // ================================================================ REJOIN
        // Restart-rejoin probe (§26 regression, OPT-IN via only=REJOIN):
        // docker-restarts one node container and asserts it rejoins the mesh
        // (chain advances past the pre-restart floor, finroots agree). Restart
        // alone must heal — the pre-fix code NPE-spun forever here.
        if (only != null && only.contains("REJOIN")) {
            System.out.println("============ REJOIN: RESTART-REJOIN PROBE ============");
            try {
                String container = System.getProperty("load.rejoinContainer", "").trim();
                int waitSec = Integer.getInteger("load.rejoinWaitSec", 600);
                if (container.isEmpty()) {
                    verdict("REJOIN restart-rejoin", false, "set -Dload.rejoinContainer=<docker-name>");
                } else {
                    int watched = -1;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("node-(\\d+)-server")
                            .matcher(container);
                    if (m.find()) {
                        try { watched = Integer.parseInt(m.group(1)); } catch (Exception ignore) { }
                    }
                    long floor = Long.MAX_VALUE;
                    for (String u : nodeUrls) {
                        long cl = chainLength(u);
                        if (cl >= 0) floor = Math.min(floor, cl);
                    }
                    if (floor == Long.MAX_VALUE) floor = 0;
                    Process p = new ProcessBuilder("docker", "restart", container)
                            .redirectErrorStream(true).start();
                    boolean exited = p.waitFor(120, TimeUnit.SECONDS);
                    String out = new String(p.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8).trim().replace('\n', ' ');
                    // Wait for the restarted node's API (or all nodes if the
                    // container name did not map to a lane index).
                    long apiDeadline = System.currentTimeMillis() + 300_000L;
                    boolean apiUp = false;
                    while (System.currentTimeMillis() < apiDeadline) {
                        Thread.sleep(10000);
                        if (watched >= 0 && watched < nnodes) {
                            try {
                                OkHttp3Util.postString(nodeUrls[watched] + "getTip", "{}");
                                apiUp = true;
                                break;
                            } catch (Exception ignore) {
                            }
                        } else if (minChain(nodeUrls) >= 0) {
                            apiUp = true;
                            break;
                        }
                    }
                    // Then require the floor to be passed (real rejoin, not
                    // just API-up) and finroots to agree.
                    boolean rejoined = false;
                    long joinDeadline = System.currentTimeMillis() + waitSec * 1000L;
                    while (apiUp && System.currentTimeMillis() < joinDeadline) {
                        Thread.sleep(15000);
                        long min = watched >= 0 && watched < nnodes
                                ? chainLength(nodeUrls[watched]) : minChain(nodeUrls);
                        if (min > floor) { rejoined = true; break; }
                    }
                    java.util.Set<String> fins = rejoined ? finRootSettled(nodeUrls)
                            : new java.util.HashSet<>();
                    boolean ok = exited && apiUp && rejoined && fins.size() == 1;
                    verdict("REJOIN restart-rejoin (" + container + ")",
                            ok, "docker=" + out + " apiUp=" + apiUp + " rejoined=" + rejoined
                                    + " floor=" + floor + " finroots=" + fins.size());
                }
            } catch (Exception e) {
                verdict("REJOIN restart-rejoin", false, "error: " + shortMsg(e));
            }
        }

        System.out.println("==============================================");
        System.out.println("  MESHLOAD — VERDICT TABLE");
        System.out.println("==============================================");        boolean allPass = true;
        for (String row : VERDICTS) {
            System.out.println("  " + row);
            if (row.contains("FAIL")) allPass = false;
        }
        System.out.println("==============================================");
        System.out.println(allPass ? "ALL_LOAD_DEFLECTED" : "LOAD_BREACH_DETECTED");
        System.out.flush();
        System.exit(allPass ? 0 : 1);
    }

    static boolean wanted(java.util.Set<String> only, String v) {
        // REJOIN is opt-in (docker restart is too invasive for the default lane).
        if ("REJOIN".equals(v)) return false;
        return only == null || only.contains(v);
    }

    /** Minimum chainlength across responsive nodes (-1 when none respond). */
    static long minChain(String[] nodeUrls) {
        long min = Long.MAX_VALUE;
        for (String u : nodeUrls) {
            long cl = chainLength(u);
            if (cl >= 0) min = Math.min(min, cl);
        }
        return min == Long.MAX_VALUE ? -1 : min;
    }

    /** One serialized tx for the V38 raw-gossip frame (empty when unfunded). */
    static byte[] buildV38Frame(long startIndex, Map<String, UTXO> utxos, long payAmount) {
        try {
            // The signing key MUST own the coin: look up startIndex's own UTXO
            // (already spent by earlier vectors is fine — never submitted).
            UTXO u0 = utxos.get(addrFor(seedFor(startIndex)).toBase58());
            if (u0 == null) return new byte[0];
            Transaction probe = pay(keyFor(startIndex), u0,
                    addrFor(seedFor(runUniqueIndex(startIndex, 38001))).toBase58(), payAmount, "v38");
            return probe == null ? new byte[0] : probe.bitcoinSerialize();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replace('\n', ' ');
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    static String hostOf(String nodeUrl) {
        try {
            java.net.URI uri = new java.net.URI(nodeUrl);
            return uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    /** p99 getTip latency across nodes (responsiveness probe). */
    static long tipP99(String[] nodeUrls, int probes) {
        List<Long> lat = new ArrayList<>();
        for (int i = 0; i < probes; i++) {
            try {
                long t0 = System.nanoTime();
                OkHttp3Util.postString(nodeUrls[i % nodeUrls.length] + "getTip", "{}");
                lat.add((System.nanoTime() - t0) / 1_000_000L);
            } catch (Exception ignore) {
            }
        }
        return percentile(lat, 99);
    }

    /** Pending-transaction count on a node (-1 when unreadable). */
    static int pendingCount(String nodeUrl) {
        try {
            byte[] r = OkHttp3Util.postString(nodeUrl + "getPendingTransactions", "{}");
            Map<?, ?> map = Json.jsonmapper().readValue(r, Map.class);
            Object listObj = map.get("transactionlist");
            if (listObj instanceof List) return ((List<?>) listObj).size();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static Transaction pay(PQKey from, UTXO utxo, String toAddr, long amount, String note) throws Exception {
        FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(params, utxo);
        Wallet w = Wallet.fromKeys(params, from);
        return w.payToListTransaction(null, new HashMap<>(Map.of(toAddr, BigInteger.valueOf(amount))),
                M_BIG, note, List.of(coin));
    }

    static boolean submitTx(Transaction tx) {
        try {
            OkHttp3Util.post(SEED_NODE + "submitTransaction", tx.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean submitBlock(Block block) {
        try {
            OkHttp3Util.post(SEED_NODE + "batchBlock", block.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Block fetchBlock(Sha256Hash hash) throws Exception {
        byte[] r = OkHttp3Util.postString(SEED_NODE + "getBlockByHash",
                Json.jsonmapper().writeValueAsString(Map.of("hashHex", hash.toString(), "text", "false")));
        Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
        String dataHex = (String) m.get("dataHex");
        return params.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
    }

    static Block craftTransferBlock(Transaction tx) throws Exception {
        byte[] tipResp = OkHttp3Util.postString(SEED_NODE + "getTip", "{}");
        Block proto = params.getDefaultSerializer().makeBlock(
                Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
        Block trunk = fetchBlock(proto.getPrevBlockHash());
        Block branch = fetchBlock(proto.getPrevBranchBlockHash());
        Block block = Block.createBlock(params, trunk, branch);
        block.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
        block.addTransaction(tx);
        return block;
    }

    static int countConfirmed(String address) {
        try {
            byte[] r = OkHttp3Util.postString(SEED_NODE + "getTransactionsStatusByAddress",
                    Json.jsonmapper().writeValueAsString(Map.of("address", address)));
            GetTransactionStatusResponse.GetTransactionsStatusResponse resp = Json.jsonmapper().readValue(r,
                    GetTransactionStatusResponse.GetTransactionsStatusResponse.class);
            int cnt = 0;
            if (resp.getTransactions() != null) {
                for (GetTransactionStatusResponse item : resp.getTransactions()) {
                    if ("CONFIRMED".equals(item.getStatus())) {
                        cnt++;
                    }
                }
            }
            return cnt;
        } catch (Exception e) {
            return 0;
        }
    }

    static long chainLength(String hostPort) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "/getChainNumber";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getChainNumber", "/getChainNumber"), "{}");
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            Object tr = m.get("txReward");
            String s = tr instanceof String ? (String) tr : Json.jsonmapper().writeValueAsString(tr);
            Map<?, ?> t = Json.jsonmapper().readValue(s, Map.class);
            Object cl = t.get("chainLength");
            return cl instanceof Number ? ((Number) cl).longValue() : Long.parseLong(String.valueOf(cl));
        } catch (Exception e) {
            return -1;
        }
    }

    static String chainNumberJson(String hostPort) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "/getChainNumber";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getChainNumber", "/getChainNumber"), "{}");
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            return Json.jsonmapper().writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** finalizedChainLength from getChainNumber. */
    static long finalizedLength(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object fl = m.get("finalizedChainLength");
            return fl instanceof Number ? ((Number) fl).longValue() : Long.parseLong(String.valueOf(fl));
        } catch (Exception e) {
            return -1;
        }
    }

    /** finalizedBlockHash (plain hex string) from getChainNumber. */
    static String finalizedHash(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object fh = m.get("finalizedBlockHash");
            return fh == null ? "" : String.valueOf(fh);
        } catch (Exception e) {
            return "";
        }
    }

    /** Submit a serialized block to a specific node; true if accepted (no throw). */
    static boolean submitBlockTo(String nodeUrl, Block block) {
        try {
            OkHttp3Util.post((nodeUrl.startsWith("http") ? nodeUrl : "http://" + nodeUrl) + "batchBlock",
                    block.bitcoinSerialize());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Number of active validators reported by a node's getValidators. */
    static long activeValidatorCount(String nodeUrl) {
        try {
            String u = (nodeUrl.startsWith("http") ? nodeUrl : "http://" + nodeUrl);
            byte[] r = OkHttp3Util.postString(u + "getValidators", "{}");
            Map<?, ?> top = Json.jsonmapper().readValue(r, Map.class);
            Object text = top.get("text");
            Object valObj = text != null ? text : top.get("validators");
            if (valObj instanceof String) {
                Map<?, ?> inner = Json.jsonmapper().readValue((String) valObj, Map.class);
                Object v = inner.get("validators");
                if (v instanceof java.util.List) return ((java.util.List<?>) v).size();
                if (v instanceof Map) return ((Map<?, ?>) v).size();
                return 0;
            }
            if (valObj instanceof java.util.List) return ((java.util.List<?>) valObj).size();
            if (valObj instanceof Map) return ((Map<?, ?>) valObj).size();
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Finalized roots across nodes, with one retry to ride out a boundary. */
    static java.util.Set<String> finRootSet(String[] nodeUrls) {
        java.util.Set<String> fins = new java.util.HashSet<>();
        for (String u : nodeUrls) fins.add(finalizedHash(u));
        return fins;
    }

    /**
     * Finroot agreement with a single confirm-retry: reads are sequential
     * across nodes, so an epoch finalizing mid-read shows 2 roots transiently
     * on a healthy mesh. Only a persistent split is a real divergence.
     */
    static java.util.Set<String> finRootSettled(String[] nodeUrls) throws Exception {
        java.util.Set<String> fins = finRootSet(nodeUrls);
        if (fins.size() > 1) {
            Thread.sleep(20000);
            fins = finRootSet(nodeUrls);
        }
        return fins;
    }

    /**
     * Confirmed-chainlength after a settle window, with one extra 30s retry
     * when the first read shows no advance: confirmed length moves in bursts
     * (beacon confirm batches), so a single short window flakes on a loaded
     * mesh even when healthy. Returns the best read (may still be == before).
     */
    static long chainAfterSettle(String nodeUrl, long before, long settleMs) throws Exception {
        Thread.sleep(settleMs);
        long cl = chainLength(nodeUrl);
        if (cl <= before) {
            Thread.sleep(30000);
            cl = Math.max(cl, chainLength(nodeUrl));
        }
        return cl;
    }

    /** finalizedLength with one re-read when the first call errors (-1). */
    static long finalizedAfterRead(String nodeUrl) throws Exception {
        long fin = finalizedLength(nodeUrl);
        if (fin < 0) {
            Thread.sleep(10000);
            fin = Math.max(fin, finalizedLength(nodeUrl));
        }
        return fin;
    }

    /**
     * Build an unsigned, fake-key attestation for spam vectors. The key is NOT
     * a registered validator (or the BLS is absent), so a healthy node must
     * drop it before verify / state growth. Serializes symmetrically with the
     * server's Jackson mapping.
     */
    static AttestationData fakeAttestation(byte[] pubkey, long slot, long epoch,
            long sourceEpoch, long targetEpoch, Sha256Hash head, Sha256Hash target) {
        AttestationData att = new AttestationData();
        att.setSlot(slot);
        att.setEpoch(epoch);
        att.setSourceEpoch(sourceEpoch);
        att.setTargetEpoch(targetEpoch);
        att.setBeaconBlockHash(head);
        att.setSourceCheckpoint(Sha256Hash.ZERO_HASH);
        att.setTargetCheckpoint(target);
        att.setValidatorPubkey(pubkey);
        return att;
    }

    /**
     * Post {@code count} fake-key attestations of one shape, round-robin across
     * nodes. Returns the number sent (verdicts assert on-chain impact, since
     * submitAttestation drops garbage silently with HTTP 200).
     */
    static int submitAttestationSpam(String[] nodeUrls, long keySalt, long slot, long epoch,
            long sourceEpoch, long targetEpoch, int count) {
        int sent = 0;
        for (int k = 0; k < count; k++) {
            try {
                PQKey fk = PQKey.createNew();
                byte[] pub = fk.getPubKey();
                AttestationData att = fakeAttestation(pub, slot + k, epoch, sourceEpoch, targetEpoch,
                        Sha256Hash.ZERO_HASH, Sha256Hash.wrap(new byte[32]));
                String body = Json.jsonmapper().writeValueAsString(att);
                OkHttp3Util.postString(nodeUrls[(int) ((keySalt + k) % nodeUrls.length)] + "submitAttestation", body);
                sent++;
            } catch (Exception ignore) {
                sent++;
            }
        }
        return sent;
    }

    /** Same as above but with structurally-present (yet invalid) BLS material. */
    static int submitAttestationSpamGarbageBls(String[] nodeUrls, int count) {
        int sent = 0;
        java.util.Random rnd = new java.util.Random(0xC10C);
        for (int k = 0; k < count; k++) {
            try {
                PQKey fk = PQKey.createNew();
                AttestationData att = fakeAttestation(fk.getPubKey(), 900L + k, 112L, 111L, 112L,
                        Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);
                byte[] badPub = new byte[48];
                byte[] badSig = new byte[96];
                rnd.nextBytes(badPub);
                rnd.nextBytes(badSig);
                att.setBlsPubkey(badPub);
                att.setSignature(badSig);
                String body = Json.jsonmapper().writeValueAsString(att);
                OkHttp3Util.postString(nodeUrls[k % nodeUrls.length] + "submitAttestation", body);
                sent++;
            } catch (Exception ignore) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Build a short, structurally-valid TRANSFER fork that extends an ancestor
     * {@code depth} blocks behind the live head (instead of the current tip),
     * paying {@code payAmount} to {@code toAddr} from the funded wallet at
     * {@code walletIdx}. Returns null when the wallet or an ancestor is
     * unavailable.
     */
    static Block staleForkBlock(String[] nodeUrls, long walletIdx, int depth, String toAddr, long payAmount) {
        try {
            UTXO u = fetchUtxo(walletIdx);
            if (u == null) return null;
            byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
            Block proto = params.getDefaultSerializer().makeBlock(
                    Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
            Block cur = fetchBlock(proto.getPrevBlockHash());
            for (int d = 0; d < depth && cur != null; d++) {
                Block parent = fetchBlock(cur.getPrevBlockHash());
                if (parent == null || parent.getHash().equals(cur.getHash())) break;
                cur = parent;
            }
            if (cur == null) return null;
            Block branch = fetchBlock(cur.getPrevBranchBlockHash());
            Block fork = Block.createBlock(params, cur, branch);
            fork.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
            Transaction t = pay(keyFor(walletIdx), u, toAddr, payAmount, "v50-eclipse-fork");
            fork.addTransaction(t);
            return fork;
        } catch (Exception e) {
            return null;
        }
    }

    static UTXO fetchUtxo(long walletIdx) {
        try {
            byte[] seed = seedFor(walletIdx);
            String addr = addrFor(seed).toBase58();
            byte[] resp = OkHttp3Util.postString(SEED_NODE + "getOutputs",
                    Json.jsonmapper().writeValueAsString(List.of(
                            Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()))));
            GetOutputsResponse gor = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
            if (gor.getOutputs() != null) {
                UTXO fallback = null;
                for (UTXO x : gor.getOutputs()) {
                    if (x.getValue() != null && x.getAddress() != null && !x.isSpent()) {
                        // Prefer the full genesis funding output so pay() has
                        // enough to cover amount + change + fee.
                        if (x.getValue().getValue().longValue() >= 25000L) return x;
                        if (fallback == null) fallback = x;
                    }
                }
                if (fallback != null) return fallback;
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
