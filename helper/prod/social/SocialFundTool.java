import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.KeyBundle;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * SocialFundTool — fund the L1 SOCIAL chain from an exported PQ wallet
 * (v1.json style) and make the l1-social-server pair validators.
 *
 * <p>Chain model: L1-social (chainId "SOCIAL") does NOT mint bc in genesis
 * (genesisMintsBIG=false). BIG reaches it only via a vault peg-in: the funding
 * wallet locks a whole confirmed L0 UTXO to the vault script (single-key mode:
 * P2PKH to the vault pubkey) for chainId SOCIAL and beneficiary = the same
 * wallet's pubkey hash; the l1-social node's PegInWatcherService mints the
 * wrapped amount 1:1 on the SOCIAL store, after which the node can stakeDeposit
 * + activateValidator.
 *
 * <p>Phases (argv[1]):
 * <ul>
 * <li>preflight   <sat>            read-only: print wallet/address, confirmed
 *                                  spendable bc UTXOs, total, and whether an
 *                                  exact <sat> UTXO exists (abort code 2 if the
 *                                  wallet is unusable / not funded).</li>
 * <li>split       <sat>            ensure an exact <sat> self-UTXO exists
 *                                  (self-transfer if only larger UTXOs exist).</li>
 * <li>pegin       <sat>            lock the exact <sat> UTXO to the vault for
 *                                  chainId SOCIAL (beneficiary = wallet pubkey).</li>
 * <li>stake       <sat>            wait for the SOCIAL node(s) to mint, then
 *                                  stakeDeposit + activateValidator on each URL
 *                                  in SOCIAL_URLS (needs API_KEY).</li>
 * </ul>
 *
 * <p>Env: L0_URL (default https://eu1.bigtangle.org), SOCIAL_URLS (space list,
 * default https://socialeu1.bigtangle.org https://socialeu2.bigtangle.org),
 * API_KEY (X-Api-Key for stake/activate).
 *
 * <p>Runs as a single-file java source against the unpacked layer0 exec jar
 * (see prod-social-fund.sh).
 */
public class SocialFundTool {
    private static final String CHAIN_ID = "SOCIAL";
    private static final BigInteger SAT_PER_BIG = BigInteger.TEN.pow(
            NetworkParameters.BIGTANGLE_DECIMAL); // 1 BIG = 10^6 sat

    private final NetworkParameters params;
    private final PQKey key;
    private final Address addr;
    private final String pubkeyHex;

    private SocialFundTool(NetworkParameters params, PQKey key, Address addr) {
        this.params = params;
        this.key = key;
        this.addr = addr;
        this.pubkeyHex = Utils.HEX.encode(key.getPubKey());
    }

    // ---- JSON wallet load ----------------------------------------------------
    @SuppressWarnings("unchecked")
    private static SocialFundTool loadWallet(String walletJson, NetworkParameters params) throws Exception {
        ObjectMapper m = Json.jsonmapper();
        Map<String, Object> root = m.readValue(new java.io.File(walletJson), Map.class);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) root.get("keys");
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("wallet has no keys[]");
        }
        Map<String, Object> k = keys.get(0);
        String keyType = String.valueOf(k.get("keyType"));
        if (!"PQ".equals(keyType)) {
            throw new IllegalArgumentException("unsupported keyType " + keyType + " (only PQ wallets)");
        }
        byte[] pubPrefixed = Utils.HEX.decode(String.valueOf(k.get("pubkey")));
        byte[] bundleBytes = pubPrefixed.length > 0 && (pubPrefixed[0] & 0xFF) == 0x05
                ? java.util.Arrays.copyOfRange(pubPrefixed, 1, pubPrefixed.length)
                : pubPrefixed;
        KeyBundle bundle = KeyBundle.deserialize(bundleBytes);
        byte[] priv = Utils.HEX.decode(String.valueOf(k.get("privateKey")));
        // privateKey = [u32 mlLen][mlPriv][u32 slhLen][slhPriv] (encodePrivateKeys)
        int mlLen = ((priv[0] & 0xFF) << 24) | ((priv[1] & 0xFF) << 16)
                | ((priv[2] & 0xFF) << 8) | (priv[3] & 0xFF);
        byte[] mlPriv = java.util.Arrays.copyOfRange(priv, 4, 4 + mlLen);
        int off = 4 + mlLen;
        int slhLen = ((priv[off] & 0xFF) << 24) | ((priv[off + 1] & 0xFF) << 16)
                | ((priv[off + 2] & 0xFF) << 8) | (priv[off + 3] & 0xFF);
        byte[] slhPriv = off + 4 + slhLen <= priv.length
                ? java.util.Arrays.copyOfRange(priv, off + 4, off + 4 + slhLen) : null;
        PQKey key = PQKey.fromPrivateKeyBundle(mlPriv, slhPriv, bundle);
        Address addr = Address.fromHash160(params, key.getPubKeyHash());
        return new SocialFundTool(params, key, addr);
    }

    // ---- L0 API ---------------------------------------------------------------
    private String l0Url() {
        String u = System.getenv("L0_URL");
        return (u == null || u.isEmpty() ? "https://eu1.bigtangle.org" : u).replaceAll("/+$", "") + "/";
    }

    private List<String> socialUrls() {
        String u = System.getenv("SOCIAL_URLS");
        String d = "https://socialeu1.bigtangle.org https://socialeu2.bigtangle.org";
        List<String> out = new ArrayList<>();
        for (String s : (u == null || u.isEmpty() ? d : u).trim().split("\\s+")) {
            if (!s.isEmpty()) out.add(s.replaceAll("/+$", ""));
        }
        return out;
    }

    private String apiKey() {
        return System.getenv("API_KEY") == null ? "" : System.getenv("API_KEY");
    }

    private List<UTXO> spendableBc() throws Exception {
        ObjectMapper m = Json.jsonmapper();
        byte[] resp = OkHttp3Util.postString(l0Url() + "getBalances",
                m.writeValueAsString(List.of(Utils.HEX.encode(key.getPubKeyHash()))));
        GetBalancesResponse bal = m.readValue(resp, GetBalancesResponse.class);
        List<UTXO> out = new ArrayList<>();
        for (UTXO u : bal.getOutputs()) {
            if (u == null || u.getValue() == null || u.isSpent() || !u.isConfirmed()) continue;
            if (java.util.Arrays.equals(u.getValue().getTokenid(), NetworkParameters.BIGTANGLE_TOKENID)) {
                out.add(u);
            }
        }
        return out;
    }

    private UTXO exactUtxo(List<UTXO> utxos, BigInteger sat) {
        for (UTXO u : utxos) {
            if (u.getValue().getValue().compareTo(sat) == 0) return u;
        }
        return null;
    }

    // ---- phases ----------------------------------------------------------------
    private void preflight(BigInteger sat) throws Exception {
        System.out.println("ADDRESS=" + addr.toBase58());
        System.out.println("PUBKEY=" + pubkeyHex);
        System.out.println("VAULT_ADDR=" + addr.toBase58() + " (single-key vault = wallet pubkey)");
        System.out.println("L0=" + l0Url());
        System.out.println("TARGET_SAT=" + sat);
        List<UTXO> utxos = spendableBc();
        BigInteger total = BigInteger.ZERO;
        for (UTXO u : utxos) {
            total = total.add(u.getValue().getValue());
        }
        System.out.println("CONFIRMED_BC_UTXOS=" + utxos.size());
        System.out.println("CONFIRMED_BC_TOTAL_SAT=" + total);
        System.out.println("EXACT_UTXO=" + (exactUtxo(utxos, sat) != null ? "yes" : "no"));
        System.out.println("SUITABLE=" + (utxos.isEmpty() ? "NO" : (exactUtxo(utxos, sat) != null ? "EXACT" : "SPLIT")));
        if (utxos.isEmpty() || total.compareTo(sat) < 0) {
            System.err.println("ABORT: wallet has no confirmed spendable bc >= target on " + l0Url());
            System.exit(2);
        }
    }

    /** Ensure a confirmed UTXO of exactly `sat` exists at the wallet address. */
    private void split(BigInteger sat) throws Exception {
        List<UTXO> utxos = spendableBc();
        if (exactUtxo(utxos, sat) != null) {
            System.out.println("EXACT_UTXO_ALREADY_PRESENT");
            return;
        }
        UTXO source = null;
        for (UTXO u : utxos) { // largest first
            if (u.getValue().getValue().compareTo(sat) > 0) {
                if (source == null || u.getValue().getValue().compareTo(source.getValue().getValue()) > 0) {
                    source = u;
                }
            }
        }
        if (source == null) {
            System.err.println("ABORT: no UTXO larger than target to split");
            System.exit(2);
        }
        BigInteger fee = Coin.FEE_DEFAULT.getValue();
        BigInteger change = source.getValue().getValue().subtract(sat).subtract(fee);
        if (change.signum() < 0) {
            System.err.println("ABORT: cannot afford fee on split (need UTXO > target + fee)");
            System.exit(2);
        }
        Transaction tx = new Transaction(params);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        TransactionInput in = tx.addInput(source.getBlockHash(), source.getTxHash(), source.getIndex(),
                source.getScript() == null ? new Script(new byte[0]) : source.getScript());
        tx.addOutput(new Coin(sat, NetworkParameters.BIGTANGLE_TOKENID), addr);
        tx.addOutput(new Coin(change, NetworkParameters.BIGTANGLE_TOKENID), addr);
        signInput(tx, in, source.getScript(), 0);
        submit(tx);
        System.out.println("SPLIT_TXID=" + tx.getHashAsString());
    }

    private UTXO waitExact(BigInteger sat, int tries) throws Exception {
        for (int i = 0; i < tries; i++) {
            UTXO u = exactUtxo(spendableBc(), sat);
            if (u != null) return u;
            Thread.sleep(3000);
        }
        return null;
    }

    private void pegin(BigInteger sat) throws Exception {
        UTXO source = waitExact(sat, 40);
        if (source == null) {
            System.err.println("ABORT: exact " + sat + " UTXO never confirmed (run split first / wait longer)");
            System.exit(2);
        }
        // Single-key vault script == P2PKH to the wallet pubkey (wallet == vault).
        Transaction tx = new Transaction(params);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        tx.setToAddressInSubtangle(addr.getHash160()); // beneficiary == wallet pubkey hash
        tx.setDataClassName("PegInInfo");
        tx.setData(Json.jsonmapper().writeValueAsBytes(Map.of("chainId", CHAIN_ID)));
        tx.addInput(source.getBlockHash(), new net.bigtangle.wallet.FreeStandingTransactionOutput(params, source));
        tx.getInputs().get(0).getOutpoint().connectedOutput =
                new net.bigtangle.wallet.FreeStandingTransactionOutput(params, source);
        tx.addOutput(source.getValue(), addr); // pays vault script (P2PKH to self)
        signInput(tx, tx.getInputs().get(0), source.getScript(), 0);
        byte[] resp = OkHttp3Util.post(l0Url() + "processPegIn", tx.bitcoinSerialize());
        System.out.println("PEGIN_RESP=" + new String(resp, java.nio.charset.StandardCharsets.UTF_8).replace('\n', ' '));
        System.out.println("PEGIN_TXID=" + tx.getHashAsString());
        System.out.println("PEGIN_AMOUNT_SAT=" + source.getValue().getValue());
    }

    private void stake(BigInteger sat) throws Exception {
        ObjectMapper m = Json.jsonmapper();
        List<String> urls = socialUrls();
        // Wait for the SOCIAL node(s) to mint the wrapped peg-in to the beneficiary.
        System.out.println("waiting for L1 SOCIAL mint of " + sat + " sat ...");
        boolean minted = false;
        for (int i = 0; i < 80; i++) {
            for (String u : urls) {
                byte[] resp = OkHttp3Util.postString(u + "/getBalances",
                        m.writeValueAsString(List.of(Utils.HEX.encode(key.getPubKeyHash()))));
                GetBalancesResponse bal = m.readValue(resp, GetBalancesResponse.class);
                BigInteger total = BigInteger.ZERO;
                for (UTXO x : bal.getOutputs()) {
                    if (x == null || x.getValue() == null || x.isSpent() || !x.isConfirmed()) continue;
                    if (java.util.Arrays.equals(x.getValue().getTokenid(), NetworkParameters.BIGTANGLE_TOKENID)) {
                        total = total.add(x.getValue().getValue());
                    }
                }
                System.out.println("  L1 " + u + " balance_sat=" + total);
                if (total.compareTo(sat) >= 0) minted = true;
            }
            if (minted) break;
            Thread.sleep(4000);
        }
        if (!minted) {
            System.err.println("ABORT: L1 SOCIAL never minted " + sat
                    + " sat — check bridge.active + vault pubkey + anchor.l0Url on the socialeu nodes");
            System.exit(3);
        }
        if (apiKey().isEmpty()) {
            System.err.println("ABORT: API_KEY not set (required for stakeDeposit/activateValidator)");
            System.exit(3);
        }
        for (String u : urls) {
            String stakeBody = m.writeValueAsString(Map.of("pubkey", pubkeyHex, "amount", sat.toString()));
            byte[] resp = postJsonKey(u + "/stakeDeposit", stakeBody, apiKey());
            System.out.println("  " + u + " stakeDeposit -> "
                    + new String(resp, java.nio.charset.StandardCharsets.UTF_8).replace('\n', ' '));
            String actBody = m.writeValueAsString(Map.of("pubkey", pubkeyHex, "epoch", 0));
            byte[] resp2 = postJsonKey(u + "/activateValidator", actBody, apiKey());
            System.out.println("  " + u + " activateValidator -> "
                    + new String(resp2, java.nio.charset.StandardCharsets.UTF_8).replace('\n', ' '));
        }
        Thread.sleep(5000);
        for (String u : urls) {
            byte[] v = OkHttp3Util.postString(u + "/getValidators", "{}");
            System.out.println("  " + u + " getValidators -> "
                    + new String(v, java.nio.charset.StandardCharsets.UTF_8).replace('\n', ' ').substring(0, Math.min(500, v.length)));
        }
    }

    private void signInput(Transaction tx, TransactionInput in, Script spendScript, int index) throws Exception {
        if (spendScript == null) spendScript = new Script(new byte[0]);
        Sha256Hash sighash = tx.hashForSignature(index, spendScript.getProgram(),
                Transaction.SigHash.ALL, false);
        in.setScriptSig(ScriptBuilder.createInputScriptForPQ(key.sign(sighash), key));
    }

    private void submit(Transaction tx) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        byte[] b = tx.bitcoinSerialize();
        dos.writeInt(b.length);
        dos.write(b);
        dos.close();
        OkHttp3Util.post(l0Url() + "submitTransactions", baos.toByteArray());
    }

    /** POST JSON with the X-Api-Key header (sensitive endpoints read it, not accessToken). */
    private static byte[] postJsonKey(String url, String body, String apiKey) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body))
                .addHeader("X-Api-Key", apiKey).build();
        okhttp3.Response r = new okhttp3.OkHttpClient().newCall(req).execute();
        try {
            if (!r.isSuccessful()) {
                throw new RuntimeException("HTTP " + r.code() + " on " + url);
            }
            return r.body().bytes();
        } finally {
            r.close();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SocialFundTool <walletJson> <preflight|split|pegin|stake> <sat>");
            System.exit(1);
        }
        String wallet = args[0];
        String cmd = args[1];
        BigInteger sat = new BigInteger(args[2]);
        NetworkParameters params = MainNetParams.get();
        SocialFundTool t = loadWallet(wallet, params);
        switch (cmd) {
            case "preflight": t.preflight(sat); break;
            case "split": t.split(sat); break;
            case "pegin": t.pegin(sat); break;
            case "stake": t.stake(sat); break;
            default:
                System.err.println("unknown phase " + cmd);
                System.exit(1);
        }
    }
}
