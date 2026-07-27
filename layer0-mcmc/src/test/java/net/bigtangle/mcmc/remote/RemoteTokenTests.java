package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

/**
 * Integration test for token creation via HTTP API calls.
 *
 * Uses the MCMC beacon chain: tokens are created via the signToken API,
 * multi-signed via getTokenSignByAddress + signToken API, and confirmed
 * by a reward block created via direct service (makeRewardBlock).
 */
public class RemoteTokenTests extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteTokenTests.class);

    @Test
    public void testTokens() throws Exception {
        // Fund a new key to pay fees, then create a simple token
        // via wallet.createToken (uses signToken HTTP endpoint internally).
        PQKey key = PQKey.createNew();
        payBigTo(key, Coin.FEE_DEFAULT.getValue(), null);

        Block block = createToken(key, "testtoken", 0, "", "test",
                BigInteger.valueOf(1000000L), true, null,
                TokenType.identity.ordinal(), key.getPublicKeyAsHex(), key.getPubKey());

        assertNotNull(block, "createToken should return a block");

        // Multi-sign: root wallet must approve (simple tokens need root approval)
        Block lastBlock = pullBlockDoMultiSign(key.getPublicKeyAsHex(), wallet.walletKeys().get(0), aesKey);
        makeRewardBlock(lastBlock);

        // Verify token exists via searchTokens API
        HashMap<String, Object> searchReq = new HashMap<>();
        byte[] searchResp = OkHttp3Util.postString(contextRoot + ReqCmd.searchTokens.name(),
                Json.jsonmapper().writeValueAsString(searchReq));
        GetTokensResponse tokensResponse = Json.jsonmapper().readValue(searchResp, GetTokensResponse.class);
        boolean found = false;
        if (tokensResponse.getTokens() != null) {
            for (Token t : tokensResponse.getTokens()) {
                if (key.getPublicKeyAsHex().equals(t.getTokenid())) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "Token should exist on server after createToken");
    }

    @Test
    public void testTokenWithDomain() throws Exception {
        // 1. Publish a domain name "testdomain" via wallet.publishDomainName
        //    (uses signToken HTTP endpoint). The domain needs multi-sign + reward.
        PQKey domainKey = PQKey.createNew();
        String domainTid = domainKey.getPublicKeyAsHex();
        payBigTo(domainKey, Coin.FEE_DEFAULT.getValue(), null);

        wallet.publishDomainName(domainKey, domainTid, "testdomain", aesKey, "");

        Block lastBlock = pullBlockDoMultiSign(domainTid, domainKey, aesKey);
        lastBlock = pullBlockDoMultiSign(domainTid, wallet.walletKeys().get(0), aesKey);
        makeRewardBlock(lastBlock);

        Token domainToken = getToken(domainTid);
        assertNotNull(domainToken);
        assertTrue("testdomain".equals(domainToken.getTokenname()));

        // 2. Create a token under the domain
        PQKey tokenKey = PQKey.createNew();
        payBigTo(tokenKey, Coin.FEE_DEFAULT.getValue(), null);

        Block tokenBlock = createToken(tokenKey, "product", 0, "testdomain", "desc",
                BigInteger.ONE, true, null,
                TokenType.token.ordinal(), tokenKey.getPublicKeyAsHex(), tokenKey.getPubKey());

        TokenInfo currentToken = new TokenInfo().parseChecked(tokenBlock.getTransactions().get(0).getData());

        // Multi-sign: domain owner + root must approve
        lastBlock = pullBlockDoMultiSign(currentToken.getToken().getTokenid(), domainKey, aesKey);
        lastBlock = pullBlockDoMultiSign(currentToken.getToken().getTokenid(), wallet.walletKeys().get(0), aesKey);
        makeRewardBlock(lastBlock);

        // 3. Verify token exists via API
        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("tokenid", currentToken.getToken().getTokenid());
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
        assertTrue(getTokensResponse.getTokens().size() == 1);
        String display = getTokensResponse.getTokens().get(0).getTokennameDisplay();
        assertTrue(display != null && display.contains("@testdomain"),
                "Token display should include domain: " + display);
    }

    private Token getToken(String idcom) throws Exception {
        HashMap<String, Object> requestParam = new HashMap<>();
        requestParam.put("tokenid", idcom);
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        GetTokensResponse r = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
        return r.getTokens() != null && !r.getTokens().isEmpty() ? r.getTokens().get(0) : null;
    }

    private Block createToken(PQKey key, String tokename, int decimals, String domainname, String description,
            BigInteger amount, boolean increment, TokenKeyValues tokenKeyValues, int tokentype, String tokenid,
            byte[] pubkeyTo) throws Exception {
        wallet.importKey(key);
        Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
                amount, !increment, decimals, "");
        token.setTokenKeyValues(tokenKeyValues);
        token.setTokentype(tokentype);
        List<MultiSignAddress> addresses = new ArrayList<>();
        addresses.add(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
        return wallet.createToken(key, domainname, increment, token, addresses, pubkeyTo, new MemoInfo("coinbase"));
    }
}
