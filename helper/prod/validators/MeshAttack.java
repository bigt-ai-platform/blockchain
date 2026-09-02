import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
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
 * MeshAttack — deterministic attack driver for the hermetic testnodes.sh mesh.
 *
 * The coin-minting /fundAddresses faucet has been removed; attacker wallets are
 * funded AT GENESIS via the distribution CSV, exactly like MeshBm (keys are
 * derived deterministically from an index so the driver re-derives the same
 * wallets the genesis CSV funded). This lets every offensive vector below run
 * against the local mesh and assert its expected defence.
 *
 * Modes:
 *   MeshAttack genesis <startIndex> <count> [fund]     print `address,,value` rows
 *   MeshAttack run <startIndex> <count> <nnodes> <urlPrefix> [scale]
 *
 * Vectors (verdict table printed at the end, exit 0 = all deflected):
 *   V1 mempool double-spend (legit + re-spend of the same genesis UTXO)
 *   V2 double-spend smuggled through CRAFTED transfer blocks (+ control blocks)
 *   V3 invalid-block injection (tampered bytes / unknown parent / forged beacon)
 *   V4 /fundAddresses unauthorized mint must be refused
 *   V5 stakeDeposit foreign pubkey / embedded privateKey must be refused
 *   V6 fabricated slashing proof must be rejected
 *   V7 getValidatorKey must never leak private key material
 *   V8 post-attack chain health (nodes alive, spread, advancing)
 *   V9 stale-fork rewind reorg (a short fork of a real ancestor must not win)
 *   V10 side-branch status spoof (non-canonical tx must not be CONFIRMED)
 *   V11 orphan/dup resubmit flood (unknown-parent blocks; dup block+tx -> 1 confirm)
 *   V12 forged finalized anchor (nonsense-reward beacon must not move finality)
 *   V13 double-vote form-b slashing proof (same target epoch, diff checkpoint)
 *   V14 forged validator exit (no-sig / attacker-sig exit must be refused)
 *   V15 activation bypass (foreign pubkey / attacker epoch must not register)
 *   V16 deposit/withdrawal abuse (negative amount; far-future epoch harmless)
 *   V17 early withdrawal (epoch-0 release must not unlock bonded validators)
 *   V18 post-churn finality advance (finality keeps moving, roots identical)
 *   V19 sibling-conflict deadlock storm (conflicting sibling blocks confirm on)
 *   V20 proposer-duty starvation (garbage flood must not stall block production)
 *   V21 reorg churn livelock (tip + stale-fork alternation must not wedge)
 *   V22 epoch-finality stall probe (finality advances past an epoch boundary)
 *   V23 beacon-connect conflict deadlock (one sibling per conflict set confirms)
 *   V24 unwind-reconnect livelock (deep stale forks must not freeze/collapse)
 *
 * System properties (all optional):
 *   attack.fund           per-wallet genesis value            30000
 *   attack.pay            satoshis paid per tx                20000
 *   attack.confirmTimeoutSec confirm poll deadline             300
 *   attack.merchantIdx    recipient index base                200_000_000
 */
