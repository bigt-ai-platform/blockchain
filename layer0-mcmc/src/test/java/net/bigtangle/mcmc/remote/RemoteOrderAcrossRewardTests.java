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

/**
 * Verifies an open sell order SURVIVES the epoch-reward boundary and can still
 * be matched afterwards.
 *
 * <p>This targets the regression where a BLOCKTYPE_ORDER_OPEN block created
 * right around the epoch reward block gets orphaned — never referenced by a
 * confirmed beacon's RewardInfo — so it never solidifies, the order never
 * appears in getOrders, and the TS RemoteOrderTests fail while the (faster)
 * Java RemoteOrderTests pass because they finish before the first reward block.
 *
 * <p>Flow: fund, create token, place a sell order, verify it is open, then
 * WAIT until the next epoch reward boundary passes, then verify the order is
 * still open and a matching buy order still executes.
 */
public class RemoteOrderAcrossRewardTests extends RemoteTest {

    private static final Logger log = LoggerFactory.getLogger(RemoteOrderAcrossRewardTests.class);

    public RemoteOrderAcrossRewardTests() {
        // All order operations run on the L1-order server.
        contextRoot = l1Url;
    }

    @Test
    public void testOpenOrderSurvivesEpochReward() throws Exception {
        // ============================================================
        // 1. Fund issuer and buyer with BC on L1.
        // ============================================================
        PQKey issuer = PQKey.createNew();
        PQKey buyer = PQKey.createNew();
        Wallet issuerWallet = Wallet.fromKeys(networkParameters, issuer, contextRoot);
        Wallet buyerWallet = Wallet.fromKeys(networkParameters, buyer, contextRoot);

        BigInteger userFunds = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(500));
        payBigTo(issuer, userFunds, null);
        Thread.sleep(25000);
        payBigTo(buyer, userFunds, null);
        Thread.sleep(25000);
        assertHasConfirmedBc(issuer);
        assertHasConfirmedBc(buyer);
        log.info("Issuer and buyer funded with confirmed BC");

        // ============================================================
        // 2. Create a custom token and confirm it.
        // ============================================================
        String tokenName = "rewardordertoken";
        BigInteger supply = BigInteger.valueOf(10000000L);
        String tokenid = issuer.getPublicKeyAsHex();
        Block block = createToken(issuer, tokenName, 0, "", "token for reward-boundary order test",
                supply, true, null, TokenType.token.ordinal(), tokenid, wallet);
        assertNotNull(block, "createToken should return a block");

        Block signed = wallet.multiSign(tokenid, wallet.walletKeys().get(0), aesKey);
        if (signed != null) Thread.sleep(2000);

        Token foundToken = null;
        for (int i = 0; i < 30; i++) {
            foundToken = getToken(tokenid);
            if (foundToken != null) break;
            Thread.sleep(2000);
        }
        assertNotNull(foundToken, "Token should exist after creation");
        log.info("Token {} created, id={}", tokenName, tokenid);

        boolean hasToken = false;
        for (int i = 0; i < 60; i++) {
            List<UTXO> utxos = getBalance(false, issuer);
            for (UTXO u : utxos) {
                if (Arrays.equals(Utils.HEX.decode(tokenid), u.getValue().getTokenid())
                        && u.getValue().getValue().signum() > 0)
                    hasToken = true;
            }
            if (hasToken) break;
            Thread.sleep(2000);
        }
        assertTrue(hasToken, "Issuer should have confirmed token UTXOs");

