package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

public class RemoteOrderTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderTests.class);

    @Test
    public void testCreateTokenAndQueryL1Orders() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "l1token";
        BigInteger supply = BigInteger.valueOf(10000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for L1",
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
        for (int i = 0; i < 20; i++) {
            foundToken = getToken(tokenId);
            if (foundToken != null) break;
            if (i < 19) Thread.sleep(3000);
        }
        assertNotNull(foundToken, "Token " + tokenId + " should exist via checkTokenId");
        log.info("Token {} created, id={}", tokenName, tokenId);

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        byte[] response0 = OkHttp3Util.post(l1Url + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);
        assertNotNull(orderdataResponse.getAllOrdersSorted(), "L1 getOrders should return order list");
        log.info("L1 getOrders returned {} orders", orderdataResponse.getAllOrdersSorted().size());
        log.info("Token creation + L1 order query test passed");
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
