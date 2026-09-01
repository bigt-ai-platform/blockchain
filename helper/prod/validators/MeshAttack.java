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

    static long chainLength(String[] urls, int skip) {
        for (int i = 0; i < urls.length; i++) {
            if (i == skip) continue;
            long cl = chainLength(urls[i]);
            if (cl >= 0) return cl;
        }
        return -1;
    }
}
