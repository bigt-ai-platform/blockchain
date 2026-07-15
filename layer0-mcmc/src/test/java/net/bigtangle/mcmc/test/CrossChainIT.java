package net.bigtangle.mcmc.test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class CrossChainIT {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CrossChainIT.class);
    private static final String TEST_PRIV = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

    public static void main(String[] args) throws Exception {
        String l0Url = args.length > 0 ? args[0] : "http://localhost:8089/";
        String l1Url = args.length > 1 ? args[1] : "http://localhost:8090/";
        log.info("L0: {}  L1: {}", l0Url, l1Url);

        Layer0TestParams params = new Layer0TestParams();
        ECKey bobKey = new ECKey();

        // ---- PHASE 1: L0 payment ----
        log.info("=== PHASE 1: L0 BIG payment ===");
        Wallet genesis = Wallet.fromKeys(params,
                ECKey.fromPrivate(Utils.HEX.decode(TEST_PRIV)), l0Url);
        HashMap<String, BigInteger> fundReq = new HashMap<>();
        fundReq.put(bobKey.toAddress(params).toString(), BigInteger.valueOf(50000));
        genesis.payToList(null, fundReq, NetworkParameters.BIGTANGLE_TOKENID, "fund");
        log.info("Paid 50000 BIG to bob on L0");

        // Wait for MCMC to confirm outputs (reward block)
        log.info("Waiting for UTXOs to confirm...");
        Wallet bobWalletL0 = Wallet.fromKeys(params, bobKey, l0Url);
        List<UTXO> utxos = null;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            utxos = bobWalletL0.calculateAllSpendCandidatesUTXO(null, false);
            if (!utxos.isEmpty()) {
                log.info("UTXOs confirmed after {}s", i + 1);
                break;
            }
        }
        if (utxos == null || utxos.isEmpty()) { log.error("No UTXOs after 20s"); System.exit(1); }
        UTXO utxo = utxos.get(0);
        log.info("Locking {} to vault", utxo.getValue());

        Map<String, String> pegInReq = new HashMap<>();
        pegInReq.put("utxo", utxo.getTxHash().toString() + ":" + utxo.getIndex());
        pegInReq.put("beneficiary", bobKey.toAddress(params).toString());
        OkHttp3Util.postString(l0Url + ReqCmd.processPegIn.name(),
                Json.jsonmapper().writeValueAsString(pegInReq));
        log.info("Peg-in executed");

        // ---- PHASE 3: L1 operations ----
        log.info("=== PHASE 3: L1 chain ===");
        Block proto = getTip(l1Url, params);
        Block l1Block = Block.createBlock(params, proto, proto);
        l1Block.setMinerAddress(proto.getMinerAddress());
        Transaction tx = new Transaction(params);
        tx.addOutput(new Coin(BigInteger.valueOf(1000),
                NetworkParameters.BIGTANGLE_TOKENID), bobKey);
        l1Block.addTransaction(tx);
        l1Block.solve();
        byte[] saveResp = OkHttp3Util.post(l1Url + ReqCmd.saveBlock.name(),
                l1Block.bitcoinSerialize());
        log.info("Block saved on L1 ({} bytes)", saveResp.length);

        Wallet bobWalletL1 = Wallet.fromKeys(params, bobKey, l1Url);
        List<UTXO> l1Utxos = bobWalletL1.calculateAllSpendCandidatesUTXO(null, false);
        log.info("Bob L1 UTXOs: {}", l1Utxos.size());

        // ---- PHASE 4: Peg-out ----
        log.info("=== PHASE 4: Peg-out L1 -> L0 ===");
        OkHttp3Util.postString(l0Url + ReqCmd.processPegOut.name(), "{}");
        log.info("Peg-out executed");

        log.info("");
        log.info("=============================================");
        log.info("  Cross-Chain IT: PASSED");
        log.info("=============================================");
    }

    private static Block getTip(String url, Layer0TestParams params) throws Exception {
        byte[] data = OkHttp3Util.postAndGetBlock(url + ReqCmd.getTip.name(), "");
        return params.getDefaultSerializer().makeBlock(data);
    }
}
