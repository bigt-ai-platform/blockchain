import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetTXRewardResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * GenesisPayoutTool — distribute the L0 genesis supply from the 2-of-3
 * ceremony multisig to a GenesisOutput CSV (address,pubkey,value) on a live
 * chain whose genesis minted the total to that multisig (GENESIS_CSV empty at
 * deploy, see helper/prod/prod.sh).
 *
 * <p>Model: the genesis coinbase output 0 is ONE bare-multisig UTXO
 * (UtilGeneseBlock.add, order = genesisPub order). Payout runs in batches
 * (BATCH_ROWS outputs per tx); each batch spends the previous batch's change
 * (change pays the SAME multisig script), so the chain of funds is fully
 * traceable back to genesis. Batches are submitted sequentially: each waits
 * for its containing block (getTransactionStatus blockHash) before the next
 * is built, because the next input must reference that block hash.
 *
 * <p>Failure mode is stall, never loss: an invalid tx simply never confirms
 * and funds stay put; keys must only be destroyed after `verify` passes.
 *
 * <p>Phases (argv[0]):
 * <ul>
 * <li>genesis — offline: print the locally recomputed genesis hash, output
 * script and value (ceremony record; compare with the node after reset).</li>
 * <li>preflight — read-only: check the node's genesis block matches the local
 * recomputation (hash + output script + value), CSV sum + fees fit the
 * genesis value, coinbase maturity/finality gate, and report already-funded
 * rows. Aborts (exit 2) on any mismatch.</li>
 * <li>payout — pay all rows in batches with progress file + per-batch tx
 * logging. Resumable via the progress file, CHANGE_OUTPOINT, or
 * `resume &lt;txHash&gt;`.</li>
 * <li>verify — every CSV row holds &gt;= its value (confirmed BIG).
 * Exit 0 iff complete.</li>
 * <li>resume &lt;txHash&gt; — derive a CHANGE_OUTPOINT export line from a
 * previously submitted batch tx (recovery when the progress file is lost;
 * keep the payout console log).</li>
 * </ul>
 *
 * <p>Env: L0_URL (default https://eu1.bigtangle.org),
 * GENESIS_PUBKEYS ("p0,p1,p2" — must equal MainNet genesisPub, order matters),
 * GENESIS_SEEDS ("s0,s1" — any 2 of the 3 seeds, any order),
 * GENESIS_CSV (default helper/prodsim/genesis/GenesisOutput.csv),
 * GENESIS_EXCLUDE_CSV (default: GenesisOutputExclude.csv next to GENESIS_CSV;
 *   one base58 address per line — excluded rows are never paid, never counted
 *   in the sum, and never required by verify. Empty = no exclusions; a missing
 *   default file = no exclusions, an explicit-but-missing path aborts),
 * BATCH_ROWS (default 200), FEE_SAT (default Coin.FEE_DEFAULT),
 * PROGRESS_FILE (default /home/jcui/validators/genesis-payout.progress),
 * CHANGE_OUTPOINT ("blockHash:txHash:index:value", manual resume),
 * MATURITY_DEPTH (default 100 = MainNet spendableCoinbaseDepth; enforced as
 * an L0 FINALITY gate — the depth constant itself is currently unenforced in
 * code, so this doubles as the fresh-chain liveness proof before funds move).
 *
 * <p>Runs as a single-file java source against the unpacked layer0 exec jar
 * (see helper/prod/genesis-payout.sh).
 */
public class GenesisPayoutTool {

    private final NetworkParameters params;
    private final String l0;
    private final ObjectMapper m = Json.jsonmapper();
    private final List<PQKey> orderedPubs;
    private final Map<String, PQKey> signerByPub = new HashMap<>();
    private final Script multisigScript;
    private final BigInteger feeSat;
    private final int batchRows;
    private final long maturityDepth;
    private final String progressFile;
    private final Set<String> excluded;

    private GenesisPayoutTool(NetworkParameters params, String l0, List<PQKey> orderedPubs,
            Map<String, PQKey> signerByPub, Script multisigScript,
            BigInteger feeSat, int batchRows, long maturityDepth, String progressFile,
            Set<String> excluded) {
        this.params = params;
        this.l0 = l0;
        this.orderedPubs = orderedPubs;
        this.signerByPub.putAll(signerByPub);
        this.multisigScript = multisigScript;
        this.feeSat = feeSat;
        this.batchRows = batchRows;
        this.maturityDepth = maturityDepth;
        this.progressFile = progressFile;
        this.excluded = excluded;
    }

    // ---- setup -----------------------------------------------------------------
    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? dflt : v;
    }

    private static String l0Url() {
        return env("L0_URL", "https://eu1.bigtangle.org").replaceAll("/+$", "") + "/";
    }

    private static List<PQKey> parsePubkeys(String csv) {
        List<PQKey> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            byte[] pubBytes = Utils.HEX.decode(s);
            if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
                List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
                entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
                        net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
                out.add(PQKey.fromPublicOnly(new net.bigtangle.crypto.pq.KeyBundle(entries)));
            } else {
                out.add(PQKey.fromPublicOnly(pubBytes));
            }
        }
        return out;
    }

    /** Output script for one CSV row — identical construction to
     * UtilGeneseBlock.addOutput, so payments are indexed exactly like
     * genesis outputs would have been. */
    private static Script rowScript(NetworkParameters params, UtilGeneseBlock.GenesisOutput out) {
        if (out.pubkeyHex != null && !out.pubkeyHex.trim().isEmpty()) {
            byte[] pubBytes = Utils.HEX.decode(out.pubkeyHex.trim());
            PQKey key;
            if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
                List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
                entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
                        net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
                key = PQKey.fromPublicOnly(new net.bigtangle.crypto.pq.KeyBundle(entries));
            } else {
                key = PQKey.fromPublicOnly(pubBytes);
            }
            return ScriptBuilder.createOutputScript(key);
        }
        return ScriptBuilder.createOutputScript(Address.fromBase58(params, out.address.trim()));
    }

    /** Balance-lookup key for one CSV row (what the server indexes the output
     * under). Address rows: the address hash160. Pubkey rows: the same
     * PQKey-construction hash the chain used at output-creation time. */
    private static String rowLookupKey(NetworkParameters params, UtilGeneseBlock.GenesisOutput out) {
        if (out.pubkeyHex != null && !out.pubkeyHex.trim().isEmpty()) {
            byte[] pubBytes = Utils.HEX.decode(out.pubkeyHex.trim());
            PQKey key;
            if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
                List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
                entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
                        net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
                key = PQKey.fromPublicOnly(new net.bigtangle.crypto.pq.KeyBundle(entries));
            } else {
                key = PQKey.fromPublicOnly(pubBytes);
            }
            return Utils.HEX.encode(key.getPubKeyHash());
        }
        return Utils.HEX.encode(Address.fromBase58(params, out.address.trim()).getHash160());
    }

    private static GenesisPayoutTool setup(boolean needSeeds) throws Exception {
        NetworkParameters params = MainNetParams.get();
        String pubkeys = System.getenv("GENESIS_PUBKEYS");
        if (pubkeys == null || pubkeys.isEmpty()) {
            System.err.println("ABORT: GENESIS_PUBKEYS not set (\"p0,p1,p2\" in genesisPub order)");
            System.exit(2);
        }
        if (!pubkeys.equals(params.genesisPub)) {
            System.err.println("ABORT: GENESIS_PUBKEYS does not equal MainNet genesisPub "
                    + "(order matters — refusing to sign the wrong script)");
            System.exit(2);
        }
        String seeds = System.getenv("GENESIS_SEEDS");
        if (seeds == null || seeds.isEmpty()) {
            if (!needSeeds) {
                seeds = "";
            } else {
                System.err.println("ABORT: GENESIS_SEEDS not set (any 2 of the 3 seeds, comma-separated)");
                System.exit(2);
            }
        }
        List<PQKey> pubs = parsePubkeys(pubkeys);
        if (pubs.size() != 3) {
            System.err.println("ABORT: expected 3 genesis pubkeys, got " + pubs.size());
            System.exit(2);
        }
        Map<String, PQKey> signers = new HashMap<>();
        for (String s : seeds.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            PQKey k = PQKey.fromPrivateKeyHex(s);
            signers.put(Utils.HEX.encode(k.getPublicKeyBytes()), k);
        }
        // Reconstruct the genesis output script exactly the way the chain did:
        // recompute the whole genesis block locally (no CSV property in this
        // JVM, so this is the code-default single/multisig path).
        Block genesis = UtilGeneseBlock.createGenesis(params);
        Script ms = genesis.getTransactions().get(0).getOutput(0).getScriptPubKey();
        BigInteger fee = new BigInteger(env("FEE_SAT", Coin.FEE_DEFAULT.getValue().toString()));
        int batch = Integer.parseInt(env("BATCH_ROWS", "200"));
        long maturity = Long.parseLong(env("MATURITY_DEPTH", "100"));
        String prog = env("PROGRESS_FILE", "/home/jcui/validators/genesis-payout.progress");
        String csvPath = env("GENESIS_CSV", "helper/prodsim/genesis/GenesisOutput.csv");
        Set<String> excluded = loadExcluded(csvPath);
        return new GenesisPayoutTool(params, l0Url(), pubs, signers, ms, fee, batch, maturity, prog,
                excluded);
    }

    private Block localGenesis() {
        return UtilGeneseBlock.createGenesis(params);
    }

    private BigInteger genesisValue() {
        return localGenesis().getTransactions().get(0).getOutput(0).getValue().getValue();
    }

    private List<UtilGeneseBlock.GenesisOutput> loadCsv() {
        String path = env("GENESIS_CSV", "helper/prodsim/genesis/GenesisOutput.csv");
        List<UtilGeneseBlock.GenesisOutput> rows = UtilGeneseBlock.loadGenesisOutputsFromCsv(path);
        System.out.println("CSV=" + path + " ROWS=" + rows.size());
        return rows;
    }

    /** Excluded base58 addresses, one per line (blank lines and an optional
     * `address` header are ignored; a missing trailing newline is fine).
     * Default: GenesisOutputExclude.csv next to GENESIS_CSV. */
    private static Set<String> loadExcluded(String csvPath) throws Exception {
        Set<String> out = new HashSet<>();
        String p = System.getenv("GENESIS_EXCLUDE_CSV");
        if (p == null || p.isEmpty()) {
            p = Paths.get(csvPath).toAbsolutePath().getParent().resolve("GenesisOutputExclude.csv").toString();
            if (!Files.exists(Paths.get(p))) {
                System.out.println("EXCLUDE_CSV=none (no " + p + ")");
                return out;
            }
        } else {
            if (!Files.exists(Paths.get(p))) {
                System.err.println("ABORT: GENESIS_EXCLUDE_CSV not found: " + p);
                System.exit(2);
            }
        }
        for (String line : Files.readAllLines(Paths.get(p))) {
            String t = line.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("address") || t.startsWith("#")) continue;
            out.add(t);
        }
        System.out.println("EXCLUDE_CSV=" + p + " EXCLUDED_ADDRESSES=" + out.size());
        return out;
    }

    /** Base58 address a CSV row pays (address column, or P2PKH derived from
     * the pubkey column exactly as the output script is built). */
    private String rowAddress(NetworkParameters params, UtilGeneseBlock.GenesisOutput out) {
        if (out.pubkeyHex != null && !out.pubkeyHex.trim().isEmpty()) {
            byte[] pubBytes = Utils.HEX.decode(out.pubkeyHex.trim());
            PQKey key;
            if (pubBytes.length > 0 && (pubBytes[0] == 0x02 || pubBytes[0] == 0x03 || pubBytes[0] == 0x04)) {
                List<net.bigtangle.crypto.pq.KeyBundle.Entry> entries = new ArrayList<>();
                entries.add(new net.bigtangle.crypto.pq.KeyBundle.Entry(
                        net.bigtangle.crypto.pq.PQConstants.ALG_ML_DSA_87, pubBytes));
                key = PQKey.fromPublicOnly(new net.bigtangle.crypto.pq.KeyBundle(entries));
            } else {
                key = PQKey.fromPublicOnly(pubBytes);
            }
            return Address.fromHash160(params, key.getPubKeyHash()).toBase58();
        }
        return out.address.trim();
    }

    /** Rows that actually get paid: positive amount and not excluded. */
    private List<UtilGeneseBlock.GenesisOutput> activeRows(List<UtilGeneseBlock.GenesisOutput> all) {
        List<UtilGeneseBlock.GenesisOutput> rows = new ArrayList<>();
        BigInteger skipped = BigInteger.ZERO;
        int skippedN = 0;
        for (UtilGeneseBlock.GenesisOutput r : all) {
            if (r.amount.signum() < 0) {
                System.err.println("ABORT: negative CSV value");
                System.exit(2);
            }
            if (r.amount.signum() == 0) continue;
            if (excluded.contains(rowAddress(params, r))) {
                skipped = skipped.add(r.amount);
                skippedN++;
                continue;
            }
            rows.add(r);
        }
        if (skippedN > 0) {
            System.out.println("EXCLUDED_ROWS=" + skippedN + " EXCLUDED_SUM_SAT=" + skipped);
        }
        return rows;
    }

    // ---- node reads ---------------------------------------------------------------
    private Block fetchBlock(String hashHex) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("hashHex", hashHex);
        byte[] data = OkHttp3Util.postAndGetBlock(l0 + ReqCmd.getBlockByHash.name(),
                m.writeValueAsString(p));
        Block b = params.getDefaultSerializer().makeBlock(data);
        if (b == null || b.getHash() == null || !b.getHashAsString().equalsIgnoreCase(hashHex)) {
            throw new RuntimeException("node returned a block whose hash does not match " + hashHex);
        }
        return b;
    }

    private long finalizedLength() throws Exception {
        byte[] resp = OkHttp3Util.postString(l0 + ReqCmd.getChainNumber.name(), "{}");
        GetTXRewardResponse r = m.readValue(resp, GetTXRewardResponse.class);
        return (r != null && r.getFinalizedChainLength() != null) ? r.getFinalizedChainLength() : -1L;
    }

    private static class TxStatus {
        String status;
        String blockHash;
    }

    private TxStatus txStatus(String txHashHex) throws Exception {
        byte[] resp = OkHttp3Util.postString(l0 + ReqCmd.getTransactionStatus.name(),
                m.writeValueAsString(Map.of("txHash", txHashHex)));
        Map<?, ?> r = m.readValue(resp, Map.class);
        TxStatus s = new TxStatus();
        s.status = String.valueOf(r.get("status"));
        Object bh = r.get("blockHash");
        s.blockHash = (bh == null) ? "" : String.valueOf(bh);
        return s;
    }

    /** Confirmed BIG totals per lookup-key, chunked so one call never carries
     * the whole distribution (see OutputService batching note). */
    private Map<String, BigInteger> confirmedTotals(List<String> keys) throws Exception {
        Map<String, BigInteger> totals = new HashMap<>();
        for (String k : keys) totals.put(k, BigInteger.ZERO);
        for (int i = 0; i < keys.size(); i += 500) {
            List<String> chunk = keys.subList(i, Math.min(i + 500, keys.size()));
            byte[] resp = OkHttp3Util.postString(l0 + ReqCmd.getBalances.name(),
                    m.writeValueAsString(chunk));
            GetBalancesResponse bal = m.readValue(resp, GetBalancesResponse.class);
            if (bal.getOutputs() == null) continue;
            for (UTXO u : bal.getOutputs()) {
                if (u == null || u.getValue() == null || u.isSpent() || !u.isConfirmed()) continue;
                if (!java.util.Arrays.equals(u.getValue().getTokenid(), NetworkParameters.BIGTANGLE_TOKENID))
                    continue;
                if (u.getAddress() == null || u.getAddress().isEmpty()) continue;
                String k;
                try {
                    k = Utils.HEX.encode(Address.fromBase58(params, u.getAddress()).getHash160());
                } catch (Exception e) {
                    continue; // non-P2PKH output (e.g. multisig change) — not a row target
                }
                if (totals.containsKey(k)) {
                    totals.put(k, totals.get(k).add(u.getValue().getValue()));
                }
            }
        }
        return totals;
    }

    private void submit(Transaction tx) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] b = tx.bitcoinSerialize();
        dos.writeInt(b.length);
        dos.write(b);
        dos.close();
        OkHttp3Util.post(l0 + "submitTransactions", baos.toByteArray());
    }

    // ---- phases -------------------------------------------------------------------
    private void phaseGenesis() {
        Block g = localGenesis();
        System.out.println("EXPECTED_GENESIS=" + g.getHashAsString());
        System.out.println("GENESIS_VALUE_SAT=" + genesisValue());
        System.out.println("GENESIS_SCRIPT_HEX=" + Utils.HEX.encode(multisigScript.getProgram()));
        System.out.println("GENESIS_OUTPUTS=" + g.getTransactions().get(0).getOutputs().size());
    }

    private static class Funding {
        Sha256Hash blockHash;
        Sha256Hash txHash;
        long index;
        BigInteger value;
    }

    private void phasePreflight() throws Exception {
        // Local checks first (no node needed): CSV parse, exclusions, sum math.
        List<UtilGeneseBlock.GenesisOutput> rows = activeRows(loadCsv());
        BigInteger sum = BigInteger.ZERO;
        List<String> keys = new ArrayList<>();
        Map<String, BigInteger> need = new HashMap<>();
        for (UtilGeneseBlock.GenesisOutput r : rows) {
            sum = sum.add(r.amount);
            String k = rowLookupKey(params, r);
            keys.add(k);
            need.merge(k, r.amount, BigInteger::add);
        }
        int batches = (rows.size() + batchRows - 1) / batchRows;
        BigInteger fees = feeSat.multiply(BigInteger.valueOf(batches));
        System.out.println("PAY_ROWS=" + rows.size());
        System.out.println("CSV_SUM_SAT=" + sum);
        System.out.println("GENESIS_VALUE_SAT=" + genesisValue());
        System.out.println("BATCHES=" + batches + " FEE_SAT_PER_TX=" + feeSat + " FEES_TOTAL_SAT=" + fees);
        System.out.println("CHANGE_SAT=" + genesisValue().subtract(sum).subtract(fees));
        if (sum.add(fees).compareTo(genesisValue()) > 0) {
            System.err.println("ABORT: CSV sum + fees exceeds the genesis value — "
                    + "trim the CSV to fit BigtangleCoinTotal");
            System.exit(2);
        }
        Block g = localGenesis();
        String gh = g.getHashAsString();
        System.out.println("EXPECTED_GENESIS=" + gh);
        Block onchain;
        try {
            onchain = fetchBlock(gh);
        } catch (Exception e) {
            System.err.println("ABORT: node has no block " + gh + " (" + e.getMessage() + ") — "
                    + "wrong chain, or genesisPub changed after deploy");
            System.exit(2);
            return;
        }
        byte[] want = multisigScript.getProgram();
        byte[] got = onchain.getTransactions().get(0).getOutput(0).getScriptPubKey().getProgram();
        BigInteger gotVal = onchain.getTransactions().get(0).getOutput(0).getValue().getValue();
        System.out.println("ONCHAIN_GENESIS_SCRIPT_MATCH=" + java.util.Arrays.equals(want, got));
        System.out.println("ONCHAIN_GENESIS_VALUE_SAT=" + gotVal);
        if (!java.util.Arrays.equals(want, got) || !gotVal.equals(genesisValue())) {
            System.err.println("ABORT: on-chain genesis output differs from the ceremony script/value");
            System.exit(2);
        }
        long fin = finalizedLength();
        System.out.println("L0_FINALIZED_LENGTH=" + fin + " MATURITY_GATE=" + maturityDepth);
        if (fin < maturityDepth) {
            System.err.println("ABORT: L0 finality below the coinbase-maturity gate — "
                    + "wait for the fresh chain to finalize past " + maturityDepth);
            System.exit(2);
        }
        // Informational: which rows already hold funds (payout skips nothing by
        // itself — batches are progress-tracked — but a fully-funded chain
        // short-circuits in payout).
        Map<String, BigInteger> totals = confirmedTotals(new ArrayList<>(new HashSet<>(keys)));
        int fundedKeys = 0;
        for (Map.Entry<String, BigInteger> e : need.entrySet()) {
            if (totals.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) >= 0) fundedKeys++;
        }
        System.out.println("ADDRESSES_FUNDED=" + fundedKeys + "/" + need.size());
    }

    private void writeProgress(Map<String, Object> p) throws Exception {
        p.put("csv", env("GENESIS_CSV", "helper/prodsim/genesis/GenesisOutput.csv"));
        p.put("genesis", localGenesis().getHashAsString());
        Files.writeString(Paths.get(progressFile), m.writeValueAsString(p));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProgress() throws Exception {
        if (!Files.exists(Paths.get(progressFile))) return null;
        return m.readValue(Files.readString(Paths.get(progressFile)), Map.class);
    }

    /** Wait until txHash sits in a block; returns that block hash. Aborts on
     * DROPPED so an invalid batch stops the run instead of chaining on it. */
    private String waitInBlock(String txHashHex) throws Exception {
        for (int i = 0; i < 80; i++) {
            TxStatus s = txStatus(txHashHex);
            if (s.status != null && s.status.toUpperCase().contains("DROP")) {
                System.err.println("ABORT: batch tx " + txHashHex + " DROPPED (status=" + s.status + ") — "
                        + "funds unchanged; fix and resume with CHANGE_OUTPOINT or resume <txHash>");
                System.exit(2);
            }
            if (s.blockHash != null && !s.blockHash.isEmpty() && !"null".equals(s.blockHash)) {
                return s.blockHash;
            }
            Thread.sleep(15000);
        }
        System.err.println("ABORT: batch tx " + txHashHex + " not in a block after 20 min — "
                + "check the node, then resume with CHANGE_OUTPOINT or resume <txHash>");
        System.exit(2);
        return null;
    }

    /** The keys that will sign, in genesisPub (script) order, capped at 2.
     * Shared by signing and dry-run verification so both agree on mapping. */
    private List<PQKey> orderedSigners() {
        List<PQKey> out = new ArrayList<>();
        for (PQKey pub : orderedPubs) {
            if (out.size() >= 2) break;
            PQKey signer = signerByPub.get(Utils.HEX.encode(pub.getPublicKeyBytes()));
            if (signer == null) continue;
            out.add(signer);
        }
        return out;
    }

    private Transaction buildBatch(Funding in, List<UtilGeneseBlock.GenesisOutput> batchRows) throws Exception {        BigInteger batchSum = BigInteger.ZERO;
        for (UtilGeneseBlock.GenesisOutput r : batchRows) batchSum = batchSum.add(r.amount);
        BigInteger change = in.value.subtract(batchSum).subtract(feeSat);
        if (change.signum() < 0) {
            System.err.println("ABORT: batch needs " + batchSum.add(feeSat) + " but funding holds " + in.value);
            System.exit(2);
        }
        Transaction tx = new Transaction(params);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        TransactionInput input = tx.addInput(in.blockHash, in.txHash, in.index, multisigScript);
        for (UtilGeneseBlock.GenesisOutput r : batchRows) {
            Script s = rowScript(params, r);
            tx.addOutput(new TransactionOutput(params, tx,
                    new Coin(r.amount, NetworkParameters.BIGTANGLE_TOKENID), s.getProgram()));
        }
        if (change.signum() > 0) {
            tx.addOutput(new TransactionOutput(params, tx,
                    new Coin(change, NetworkParameters.BIGTANGLE_TOKENID), multisigScript.getProgram()));
        }
        // 2-of-3, signatures ordered by genesisPub position (CHECKMULTISIG is
        // order-sensitive) — same pattern as BridgeService.signVaultRelease.
        List<PQKey> signers = orderedSigners();
        if (signers.size() < 2) {
            System.err.println("ABORT: only " + signers.size() + " of the 3 genesis seeds match "
                    + "GENESIS_PUBKEYS — need 2");
            System.exit(2);
        }
        List<byte[]> sigs = new ArrayList<>();
        for (PQKey signer : signers) {
            Sha256Hash sighash = tx.hashForSignature(0, multisigScript.getProgram(),
                    Transaction.SigHash.ALL, false);
            sigs.add(signer.sign(sighash).serialize());
        }
        input.setScriptSig(ScriptBuilder.createMultiSigInputScriptBytes(sigs, null));
        return tx;
    }

    /**
     * Offline end-to-end proof (no node): build batch 0 against the locally
     * recomputed genesis, sign it 2-of-3, then verify everything a validator
     * will check — outpoint, conservation (in == outs + fee), change script,
     * and each signature via PQScriptUtils.verifyPQ (the consensus primitive)
     * over an independently recomputed sighash against the script-ordered
     * pubkey. Exit 2 on any failure.
     */
    private void phaseDryrun() throws Exception {
        List<UtilGeneseBlock.GenesisOutput> rows = activeRows(loadCsv());
        if (rows.isEmpty()) {
            System.err.println("ABORT: no payable rows");
            System.exit(2);
        }
        BigInteger sum = BigInteger.ZERO;
        for (UtilGeneseBlock.GenesisOutput r : rows) sum = sum.add(r.amount);
        int batches = (rows.size() + batchRows - 1) / batchRows;
        BigInteger fees = feeSat.multiply(BigInteger.valueOf(batches));
        System.out.println("PAY_ROWS=" + rows.size());
        System.out.println("CSV_SUM_SAT=" + sum);
        System.out.println("BATCHES=" + batches + " FEES_TOTAL_SAT=" + fees);
        System.out.println("CHANGE_SAT=" + genesisValue().subtract(sum).subtract(fees));
        if (sum.add(fees).compareTo(genesisValue()) > 0) {
            System.err.println("ABORT: CSV sum + fees exceeds the genesis value");
            System.exit(2);
        }
        Block g = localGenesis();
        Funding in = new Funding();
        in.blockHash = g.getHash();
        in.txHash = g.getTransactions().get(0).getHash();
        in.index = 0;
        in.value = genesisValue();
        List<UtilGeneseBlock.GenesisOutput> part =
                rows.subList(0, Math.min(batchRows, rows.size()));
        Transaction tx = buildBatch(in, part);
        // 1. outpoint = the genesis coinbase output
        if (!tx.getInput(0).getOutpoint().getTxHash().equals(in.txHash)
                || !tx.getInput(0).getOutpoint().getBlockHash().equals(in.blockHash)
                || tx.getInput(0).getOutpoint().getIndex() != 0) {
            System.err.println("ABORT: batch input does not reference the genesis output");
            System.exit(2);
        }
        System.out.println("DRYRUN_OUTPOINT_OK=" + in.blockHash + ":" + in.txHash + ":0");
        // 2. conservation
        BigInteger out = BigInteger.ZERO;
        for (TransactionOutput o : tx.getOutputs()) out = out.add(o.getValue().getValue());
        if (!in.value.equals(out.add(feeSat))) {
            System.err.println("ABORT: conservation violated (in=" + in.value + " outs=" + out
                    + " fee=" + feeSat + ")");
            System.exit(2);
        }
        System.out.println("DRYRUN_CONSERVATION_OK=in:" + in.value + " outs:" + out + " fee:" + feeSat);
        // 3. change (if any) pays the ceremony script; row outputs match
        List<TransactionOutput> outs = tx.getOutputs();
        boolean hasChange = outs.size() == part.size() + 1;
        if (outs.size() != part.size() && !hasChange) {
            System.err.println("ABORT: unexpected output count " + outs.size());
            System.exit(2);
        }
        if (hasChange && !java.util.Arrays.equals(
                outs.get(outs.size() - 1).getScriptPubKey().getProgram(), multisigScript.getProgram())) {
            System.err.println("ABORT: change output does not pay the ceremony script");
            System.exit(2);
        }
        System.out.println("DRYRUN_OUTPUTS=" + outs.size() + " CHANGE=" + hasChange);
        // 4. each signature verifies against its script-ordered pubkey over a
        // freshly recomputed sighash (same primitive consensus uses).
        Sha256Hash sighash = tx.hashForSignature(0, multisigScript.getProgram(),
                Transaction.SigHash.ALL, false);
        List<PQKey> signers = orderedSigners();
        List<byte[]> sigs = new ArrayList<>();
        for (net.bigtangle.script.ScriptChunk c : tx.getInput(0).getScriptSig().getChunks()) {
            if (c.data != null && c.data.length > 0) sigs.add(c.data);
        }
        if (sigs.size() != signers.size()) {
            System.err.println("ABORT: scriptSig holds " + sigs.size() + " signatures for "
                    + signers.size() + " signers");
            System.exit(2);
        }
        for (int i = 0; i < sigs.size(); i++) {
            // pubkey bytes in script order = orderedPubs order (setup asserts
            // GENESIS_PUBKEYS == genesisPub; add() preserves that order).
            byte[] pubBytes = orderedPubsOrderedBytes(i, signers);
            boolean ok;
            try {
                ok = net.bigtangle.crypto.pq.PQScriptUtils.verifyPQ(pubBytes, sigs.get(i), sighash);
            } catch (Exception e) {
                ok = false;
            }
            System.out.println("DRYRUN_SIG" + i + "_VERIFY=" + ok);
            if (!ok) {
                System.err.println("ABORT: signature " + i + " does not verify");
                System.exit(2);
            }
        }
        System.out.println("DRYRUN_TXID=" + tx.getHashAsString());
        System.out.println("DRYRUN_OK");
    }

    /** Raw (prefixed) pubkey bytes of the i-th signer, in script order. */
    private byte[] orderedPubsOrderedBytes(int i, List<PQKey> signers) {
        PQKey signer = signers.get(i);
        for (PQKey pub : orderedPubs) {
            if (Utils.HEX.encode(pub.getPublicKeyBytes())
                    .equals(Utils.HEX.encode(signer.getPublicKeyBytes()))) {
                return pub.getPublicKeyBytes();
            }
        }
        throw new IllegalStateException("signer not in genesisPub order");
    }

    private void phasePayout() throws Exception {
        List<UtilGeneseBlock.GenesisOutput> rows = activeRows(loadCsv());
        int batches = (rows.size() + batchRows - 1) / batchRows;
        BigInteger sum = BigInteger.ZERO;
        for (UtilGeneseBlock.GenesisOutput r : rows) sum = sum.add(r.amount);
        BigInteger fees = feeSat.multiply(BigInteger.valueOf(batches));
        if (sum.add(fees).compareTo(genesisValue()) > 0) {
            System.err.println("ABORT: CSV sum + fees exceeds the genesis value");
            System.exit(2);
        }
        // Fast path: everything already funded.
        List<String> keys = new ArrayList<>();
        for (UtilGeneseBlock.GenesisOutput r : rows) keys.add(rowLookupKey(params, r));
        Map<String, BigInteger> need = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) need.merge(keys.get(i), rows.get(i).amount, BigInteger::add);
        Map<String, BigInteger> have = confirmedTotals(new ArrayList<>(need.keySet()));
        boolean allFunded = true;
        for (Map.Entry<String, BigInteger> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) < 0) {
                allFunded = false;
                break;
            }
        }
        if (allFunded) {
            System.out.println("ALREADY_DISTRIBUTED: every CSV row holds >= its value — nothing to do");
            return;
        }
        // Starting outpoint: progress file > CHANGE_OUTPOINT > genesis.
        Funding in = null;
        int startBatch = 0;
        Map<String, Object> prog = readProgress();
        String csvPath = env("GENESIS_CSV", "helper/prodsim/genesis/GenesisOutput.csv");
        if (prog != null && csvPath.equals(String.valueOf(prog.get("csv")))) {
            Number bi = (Number) prog.get("batchIndex");
            startBatch = (bi == null ? 0 : bi.intValue()) + 1;
            in = new Funding();
            in.blockHash = Sha256Hash.wrap(String.valueOf(prog.get("changeBlockHash")));
            in.txHash = Sha256Hash.wrap(String.valueOf(prog.get("changeTxHash")));
            in.index = ((Number) prog.get("changeIndex")).longValue();
            in.value = new BigInteger(String.valueOf(prog.get("changeValue")));
            System.out.println("RESUME from progress file at batch " + startBatch + "/" + batches);
            } else {
                String co = System.getenv("CHANGE_OUTPOINT");
                if (co != null && !co.isEmpty()) {
                    String[] c = co.split(":");
                    in = new Funding();
                    in.blockHash = Sha256Hash.wrap(c[0]);
                    in.txHash = Sha256Hash.wrap(c[1]);
                    in.index = Long.parseLong(c[2]);
                    in.value = new BigInteger(c[3]);
                    // Derive the batch index from the change VALUE, not from
                    // row funding: batches complete strictly in order and the
                    // change after batch k is deterministic
                    // (total - prefixSum(k) - fee*(k+1)), so the value pins
                    // down exactly which batch comes next. Row-funding
                    // attribution would misfire on addresses shared across
                    // batches (re-paying a complete batch = double-pay).
                    startBatch = batchAfterChange(rows, in.value);
                    if (startBatch < 0 || startBatch >= batches) {
                        System.err.println("ABORT: CHANGE_OUTPOINT value " + in.value
                                + " matches no batch boundary — foreign outpoint?");
                        System.exit(2);
                    }
                    System.out.println("RESUME from CHANGE_OUTPOINT at batch " + startBatch + "/" + batches);
                } else {
                if (!allFunded && anyFunded(need, have)) {
                    System.err.println("ABORT: some rows are already funded but no progress file "
                            + "and no CHANGE_OUTPOINT — refusing to guess the funding outpoint "
                            + "(would risk double-pay). Recover with: resume <batchTxHash> "
                            + "from the payout console log, then re-run with CHANGE_OUTPOINT.");
                    System.exit(2);
                }
                long fin = finalizedLength();
                if (fin < maturityDepth) {
                    System.err.println("ABORT: L0 finality " + fin + " below coinbase-maturity gate "
                            + maturityDepth + " — wait for the fresh chain to finalize first");
                    System.exit(2);
                }
                Block g = localGenesis();
                in = new Funding();
                in.blockHash = g.getHash();
                in.txHash = g.getTransactions().get(0).getHash();
                in.index = 0;
                in.value = genesisValue();
                System.out.println("START from genesis output (" + in.value + " sat) at batch 0/" + batches);
            }
        }
        for (int b = startBatch; b < batches; b++) {
            List<UtilGeneseBlock.GenesisOutput> part =
                    rows.subList(b * batchRows, Math.min((b + 1) * batchRows, rows.size()));
            Transaction tx = buildBatch(in, part);
            submit(tx);
            String txHash = tx.getHashAsString();
            System.out.println("BATCH " + b + "/" + (batches - 1) + " SUBMITTED tx=" + txHash
                    + " rows=" + part.size());
            String inBlock = waitInBlock(txHash);
            // Next funding = this tx's change output (last output) when present.
            BigInteger paid = BigInteger.ZERO;
            for (UtilGeneseBlock.GenesisOutput r : part) paid = paid.add(r.amount);
            BigInteger change = in.value.subtract(paid).subtract(feeSat);
            Funding next = null;
            if (change.signum() > 0) {
                next = new Funding();
                next.blockHash = Sha256Hash.wrap(inBlock);
                next.txHash = Sha256Hash.wrap(txHash);
                next.index = part.size(); // change is appended after the row outputs
                next.value = change;
            }
            Map<String, Object> p = new HashMap<>();
            p.put("batchIndex", b);
            p.put("batchTx", txHash);
            p.put("batchBlock", inBlock);
            if (next != null) {
                p.put("changeBlockHash", inBlock);
                p.put("changeTxHash", txHash);
                p.put("changeIndex", next.index);
                p.put("changeValue", next.value.toString());
            }
            writeProgress(p);
            System.out.println("BATCH " + b + " CONFIRMED in " + inBlock
                    + (next != null ? (" change=" + change) : " NO_CHANGE"));
            in = next;
            if (in == null && b < batches - 1) {
                System.err.println("ABORT: ran out of funding at batch " + b + " — CSV/fees mis-sized");
                System.exit(2);
            }
        }
        System.out.println("PAYOUT COMPLETE: " + rows.size() + " rows in " + batches
                + " batches — run verify, then destroy the genesis seeds");
    }

    /** Batch that follows the batch whose confirmed change equals
     * {@code changeValue}, or -1 when no batch boundary matches. */
    private int batchAfterChange(List<UtilGeneseBlock.GenesisOutput> rows, BigInteger changeValue) {
        BigInteger paid = BigInteger.ZERO;
        int batches = (rows.size() + batchRows - 1) / batchRows;
        for (int b = 0; b < batches; b++) {
            List<UtilGeneseBlock.GenesisOutput> part =
                    rows.subList(b * batchRows, Math.min((b + 1) * batchRows, rows.size()));
            for (UtilGeneseBlock.GenesisOutput r : part) paid = paid.add(r.amount);
            BigInteger change = genesisValue().subtract(paid)
                    .subtract(feeSat.multiply(BigInteger.valueOf(b + 1)));
            if (change.equals(changeValue)) return b + 1;
        }
        return -1;
    }

    private boolean anyFunded(Map<String, BigInteger> need, Map<String, BigInteger> have) {
        for (Map.Entry<String, BigInteger> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), BigInteger.ZERO).signum() > 0) return true;
        }
        return false;
    }

    private void phaseVerify() throws Exception {
        List<UtilGeneseBlock.GenesisOutput> rows = activeRows(loadCsv());
        List<String> keys = new ArrayList<>();
        for (UtilGeneseBlock.GenesisOutput r : rows) keys.add(rowLookupKey(params, r));
        Map<String, BigInteger> need = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) need.merge(keys.get(i), rows.get(i).amount, BigInteger::add);
        Map<String, BigInteger> have = confirmedTotals(new ArrayList<>(need.keySet()));
        int missing = 0;
        for (int i = 0; i < rows.size() && missing < 20; i++) {
            BigInteger got = have.getOrDefault(keys.get(i), BigInteger.ZERO);
            if (got.compareTo(need.get(keys.get(i))) < 0) {
                missing++;
                String who = rows.get(i).address != null && !rows.get(i).address.trim().isEmpty()
                        ? rows.get(i).address.trim() : ("pubkey:" + rows.get(i).pubkeyHex.substring(0, 16) + "…");
                System.out.println("MISSING row=" + i + " " + who + " need=" + need.get(keys.get(i))
                        + " have=" + got);
            }
        }
        int missingKeys = 0;
        for (Map.Entry<String, BigInteger> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), BigInteger.ZERO).compareTo(e.getValue()) < 0) missingKeys++;
        }
        System.out.println("VERIFY: funded " + (need.size() - missingKeys) + "/" + need.size() + " addresses");
        if (missingKeys > 0) System.exit(2);
    }

    private void phaseResume(String txHashHex) throws Exception {
        TxStatus s = txStatus(txHashHex);
        if (s.blockHash == null || s.blockHash.isEmpty() || "null".equals(s.blockHash)) {
            System.err.println("ABORT: tx " + txHashHex + " is not in any block (status=" + s.status + ")");
            System.exit(2);
        }
        Block b = fetchBlock(s.blockHash);
        net.bigtangle.core.Transaction found = null;
        for (net.bigtangle.core.Transaction t : b.getTransactions()) {
            if (t.getHashAsString().equalsIgnoreCase(txHashHex)) {
                found = t;
                break;
            }
        }
        if (found == null) {
            System.err.println("ABORT: tx not present in block " + s.blockHash);
            System.exit(2);
        }
        // The change output is the last one paying the ceremony multisig script.
        byte[] ms = multisigScript.getProgram();
        int changeIdx = -1;
        BigInteger changeVal = null;
        List<TransactionOutput> outs = found.getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            if (java.util.Arrays.equals(outs.get(i).getScriptPubKey().getProgram(), ms)) {
                changeIdx = i;
                changeVal = outs.get(i).getValue().getValue();
            }
        }
        if (changeIdx < 0) {
            System.err.println("ABORT: tx has no change output to the ceremony multisig — "
                    + "it is not one of ours (or it was the final batch: nothing to resume)");
            System.exit(2);
        }
        System.out.println("CHANGE_OUTPOINT=" + s.blockHash + ":" + txHashHex + ":" + changeIdx + ":" + changeVal);
        System.out.println("Re-run payout with CHANGE_OUTPOINT set to the line above.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: GenesisPayoutTool <genesis|preflight|dryrun|payout|verify|resume> [...]");
            System.err.println("  genesis               print locally recomputed genesis (offline, no node)");
            System.err.println("  dryrun                build+sign+verify batch 0 offline (no node, nothing sent)");
            System.err.println("  preflight             read-only checks against L0_URL + GENESIS_CSV");
            System.err.println("  payout                pay the CSV in batches (resumable, progress-tracked)");
            System.err.println("  verify                every CSV row funded (exit 0 iff complete)");
            System.err.println("  resume <txHash>       print CHANGE_OUTPOINT for a submitted batch tx");
            System.exit(1);
        }
        GenesisPayoutTool t = setup("payout".equals(args[0]) || "dryrun".equals(args[0]));
        switch (args[0]) {
            case "genesis": t.phaseGenesis(); break;
            case "dryrun": t.phaseDryrun(); break;
            case "preflight": t.phasePreflight(); break;
            case "payout": t.phasePayout(); break;
            case "verify": t.phaseVerify(); break;
            case "resume":
                if (args.length < 2) {
                    System.err.println("usage: GenesisPayoutTool resume <txHash>");
                    System.exit(1);
                }
                t.phaseResume(args[1]);
                break;
            default:
                System.err.println("unknown phase " + args[0]);
                System.exit(1);
        }
    }
}
