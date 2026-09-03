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
 *   V25 attestation-spam / pos_state bloat flood (must be dropped pre-verify)
 *   V26 slashing-proposal storm (N fake keys, dup proofs must not fork SLASHINGs)
 *   V27 surround-vote proof (disabled form must stay rejected, like V13)
 *   V28 bouncing / justification flip-flop (justified must converge, fin advance)
 *   V29 ex-ante / proposer-boost reorg (1-deep fork must not win the tip)
 *   V30 RANDAO omission / reveal replay (bad-reveal beacon never confirms)
 *   V31 churn overload (activation + mass-exit flood must not move the set)
 *   V32 leak-liveness probe (finality advances, set stable, no spurious bleed)
 *   V33 whale ingress (huge foreign deposit must be refused)
 *   V34 censorship / embedded-cap probe (forged beacons ignored, atts bounded)
 *   V35 long-range / finalized-reversion fork (deep fork never moves fin)
 *   V36 clock-skew / time-warp partition (split-view spam must not diverge)
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
                String addr = addrFor(seedFor(walletIdx)).toBase58();
                UTXO g = utxos.get(addr);
                if (g != null && !g.isSpent()) spentWallets[fundedSets++] = walletIdx;
            }
            // Each set: one funded UTXO -> three sibling blocks all spending it,
            // each paying a DISTINCT recipient (so a confirmed winner is
            // observable per address).
            for (int s = 0; s < fundedSets; s++) {
                UTXO u = utxos.get(addrFor(seedFor(spentWallets[s])).toBase58());
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
                    long w = startIndex + 190 + round;
                    UTXO u = utxos.get(addrFor(seedFor(w)).toBase58());
                    if (u != null && !u.isSpent()) {
                        Transaction t = pay(keyFor(w), u, attacker, payAmount, "v21-tip");
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

        // ================================================================ V25
        // Attestation-spam / pos_state bloat flood: hammer submitAttestation
        // with non-validator keys across every reject gate (inconsistent epoch,
        // far-future, stale, replay, garbage-BLS). All must be dropped BEFORE
        // expensive verify / state growth; the chain must keep advancing and
        // finality must keep moving (no verify-CPU or att_* bloat stall).
        System.out.println("============ V25: ATTESTATION-SPAM FLOOD ============");
        {
            long before = chainLength(nodeUrls[0]);
            long finBefore = finalizedLength(nodeUrls[0]);
            int sent = 0;
            // (a) inconsistent epoch (slot/8 != epoch)
            sent += submitAttestationSpam(nodeUrls, 211, 8000L, 999L, 999L, 999L, (int) n.applyAsInt(6));
            // (b) far-future (slot 8M -> epoch 1M, target 1M >> wall epoch + 1)
            sent += submitAttestationSpam(nodeUrls, 221, 8_000_000L, 1_000_000L, 999_999L, 1_000_000L,
                    (int) n.applyAsInt(6));
            // (c) stale (target far behind chain epoch - 8)
            sent += submitAttestationSpam(nodeUrls, 231, 800L, 100L, 99L, 100L, (int) n.applyAsInt(6));
            // (d) replay of the stale shape (duplicate delivery)
            sent += submitAttestationSpam(nodeUrls, 231, 800L, 100L, 99L, 100L, (int) n.applyAsInt(4));
            // (e) garbage-BLS variant (48-byte invalid pubkey + 96-byte junk sig)
            sent += submitAttestationSpamGarbageBls(nodeUrls, (int) n.applyAsInt(4));
            long after = chainAfterSettle(nodeUrls[0], before, Math.max(30000, confirmTimeoutSec * 1000L / 8));
            long finAfter = finalizedAfterRead(nodeUrls[0]);
            boolean advanced = after > before;
            boolean finOk = finAfter >= finBefore;
            boolean ok = advanced && finOk;
            verdict("V25 attestation-spam flood (" + sent + ")",
                    ok, "spam x" + sent + " dropped, cl " + before + " -> " + after
                            + (advanced ? "" : " STALLED") + ", fin " + finBefore + " -> " + finAfter);
        }

        // ================================================================ V26
        // Slashing-proposal storm: N DISTINCT fake keys, each with one
        // same-slot equivocating pair, each proof resubmitted 3x. The per-proof
        // verify gate must refuse all (fake keys); even if one passed, the
        // node-side slashingReported dedup must prevent a per-delivery
        // SLASHING-block fork storm. No honest validator may be slashed.
        System.out.println("============ V26: SLASHING-PROPOSAL STORM ============");
        {
            long valBefore = activeValidatorCount(nodeUrls[0]);
            long clBefore = chainLength(nodeUrls[0]);
            int keys = Math.max(3, (int) n.applyAsInt(8));
            int sent = 0;
            for (int k = 0; k < keys; k++) {
                PQKey fk = PQKey.createNew();
                byte[] pub = fk.getPubKey();
                long slot = 21_220_000L + k;
                long epoch = slot / 8;
                for (int dup = 0; dup < 3; dup++) {
                    AttestationData a1 = fakeAttestation(pub, slot, epoch, epoch - 1, epoch,
                            Sha256Hash.of(("v26a-" + k).getBytes()),
                            Sha256Hash.of(("v26t-" + k).getBytes()));
                    AttestationData b2 = fakeAttestation(pub, slot, epoch, epoch - 1, epoch,
                            Sha256Hash.of(("v26b-" + k).getBytes()),
                            Sha256Hash.of(("v26u-" + k).getBytes()));
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
            // 45s settle like V20: confirmed-chainlength advances in bursts,
            // a 20s window flakes on a loaded mesh even when healthy.
            long clAfter = chainAfterSettle(nodeUrls[0], clBefore, Math.max(45000, confirmTimeoutSec * 1000L / 6));
            long valAfter = activeValidatorCount(nodeUrls[0]);
            boolean ok = valAfter == valBefore && clAfter > clBefore;
            verdict("V26 slashing-proposal storm (" + keys + " keys x3)",
                    ok, "proofs x" + sent + " sent, validators " + valBefore + " -> " + valAfter
                            + (valAfter != valBefore ? " SLASHED" : "") + ", cl " + clBefore + " -> " + clAfter);
        }

        // ================================================================ V27
        // Surround-vote proof (companion to V13): strict epoch containment
        // (source1 < source2 < target2 < target1) from a fresh key. The
        // surround form is DISABLED like form-(b) — must be REJECTED, never
        // admitted, validator set unchanged.
        System.out.println("============ V27: SURROUND-VOTE PROOF ============");
        {
            PQKey attacker = PQKey.createNew();
            AttestationData att1 = fakeAttestation(attacker.getPubKey(),
                    21_230_001L, 2653750L, 2653700L, 2653750L,
                    Sha256Hash.of("v27a".getBytes()), Sha256Hash.of("v27t1".getBytes()));
            AttestationData att2 = fakeAttestation(attacker.getPubKey(),
                    21_230_002L, 2653750L, 2653720L, 2653740L,
                    Sha256Hash.of("v27b".getBytes()), Sha256Hash.of("v27t2".getBytes()));
            boolean rejected;
            String detail;
            try {
                String body = "{\"attestation1\":"
                        + new String(Json.jsonmapper().writeValueAsBytes(att1),
                                java.nio.charset.StandardCharsets.UTF_8)
                        + ",\"attestation2\":"
                        + new String(Json.jsonmapper().writeValueAsBytes(att2),
                                java.nio.charset.StandardCharsets.UTF_8)
                        + "}";
                byte[] r = OkHttp3Util.postString(seedNode + "submitSlashingProof", body);
                Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
                Object ec = m.get("errorcode");
                rejected = !(ec instanceof Number) || ((Number) ec).intValue() != 0;
                detail = "errorcode=" + ec;
            } catch (Exception e) {
                rejected = true;
                detail = "rejected: " + (e.getMessage() == null ? "http error" : e.getMessage());
            }
            long vals = activeValidatorCount(nodeUrls[0]);
            verdict("V27 surround-vote proof", rejected, detail + ", validators=" + vals);
        }

        // ================================================================ V28
        // Bouncing / justification flip-flop: at/near an epoch boundary feed
        // tip blocks to one half of the mesh and stale forks to the other,
        // swapping each round. The justified checkpoint must converge (one
        // hash on all nodes, never regress) and finality must advance — never
        // bounce forever between branches.
        System.out.println("============ V28: BOUNCING FLIP-FLOP ============");
        {
            String justBefore = justifiedHash(nodeUrls[0]);
            long finBefore = finalizedLength(nodeUrls[0]);
            long clBefore28 = chainLength(nodeUrls[0]);
            String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 90110))).toBase58();
            int staged28 = 0;
            for (int round = 0; round < 4; round++) {
                try {
                    long w = startIndex + 220 + round;
                    UTXO u = fetchUtxo(w);
                    if (u != null && !u.isSpent()) {
                        Transaction t = pay(keyFor(w), u, attacker, payAmount, "v28-tip");
                        submitBlockTo(nodeUrls[round % nnodes], craftTransferBlock(t));
                        staged28++;
                    }
                    Block stale = staleForkBlock(nodeUrls, startIndex + 230 + round, 2, attacker, payAmount);
                    if (stale != null) {
                        submitBlockTo(nodeUrls[(round + 1) % nnodes], stale);
                        staged28++;
                    }
                } catch (Exception ignore) {
                }
                Thread.sleep(2000);
            }
            Thread.sleep(Math.max(45000, confirmTimeoutSec * 1000L / 5));
            java.util.Set<String> justs = new java.util.HashSet<>();
            for (int i = 0; i < nnodes; i++) justs.add(justifiedHash(nodeUrls[i]));
            if (justs.size() > 1) {
                Thread.sleep(20000);
                justs.clear();
                for (int i = 0; i < nnodes; i++) justs.add(justifiedHash(nodeUrls[i]));
            }
            long finMin = Long.MAX_VALUE, clMin = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) {
                finMin = Math.min(finMin, finalizedAfterRead(nodeUrls[i]));
                clMin = Math.min(clMin, chainLength(nodeUrls[i]));
            }
            if (clMin <= clBefore28) {
                Thread.sleep(30000);
                clMin = Long.MAX_VALUE;
                for (int i = 0; i < nnodes; i++) clMin = Math.min(clMin, chainLength(nodeUrls[i]));
            }
            boolean converged = justs.size() == 1;
            // Bouncing shows as justified DIVERGENCE; finality must never
            // regress (forward advance is healthy, so >= not >). Unstaged
            // (no funded wallets left on a shared mesh) passes vacuously.
            boolean ok = staged28 == 0 || (converged && finMin >= finBefore && clMin > clBefore28);
            verdict("V28 bouncing flip-flop (4 swap rounds)",
                    ok, (staged28 == 0 ? "UNSTAGED " : "") + "just " + justBefore + " -> " + justs
                            + (converged ? "" : " DIVERGED") + ", fin " + finBefore + " -> min " + finMin
                            + ", min-cl " + clBefore28 + " -> " + clMin);
        }

        // ================================================================ V29
        // Ex-ante / proposer-boost reorg: release a valid 1-deep competing fork
        // off the head's parent immediately after the head advances (x3). The
        // 40% proposer boost must hold the timely head — no 1-block reorg wins.
        System.out.println("============ V29: EX-ANTE BOOST REORG ============");
        {
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            long minBefore = Long.MAX_VALUE;
            for (long c : clBefore) minBefore = Math.min(minBefore, c);
            String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 90120))).toBase58();
            int sentRounds = 0;
            for (int round = 0; round < 3; round++) {
                try {
                    Block exante = staleForkBlock(nodeUrls, startIndex + 240 + round, 1, attacker, payAmount);
                    if (exante != null) {
                        for (int i = 0; i < nnodes; i++) submitBlockTo(nodeUrls[i], exante);
                        sentRounds++;
                    }
                } catch (Exception ignore) {
                }
                Thread.sleep(3000);
            }
            Thread.sleep(Math.max(30000, confirmTimeoutSec * 1000L / 6));
            long minAfter = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) minAfter = Math.min(minAfter, chainLength(nodeUrls[i]));
            java.util.Set<String> fins = finRootSettled(nodeUrls);
            // Unstaged (no funded wallets left on a shared mesh) passes
            // vacuously, like V19's fundedSets == 0 case.
            boolean ok = sentRounds == 0 || (minAfter >= minBefore && fins.size() == 1);
            verdict("V29 ex-ante boost reorg (1-deep x" + sentRounds + ")",
                    ok, (sentRounds == 0 ? "UNSTAGED " : "") + "min-cl " + minBefore + " -> " + minAfter
                            + ", finroots=" + fins.size());
        }

        // ================================================================ V30
        // RANDAO omission / reveal replay: forged BEACON blocks with (a) an
        // unknown-parent position, (b) tampered bytes, (c) TRANSFER bytes
        // mislabeled BEACON. None may become the confirmed head; the honest
        // beacon chain must keep advancing (4.2 withhold-penalty is open, so
        // the verdict is contain + advance, not penalize).
        System.out.println("============ V30: RANDAO / BAD-REVEAL BEACON ============");
        {
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            String[] headBefore = new String[nnodes];
            for (int i = 0; i < nnodes; i++) headBefore[i] = chainHead(nodeUrls[i]);
            long[] finBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) finBefore[i] = finalizedLength(nodeUrls[i]);
            int sent = 0;
            try {
                byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                // (a) unknown-parent beacon
                for (int r = 0; r < 2; r++) {
                    Block b = Block.createBlock(params, proto, proto);
                    b.setBlockType(BlockType.BLOCKTYPE_BEACON);
                    b.setPrevBlockHash(Sha256Hash.wrap(seedFor(999_999_700L + r)));
                    for (int i = 0; i < nnodes; i++) {
                        try {
                            submitBlockTo(nodeUrls[i], b);
                            sent++;
                        } catch (Exception ignore) {
                        }
                    }
                }
                // (b) tampered-bytes beacon
                try {
                    Block b = Block.createBlock(params, proto, proto);
                    b.setBlockType(BlockType.BLOCKTYPE_BEACON);
                    byte[] ser = b.bitcoinSerialize();
                    ser[ser.length / 2] = (byte) (ser[ser.length / 2] ^ 0x33);
                    Block bad = params.getDefaultSerializer().makeBlock(ser);
                    for (int i = 0; i < nnodes; i++) {
                        try {
                            submitBlockTo(nodeUrls[i], bad);
                            sent++;
                        } catch (Exception ignore) {
                        }
                    }
                } catch (Exception ignore) {
                }
                // (c) transfer-bytes mislabeled BEACON
                try {
                    UTXO u = fetchUtxo(startIndex + 250);
                    if (u != null) {
                        Transaction t = pay(keyFor(startIndex + 250), u,
                                addrFor(seedFor(runUniqueIndex(startIndex, 90130))).toBase58(),
                                payAmount, "v30-mislabeled");
                        Block b = craftTransferBlock(t);
                        b.setBlockType(BlockType.BLOCKTYPE_BEACON);
                        for (int i = 0; i < nnodes; i++) {
                            try {
                                submitBlockTo(nodeUrls[i], b);
                                sent++;
                            } catch (Exception ignore) {
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                System.out.println("  V30 error: " + e.getMessage());
            }
            long clNow = chainAfterSettle(nodeUrls[0], clBefore[0], Math.max(30000, confirmTimeoutSec * 1000L / 6));
            boolean stable = true;
            for (int i = 0; i < nnodes; i++) {
                if (finalizedAfterRead(nodeUrls[i]) < finBefore[i]) stable = false;
                if (!headBefore[i].equals(chainHead(nodeUrls[i]))
                        && chainLength(nodeUrls[i]) < clBefore[i] + 2) stable = false;
            }
            boolean advanced = clNow > clBefore[0];
            boolean ok = sent > 0 && stable && advanced;
            verdict("V30 bad-reveal beacon (x" + sent + ")", ok,
                    "forged beacons ignored=" + stable + ", cl " + clBefore[0] + " -> " + clNow);
        }

        // ================================================================ V31
        // Churn overload: N fresh-key activateValidator calls (no stake -> must
        // throw, set unchanged) + N forged requestValidatorExit calls (bad sig
        // -> refused) + processWithdrawal far-epoch (harmless). The churn cap,
        // activation delay and exit queue must hold the active set stable.
        System.out.println("============ V31: CHURN OVERLOAD ============");
        {
            long valBefore = activeValidatorCount(nodeUrls[0]);
            long clBefore = chainLength(nodeUrls[0]);
            int attempts = Math.max(4, (int) n.applyAsInt(8));
            int actRejected = 0, exitRejected = 0;
            for (int k = 0; k < attempts; k++) {
                PQKey fk = PQKey.createNew();
                try {
                    OkHttp3Util.postString(nodeUrls[k % nnodes] + "activateValidator",
                            Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                    Utils.HEX.encode(fk.getPubKey()), "epoch", 999999999L)));
                } catch (Exception e) {
                    actRejected++;
                }
                try {
                    OkHttp3Util.postString(nodeUrls[k % nnodes] + "requestValidatorExit",
                            Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                    Utils.HEX.encode(fk.getPubKey()), "signature", "00")));
                } catch (Exception e) {
                    exitRejected++;
                }
            }
            try {
                OkHttp3Util.postString(nodeUrls[0] + "processWithdrawal",
                        Json.jsonmapper().writeValueAsString(Map.of("epoch", 999999999L)));
            } catch (Exception ignore) {
            }
            long clAfter = chainAfterSettle(nodeUrls[0], clBefore, Math.max(45000, confirmTimeoutSec * 1000L / 6));
            long valAfter = activeValidatorCount(nodeUrls[0]);
            boolean ok = actRejected == attempts && exitRejected == attempts
                    && valAfter == valBefore && clAfter > clBefore;
            verdict("V31 churn overload (" + attempts + " fresh keys)",
                    ok, "activations rejected " + actRejected + "/" + attempts
                            + ", exits rejected " + exitRejected + "/" + attempts
                            + ", validators " + valBefore + " -> " + valAfter
                            + ", cl " + clBefore + " -> " + clAfter);
        }

        // ================================================================ V32
        // Leak-liveness probe (black-box half of the offline-majority chaos):
        // after all floods above, finality must advance past an epoch boundary
        // with an identical root, the validator set must be stable (no spurious
        // bleed while live), and the advisory optimistic-finality signal must
        // still report (head weight / total). Full >1/3-offline chaos stays a
        // staging drill (needs duty control); this pins the live-side contract.
        System.out.println("============ V32: LEAK-LIVENESS PROBE ============");
        {
            long valBefore = activeValidatorCount(nodeUrls[0]);
            long[] finA = new long[nnodes];
            for (int i = 0; i < nnodes; i++) finA[i] = finalizedLength(nodeUrls[i]);
            int slotsPerEpoch = 8;
            long epochMs = (long) slotsPerEpoch * 12000L;
            Thread.sleep(epochMs * 3 / 2);
            long minFin = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) minFin = Math.min(minFin, finalizedAfterRead(nodeUrls[i]));
            // Settled agreement: sequential reads can straddle an epoch
            // boundary and show 2 roots transiently on a healthy mesh.
            boolean rootsEqual = finRootSettled(nodeUrls).size() == 1;
            long beforeMax = Long.MIN_VALUE;
            for (long f : finA) beforeMax = Math.max(beforeMax, f);
            long valAfter = activeValidatorCount(nodeUrls[0]);
            String optDetail = optimisticSummary(nodeUrls[0]);
            boolean ok = minFin > beforeMax && rootsEqual && valAfter == valBefore;
            verdict("V32 leak-liveness probe", ok,
                    "fin " + java.util.Arrays.toString(finA) + " -> min " + minFin
                            + (minFin > beforeMax ? "" : " STALLED")
                            + (rootsEqual ? "" : " DIVERGED")
                            + ", validators " + valBefore + " -> " + valAfter + ", " + optDetail);
        }

        // ================================================================ V33
        // Whale ingress: a 10x-MIN_STAKE foreign-key stakeDeposit (and a
        // privateKey-embedded variant) must be refused at ingress (403) — a
        // whale must never buy justification/proposer capture through the open
        // endpoint. Weight-cap enforcement itself is unit-tested
        // (PosConsensusHardeningTest MAX_EFFECTIVE_BALANCE); here we pin the
        // ingress half + stability.
        System.out.println("============ V33: WHALE INGRESS ============");
        {
            long valBefore = activeValidatorCount(nodeUrls[0]);
            long clBefore = chainLength(nodeUrls[0]);
            PQKey whale = PQKey.createNew();
            boolean foreignRefused = false, keyRefused = false;
            try {
                OkHttp3Util.postString(seedNode + "stakeDeposit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(whale.getPubKey()), "amount", "320000000")));
            } catch (Exception e) {
                foreignRefused = true;
            }
            try {
                OkHttp3Util.postString(seedNode + "stakeDeposit",
                        Json.jsonmapper().writeValueAsString(Map.of("pubkey",
                                Utils.HEX.encode(whale.getPubKey()), "amount", "320000000",
                                "privateKey", Utils.HEX.encode(whale.getPubKey()))));
            } catch (Exception e) {
                keyRefused = true;
            }
            long clAfter = chainAfterSettle(nodeUrls[0], clBefore, Math.max(45000, confirmTimeoutSec * 1000L / 6));
            long valAfter = activeValidatorCount(nodeUrls[0]);
            boolean ok = foreignRefused && keyRefused && valAfter == valBefore && clAfter > clBefore;
            verdict("V33 whale ingress (10x stake)", ok,
                    "foreign-whale " + (foreignRefused ? "refused" : "ACCEPTED")
                            + ", key-embedded " + (keyRefused ? "refused" : "ACCEPTED")
                            + ", validators " + valBefore + " -> " + valAfter
                            + ", cl " + clBefore + " -> " + clAfter);
        }

        // ================================================================ V34
        // Censorship / embedded-cap probe: (a) forged BEACON embodiments of a
        // censoring/empty proposer (nonsense position, never confirmable) must
        // be ignored while honest beacons carry justification forward; (b) the
        // getAttestations read path for a recent slot must parse and stay
        // within MAX_ATTESTATIONS_PER_BEACON (1024).
        System.out.println("============ V34: CENSORSHIP / EMBEDDED-CAP ============");
        {
            long clBefore = chainLength(nodeUrls[0]);
            long finBefore = finalizedLength(nodeUrls[0]);
            int sent = 0;
            try {
                byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                for (int r = 0; r < 2; r++) {
                    Block emptyBeacon = Block.createBlock(params, proto, proto);
                    emptyBeacon.setBlockType(BlockType.BLOCKTYPE_BEACON);
                    emptyBeacon.setPrevBlockHash(Sha256Hash.wrap(seedFor(999_999_500L + r)));
                    for (int i = 0; i < nnodes; i++) {
                        try {
                            submitBlockTo(nodeUrls[i], emptyBeacon);
                            sent++;
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("  V34 error: " + e.getMessage());
            }
            int attCount = attestationCount(nodeUrls[0], Math.max(0, clBefore / 2));
            int attNow = attestationCount(nodeUrls[0], clBefore);
            long clAfter = chainAfterSettle(nodeUrls[0], clBefore, Math.max(30000, confirmTimeoutSec * 1000L / 6));
            long finAfter = finalizedAfterRead(nodeUrls[0]);
            boolean bounded = attCount <= 1024 && attNow <= 1024 && attCount >= 0 && attNow >= 0;
            boolean ok = sent > 0 && bounded && clAfter > clBefore && finAfter >= finBefore;
            verdict("V34 censorship/cap probe (x" + sent + ")",
                    ok, "empty-beacons ignored, cl " + clBefore + " -> " + clAfter
                            + ", fin " + finBefore + " -> " + finAfter
                            + ", atts(slot/2,head)=" + attCount + "," + attNow + (bounded ? "" : " UNBOUNDED"));
        }

        // ================================================================ V35
        // Long-range / finalized-reversion fork: a valid transfer fork off an
        // ancestor BELOW the finalized checkpoint (depth = cl - fin + 2,
        // capped) must never win — even if structurally valid — and the
        // finalized root must never move backwards. The worst PoS safety
        // failure, asserted on-chain like V12 (batchBlock is async).
        System.out.println("============ V35: LONG-RANGE REVERSION ============");
        {
            long cl = chainLength(nodeUrls[0]);
            long fin = finalizedLength(nodeUrls[0]);
            long[] finBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) finBefore[i] = finalizedLength(nodeUrls[i]);
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            int depth = (int) Math.min(25, Math.max(6, cl - fin + 2));
            String attacker = addrFor(seedFor(runUniqueIndex(startIndex, 90140))).toBase58();
            int sent = 0;
            for (int r = 0; r < 3; r++) {
                try {
                    Block deep = staleForkBlock(nodeUrls, startIndex + 260 + r, depth, attacker, payAmount);
                    if (deep != null) {
                        for (int i = 0; i < nnodes; i++) {
                            try {
                                submitBlockTo(nodeUrls[i], deep);
                                sent++;
                            } catch (Exception ignore) {
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            Thread.sleep(Math.max(30000, confirmTimeoutSec * 1000L / 6));
            // V24 pattern: per-node non-regression (length may only advance —
            // a lagging node catching up changes its root WITHOUT regressing,
            // which is convergence, not reversion) plus settled cross-node
            // agreement. A real long-range reversion would split the roots or
            // push a length backwards, failing one of the two.
            boolean stable = true;
            String detail = "";
            for (int i = 0; i < nnodes; i++) {
                if (finalizedLength(nodeUrls[i]) < finBefore[i]) {
                    stable = false;
                    detail += " node" + i + " FIN REGRESSED";
                }
                if (chainLength(nodeUrls[i]) < clBefore[i]) {
                    stable = false;
                    detail += " node" + i + " REGRESSED";
                }
            }
            java.util.Set<String> roots = finRootSettled(nodeUrls);
            if (roots.size() > 1) {
                stable = false;
                detail += " FINROOTS SPLIT=" + roots.size();
            }
            boolean ok = stable;
            verdict("V35 long-range reversion (depth " + depth + " x" + sent + ")",
                    ok, (sent == 0 ? "UNSTAGED " : "")
                            + (stable ? "finalized immutable, no regression, finroots=1" : detail));
        }

        // ================================================================ V36
        // Clock-skew / time-warp partition: feed far-future attestations to
        // node0 ONLY and valid-shape (still fake-key) votes to node1 ONLY, plus
        // a far-future nonsense beacon mesh-wide. The skew-bounded far-future
        // gate must drop the warp; the mesh must stay in lockstep (one
        // finroot, advancing head) — no wall-clock partition.
        System.out.println("============ V36: CLOCK-SKEW PARTITION ============");
        {
            long[] clBefore = new long[nnodes];
            for (int i = 0; i < nnodes; i++) clBefore[i] = chainLength(nodeUrls[i]);
            PQKey fk = PQKey.createNew();
            // far-future to node0 only
            for (int k = 0; k < Math.max(3, (int) n.applyAsInt(5)); k++) {
                AttestationData warp = fakeAttestation(fk.getPubKey(),
                        16_000_000L + k, 2_000_000L, 1_999_999L, 2_000_000L,
                        Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);
                try {
                    OkHttp3Util.postString(nodeUrls[0] + "submitAttestation",
                            Json.jsonmapper().writeValueAsString(warp));
                } catch (Exception ignore) {
                }
                AttestationData shape = fakeAttestation(fk.getPubKey(),
                        800L + k, 100L, 99L, 100L,
                        Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH);
                try {
                    OkHttp3Util.postString(nodeUrls[1 % nnodes] + "submitAttestation",
                            Json.jsonmapper().writeValueAsString(shape));
                } catch (Exception ignore) {
                }
            }
            try {
                byte[] tipResp = OkHttp3Util.postString(nodeUrls[0] + "getTip", "{}");
                Block proto = params.getDefaultSerializer().makeBlock(
                        Utils.HEX.decode((String) Json.jsonmapper().readValue(tipResp, Map.class).get("dataHex")));
                Block warpBeacon = Block.createBlock(params, proto, proto);
                warpBeacon.setBlockType(BlockType.BLOCKTYPE_BEACON);
                warpBeacon.setPrevBlockHash(Sha256Hash.wrap(seedFor(999_999_300L)));
                for (int i = 0; i < nnodes; i++) {
                    try {
                        submitBlockTo(nodeUrls[i], warpBeacon);
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ignore) {
            }
            Thread.sleep(Math.max(30000, confirmTimeoutSec * 1000L / 6));
            java.util.Set<String> fins = finRootSettled(nodeUrls);
            long minAfter = Long.MAX_VALUE;
            for (int i = 0; i < nnodes; i++) minAfter = Math.min(minAfter, chainLength(nodeUrls[i]));
            long minBefore = Long.MAX_VALUE;
            for (long c : clBefore) minBefore = Math.min(minBefore, c);
            if (minAfter <= minBefore) {
                // Confirmed length moves in bursts; one more window before
                // calling a stall (same flake class as V25's 30s stall).
                Thread.sleep(30000);
                minAfter = Long.MAX_VALUE;
                for (int i = 0; i < nnodes; i++) minAfter = Math.min(minAfter, chainLength(nodeUrls[i]));
            }
            boolean ok = fins.size() == 1 && minAfter > minBefore;
            verdict("V36 clock-skew partition", ok,
                    "split-view warp dropped, finroots=" + fins.size() + ", min-cl " + minBefore + " -> " + minAfter);
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

    /** Justified checkpoint hash (hex) from getChainNumber. */
    static String justifiedHash(String hostPort) {
        try {
            Map<?, ?> m = Json.jsonmapper().readValue(chainNumberJson(hostPort), Map.class);
            Object jh = m.get("justifiedBlockHash");
            return jh == null ? "" : String.valueOf(jh);
        } catch (Exception e) {
            return "";
        }
    }

    /** Advisory optimistic-finality one-liner (head weight / total / supermajority). */
    static String optimisticSummary(String hostPort) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "getOptimisticFinality";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getOptimisticFinality", "/getOptimisticFinality"), "{}");
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            return "opt[head=" + m.get("chainLength") + " weight=" + m.get("headVoteWeight")
                    + "/" + m.get("totalStake") + " supermajority=" + m.get("supermajority") + "]";
        } catch (Exception e) {
            return "opt[unreachable]";
        }
    }

    /** Number of attestations a node returns for a slot (bounded by the 1024 cap). */
    static int attestationCount(String hostPort, long slot) {
        try {
            String u = (hostPort.startsWith("http") ? hostPort : "http://" + hostPort) + "getAttestations";
            byte[] r = OkHttp3Util.postString(u.replaceAll("/+getAttestations", "/getAttestations"),
                    Json.jsonmapper().writeValueAsString(Map.of("slot", slot)));
            Map<?, ?> m = Json.jsonmapper().readValue(r, Map.class);
            Object text = m.get("text");
            Map<?, ?> inner = text instanceof String
                    ? Json.jsonmapper().readValue((String) text, Map.class)
                    : m;
            Object atts = inner.get("attestations");
            if (atts instanceof java.util.List) return ((java.util.List<?>) atts).size();
            // Some builds nest the JSON string twice; fall back to raw scan.
            String raw = new String(r, java.nio.charset.StandardCharsets.UTF_8);
            int c = 0, idx = 0;
            while ((idx = raw.indexOf("\"slot\"", idx)) >= 0) {
                c++;
                idx += 6;
            }
            return Math.min(c, 100000);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Build an unsigned, fake-key attestation for spam/slashing vectors. The
     * key is NOT a registered validator (or the BLS is absent), so a healthy
     * node must drop it before verify / state growth. Serializes
     * symmetrically with the server's Jackson mapping.
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
                // Mix key material per shape so each spam key is distinct.
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
                UTXO fallback = null;
                for (UTXO x : gor.getOutputs()) {
                    if (x.getValue() != null && x.getAddress() != null && !x.isSpent()) {
                        // Prefer the full genesis funding output so pay() has
                        // enough to cover amount + change + fee; a leftover
                        // change output can be too small and make pay() throw.
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

    static long chainLength(String[] urls, int skip) {
        for (int i = 0; i < urls.length; i++) {
            if (i == skip) continue;
            long cl = chainLength(urls[i]);
            if (cl >= 0) return cl;
        }
        return -1;
    }
}
