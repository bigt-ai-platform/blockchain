package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Remote integration tests for token operations.
 * Connects to a running L0 server via HTTP API only.
 *
 * Token creation uses the batchBlock endpoint which stores
 * the block in the batch pipeline. The token becomes visible
 * after the server's batch processing cycle completes (5s with
 * blockbatchrate=5000, or 50s by default).
 */
public class RemoteTokenTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteTokenTests.class);

    @Test
    public void testServerHealth() throws Exception {
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(MediaType.parse("application/octet-stream; charset=utf-8"), "");
        Request request = new Request.Builder().url(contextRoot).post(body).build();
        Response response = client.newCall(request).execute();
        String respBody = response.body().string();
        assertTrue(respBody.contains("Bigtangle") || respBody.contains("duration") || respBody.isEmpty(),
                "Server should respond at root: " + respBody);
    }

    @Test
    public void testGenesisTokenExists() throws Exception {
        GetTokensResponse tokensResponse = wallet.searchTokens(null);
        assertNotNull(tokensResponse, "searchTokens should return a response");
        assertNotNull(tokensResponse.getTokens(), "tokens list should not be null");
        boolean foundBIG = false;
        for (Token t : tokensResponse.getTokens()) {
            if ("bc".equals(t.getTokenid()) || "BIG".equals(t.getTokenname())) {
                foundBIG = true;
                break;
            }
        }
        assertTrue(foundBIG, "Genesis BIG token should exist on server");
    }

    @Test
    public void testGetTokenByHash() throws Exception {
        GetTokensResponse tokensResponse = wallet.searchTokens(null);
        assertNotNull(tokensResponse);
        assertNotNull(tokensResponse.getTokens());
        assertTrue(!tokensResponse.getTokens().isEmpty(), "At least one token must exist");

        Token bigToken = wallet.checkTokenId("bc");
        assertNotNull(bigToken);
    }

    @Test
    public void testCreateTokenViaSignToken() throws Exception {
        PQKey key = PQKey.createNew();
        Block block = createToken(key, "testtoken", 0, "", "test",
                BigInteger.valueOf(1000000L), true, null,
                TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet);

        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(key.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String expectedTokenId = key.getPublicKeyAsHex();
        Token foundToken = null;
        for (int i = 0; i < 15; i++) {
            foundToken = getToken(expectedTokenId);
            if (foundToken != null) break;
            if (i < 14) Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token " + expectedTokenId + " should exist via getTokenById");
        assertEquals("testtoken", foundToken.getTokenname());
        log.info("Token created and verified: {} ({})", foundToken.getTokenname(), expectedTokenId);
    }

    @Test
    public void testBeaconChainExists() throws Exception {
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getAllConfirmedReward.name(),
                Json.jsonmapper().writeValueAsString(new HashMap<>()));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
        assertNotNull(result, "getAllConfirmedReward should return a response");
        log.info("Max confirmed reward: {}", result);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testCreateTokenViaWallet() throws Exception {
        PQKey key = PQKey.createNew();
        Block block = createToken(key, "wallettoken", 0, "", "wallet test",
                BigInteger.valueOf(500000L), true, null,
                TokenType.token.ordinal(), key.getPublicKeyAsHex(), wallet);

        assertNotNull(block, "createToken via wallet should return a block");

        Block signed = wallet.multiSign(key.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String expectedTokenId = key.getPublicKeyAsHex();
        Token foundToken = null;
        for (int i = 0; i < 15; i++) {
            foundToken = getToken(expectedTokenId);
            if (foundToken != null) break;
            if (i < 14) Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token created via wallet should exist via getTokenById");
        assertEquals("wallettoken", foundToken.getTokenname());
        log.info("Wallet-created token verified: {} ({})", foundToken.getTokenname(), expectedTokenId);
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
