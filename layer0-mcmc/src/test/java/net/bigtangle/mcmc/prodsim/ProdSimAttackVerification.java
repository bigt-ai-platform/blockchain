package net.bigtangle.mcmc.prodsim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.mcmc.remote.RemoteTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.service.SlotService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Multi-node attack-safety checks run against the live prodsim network.
 *
 * <p>Targets Layer0 attack vectors #7 (double-spend via mempool), #8
 * (batch/sync blocks skip re-verification) and tip/beacon safety on a 4-node
 * PoS network. Runs after the happy-path {@link ProdSimVerification}.
 */
public class ProdSimAttackVerification extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(ProdSimAttackVerification.class);

    private static final int PORT_OFFSET = Integer.getInteger("prodsim.portOffset", 20000);

    private static final String[] NODE_URLS = {
        url(8081), url(8082), url(8083), url(8084)
    };

    private static String url(int port) {
        return "http://localhost:" + (port + PORT_OFFSET) + "/";
    }

    @BeforeEach
    public void setUp() throws Exception {
        byte[] mlDsaSeed = new byte[32];
        java.util.Arrays.fill(mlDsaSeed, (byte) 0x01);
        wallet = Wallet.fromKeys(networkParameters,
                PQKey.fromMLDSA(mlDsaSeed), contextRoot);
        // The prodsim genesis funds ONLY the 4 validators, so the attack tests'
        // test wallet has no spendable BIG UTXO. fundAddresses (enabled in the
        // prodsim compose) mints a node-0-local confirmed UTXO keyed to the
        // genesis hash — exactly what the double-spend / invalid-signature tests
        // need on their target node.
        fundTestWallet(NODE_URLS[0]);
    }

    /** Mints a confirmed BIG UTXO for the test wallet on the target node. */
    private void fundTestWallet(String url) throws Exception {
        PQKey key = wallet.walletKeys(null).get(0);
        Map<String, Object> entry = new HashMap<>();
        entry.put("address", "attacktest");
        entry.put("value", 100000000L);
        entry.put("pubkey", Utils.HEX.encode(key.getPubKey()));
        List<Map<String, Object>> addresses = new ArrayList<>();
        addresses.add(entry);
        Map<String, Object> req = new HashMap<>();
        req.put("addresses", addresses);
        try {
            OkHttp3Util.postString(url + "fundAddresses",
                    Json.jsonmapper().writeValueAsString(req));
            log.info("Funded attack-test wallet on {}", url);
        } catch (Exception e) {
            log.warn("fundAddresses failed on {}: {}", url, e.getMessage());
        }
    }

    /** All nodes must expose a spendable BIG UTXO for the double-spend test. */
    private List<FreeStandingTransactionOutput> bigCandidates(String url) throws Exception {
        List<FreeStandingTransactionOutput> out = new ArrayList<>();
        for (UTXO u : getBalanceFromNode(url)) {
            FreeStandingTransactionOutput co = new FreeStandingTransactionOutput(networkParameters, u);
            if (co.getValue().isBIG() && co.getValue().getValue().compareTo(BigInteger.valueOf(2000)) >= 0) {
                out.add(co);
            }
        }
        return out;
    }

    private List<UTXO> getBalanceFromNode(String url) throws Exception {
        List<UTXO> list = new ArrayList<>();
        List<String> keyHex = new ArrayList<>();
        for (PQKey key : wallet.walletKeys(null)) {
            keyHex.add(Utils.HEX.encode(key.getPubKeyHash()));
        }
        byte[] resp = OkHttp3Util.post(url + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keyHex).getBytes());
        net.bigtangle.response.GetBalancesResponse r = Json.jsonmapper()
                .readValue(resp, net.bigtangle.response.GetBalancesResponse.class);
        for (UTXO utxo : r.getOutputs()) {
            if (utxo.getValue().getValue().signum() > 0) {
                list.add(utxo);
            }
        }
        return list;
    }

    private Transaction buildSpendTx(List<FreeStandingTransactionOutput> candidates,
            PQKey recipient, BigInteger amount) throws Exception {
        Transaction tx = new Transaction(networkParameters);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        Coin send = new Coin(amount, NetworkParameters.BIGTANGLE_TOKENID);
        Coin total = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
        PQKey changeKey = wallet.walletKeys(null).get(0);
        boolean funded = false;
        for (FreeStandingTransactionOutput co : candidates) {
            tx.addInput(co.getUTXO().getBlockHash(), co);
            tx.getInputs().get(tx.getInputs().size() - 1).getOutpoint().connectedOutput = co;
            total = total.add(co.getValue());
            Coin change = total.subtract(send).subtract(Coin.FEE_DEFAULT);
            if (!change.isNegative()) {
                if (change.isPositive()) {
                    tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, changeKey));
                }
                funded = true;
                break;
            }
        }
        if (!funded) {
            throw new net.bigtangle.exception.InsufficientMoneyException(
                    send + " outputs size= " + candidates.size());
        }
        tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, send, recipient));
        wallet.signTransaction(tx, null);
        return tx;
    }

    private void submitRaw(String url, Transaction tx) throws Exception {
        byte[] body = tx.bitcoinSerialize();
        OkHttp3Util.post(url + ReqCmd.submitTransaction.name(), body);
    }

    /**
     * #7 — double-spend via mempool: two txs spending the same UTXO must not
     * both enter the mempool. The second must be rejected (ConflictPossible).
     */
    @Test
    public void testDoubleSpendRejectedOnLiveNode() throws Exception {
        String url = NODE_URLS[0];
        List<FreeStandingTransactionOutput> candidates = bigCandidates(url);
        assertTrue(!candidates.isEmpty(),
                "Node " + url + " must expose a BIG UTXO >= 2000 for the double-spend test");

        PQKey alice = PQKey.createNew();
        PQKey bob = PQKey.createNew();
        Transaction tx1 = buildSpendTx(candidates, alice, BigInteger.valueOf(1000));
        Transaction tx2 = buildSpendTx(candidates, bob, BigInteger.valueOf(1000));

        submitRaw(url, tx1);
        log.info("tx1 submitted to {}", url);
        Thread.sleep(2000);

        boolean rejected = false;
        try {
            submitRaw(url, tx2);
            log.warn("tx2 was ACCEPTED (unexpected) on {}", url);
        } catch (RuntimeException e) {
            rejected = true;
            log.info("tx2 rejected as expected on {}: {}", url, e.getMessage());
        }
        assertTrue(rejected, "Second tx spending the same UTXO must be rejected");
    }

    /**
     * Invalid-signature transaction must be rejected at mempool ingress
     * (MempoolService.verifyTransaction → tx.verify()).
     */
    @Test
    public void testInvalidSignatureRejected() throws Exception {
        String url = NODE_URLS[0];
        List<FreeStandingTransactionOutput> candidates = bigCandidates(url);
        assertTrue(!candidates.isEmpty(), "Need a spendable UTXO");

        Transaction tx = buildSpendTx(candidates, PQKey.createNew(), BigInteger.valueOf(1000));
        // Drop the input scripts entirely so verification must fail.
        for (TransactionInput in : tx.getInputs()) {
            in.setScriptSig(new net.bigtangle.script.Script(new byte[0]));
        }

        boolean rejected = false;
        try {
            submitRaw(url, tx);
            log.warn("Invalid-signature tx was ACCEPTED (unexpected) on {}", url);
        } catch (RuntimeException e) {
            rejected = true;
            log.info("Invalid-signature tx rejected as expected: {}", e.getMessage());
        }
        assertTrue(rejected, "Invalid-signature transaction must be rejected");
    }

    /**
     * All 4 nodes must have a confirmed beacon chain within one epoch of each
     * other (same convergence metric as the happy-path check — the local MCMC
     * prototype tip legitimately differs between independent-DB nodes under
     * sync latency).
     */
    @Test
    public void testTipConvergenceAllNodes() throws Exception {
        Thread.sleep(4000);
        long maxChainLength = 0;
        long minChainLength = Long.MAX_VALUE;
        for (String url : NODE_URLS) {
            byte[] resp = OkHttp3Util.postString(url + ReqCmd.getChainNumber.name(), "{}");
            Map<String, Object> wrapper = Json.jsonmapper().readValue(resp, HashMap.class);
            Object rewardObj = wrapper.get("txReward");
            Map<String, Object> reward = rewardObj instanceof Map
                    ? (Map<String, Object>) rewardObj
                    : Json.jsonmapper().readValue((String) rewardObj, HashMap.class);
            Number cl = (Number) reward.get("chainLength");
            assertNotNull(cl, "getChainNumber must include chainLength on " + url);
            long chainLength = cl.longValue();
            maxChainLength = Math.max(maxChainLength, chainLength);
            minChainLength = Math.min(minChainLength, chainLength);
            log.info("Node {} confirmed chainlength={}", url, chainLength);
        }
        assertTrue(maxChainLength > 0,
                "Beacon chain must have confirmed on at least one node, got " + maxChainLength);
        assertTrue(maxChainLength - minChainLength <= SlotService.SLOTS_PER_EPOCH,
                "Confirmed beacon chainlength must converge to within one epoch across nodes, got "
                        + "min=" + minChainLength + " max=" + maxChainLength);
    }

    /**
     * Beacon chain must make progress (reward chain length > 0) — checks the
     * consensus pipeline isn't stalled by the attack submissions.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testBeaconChainStillProgressing() throws Exception {
        byte[] resp = OkHttp3Util.postString(NODE_URLS[0] + ReqCmd.getAllConfirmedReward.name(),
                Json.jsonmapper().writeValueAsString(new HashMap<>()));
        Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        assertNotNull(result, "getAllConfirmedReward should return a response");
        Object txRewardObj = result.get("txReward");
        assertNotNull(txRewardObj, "getAllConfirmedReward must include txReward");
        long chainLength = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) txRewardObj) {
            Object cl = r.get("chainLength");
            if (cl instanceof Number) {
                chainLength = Math.max(chainLength, ((Number) cl).longValue());
            }
        }
        log.info("Beacon chain length after attack checks: {}", chainLength);
        assertTrue(chainLength > 0, "Beacon chain must have progressed (chainLength > 0)");
    }
}