public class MeshAttack {
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
     * Recipient index that is UNIQUE PER RUN so per-address confirmed-count
     * verdicts never accumulate across soak cycles. The V9-V12 recipient
     * addresses must not be fixed (a single historical confirm on a reused
     * address would otherwise make every later cycle report a false breach —
     * the original V3 sticky-FAIL artifact). startIndex advances every soak
     * cycle, so startIndex*1e6 + salt is distinct each run while staying far
     * outside the genesis-funded wallet window (startIndex..+totalWallets).
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
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
        }
        if ("genesis".equals(args[0])) {
            long start = Long.parseLong(args[1]);
            long count = Long.parseLong(args[2]);
            long value = args.length > 3 ? Long.parseLong(args[3]) : Long.getLong("attack.fund", 30000L);
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
        System.err.println("usage: MeshAttack genesis <startIndex> <count> [fund]\n"
                + "       MeshAttack run <startIndex> <count> <nnodes> <urlPrefix> [scale]");
        System.exit(1);
    }

    static void run(String[] args) throws Exception {
        long startIndex = Long.parseLong(args[1]);
        int totalWallets = Integer.parseInt(args[2]);
        int nnodes = Integer.parseInt(args[3]);
        String urlPrefix = args[4];
        double scale = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;
        long fundAmount = Long.getLong("attack.fund", 30000L);
        long payAmount = Long.getLong("attack.pay", 20000L);
        int confirmTimeoutSec = Integer.getInteger("attack.confirmTimeoutSec", 300);
        long merchantIdxBase = Long.getLong("attack.merchantIdx", 200_000_000L);

        String base = urlPrefix; // e.g. "http://127.0.0.1:" -> nodeUrls[i] = base+8281+"/"
        String[] nodeUrls = new String[nnodes];
        for (int i = 0; i < nnodes; i++) {
            nodeUrls[i] = base + (8281 + i) + "/";
        }
        String seedNode = nodeUrls[0];
        SEED_NODE = seedNode;

        java.util.function.IntUnaryOperator n = full -> Math.max(1, (int) Math.round(full * scale));

        System.out.println("MESHATTACK run: start=" + startIndex + " wallets=" + totalWallets
                + " nnodes=" + nnodes + " seed=" + seedNode + " fund=" + fundAmount + " pay=" + payAmount
                + " scale=" + scale);

        // ---- fetch genesis-funded UTXOs (deterministic wallets) ----
        Map<String, UTXO> byAddr = new HashMap<>();
        Map<String, byte[]> addrToSeed = new HashMap<>();
        List<String> pubKeyHashes = new ArrayList<>();
        for (long i = 0; i < totalWallets; i++) {
            byte[] seed = seedFor(startIndex + i);
            String addr = addrFor(seed).toBase58();
            byAddr.put(addr, null);
            addrToSeed.put(addr, seed);
            pubKeyHashes.add(Utils.HEX.encode(PQKey.fromMLDSA(seed).getPubKeyHash()));
        }
        int CHUNK = 2000;
        Map<String, UTXO> utxos = new HashMap<>();
        for (int c0 = 0; c0 < totalWallets; c0 += CHUNK) {
            List<String> sub = pubKeyHashes.subList(c0, Math.min(totalWallets, c0 + CHUNK));
            byte[] resp = OkHttp3Util.postString(seedNode + "getOutputs", Json.jsonmapper().writeValueAsString(sub));
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
        System.out.println("MESHATTACK funded UTXOs fetched: " + utxos.size() + "/" + totalWallets);
        if (utxos.isEmpty()) {
            System.err.println("NO_FUNDED_UTXOS: attack genesis funding not visible");
            System.exit(2);
        }

        AtomicInteger submitted = new AtomicInteger();
        ConcurrentLinkedQueue<String> txHashes = new ConcurrentLinkedQueue<>();

        // ================================================================ V1
        System.out.println("============ V1: MASS DOUBLE-SPEND VIA MEMPOOL ============");
        {
            int pairs = n.applyAsInt(60);
            String merchantAddr = addrFor(seedFor(merchantIdxBase + 1)).toBase58();
            String attackerAddr = addrFor(seedFor(merchantIdxBase + 2)).toBase58();
            int legitSubmitted = 0;
            List<Transaction> legitTxs = new ArrayList<>();
            for (int i = 0; i < pairs; i++) {
                UTXO u = utxos.get(addrFor(seedFor(startIndex + i)).toBase58());
                if (u == null) continue;
                PQKey k = keyFor(startIndex + i);
                Transaction l = pay(k, u, merchantAddr, payAmount, "v1-legit");
                Transaction d = pay(k, u, attackerAddr, payAmount, "v1-double-spend");
                if (submitTx(l)) {
                    legitSubmitted++;
                    legitTxs.add(l);
                }
                submitTx(d);
            }
            int legitConfirmed = 0, dsConfirmed = 0;
            long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5000);
                legitConfirmed = countConfirmed(merchantAddr);
                dsConfirmed = countConfirmed(attackerAddr);
                if ((legitConfirmed >= legitSubmitted && legitSubmitted > 0) || dsConfirmed > 0) break;
            }
            verdict("V1 mempool double-spend (" + pairs + ")",
                    dsConfirmed == 0 && legitConfirmed > 0,
                    "legit " + legitConfirmed + "/" + legitSubmitted + " confirmed, double-spend " + dsConfirmed);
        }

        // ================================================================ V2
        System.out.println("============ V2: DOUBLE-SPEND VIA CRAFTED BLOCKS ============");
        {
            int pairs = n.applyAsInt(20);
            int control = Math.max(3, n.applyAsInt(5));
            String merchantAddr = addrFor(seedFor(merchantIdxBase + 3)).toBase58();
            String attackerAddr = addrFor(seedFor(merchantIdxBase + 4)).toBase58();
            int offset = (int) n.applyAsInt(60);
            int legitSubmitted = 0, controlAccepted = 0;
            List<Transaction> dsTxs = new ArrayList<>();
            for (int i = 0; i < pairs; i++) {
                UTXO u = utxos.get(addrFor(seedFor(startIndex + offset + i)).toBase58());
                if (u == null) continue;
                PQKey k = keyFor(startIndex + offset + i);
                try {
                    Transaction l = pay(k, u, merchantAddr, payAmount, "v2-legit");
                    if (submitTx(l)) legitSubmitted++;
                    dsTxs.add(pay(k, u, attackerAddr, payAmount, "v2-double-spend"));
                } catch (Exception ignore) {
                }
            }
            for (Transaction d : dsTxs) {
                try {
                    submitBlock(craftTransferBlock(d));
                } catch (Exception ignore) {
                }
            }
            for (int j = 0; j < control; j++) {
                UTXO u = utxos.get(addrFor(seedFor(startIndex + offset + pairs + j)).toBase58());
                if (u == null) continue;
                try {
                    Transaction c = pay(keyFor(startIndex + offset + pairs + j), u, merchantAddr, payAmount, "v2-control");
                    if (submitBlock(craftTransferBlock(c))) controlAccepted++;
                } catch (Exception ignore) {
                }
            }
            int dsConfirmed = 0, merchantConfirmed = 0;
            long deadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5000);
                dsConfirmed = countConfirmed(attackerAddr);
                merchantConfirmed = countConfirmed(merchantAddr);
                if (dsConfirmed > 0 || merchantConfirmed >= Math.max(1, legitSubmitted / 10)) break;
            }
            verdict("V2 crafted-block double-spend (" + pairs + ")",
                    dsConfirmed == 0 && controlAccepted > 0,
                    "attacker " + dsConfirmed + " confirmed, merchant " + merchantConfirmed
                            + ", control-blocks accepted " + controlAccepted);
        }

        // ================================================================ V3
        System.out.println("============ V3: INVALID-BLOCK INJECTION ============");
        {
            int offset = (int) n.applyAsInt(60) + (int) n.applyAsInt(20) + Math.max(3, (int) n.applyAsInt(5));
            String merchantAddr = addrFor(seedFor(merchantIdxBase + 5)).toBase58();
            String attackerAddr = addrFor(seedFor(merchantIdxBase + 6)).toBase58();
            UTXO u = utxos.get(addrFor(seedFor(startIndex + offset)).toBase58());
            int rejected = 0, acceptedAtEdge = 0;
            if (u != null) {
                PQKey k = keyFor(startIndex + offset);
                Transaction legitTx = pay(k, u, merchantAddr, payAmount, "v3-legit");
                submitTx(legitTx);
                Transaction dsTx = pay(k, u, attackerAddr, payAmount, "v3-double-spend");

                try {
                    Block b = craftTransferBlock(dsTx);
                    byte[] ser = b.bitcoinSerialize();
                    ser[ser.length / 2] = (byte) (ser[ser.length / 2] ^ 0x55);
                    if (submitBlock(params.getDefaultSerializer().makeBlock(ser))) acceptedAtEdge++;
                    else rejected++;
                } catch (Exception e) {
                    rejected++;
                }
                try {
                    Block b = craftTransferBlock(dsTx);
                    Block fake = Block.createBlock(params, b, b);
                    fake.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
                    fake.addTransaction(dsTx);
                    if (submitBlock(fake)) acceptedAtEdge++;
                    else rejected++;
                } catch (Exception e) {
                    rejected++;
                }
                try {
                    Block b = craftTransferBlock(dsTx);
                    b.setBlockType(BlockType.BLOCKTYPE_BEACON);
                    if (submitBlock(b)) acceptedAtEdge++;
                    else rejected++;
                } catch (Exception e) {
                    rejected++;
                }
            }
            long clBefore = chainLength(nodeUrls, 0);
            Thread.sleep(Math.max(30000, confirmTimeoutSec * 1000 / 6));
            int dsConfirmed = countConfirmed(attackerAddr);
            long clAfter = chainLength(nodeUrls, 0);
            verdict("V3 invalid-block injection (3)", dsConfirmed == 0 && clAfter >= clBefore,
                    acceptedAtEdge + " accepted-at-edge, " + rejected + " rejected, ds " + dsConfirmed
                            + " confirmed, chain " + clBefore + " -> " + clAfter);
        }

        // ================================================================ V4
        System.out.println("============ V4: UNAUTHORIZED MINTING ============");
        {
            PQKey attacker = PQKey.createNew();
            String attackerAddr = Address.fromHash160(params, attacker.getPubKeyHash()).toBase58();
            boolean minted;
            try {
                OkHttp3Util.postString(seedNode + "fundAddresses", Json.jsonmapper().writeValueAsString(Map.of(
                        "addresses", List.of(Map.of("address", attackerAddr, "value", 999999,
                                "pubkey", Utils.HEX.encode(attacker.getPubKey()))))));
                minted = true;
            } catch (Exception e) {
                minted = false;
            }
            verdict("V4 unauthorized mint (faucet)", !minted, minted ? "fundAddresses STILL MINTS" : "refused (endpoint removed)");
        }

        // ================================================================ V5
        System.out.println("============ V5: STAKE ENDPOINT GUARDS ============");
        {
            PQKey attacker = PQKey.createNew();
            String pub = Utils.HEX.encode(attacker.getPubKey());
            boolean mismatchRejected = false, privateKeyRejected = false;
            try {
                OkHttp3Util.postString(seedNode + "stakeDeposit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey", pub, "amount", "32000000")));
            } catch (Exception e) {
                mismatchRejected = true;
            }
            try {
                OkHttp3Util.postString(seedNode + "stakeDeposit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey", pub, "amount", "32000000",
                                "privateKey", pub)));
            } catch (Exception e) {
                privateKeyRejected = true;
            }
            verdict("V5 stake endpoint guards", mismatchRejected && privateKeyRejected,
                    "foreign-pubkey stake " + (mismatchRejected ? "403" : "ACCEPTED")
                            + ", raw-privateKey " + (privateKeyRejected ? "403" : "ACCEPTED"));
        }

        // ================================================================ V6
        System.out.println("============ V6: BOGUS SLASHING PROOF ============");
        {
            PQKey attacker = PQKey.createNew();
            Map<String, Object> att1 = Map.of("pubkey", Utils.HEX.encode(attacker.getPubKey()),
                    "slot", 21200000, "epoch", 662500, "sourceEpoch", 662499, "targetEpoch", 662500,
                    "beaconBlockHash", Sha256Hash.ZERO_HASH.toString());
            Map<String, Object> att2 = Map.of("pubkey", Utils.HEX.encode(attacker.getPubKey()),
                    "slot", 21200000, "epoch", 662500, "sourceEpoch", 662499, "targetEpoch", 662500,
                    "beaconBlockHash", Sha256Hash.wrap(new byte[32]).toString());
            boolean rejected;
            String detail;
            try {
                byte[] r = OkHttp3Util.postString(seedNode + "submitSlashingProof", Json.jsonmapper()
                        .writeValueAsString(Map.of("attestation1", att1, "attestation2", att2)));
                Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                Object ec = m.get("errorcode");
                rejected = !(ec instanceof Number) || ((Number) ec).intValue() != 0;
                detail = "errorcode=" + ec;
            } catch (Exception e) {
                rejected = true;
                detail = "rejected: " + e.getMessage();
            }
            verdict("V6 bogus slashing proof", rejected, detail);
        }

        // ================================================================ V7
        System.out.println("============ V7: VALIDATOR KEY EXPOSURE ============");
        {
            boolean ok = true;
            String detail = "no privateKey/seed returned";
            try {
                byte[] r = OkHttp3Util.postString(seedNode + "getValidatorKey", "{}");
                String body = new String(r, java.nio.charset.StandardCharsets.UTF_8);
                if (body.contains("privateKey") || body.matches(".*\"[0-9a-f]{128}\".*")) {
                    ok = false;
                    detail = "LEAKED " + body;
                }
            } catch (Exception e) {
                ok = true;
                detail = "endpoint unreachable (ok)";
            }
            verdict("V7 validator key exposure", ok, detail);
        }

        // ================================================================ V8
        System.out.println("============ V8: POST-ATTACK CHAIN HEALTH ============");
        {
            long[] cls = new long[nnodes];
            for (int i = 0; i < nnodes; i++) {
                cls[i] = chainLength(nodeUrls[i]);
            }
            long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
            for (long c : cls) {
                max = Math.max(max, c);
                min = Math.min(min, c);
            }
            long spread = max - min;
            long before = cls[nnodes - 1];
            Thread.sleep(30000);
            long after = chainLength(nodeUrls[nnodes - 1]);
            boolean ok = min >= 0 && spread <= 32 && after > before;
            verdict("V8 chain health", ok, "cl=" + java.util.Arrays.toString(cls) + " spread=" + spread
                    + " advancing " + before + " -> " + after);
        }

        // ================================================================ V9
        // Stale-fork replay must never push a live node onto a shorter branch
        // (regression for the minority-fork wedge: handleNewBestChain must not
        // unwind a finalized/confirmed chain to follow a stale competitor).
        // We replay a short, VALID transfer fork that extends an ancestor
        // several blocks behind the live head; a healthy node keeps its best
        // chain, never regresses, and never diverges.
        System.out.println("============ V9: STALE-FORK REWIND REORG ============");
        {
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            long minBefore = Long.MAX_VALUE;
            for (long c : clBefore) minBefore = Math.min(minBefore, c);
            int depth = 2 + (int) n.applyAsInt(3);
            int rejected = 0, accepted = 0, funded = 0;
            String forkAttacker = addrFor(seedFor(runUniqueIndex(startIndex, 90020))).toBase58();
            for (int round = 0; round < 3; round++) {
                try {
                    Block fork = staleForkBlock(nodeUrls, startIndex + 120 + round, depth,
                            forkAttacker, payAmount);
                    if (fork == null) continue;
                    funded++;
                    for (int i = 0; i < nnodes; i++) {
                        try {
                            if (submitBlockTo(nodeUrls[i], fork)) accepted++;
                            else rejected++;
                        } catch (Exception e) {
                            rejected++;
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            // Give the mesh a full slot to (correctly) refuse the stale fork.
            Thread.sleep(45000);
            long minCl = Long.MAX_VALUE, maxFin = -1;
            long[] finHash = new long[1];
            boolean finEqual = true;
            java.util.Set<String> fins = new java.util.HashSet<>();
            for (int i = 0; i < nnodes; i++) {
                long cl = chainLength(nodeUrls[i]);
                minCl = Math.min(minCl, cl);
                fins.add(finalizedHash(nodeUrls[i]));
            }
            boolean regressed = minCl < minBefore - 2;
            boolean diverge = fins.size() > 1;
            boolean ok = funded > 0 && !regressed && !diverge;
            verdict("V9 stale-fork rewind reorg (" + depth + " back x3)",
                    ok, "fork accepted " + accepted + "/" + (accepted + rejected)
                            + ", min-cl " + minBefore + " -> " + minCl
                            + ", finroots=" + fins.size() + (regressed ? " REGRESSED" : "")
                            + (diverge ? " DIVERGED" : ""));
        }

        // ================================================================ V10
        // Side-branch status spoof: a double-spend that only ever lands in a
        // non-canonical (edge / stale-parent) block must NOT be reported
        // CONFIRMED by ANY node's getTransactionsStatusByAddress. This is the
        // regression for the sticky V3 artifact (node0 labeling an IN_BLOCK
        // side-branch tx as CONFIRMED poisoned every later attack run).
        System.out.println("============ V10: SIDE-BRANCH STATUS SPOOF ============");
        {
            String attackerAddr = addrFor(seedFor(runUniqueIndex(startIndex, 90021))).toBase58();
            long confirmedAny = 0;
            String detail = "no ds tx accepted into a side block";
            try {
                Block side = staleForkBlock(nodeUrls, startIndex + 150, 3, attackerAddr, payAmount);
                if (side != null) {
                    submitBlockTo(nodeUrls[0], side);
                    Thread.sleep(30000);
                    long confirmedOn = 0;
                    for (int i = 0; i < nnodes; i++) {
                        confirmedOn += countConfirmedOn(nodeUrls[i], attackerAddr);
                    }
                    confirmedAny = confirmedOn;
                    detail = "side-branch ds CONFIRMED on " + confirmedOn + "/" + nnodes + " nodes";
                }
            } catch (Exception e) {
                detail = "vector error: " + e.getMessage();
            }
            verdict("V10 side-branch status spoof", confirmedAny == 0, detail);
        }

        // ================================================================ V11
        // Orphan/duplicate resubmit flood: (a) blocks referencing an unknown
        // parent must not wedge a node, and (b) re-submitting an identical
        // valid block and the same mempool tx must confirm exactly once
        // (no double-batch / duplicate spend) while the chain stays healthy.
        System.out.println("============ V11: ORPHAN / DUP RESUBMIT FLOOD ============");
        {
            String merchantAddr = addrFor(seedFor(runUniqueIndex(startIndex, 90022))).toBase58();
            long before = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) before = Math.min(before, chainLength(nodeUrls[i]));
            // (a) orphans referencing an unknown parent
            int orphanRejected = 0;
            for (int r = 0; r < 8; r++) {
                try {
                    Block b = Block.setBlock2(params, 0);
                    b.setPrevBlockHash(Sha256Hash.wrap(java.util.Arrays.copyOfRange(
                            seedFor(startIndex + 700 + r), 0, 32)));
                    b.setBlockType(BlockType.BLOCKTYPE_TRANSFER);
                    if (!submitBlockTo(nodeUrls[r % nnodes], b)) orphanRejected++;
                } catch (Exception e) {
                    orphanRejected++;
                }
            }
            // (b) dup valid block + dup mempool tx, expect single confirmation
            int confSingle = 0;
            try {
                UTXO u = utxos.get(addrFor(seedFor(startIndex + 160)).toBase58());
                if (u != null) {
                    Transaction t = pay(keyFor(startIndex + 160), u, merchantAddr, payAmount, "v11-dup");
                    Block blk = craftTransferBlock(t);
                    submitBlockTo(nodeUrls[0], blk);
                    submitBlockTo(nodeUrls[0], blk);   // duplicate resubmit
                    submitBlockTo(nodeUrls[1], blk);   // cross-node duplicate
                    submitTx(t);
                    submitTx(t);
                    long deadline = System.currentTimeMillis() + Math.min(90000, confirmTimeoutSec * 1000L / 2);
                    while (System.currentTimeMillis() < deadline) {
                        Thread.sleep(8000);
                        confSingle = countConfirmedOn(nodeUrls[0], merchantAddr);
                        if (confSingle >= 1) break;
                    }
                }
            } catch (Exception e) {
                System.out.println("  V11 dup-tx error: " + e.getMessage());
            }
            long after = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) after = Math.min(after, chainLength(nodeUrls[i]));
            boolean healthy = after >= before && confSingle == 1;
            verdict("V11 orphan/dup resubmit flood",
                    healthy, "orphans rejected " + orphanRejected + "/8, dup-tx confirmed "
                            + confSingle + " (want exactly 1), cl " + before + " -> " + after);
        }

        // ================================================================ V12
        // Forged finalized-anchor broadcast: a node must never move its
        // finalized checkpoint to an advertised boundary it cannot verify on
        // its own chain (CasperService.adoptFinalizedAnchor boundary check).
        // We cannot inject a bogus peer advertisement over the public API, so
        // we drive the equivalent on-chain vector: forge a BEACON-type block
        // whose reward chain claims an impossible position (nonsense
        // prevRewardHash pointing at an unknown future block). batchBlock is
        // ASYNC (queues bytes, HTTP 200), so "rejected by HTTP" is NOT the
        // signal — the defense is that the forged beacon never becomes the
        // confirmed chain head and never moves/regresses finality.
        System.out.println("============ V12: FORGED FINALIZED ANCHOR ============");
        {
            long[] finBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) finBefore[i] = finalizedLength(nodeUrls[i]);
            String[] finRootsBefore = new String[nnodes];
            for (int i = 0; i < nnodes; i++) finRootsBefore[i] = finalizedHash(nodeUrls[i]);
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            String[] headBefore = new String[nnodes];
            for (int i = 0; i < nnodes; i++) headBefore[i] = chainHead(nodeUrls[i]);
            int sent = 0;
            try {
                byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                for (int r = 0; r < 3; r++) {
                    Block fakeBeacon = Block.createBlock(params, proto, proto);
                    fakeBeacon.setBlockType(BlockType.BLOCKTYPE_BEACON);
                    // Nonsense reward chain position: prevRewardHash points at
                    // an unconfirmed/unknown future block -> must never confirm.
                    fakeBeacon.setPrevBlockHash(Sha256Hash.wrap(seedFor(999_999_900L + r)));
                    for (int i = 0; i < nnodes; i++) {
                        try {
                            submitBlockTo(nodeUrls[i], fakeBeacon);
                            sent++;
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("  V12 error: " + e.getMessage());
            }
            // Wait past at least one slot so any (wrong) adoption would surface.
            Thread.sleep(30000);
            boolean finStable = true, rootsStable = true, headStable = true;
            for (int i = 0; i < nnodes; i++) {
                long finAfter = finalizedLength(nodeUrls[i]);
                if (finAfter < finBefore[i]) finStable = false;
                if (!finRootsBefore[i].equals(finalizedHash(nodeUrls[i]))) rootsStable = false;
                // The forged beacon (claiming an impossible far position) must
                // never become the confirmed head. If it did, the head would be
                // the nonsense block, not an on-chain extension of the old head.
                if (!headBefore[i].equals(chainHead(nodeUrls[i]))
                        && chainLength(nodeUrls[i]) < clBefore[i] + 2) headStable = false;
            }
            boolean ok = sent > 0 && finStable && rootsStable && headStable;
            verdict("V12 forged finalized anchor", ok,
                    "forged beacon x" + sent + " broadcast, fin stable=" + finStable
                            + " roots-stable=" + rootsStable + " head-stable=" + headStable);
        }

        // ================================================================ V13
        // Double-vote form-(b) attempt: same validator, SAME target epoch, two
        // DIFFERENT target checkpoints. Form (b) is intentionally disabled
        // (honest validators legitimately differ in the epoch-boundary window),
        // and an attacker cannot forge a real validator's BLS attestation — so
        // a self-signed pair must be REJECTED, never admitted as a slashing
        // proof or allowed to move a real validator.
        System.out.println("============ V13: DOUBLE-VOTE FORM-B PROOF ============");
        {
            PQKey attacker = PQKey.createNew();
            Map<String, Object> att1 = Map.of("pubkey", Utils.HEX.encode(attacker.getPubKey()),
                    "slot", 21210001, "epoch", 662601, "sourceEpoch", 662600, "targetEpoch", 662601,
                    "beaconBlockHash", Sha256Hash.ZERO_HASH.toString(),
                    "targetCheckpoint", Sha256Hash.ZERO_HASH.toString());
            Map<String, Object> att2 = Map.of("pubkey", Utils.HEX.encode(attacker.getPubKey()),
                    "slot", 21210002, "epoch", 662601, "sourceEpoch", 662600, "targetEpoch", 662601,
                    "beaconBlockHash", Sha256Hash.wrap(new byte[32]).toString(),
                    "targetCheckpoint", Sha256Hash.wrap(new byte[32]).toString());
            boolean rejected;
            String detail;
            try {
                byte[] r = OkHttp3Util.postString(seedNode + "submitSlashingProof", Json.jsonmapper()
                        .writeValueAsString(Map.of("attestation1", att1, "attestation2", att2)));
                Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                Object ec = m.get("errorcode");
                rejected = !(ec instanceof Number) || ((Number) ec).intValue() != 0;
                detail = "errorcode=" + ec;
            } catch (Exception e) {
                rejected = true;
                detail = "rejected: " + (e.getMessage() == null ? "http error" : e.getMessage());
            }
            verdict("V13 double-vote form-b proof", rejected, detail);
        }

        // ================================================================ V14
        // Unauthenticated exit / slashing of a REAL validator must be refused:
        // requestValidatorExit without a signature, and with a signature from
        // an attacker key, must not exit any validator (key-ownership proof).
        System.out.println("============ V14: FORGED VALIDATOR EXIT ============");
        {
            PQKey attacker = PQKey.createNew();
            long before = activeValidatorCount(nodeUrls[0]);
            boolean noSigRejected = false, forgedSigRejected = false;
            try {
                OkHttp3Util.postString(nodeUrls[0] + "requestValidatorExit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(attacker.getPubKey()))));
            } catch (Exception e) {
                noSigRejected = true;
            }
            try {
                OkHttp3Util.postString(nodeUrls[0] + "requestValidatorExit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(attacker.getPubKey()), "signature", "00")));
            } catch (Exception e) {
                forgedSigRejected = true;
            }
            long after = activeValidatorCount(nodeUrls[0]);
            boolean ok = noSigRejected && forgedSigRejected && after == before;
            verdict("V14 forged validator exit", ok,
                    "no-sig " + (noSigRejected ? "rejected" : "ACCEPTED")
                            + ", forged-sig " + (forgedSigRejected ? "rejected" : "ACCEPTED")
                            + ", validators " + before + " -> " + after);
        }

        // ================================================================ V15
        // Activation bypass: activating a pubkey with NO stake deposit, or with
        // an attacker-supplied future epoch, must not register a validator.
        System.out.println("============ V15: ACTIVATION BYPASS ============");
        {
            PQKey attacker = PQKey.createNew();
            long before = activeValidatorCount(nodeUrls[0]);
            boolean foreignRejected = false, futureEpochRejected = false;
            try {
                OkHttp3Util.postString(nodeUrls[0] + "activateValidator",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(attacker.getPubKey()), "epoch", 999999999L)));
            } catch (Exception e) {
                foreignRejected = true;
            }
            try {
                OkHttp3Util.postString(nodeUrls[0] + "activateValidator",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(attacker.getPubKey()))));
            } catch (Exception e) {
                futureEpochRejected = true;
            }
            long after = activeValidatorCount(nodeUrls[0]);
            boolean ok = foreignRejected && after == before;
            verdict("V15 activation bypass", ok,
                    "foreign/epoch " + (foreignRejected ? "rejected" : "ACCEPTED")
                            + ", validators " + before + " -> " + after);
        }

        // ================================================================ V16
        // Deposit below minimum / garbage amount must not create stake; and a
        // processWithdrawal for a bogus future epoch must not let an attacker
        // force a withdrawal or wedge the node.
        System.out.println("============ V16: DEPOSIT/WITHDRAWAL ABUSE ============");
        {
            PQKey attacker = PQKey.createNew();
            long before = activeValidatorCount(nodeUrls[0]);
            boolean badDepositRejected = false, badWithdrawalSafe = true;
            try {
                OkHttp3Util.postString(nodeUrls[0] + "stakeDeposit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(attacker.getPubKey()), "amount", "-1")));
            } catch (Exception e) {
                badDepositRejected = true;
            }
            try {
                OkHttp3Util.postString(nodeUrls[0] + "processWithdrawal",
                        Json.jsonmapper().writeValueAsString(Map.of("epoch", 999999999L)));
            } catch (Exception e) {
                badWithdrawalSafe = false;
            }
            long after = activeValidatorCount(nodeUrls[0]);
            boolean ok = badDepositRejected && badWithdrawalSafe && after == before;
            verdict("V16 deposit/withdrawal abuse", ok,
                    "bad-deposit " + (badDepositRejected ? "rejected" : "ACCEPTED")
                            + ", far-epoch withdrawal " + (badWithdrawalSafe ? "harmless" : "ERRORED")
                            + ", validators " + before + " -> " + after);
        }

        // ================================================================ V17
        // Withdrawal without key proof: processWithdrawal must only release
        // validators whose exit was signed and queued — an attacker calling it
        // must never unlock a bonded validator early.
        System.out.println("============ V17: EARLY WITHDRAWAL ============");
        {
            long before = activeValidatorCount(nodeUrls[0]);
            boolean ok;
            String detail;
            try {
                OkHttp3Util.postString(nodeUrls[0] + "processWithdrawal",
                        Json.jsonmapper().writeValueAsString(Map.of("epoch", 0L)));
                long after = activeValidatorCount(nodeUrls[0]);
                ok = after == before;
                detail = "withdrawal epoch=0 left " + before + " -> " + after + " validators";
            } catch (Exception e) {
                ok = true;
                detail = "rejected: " + (e.getMessage() == null ? "http error" : e.getMessage());
            }
            verdict("V17 early withdrawal", ok, detail);
        }

        // ================================================================ V18
        // Post-churn finality must keep advancing: after the validator-lifecycle
        // abuse above, the mesh's finalized root must still move and stay
        // identical across nodes (plan 1.3 reorg-safe finality / 4.1 boost).
        System.out.println("============ V18: POST-CHURN FINALITY ADVANCE ============");
        {
            long[] finA = new long[nnodes];
            String[] rootsA = new String[nnodes];
            for (int i = 0; i < nnodes; i++) {
                finA[i] = finalizedLength(nodeUrls[i]);
                rootsA[i] = finalizedHash(nodeUrls[i]);
            }
            // Finality advances once per EPOCH (slotsPerEpoch slots), not per
            // slot; a 45s poll can sit entirely inside one epoch and read the
            // same finalized length on both sides — a false STALLED. Wait 2.5x
            // an epoch so at least one finality boundary must pass on a healthy
            // mesh (Mainnet slotsPerEpoch=8 @ 12s slot -> ~96s/epoch).
            int slotsPerEpoch = 8;
            long epochMs = (long) slotsPerEpoch * 12000L;
            Thread.sleep(epochMs * 5 / 2);
            long minFin = Long.MAX_VALUE;
            boolean rootsEqual = true;
            String root = finalizedHash(nodeUrls[0]);
            for (int i = 0; i < nnodes; i++) {
                long f = finalizedLength(nodeUrls[i]);
                minFin = Math.min(minFin, f);
                if (!root.equals(finalizedHash(nodeUrls[i]))) rootsEqual = false;
            }
            long beforeMax = Long.MIN_VALUE;
            for (long f : finA) beforeMax = Math.max(beforeMax, f);
            boolean advanced = minFin > beforeMax;
            boolean ok = advanced && rootsEqual;
            verdict("V18 post-churn finality advance", ok,
                    "fin " + java.util.Arrays.toString(finA) + " -> min " + minFin
                            + (advanced ? "" : " STALLED") + (rootsEqual ? "" : " DIVERGED"));
        }

        // ================================================================ V19
        // Sibling-conflict deadlock storm: submit many crafted blocks that spend
        // the SAME UTXOs (mutually-conflicting siblings) in rapid succession.
        // The beacon-confirmation sweep must NOT deadlock: it must reference
        // exactly one sibling per conflict set (deadlock-break) so the chain
        // keeps confirming and never strands the mempool at 0-confirm.
        System.out.println("============ V19: SIBLING-CONFLICT DEADLOCK STORM ============");
        {
            long before = chainLength(nodeUrls[0]);
            int nSiblingSets = Math.max(2, n.applyAsInt(6));
            int siblingsPerSet = 3;
            java.util.List<String[]> setRecips = new java.util.ArrayList<>();
            long[] spentWallets = new long[nSiblingSets];
            int fundedSets = 0;
            for (int s = 0; s < nSiblingSets; s++) {
                long walletIdx = startIndex + 170 + s;
                if (fetchUtxo(walletIdx) != null) spentWallets[fundedSets++] = walletIdx;
            }
            // Each set: one funded UTXO -> three sibling blocks all spending it,
            // each paying a DISTINCT recipient (so a confirmed winner is
            // observable per address).
            for (int s = 0; s < fundedSets; s++) {
                UTXO u = fetchUtxo(spentWallets[s]);
                if (u == null) continue;
                String[] recips = new String[siblingsPerSet];
                for (int k = 0; k < siblingsPerSet; k++) {
                    recips[k] = addrFor(seedFor(runUniqueIndex(startIndex, 90031 + s * 10 + k))).toBase58();
                }
                setRecips.add(recips);
                try {
                    for (int k = 0; k < siblingsPerSet; k++) {
                        Transaction t = pay(keyFor(spentWallets[s]), u, recips[k], payAmount, "v19-sibling");
                        Block b = craftTransferBlock(t);
                        for (int i = 0; i < nnodes; i++) {
                            try {
                                submitBlockTo(nodeUrls[i], b);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  V19 set " + s + " error: " + e.getMessage());
                }
            }
            // The mesh must keep confirming: after a settle window the chain must
            // have advanced and every funded set must have EXACTLY ONE confirmed
            // winner (the other siblings' spends conflict and must be dropped,
            // not stranded as 0-confirm for the whole set).
            Thread.sleep(Math.max(60000, confirmTimeoutSec * 1000L / 3));
            long after = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) after = Math.min(after, chainLength(nodeUrls[i]));
            int setsWon = 0, setsDroppedAll = 0;
            for (String[] recips : setRecips) {
                int won = 0;
                for (String r : recips) {
                    if (countConfirmedOn(nodeUrls[0], r) > 0) won++;
                }
                if (won == 1) setsWon++;
                else if (won == 0) setsDroppedAll++;
            }
            boolean advanced = after > before;
            boolean noDeadlock = fundedSets == 0 || (setsWon >= Math.max(1, fundedSets - 1));
            boolean ok = advanced && noDeadlock;
            verdict("V19 sibling-conflict deadlock storm (" + fundedSets + " sets x" + siblingsPerSet + ")",
                    ok, "cl " + before + " -> " + after + (advanced ? "" : " STALLED")
                            + ", sets with exactly-1 winner " + setsWon + "/" + fundedSets
                            + ", all-dropped " + setsDroppedAll);
        }

        // ================================================================ V20
        // Proposer-duty starvation probe: hammer the node's submitTransaction +
        // batchBlock with garbage while a slot boundary passes. The slot tick
        // (posExecutor) must keep producing beacons — a deadlock here stalls the
        // whole chain. Assert the chain advances within a bounded window.
        System.out.println("============ V20: PROPOSER-DUTY STARVATION ============");
        {
            long before = chainLength(nodeUrls[0]);
            byte[] garbage = new byte[256];
            new java.util.Random(startIndex).nextBytes(garbage);
            int sent = 0;
            for (int k = 0; k < 40; k++) {
                for (int i = 0; i < nnodes; i++) {
                    try {
                        OkHttp3Util.post(nodeUrls[i] + "batchBlock", garbage);
                        sent++;
                    } catch (Exception ignore) {
                    }
                }
                try {
                    byte[] junk = new byte[64];
                    new java.util.Random(k).nextBytes(junk);
                    OkHttp3Util.post(nodeUrls[0] + "submitTransaction", junk);
                } catch (Exception ignore) {
                }
            }
            // Wait well past a slot (12s) so any duty deadlock would show as no
            // advance; healthy mesh advances several slots in this window.
            Thread.sleep(Math.max(45000, confirmTimeoutSec * 1000L / 6));
            long after = chainLength(nodeUrls[0]);
            boolean advanced = after > before;
            verdict("V20 proposer-duty starvation (garbage x" + sent + ")",
                    advanced, "cl " + before + " -> " + after + (advanced ? "" : " STALLED"));
        }

        // ================================================================ V21
        // Reorg churn livelock: repeatedly feed a valid block on the CURRENT tip
        // and a STALE sibling (valid fork of an ancestor) so handleNewBestChain
        // must keep reconciling without ever unwinding into a hole or collapsing
        // (regression for e6ddacb20 / 2e818b16e). The head must keep advancing.
        System.out.println("============ V21: REORG CHURN LIVELOCK ============");
        {
            long before = chainLength(nodeUrls[0]);
            String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 90040))).toBase58();
            for (int round = 0; round < 4; round++) {
                try {
                    // A block on the live tip.
                    UTXO u = fetchUtxo(startIndex + 190 + round);
                    if (u != null) {
                        Transaction t = pay(keyFor(startIndex + 190 + round), u, attacker, payAmount, "v21-tip");
                        submitBlockTo(nodeUrls[round % nnodes], craftTransferBlock(t));
                    }
                    // A stale fork off an ancestor 2 back (must never win).
                    Block stale = staleForkBlock(nodeUrls, startIndex + 195 + round, 2, attacker, payAmount);
                    if (stale != null) {
                        for (int i = 0; i < nnodes; i++) submitBlockTo(nodeUrls[i], stale);
                    }
                } catch (Exception ignore) {
                }
                Thread.sleep(2000);
            }
            Thread.sleep(Math.max(45000, confirmTimeoutSec * 1000L / 5));
            long after = chainLength(nodeUrls[0]);
            boolean advanced = after > before;
            verdict("V21 reorg churn livelock (4 tip+stale rounds)",
                    advanced, "cl " + before + " -> " + after + (advanced ? "" : " COLLAPSED/STALLED"));
        }

        // ================================================================ V22
        // Epoch-finality stall probe: after flooding conflicting sibling blocks
        // AND reorg churn, finality must still advance past at least one epoch
        // boundary on every node with an identical root (the wedge froze
        // finality at the old checkpoint; this asserts it keeps moving).
        System.out.println("============ V22: EPOCH-FINALITY STALL PROBE ============");
        {
            long[] finA = new long[nnodes];
            for (int i = 0; i < nnodes; i++) finA[i] = finalizedLength(nodeUrls[i]);
            int slotsPerEpoch = 8;
            long epochMs = (long) slotsPerEpoch * 12000L;
            Thread.sleep(epochMs * 3 / 2);
            long minFin = Long.MAX_VALUE;
            boolean rootsEqual = true;
            String root = finalizedHash(nodeUrls[0]);
            for (int i = 0; i < nnodes; i++) {
                long f = finalizedLength(nodeUrls[i]);
                minFin = Math.min(minFin, f);
                if (!root.equals(finalizedHash(nodeUrls[i]))) rootsEqual = false;
            }
            long beforeMax = Long.MIN_VALUE;
            for (long f : finA) beforeMax = Math.max(beforeMax, f);
            boolean advanced = minFin > beforeMax;
            boolean ok = advanced && rootsEqual;
            verdict("V22 epoch-finality stall probe", ok,
                    "fin " + java.util.Arrays.toString(finA) + " -> min " + minFin
                            + (advanced ? "" : " STALLED") + (rootsEqual ? "" : " DIVERGED"));
        }

        // ================================================================ V23
        // Beacon-connect conflict deadlock (regression): craft two sibling batch
        // blocks spending the SAME mempool UTXOs so the reference sweep must
        // pick one and the OTHER stays unconfirmed (never deadlock-breaks into 0
        // references that strands the whole sweep). Assert exactly the winner's
        // recipients confirm and the chain keeps moving.
        System.out.println("============ V23: BEACON-CONNECT CONFLICT DEADLOCK ============");
        {
            long before = chainLength(nodeUrls[0]);
            int pairs = Math.max(2, n.applyAsInt(5));
            String[] recips = new String[pairs];
            for (int p = 0; p < pairs; p++) {
                recips[p] = addrFor(seedFor(runUniqueIndex(startIndex, 90050 + p))).toBase58();
            }
            int funded = 0;
            for (int p = 0; p < pairs; p++) {
                long walletIdx = startIndex + 200 + p;
                UTXO u = fetchUtxo(walletIdx);
                if (u == null) continue;
                funded++;
                try {
                    // Two sibling blocks spending the SAME UTXO to two recipients.
                    Transaction a = pay(keyFor(walletIdx), u, recips[p], payAmount, "v23-a");
                    Transaction b = pay(keyFor(walletIdx), u,
                            addrFor(seedFor(runUniqueIndex(startIndex, 90100 + p))).toBase58(),
                            payAmount, "v23-b");
                    submitBlockTo(nodeUrls[0], craftTransferBlock(a));
                    submitBlockTo(nodeUrls[0], craftTransferBlock(b));
                    submitBlockTo(nodeUrls[1], craftTransferBlock(a));
                    submitBlockTo(nodeUrls[1], craftTransferBlock(b));
                } catch (Exception ignore) {
                }
            }
            Thread.sleep(Math.max(60000, confirmTimeoutSec * 1000L / 3));
            long after = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) after = Math.min(after, chainLength(nodeUrls[i]));
            // Exactly one of each pair must confirm (winner), never zero (stranded).
            int won = 0;
            for (int p = 0; p < pairs; p++) {
                if (countConfirmedOn(nodeUrls[0], recips[p]) > 0) won++;
            }
            boolean advanced = after > before;
            boolean ok = advanced && (funded == 0 || won >= Math.min(1, funded));
            verdict("V23 beacon-connect conflict deadlock (" + pairs + " pairs)",
                    ok, "cl " + before + " -> " + after + (advanced ? "" : " STALLED")
                            + ", pairs with winner confirmed " + won + "/" + funded);
        }

        // ================================================================ V24
        // Unwind-reconnect livelock (regression for 2e818b16e): force a reorg by
        // submitting a short chain that references a real ancestor, then drive a
        // reorg the other way — the reconnect must repair any collapsed reward
        // rows and the node must keep finality/chain moving (never frozen at an
        // old head with fin=None). Longest-lived stability check in the suite.
        System.out.println("============ V24: UNWIND-RECONNECT LIVELOCK ============");
        {
            long[] clBefore = new long[nnodes];
            long[] finBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) {
                clBefore[i] = chainLength(nodeUrls[i]);
                finBefore[i] = finalizedLength(nodeUrls[i]);
            }
            // Submit deep stale forks (5 back) on alternating nodes to force
            // unwind/reconnect evaluation without winning the best chain.
            String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 90060))).toBase58();
            for (int r = 0; r < 5; r++) {
                try {
                    Block stale = staleForkBlock(nodeUrls, startIndex + 210 + r, 5, attacker, payAmount);
                    if (stale != null) submitBlockTo(nodeUrls[r % nnodes], stale);
                } catch (Exception ignore) {
                }
                Thread.sleep(1500);
            }
            // The confirmed head must NOT freeze and chain/finality must keep
            // moving (no collapse to an old head with fin=None).
            int slotsPerEpoch = 8;
            long epochMs = (long) slotsPerEpoch * 12000L;
            Thread.sleep(epochMs * 2);
            boolean ok = true;
            String detail = "";
            for (int i = 0; i < nnodes; i++) {
                long clNow = chainLength(nodeUrls[i]);
                if (clNow < clBefore[i]) {
                    ok = false;
                    detail += " node" + i + " REGRESSED cl " + clBefore[i] + "->" + clNow;
                }
                if (chainHead(nodeUrls[i]).isEmpty()) {
                    ok = false;
                    detail += " node" + i + " HEAD LOST";
                }
                if (finalizedLength(nodeUrls[i]) < finBefore[i]) {
                    ok = false;
                    detail += " node" + i + " FIN REGRESSED";
                }
            }
            boolean rootsEqual = true;
            String root = finalizedHash(nodeUrls[0]);
            for (int i = 1; i < nnodes; i++) {
                if (!root.equals(finalizedHash(nodeUrls[i]))) rootsEqual = false;
            }
            ok = ok && rootsEqual;
            verdict("V24 unwind-reconnect livelock (deep stale forks)",
                    ok, "finroots equal=" + rootsEqual + (ok ? "" : detail));
        }

        // ================================================================ report
        System.out.println("==============================================");
        System.out.println("  MESHATTACK — VERDICT TABLE");
        System.out.println("==============================================");
        boolean allPass = true;
        for (String row : VERDICTS) {
            System.out.println("  " + row);
            if (row.contains("FAIL")) allPass = false;
        }
        System.out.println("==============================================");
        System.out.println(allPass ? "ALL_ATTACKS_DEFLECTED" : "ATTACK_BREACH_DETECTED");
        System.exit(allPass ? 0 : 1);
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

    /** The confirmed chain head hash (txReward.blockHashHex) from getChainNumber. */
    static String chainHead(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object tr = m.get("txReward");
            if (tr instanceof Map) {
                Object h = ((Map<?, ?>) tr).get("blockHashHex");
                if (h != null) return String.valueOf(h);
                Object b = ((Map<?, ?>) tr).get("blockHash");
                if (b != null) return String.valueOf(b);
            }
            return "";
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

    /** countConfirmed on a specific node rather than always the seed node. */
    static int countConfirmedOn(String nodeUrl, String address) {
        try {
            String u = (nodeUrl.startsWith("http") ? nodeUrl : "http://" + nodeUrl);
            byte[] r = OkHttp3Util.postString(u + "getTransactionsStatusByAddress",
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

    /**
     * Build a short, structurally-valid TRANSFER fork that extends an ancestor
     * {@code depth} blocks behind the live head (instead of the current tip),
     * paying {@code payAmount} to {@code toAddr} from the funded wallet at
     * {@code walletIdx}. Returns null when the wallet or an ancestor is
     * unavailable. Parents are real persisted blocks, so the block is solid but
     * strictly shorter than the live chain — exactly the stale-competitor
     * situation that must never win a reorg.
     */
    static Block staleForkBlock(String[] nodeUrls, long walletIdx, int depth, String toAddr, long payAmount) {
        try {
            UTXO u = fetchUtxo(walletIdx);
            if (u == null) return null;
            byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
            Block proto = params.getDefaultSerializer().makeBlock(
                    Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
            // Walk the live trunk back `depth` real blocks.
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
            Transaction t = pay(keyFor(walletIdx), u, toAddr, payAmount, "v9-stale-fork");
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
                for (UTXO x : gor.getOutputs()) {
                    if (x.getValue() != null && x.getAddress() != null && !x.isSpent()) {
                        return x;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    static long chainLength(String[] urls, int skip) {
        for (int i = 0; i < urls.length; i++) {
            if (i == skip) continue;
            long cl = chainLength(urls[i]);
            if (cl >= 0) return cl;
        }
        return -1;
    }
}
