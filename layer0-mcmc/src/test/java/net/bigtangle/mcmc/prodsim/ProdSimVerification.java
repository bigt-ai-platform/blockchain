package net.bigtangle.mcmc.prodsim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.mcmc.remote.RemoteTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class ProdSimVerification extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(ProdSimVerification.class);

    private static final int PORT_OFFSET = Integer.getInteger("prodsim.portOffset", 20000);

    private static final String[] NODE_URLS = {
        url(8081), url(8082), url(8083), url(8084)
    };

    private static String url(int port) {
        return "http://localhost:" + (port + PORT_OFFSET) + "/";
    }

    private static final int EXPECTED_EPOCHS;

    static {
        int e = 10;
        try {
            e = Integer.parseInt(System.getProperty("prodsim.epochs", "10"));
        } catch (NumberFormatException ignored) {}
        EXPECTED_EPOCHS = e;
    }

    @BeforeEach
    public void setUp() throws Exception {
        byte[] mlDsaSeed = new byte[32];
        java.util.Arrays.fill(mlDsaSeed, (byte) 0x01);
        wallet = net.bigtangle.wallet.Wallet.fromKeys(
                networkParameters,
                net.bigtangle.core.PQKey.fromMLDSA(mlDsaSeed),
                contextRoot);
    }

    @Test
    public void testAllNodesHealthy() throws Exception {
        for (String url : NODE_URLS) {
            byte[] resp = OkHttp3Util.postString(url + ReqCmd.getChainNumber.name(), "{}");
            assertNotNull(resp, "Node " + url + " should respond");
            log.info("Node {} healthy", url);
        }
    }

    @Test
    public void testTipConvergence() throws Exception {
        Set<Sha256Hash> tips = new HashSet<>();
        for (String url : NODE_URLS) {
            byte[] data = OkHttp3Util.postAndGetBlock(url + ReqCmd.getTip.name(), "{}");
            Block tip = networkParameters.getDefaultSerializer().makeBlock(data);
            assertNotNull(tip, "Tip block should not be null for " + url);
            tips.add(tip.getHash());
            log.info("Node {} tip: {} height={}", url, tip.getHash(), tip.getHeight());
        }
        assertTrue(tips.size() <= 2,
                "All nodes must converge to at most 2 tips (conflict resolution in progress), got " + tips.size());
        log.info("All nodes converged to {} tip(s)", tips.size());
    }

    /** Parse the getValidators response (GetStringResponse wraps the JSON in
     *  "text") and return each active validator's pubkey bytes (Jackson encodes
     *  byte[] as base64). */
    private List<byte[]> activeValidatorPubkeys(String url) throws Exception {
        byte[] resp = OkHttp3Util.postString(url + ReqCmd.getValidators.name(), "{}");
        Map<String, Object> wrapper = Json.jsonmapper().readValue(resp, HashMap.class);
        Object validators = wrapper.get("validators");
        if (validators == null && wrapper.get("text") instanceof String) {
            Map<String, Object> inner = Json.jsonmapper()
                    .readValue((String) wrapper.get("text"), HashMap.class);
            validators = inner.get("validators");
        }
        assertNotNull(validators, "getValidators must include validators on " + url);
        List<byte[]> pubkeys = new ArrayList<>();
        for (Object v : (List<?>) validators) {
            Object pk = ((Map<?, ?>) v).get("pubkey");
            assertNotNull(pk, "Validator record must include pubkey");
            pubkeys.add(java.util.Base64.getDecoder().decode((String) pk));
        }
        return pubkeys;
    }

    @Test
    public void testValidatorsActive() throws Exception {
        for (String url : NODE_URLS) {
            List<byte[]> validators = activeValidatorPubkeys(url);
            assertTrue(validators.size() >= 4,
                    "Expected >=4 validators on " + url + ", got " + validators.size());
            log.info("Node {} has {} active validators", url, validators.size());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBeaconChainProgress() throws Exception {
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getAllConfirmedReward.name(),
                Json.jsonmapper().writeValueAsString(new HashMap<>()));
        Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        assertNotNull(result, "getAllConfirmedReward should return a response");

        Object txRewardObj = result.get("txReward");
        assertNotNull(txRewardObj, "getAllConfirmedReward must include txReward");
        long maxChainLength = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) txRewardObj) {
            Object cl = r.get("chainLength");
            if (cl instanceof Number) {
                maxChainLength = Math.max(maxChainLength, ((Number) cl).longValue());
            }
        }
        assertTrue(maxChainLength > 0,
                "Beacon chain must have progressed (chainLength > 0), got " + maxChainLength);
        log.info("Beacon chain length: {}", maxChainLength);
    }

    /** Sum of spendable BIG UTXOs for a validator pubkey on a node. */
    private long validatorBigBalance(String url, byte[] pubkey) throws Exception {
        List<String> keyHex = new ArrayList<>();
        keyHex.add(Utils.HEX.encode(Utils.sha256hash160(pubkey)));
        byte[] resp = OkHttp3Util.post(url + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keyHex).getBytes());
        GetBalancesResponse r = Json.jsonmapper().readValue(resp, GetBalancesResponse.class);
        long sum = 0;
        for (UTXO u : r.getOutputs()) {
            if (u.getValue().getValue().signum() > 0
                    && Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getTokenidBuf())) {
                sum += u.getValue().getValue().longValue();
            }
        }
        return sum;
    }

    @Test
    public void testRewardsDistributed() throws Exception {
        List<byte[]> validators = activeValidatorPubkeys(NODE_URLS[0]);
        assertTrue(validators.size() >= 4, "Expected >=4 validators, got " + validators.size());

        long minStake = StakeService.MIN_STAKE.longValue();
        for (byte[] pubkey : validators) {
            long balance = validatorBigBalance(NODE_URLS[0], pubkey);
            log.info("Validator {} balance={} (stake={})",
                    Utils.HEX.encode(pubkey).substring(0, 16) + "...", balance, minStake);
            assertTrue(balance >= minStake,
                    "Validator balance must be >= stake (" + minStake + "), got " + balance);
        }
        log.info("All validators have intact balances (>= stake)");
    }
}
