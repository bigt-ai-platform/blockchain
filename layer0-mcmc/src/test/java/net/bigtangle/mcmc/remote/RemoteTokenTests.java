package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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

    @Test
    public void testCreateAndPayToken() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "paytoken";
        BigInteger supply = BigInteger.valueOf(10000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for payment test",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String tokenId = issuer.getPublicKeyAsHex();
        Token foundToken = null;
        for (int i = 0; i < 15; i++) {
            foundToken = getToken(tokenId);
            if (foundToken != null) break;
            Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token should exist after creation");
        log.info("Token {} created, id={}", tokenName, tokenId);

        Thread.sleep(5000);

        wallet.importKey(issuer);
        byte[] tokenidBuf = Utils.HEX.decode(tokenId);
        List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
        List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
        for (FreeStandingTransactionOutput co : candidates) {
            if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())) {
                tokenUtxos.add(co);
            }
        }
        assertTrue(!tokenUtxos.isEmpty(), "Issuer should have token UTXOs");
        log.info("Issuer has {} token UTXOs", tokenUtxos.size());

        PQKey recipient = PQKey.createNew();
        BigInteger total = BigInteger.ZERO;
        Transaction tx = new Transaction(networkParameters);
        tx.setVersion(PQConstants.TX_PQ_VERSION);
        Coin sendAmount = new Coin(BigInteger.valueOf(1000L), tokenidBuf);

        for (FreeStandingTransactionOutput co : tokenUtxos) {
            tx.addInput(co.getUTXO().getBlockHash(), co);
            tx.getInputs().get(tx.getInputs().size() - 1).getOutpoint().connectedOutput = co;
            total = total.add(co.getValue().getValue());
            tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, sendAmount, recipient));
            Coin change = new Coin(total, tokenidBuf).subtract(sendAmount);
            if (!change.isNegative()) {
                tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, issuer));
                break;
            }
        }
        if (total.compareTo(BigInteger.valueOf(1000L)) < 0) {
            throw new RuntimeException("Insufficient token balance");
        }
        wallet.signTransaction(tx, null);
        wallet.submitTransaction(tx);
        makeRewardBlock();
        log.info("Paid 1000 {} tokens to recipient", tokenName);

        List<FreeStandingTransactionOutput> after = wallet.calculateAllSpendCandidates(null, false);
        long tokenUtxoCount = after.stream()
                .filter(co -> java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf()))
                .count();
        log.info("Wallet token UTXOs after payment: {}", tokenUtxoCount);
        assertTrue(tokenUtxoCount > 0, "Wallet should still have token UTXOs after payment");
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
