package net.bigtangle.server.remote;

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

/**
 * Restored from layer0-mcmc RemoteOrderTests (removed with the MCMC module):
 * L1-order is a fully separated chain. Order operations (token creation for
 * trading, funding, buy/sell orders, matching) all happen on the L1-order
 * server; Layer 0 only handles payment + token creation for the L0 chain.
 */
public class RemoteOrderIT extends RemoteTestBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderIT.class);

    public RemoteOrderIT() {
        // All operations run against the L1-order server.
        contextRoot = l1Url;
    }

    @Test
    public void testCreateTokenAndTrade() throws Exception {
        // 1. Create keys and wallets
        PQKey issuer = PQKey.createNew();
        PQKey buyer = PQKey.createNew();

        byte[] bcToken = NetworkParameters.BIGTANGLE_TOKENID;

        // 2. Fund issuer and buyer with BC (before token creation).
        // Pay sequentially: each payment spends the wallet's only confirmed
        // UTXO, so wait for the change/recipient to confirm before the next
        // pay (calculateAllSpendCandidates ignores unconfirmed change).
        BigInteger userFunds = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(500));
        payBigTo(issuer, userFunds, null);
        waitForConfirmedBc(issuer);
        payBigTo(buyer, userFunds, null);

        // Verify both have confirmed BC
        for (PQKey key : new PQKey[] { issuer, buyer }) {
            waitForConfirmedBc(key);
        }
        log.info("Issuer and buyer funded with BC");

        // 3. Create a custom token
        String tokenName = "tradetoken";
        BigInteger supply = BigInteger.valueOf(10000000L);
        Block block = createToken(issuer, tokenName, 0, "", "token for buy/sell test",
                supply, true, null,
                TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
        assertNotNull(block, "createToken should return a block");

        String tokenId = issuer.getPublicKeyAsHex();
        Block signed = wallet.multiSign(tokenId, wallet.walletKeys(null).get(0), aesKey);
        if (signed != null)
            log.info("Token multi-signed");

        Token foundToken = null;
        for (int i = 0; i < 30; i++) {
            foundToken = getToken(tokenId);
            if (foundToken != null)
                break;
            Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token should exist");
        log.info("Token {} created", tokenName);

        // Wait for token UTXOs to be confirmed
        byte[] tokenBytes = Utils.HEX.decode(tokenId);
        boolean hasToken = false;
        for (int i = 0; i < 60; i++) {
            List<UTXO> utxos = getBalance(false, issuer);
            for (UTXO u : utxos) {
                if (Arrays.equals(tokenBytes, u.getValue().getTokenid())
                        && u.getValue().getValue().compareTo(BigInteger.ZERO) > 0)
                    hasToken = true;
            }
            if (hasToken)
                break;
            Thread.sleep(2000);
        }
        assertTrue(hasToken, "Issuer should have confirmed token UTXOs");

        // 4. Create a sell order: sell 100 tradetoken at price 1000 (BC base)
        long sellPrice = 1000;
        long sellAmount = 100;
        log.info("Sell: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        Wallet issuerWallet = Wallet.fromKeys(networkParameters, issuer, contextRoot);
        issuerWallet.sellOrder(aesKey, tokenId, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        Thread.sleep(3000);

        // 5. Verify sell order is open via L1 order API
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

        // 6. Create a matching buy order
        boolean buyerReady = false;
        for (int i = 0; i < 30; i++) {
            List<UTXO> utxos = getBalance(false, buyer);
            for (UTXO u : utxos) {
                if (Arrays.equals(bcToken, u.getValue().getTokenid()))
                    buyerReady = true;
            }
            if (buyerReady)
                break;
            Thread.sleep(2000);
        }
        assertTrue(buyerReady, "Buyer should have confirmed BC");

        log.info("Buy: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        Wallet buyerWallet = Wallet.fromKeys(networkParameters, buyer, contextRoot);
        buyerWallet.buyOrder(aesKey, tokenId, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, false);
        Thread.sleep(5000);

        // 7. Wait for order matching and verify
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

		// 8. Verify issuer received BC from the trade. The matcher's payout
		// output confirms on its own schedule AFTER the order book empties, so
		// poll (getBalance returns only confirmed outputs) instead of checking
		// immediately — otherwise the payout is still unconfirmed and the check
		// races the confirmation.
		boolean hasBcTrade = false;
		for (int i = 0; i < 60 && !hasBcTrade; i++) {
			List<UTXO> issuerBalance = getBalance(false, issuer);
			log.info("Issuer has {} UTXOs after trade (poll {})", issuerBalance.size(), i);
			for (UTXO u : issuerBalance) {
				log.info("  UTXO: {} value={}", u.getTokenId(), u.getValue().getValue());
				if (Arrays.equals(bcToken, u.getValue().getTokenid()))
					hasBcTrade = true;
			}
			if (!hasBcTrade)
				Thread.sleep(2000);
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

    /**
     * The L1-order server inherits {@code getOutputsHistory} from
     * BaseDispatcherController (same as Layer 0). It must return a valid
     * outputs history for an address with optional from/to date filters instead
     * of an unknown-command error.
     */
    @Test
    public void testGetOutputsHistoryOnL1() throws Exception {
        PQKey key = PQKey.createNew();
        HashMap<String, Object> params = new HashMap<>();
        params.put("fromaddress", "");
        params.put("toaddress", key.toAddress(networkParameters).toHex());
        params.put("starttime", null);
        params.put("endtime", null);

        byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.getOutputsHistory.name(),
                Json.jsonmapper().writeValueAsString(params));

        net.bigtangle.response.GetBalancesResponse balancesResponse = Json.jsonmapper().readValue(response,
                net.bigtangle.response.GetBalancesResponse.class);
        assertNotNull(balancesResponse.getOutputs(),
                "getOutputsHistory on L1 must return an outputs list (not an error)");
        log.info("getOutputsHistory on L1 returned {} outputs", balancesResponse.getOutputs().size());
    }
}
