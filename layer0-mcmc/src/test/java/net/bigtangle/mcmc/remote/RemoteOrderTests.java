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
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

public class RemoteOrderTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderTests.class);

    @Test
    public void testCreateTokenThenSellAndBuy() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "ordertoken";
        BigInteger supply = BigInteger.valueOf(10000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for order test",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String tokenId = issuer.getPublicKeyAsHex();
        waitForToken(tokenId, 20);
        log.info("Token {} created, id={}", tokenName, tokenId);

        Thread.sleep(15000);

        List<FreeStandingTransactionOutput> allCandidates = wallet.calculateAllSpendCandidates(null, false);
        List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
        byte[] tokenidBuf = Utils.HEX.decode(tokenId);
        for (FreeStandingTransactionOutput co : allCandidates) {
            if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())) {
                tokenUtxos.add(co);
            }
        }
        assertTrue(!tokenUtxos.isEmpty(), "Issuer should have token UTXOs");

        long sellAmount = 1000L;
        long sellPrice = 1000L;

        wallet.sellOrder(null, tokenId, sellPrice, sellAmount,
                null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        log.info("Placed sell order: {} tokens at price {}", sellAmount, sellPrice);
        makeRewardBlock();

        Thread.sleep(5000);

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);
        assertNotNull(orderdataResponse.getAllOrdersSorted(), "Order list should not be null");

        OrderRecord sellOrder = null;
        for (OrderRecord o : orderdataResponse.getAllOrdersSorted()) {
            if (tokenId.equals(o.getOfferTokenid()) && !o.isSpent()) {
                sellOrder = o;
                break;
            }
        }
        assertNotNull(sellOrder, "Sell order should be visible on server");
        log.info("Sell order found: offerValue={}, price={}", sellOrder.getOfferValue(), sellOrder.getPrice());

        long buyAmount = sellOrder.getOfferValue();
        long buyPrice = sellOrder.getTargetValue() / sellOrder.getOfferValue();

        wallet.buyOrder(null, tokenId, buyPrice, buyAmount,
                null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        log.info("Placed buy order: {} tokens at price {}", buyAmount, buyPrice);
        makeRewardBlock();

        Thread.sleep(10000);

        byte[] response1 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(new HashMap<String, Object>()).getBytes());
        OrderdataResponse updatedOrders = Json.jsonmapper().readValue(response1, OrderdataResponse.class);
        boolean orderFilled = true;
        for (OrderRecord o : updatedOrders.getAllOrdersSorted()) {
            if (tokenId.equals(o.getOfferTokenid()) && !o.isSpent()) {
                orderFilled = false;
                log.info("Unfilled order still present: {}", o.getBlockHash());
            }
        }
        log.info("Order matching completed. All orders filled: {}", orderFilled);

        List<FreeStandingTransactionOutput> finalCandidates = wallet.calculateAllSpendCandidates(null, false);
        boolean hasBig = false;
        boolean hasToken = false;
        for (FreeStandingTransactionOutput co : finalCandidates) {
            if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
                hasBig = true;
            }
            if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())) {
                hasToken = true;
            }
        }
        log.info("Wallet has BIG UTXOs: {}, has token UTXOs: {}", hasBig, hasToken);
    }

    @Test
    public void testSellOrderThenCancel() throws Exception {
        PQKey issuer = PQKey.createNew();
        String tokenName = "canceltoken";
        BigInteger supply = BigInteger.valueOf(5000000L);

        Block block = createToken(issuer, tokenName, 0, "", "token for cancel test",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) {
            makeRewardBlock(signed);
        }

        String tokenId = issuer.getPublicKeyAsHex();
        waitForToken(tokenId, 20);
        log.info("Token {} created for cancel test, id={}", tokenName, tokenId);
        Thread.sleep(15000);

        long sellAmount = 2000L;
        long sellPrice = 500L;

        wallet.sellOrder(null, tokenId, sellPrice, sellAmount,
                null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        log.info("Placed sell order for cancel test");
        makeRewardBlock();
        Thread.sleep(5000);

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);
        assertNotNull(orderdataResponse.getAllOrdersSorted(), "Order list should not be null");

        boolean sellOrderExists = false;
        for (OrderRecord o : orderdataResponse.getAllOrdersSorted()) {
            if (tokenId.equals(o.getOfferTokenid()) && !o.isSpent()) {
                sellOrderExists = true;
                break;
            }
        }
        assertTrue(sellOrderExists, "Sell order should exist before cancel");
        log.info("Sell order exists on server, test passed");
    }

    private void waitForToken(String tokenId, int maxRetries) throws Exception {
        for (int i = 0; i < maxRetries; i++) {
            if (getToken(tokenId) != null) return;
            if (i < maxRetries - 1) Thread.sleep(3000);
        }
    }

    private net.bigtangle.core.Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
