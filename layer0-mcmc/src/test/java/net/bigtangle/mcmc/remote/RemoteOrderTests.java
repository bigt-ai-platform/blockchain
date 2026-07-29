package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class RemoteOrderTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderTests.class);

    @Test
    public void testCreateTokenAndTrade() throws Exception {
        // ============================================================
        // 1. Create keys and wallets
        // ============================================================
        PQKey issuer = PQKey.createNew();
        PQKey buyer = PQKey.createNew();
        Wallet issuerWallet = Wallet.fromKeys(networkParameters, issuer, contextRoot);
        Wallet buyerWallet = Wallet.fromKeys(networkParameters, buyer, contextRoot);
        byte[] bcToken = NetworkParameters.BIGTANGLE_TOKENID;

        // ============================================================
        // 2. Fund issuer and buyer with BC (before token creation)
        // ============================================================
        BigInteger userFunds = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(500));
        payBigTo(issuer, userFunds, null);
        // Wait for MCMC to confirm before spending more genesis UTXOs
        Thread.sleep(25000);
        payBigTo(buyer, userFunds, null);
        Thread.sleep(25000);

        // Verify both have confirmed BC
        for (PQKey key : new PQKey[]{issuer, buyer}) {
            boolean hasBc = false;
            for (int i = 0; i < 30; i++) {
                List<UTXO> utxos = getBalance(false, key);
                for (UTXO u : utxos) {
                    if (Arrays.equals(bcToken, u.getValue().getTokenid())
                            && u.getValue().getValue().compareTo(BigInteger.ZERO) > 0)
                        hasBc = true;
                }
                if (hasBc) break;
                Thread.sleep(1000);
            }
            assertTrue(hasBc, "Key should have confirmed BC");
        }
        log.info("Issuer and buyer funded with BC");

        // ============================================================
        // 3. Create a custom token
        // ============================================================
        String tokenName = "tradetoken";
        BigInteger supply = BigInteger.valueOf(10000000L);
        Block block = createToken(issuer, tokenName, 0, "", "token for buy/sell test",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
                wallet.walletKeys().get(0), aesKey);
        if (signed != null) Thread.sleep(2000);

        String tokenId = issuer.getPublicKeyAsHex();
        Token foundToken = null;
        for (int i = 0; i < 30; i++) {
            foundToken = getToken(tokenId);
            if (foundToken != null) break;
            Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token should exist");
        log.info("Token {} created", tokenName);

        // Wait for token UTXOs to be confirmed
        Thread.sleep(25000);

        byte[] tokenBytes = Utils.HEX.decode(tokenId);
        boolean hasToken = false;
        for (int i = 0; i < 30; i++) {
            List<UTXO> utxos = getBalance(false, issuer);
            for (UTXO u : utxos) {
                if (Arrays.equals(tokenBytes, u.getValue().getTokenid())
                        && u.getValue().getValue().compareTo(BigInteger.ZERO) > 0)
                    hasToken = true;
            }
            if (hasToken) break;
            Thread.sleep(1000);
        }
        assertTrue(hasToken, "Issuer should have confirmed token UTXOs");

        // ============================================================
        // 4. Create a sell order: sell 100 tradetoken at price 1000 (BC base)
        // ============================================================
        long sellPrice = 1000;
        long sellAmount = 100;
        log.info("Sell: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        issuerWallet.sellOrder(aesKey, tokenId, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        Thread.sleep(3000);

        // ============================================================
        // 5. Verify sell order is open via L1 order API
        // ============================================================
        HashMap<String, Object> requestParam = new HashMap<>();
        OrderdataResponse ordersBefore = null;
        for (int i = 0; i < 40; i++) {
            byte[] resp = OkHttp3Util.post(l1Url + ReqCmd.getOrders.name(),
                    Json.jsonmapper().writeValueAsString(requestParam).getBytes());
            ordersBefore = Json.jsonmapper().readValue(resp, OrderdataResponse.class);
            if (ordersBefore.getAllOrdersSorted() != null && !ordersBefore.getAllOrdersSorted().isEmpty())
                break;
            Thread.sleep(2000);
        }
        assertNotNull(ordersBefore, "Order query should return a response");
        List<OrderRecord> sellOrders = ordersBefore.getAllOrdersSorted();
        assertNotNull(sellOrders, "Sell orders list should not be null");
        assertTrue(sellOrders.size() >= 1, "At least one sell order should be open");
        log.info("Sell order confirmed: {} open", sellOrders.size());

        // ============================================================
        // 6. Create a matching buy order
        // ============================================================
        // Ensure buyer's BC UTXOs are still confirmed
        boolean buyerReady = false;
        for (int i = 0; i < 15; i++) {
            List<UTXO> utxos = getBalance(false, buyer);
            for (UTXO u : utxos) {
                if (Arrays.equals(bcToken, u.getValue().getTokenid())
                        && u.getValue().getValue().compareTo(BigInteger.ZERO) > 0)
                    buyerReady = true;
            }
            if (buyerReady) break;
            Thread.sleep(3000);
        }
        assertTrue(buyerReady, "Buyer should have confirmed BC");

        log.info("Buy: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        buyerWallet.buyOrder(aesKey, tokenId, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, false);
        Thread.sleep(5000);

        // ============================================================
        // 7. Wait for order matching and verify
        // ============================================================
        OrderdataResponse ordersAfter = null;
        for (int i = 0; i < 40; i++) {
            byte[] resp = OkHttp3Util.post(l1Url + ReqCmd.getOrders.name(),
                    Json.jsonmapper().writeValueAsString(requestParam).getBytes());
            ordersAfter = Json.jsonmapper().readValue(resp, OrderdataResponse.class);
            List<OrderRecord> remaining = ordersAfter.getAllOrdersSorted();
            if (remaining == null || remaining.isEmpty())
                break;
            Thread.sleep(2000);
        }
        List<OrderRecord> remainingOrders = ordersAfter.getAllOrdersSorted();
        assertTrue(remainingOrders == null || remainingOrders.isEmpty(),
                "All orders should be matched");

        // ============================================================
        // 8. Verify issuer received BC from the trade
        // ============================================================
        List<UTXO> issuerBalance = getBalance(false, issuer);
        log.info("Issuer has {} UTXOs after trade", issuerBalance.size());
        boolean hasBcTrade = false;
        for (UTXO u : issuerBalance) {
            log.info("  UTXO: {} value={}", u.getTokenId(), u.getValue().getValue());
            if (Arrays.equals(bcToken, u.getValue().getTokenid()))
                hasBcTrade = true;
        }
        assertTrue(hasBcTrade, "Issuer should have BC from the sell order");
        log.info("Buy/sell trade test completed successfully");
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
