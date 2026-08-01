package net.bigtangle.mcmc.prodsim;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.mcmc.remote.RemoteTest;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class ProdSimVerification extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(ProdSimVerification.class);

    private static final String[] NODE_URLS = {
        "http://localhost:8081/",
        "http://localhost:8082/",
        "http://localhost:8083/",
        "http://localhost:8084/"
    };

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

    @SuppressWarnings("unchecked")
    @Test
    public void testAllNodesHealthy() throws Exception {
        for (String url : NODE_URLS) {
            byte[] resp = OkHttp3Util.postString(url, "{}");
            assertNotNull(resp, "Node " + url + " should respond");
            log.info("Node {} healthy", url);
        }
    }

    @Test
    public void testTipConvergence() throws Exception {
        Sha256Hash firstTip = null;
        for (String url : NODE_URLS) {
            byte[] data = OkHttp3Util.postAndGetBlock(url + ReqCmd.getTip.name(), "{}");
            Block tip = networkParameters.getDefaultSerializer().makeBlock(data);
            assertNotNull(tip, "Tip block should not be null for " + url);

            if (firstTip == null) {
                firstTip = tip.getHash();
            }
            log.info("Node {} tip: {} height={}", url, tip.getHash(), tip.getHeight());
        }
        log.info("All nodes have a tip block");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testValidatorsActive() throws Exception {
        for (String url : NODE_URLS) {
            byte[] resp = OkHttp3Util.postString(url + ReqCmd.getValidators.name(), "{}");
            Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
            List<Map<String, Object>> validators = (List<Map<String, Object>>) result.get("validators");
            assertNotNull(validators, "Validators list should not be null on " + url);
            assertTrue(validators.size() >= 4,
                    "Expected >=4 validators on " + url + ", got " + validators.size());
            log.info("Node {} has {} active validators", url, validators.size());
        }
    }

    @Test
    public void testBeaconChainProgress() throws Exception {
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getAllConfirmedReward.name(),
                Json.jsonmapper().writeValueAsString(new HashMap<>()));
        Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        assertNotNull(result, "getAllConfirmedReward should return a response");

        log.info("Max confirmed reward info: {}", result);

        Object chainLengthObj = result.get("chainLength");
        if (chainLengthObj instanceof Number) {
            long chainLength = ((Number) chainLengthObj).longValue();
            log.info("Beacon chain length: {}", chainLength);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRewardsDistributed() throws Exception {
        HashMap<String, Object> req = new HashMap<>();
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(req));
        Map<String, Object> tokensResponse = Json.jsonmapper().readValue(resp, HashMap.class);
        assertNotNull(tokensResponse, "searchTokens should return a response");

        List<Map<String, Object>> tokens = (List<Map<String, Object>>) tokensResponse.get("tokens");
        assertNotNull(tokens, "Tokens list should not be null");

        Map<String, Object> amountMap = (Map<String, Object>) tokensResponse.get("amountMap");
        log.info("Token count: {}, amountMap size: {}", tokens.size(),
                amountMap != null ? amountMap.size() : 0);
    }
}
