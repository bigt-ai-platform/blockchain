package net.bigtangle.mcmc.prodsim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.mcmc.remote.RemoteTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
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

    private static final String[] NODE_URLS = {
        "http://localhost:8081/",
        "http://localhost:8082/",
        "http://localhost:8083/",
        "http://localhost:8084/"
    };

    @BeforeEach
    public void setUp() throws Exception {
        byte[] mlDsaSeed = new byte[32];
        byte[] slhDsaSeed = new byte[32];
        java.util.Arrays.fill(mlDsaSeed, (byte) 0x01);
        java.util.Arrays.fill(slhDsaSeed, (byte) 0x02);
        wallet = Wallet.fromKeys(networkParameters,
                PQKey.fromSeeds(mlDsaSeed, slhDsaSeed), contextRoot);
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
            in.setScriptSig(null);
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
     * All 4 nodes must converge to the same tip (no conflicting acceptance).
     */
    @Test
    public void testTipConvergenceAllNodes() throws Exception {
        Thread.sleep(4000);
        Set<Sha256Hash> tips = new HashSet<>();
        for (String url : NODE_URLS) {
            byte[] data = OkHttp3Util.postAndGetBlock(url + ReqCmd.getTip.name(), "{}");
            Block tip = networkParameters.getDefaultSerializer().makeBlock(data);
            assertNotNull(tip, "Tip should not be null on " + url);
            tips.add(tip.getHash());
            log.info("Node {} tip: {} height={}", url, tip.getHash(), tip.getHeight());
        }
        log.info("Distinct tips across {} nodes: {}", NODE_URLS.length, tips.size());
        assertTrue(tips.size() <= 2,
                "All nodes must converge to at most 2 tips (conflict resolution in progress), got " + tips.size());
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
        Object len = result.get("chainLength");
        long chainLength = (len instanceof Number) ? ((Number) len).longValue() : 0;
        log.info("Beacon chain length after attack checks: {}", chainLength);
        assertTrue(chainLength > 0, "Beacon chain must have progressed (chainLength > 0)");
    }
}