        // ============================================================
        // 3. Place a sell order BEFORE the epoch boundary.
        // ============================================================
        long sellPrice = 1000;
        long sellAmount = 100;
        log.info("Sell: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        issuerWallet.sellOrder(aesKey, tokenid, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
        Thread.sleep(5000);

        // Verify the order is open now (before the reward boundary).
        List<OrderRecord> sellOrders = null;
        for (int i = 0; i < 40; i++) {
            sellOrders = queryOpenOrders();
            if (!sellOrders.isEmpty()) break;
            Thread.sleep(2000);
        }
        assertTrue(sellOrders != null && !sellOrders.isEmpty(),
                "Sell order should be open before the epoch reward");
        log.info("Sell order open before epoch reward: {} orders", sellOrders.size());

        // ============================================================
        // 4. Wait until the next epoch reward boundary has passed.
        // ============================================================
        // Epoch length must match the harness slot interval (remote.sh runs
        // pos.slotIntervalMs=2000, so an epoch is 2000*32 = 64 s — NOT the
        // 12 s default). Aligning the wait with the REAL epoch boundary is what
        // makes the buy land mid-epoch, where it confirms via the next beacon
        // instead of being picked up only by the (reward-payout) epoch-start one.
        long epochMs = 2_000L * 32L; // slotIntervalMs * SLOTS_PER_EPOCH
        long origin = 1532896109000L;
        long now = System.currentTimeMillis();
        long currentEpoch = (now - origin) / epochMs;
        long nextBoundaryMs = (currentEpoch + 1) * epochMs + origin + 2_000L; // margin for the tick
        long waitMs = nextBoundaryMs - System.currentTimeMillis();
        log.info("Waiting {} ms for next epoch reward boundary...", waitMs);
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
        Thread.sleep(15_000); // let processEpoch + reward block confirmation run

        // ============================================================
        // 5. Verify the order is STILL open after the reward block.
        // ============================================================
        sellOrders = null;
        for (int i = 0; i < 60; i++) {
            sellOrders = queryOpenOrders();
            if (!sellOrders.isEmpty()) break;
            Thread.sleep(2000);
        }
        assertTrue(sellOrders != null && !sellOrders.isEmpty(),
                "Sell order should survive the epoch reward block — it was orphaned/unconfirmed");
        log.info("Sell order still open after epoch reward: {} orders", sellOrders.size());

        // ============================================================
        // 6. Place a matching buy order and verify it executes.
        // ============================================================
        log.info("Buy: {} {} @ price {}", sellAmount, tokenName, sellPrice);
        buyerWallet.buyOrder(aesKey, tokenid, sellPrice, sellAmount, null, null,
                NetworkParameters.BIGTANGLE_TOKENID_STRING, false);

        // The match is deterministic but can lag: L1 order matching loads the
        // resting book by the previous reward-chain head (getOrderMatchingIssuedOrders),
        // which only advances once the sell's confirming beacon confirms. With 2 s
        // slots that confirmation latency occasionally exceeds a 2 min window, so poll
        // up to 6 min before declaring failure.
        List<OrderRecord> remaining = null;
        for (int i = 0; i < 180; i++) {
            remaining = queryOpenOrders();
            if (remaining.isEmpty()) break;
            Thread.sleep(2000);
        }
        assertTrue(remaining != null && remaining.isEmpty(),
                "All orders should have matched after the buy order, but " + remaining.size() + " remain");
        log.info("Order executed across the epoch reward boundary: PASS");
    }

    private void assertHasConfirmedBc(PQKey key) throws Exception {
        boolean hasBc = false;
        for (int i = 0; i < 30; i++) {
            List<UTXO> utxos = getBalance(false, key);
            for (UTXO u : utxos) {
                if (Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, u.getValue().getTokenid())
                        && u.getValue().getValue().signum() > 0)
                    hasBc = true;
            }
            if (hasBc) break;
            Thread.sleep(2000);
        }
        assertTrue(hasBc, "Key should have confirmed BC");
    }

    private List<OrderRecord> queryOpenOrders() throws Exception {
        HashMap<String, Object> requestParam = new HashMap<>();
        byte[] resp = OkHttp3Util.post(l1Url + ReqCmd.getOrders.name(),
                Json.jsonmapper().writeValueAsString(requestParam).getBytes());
        OrderdataResponse response = Json.jsonmapper().readValue(resp, OrderdataResponse.class);
        List<OrderRecord> orders = response.getAllOrdersSorted();
        return orders == null ? java.util.Collections.emptyList() : orders;
    }

    private Token getToken(String idcom) throws Exception {
        try {
            return wallet.checkTokenId(idcom);
        } catch (Exception e) {
            return null;
        }
    }
}
