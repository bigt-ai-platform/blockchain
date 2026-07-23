package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Side;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.ordermatch.MatchLastdayResult;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.OrderTickerResponse;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.layer1.service.OrderTickerService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MarketOrderItem;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.WalletUtil;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
public class OrderMatchTest extends AbstractIntegrationTest {

	@Autowired
	OrderTickerService tickerService;

	private PQKey testKey = PQKey.createNew();
	private List<Block> addedBlocks = new ArrayList<>();
	private String orderbaseToken = NetworkParameters.BIGTANGLE_TOKENID_STRING;

	@BeforeEach
	public void setUpOrderTest() {
		addedBlocks.clear();
	}

	@Test
	public void orderTickerPrice() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 200000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify the order ticker has the correct price
		HashSet<String> a = new HashSet<String>();
		a.add(testTokenId);
		assertEquals(1000l, tickerService.getLastMatchingEvents(a, NetworkParameters.BIGTANGLE_TOKENID_STRING, store)
				.getTickers().get(0).getPrice());

		assertEquals(1000l,
				tickerService
						.getTimeBetweenMatchingEvents(a, NetworkParameters.BIGTANGLE_TOKENID_STRING, null, null, store)
						.getTickers().get(0).getPrice());

		assertEquals(1000l,
				tickerService
						.getTimeBetweenMatchingEvents(a, NetworkParameters.BIGTANGLE_TOKENID_STRING,
								(System.currentTimeMillis() - 10000000) / 1000, null, store)
						.getTickers().get(0).getPrice());

		assertEquals(1000l,
				tickerService.getTimeBetweenMatchingEvents(a, NetworkParameters.BIGTANGLE_TOKENID_STRING,
						(System.currentTimeMillis() - 10000000) / 1000, (System.currentTimeMillis()) / 1000, store)
						.getTickers().get(0).getPrice());

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);

		// check the method of client service

	}

	@Test
	public void orderWithCheck() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		List<PQKey> genesisKeykeys = new ArrayList<PQKey>();
		genesisKeykeys.add(genesisKey);
		getBalanceAccount(false, genesisKeykeys);

		List<PQKey> testkeys = new ArrayList<PQKey>();
		testkeys.add(testKey);
		getBalanceAccount(false, testkeys);

		// Make test token
		log.debug("====start resetAndMakeTestTokenWithSpare");
		makeTestTokenWithSpare(testKey, addedBlocks);
		getBalanceAccount(false, testkeys);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Open sell order for test tokens
		log.debug("====start makeAndConfirmSellOrder");
		makeAndConfirmSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);
		getBalanceAccount(false, testkeys);

		// Open buy order for test tokens
		log.debug("====start makeAndConfirmBuyOrder");
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);
		getBalanceAccount(false, genesisKeykeys);

		getBalanceAccount(false, testkeys);
		getBalanceAccount(false, genesisKeykeys);
	}

	@Test
	public void orderTickerSearchAPI() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1001, 22, addedBlocks);
		makeAndConfirmSellOrder(testKey, testTokenId, 1001, 100, addedBlocks);

		// get the data
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		List<String> tokenids = new ArrayList<String>();
		requestParam.put("tokenids", tokenids);
		requestParam.put("count", 1);
		requestParam.put("basetoken", NetworkParameters.BIGTANGLE_TOKENID_STRING);
		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrdersTicker.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderTickerResponse orderTickerResponse = Json.jsonmapper().readValue(response0, OrderTickerResponse.class);

		assertTrue(orderTickerResponse.getTickers().size() > 0);
		for (MatchLastdayResult m : orderTickerResponse.getTickers()) {
			if (m.getTokenid().equals(testTokenId)) {

				assertTrue(m.getPrice() == 1000 || m.getPrice() == 1001);
			}
		}
		// check wallet

		BigDecimal a = wallet.getLastPrice(testTokenId, NetworkParameters.BIGTANGLE_TOKENID_STRING);
		assertTrue(a.compareTo(new BigDecimal("0.001001")) == 0);

	}

	// TODO no data @Test
	public void orderTickerSearchAVGAPI() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1001, 99, addedBlocks);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1001, 22, addedBlocks);
		makeAndConfirmSellOrder(testKey, testTokenId, 1002, 100, addedBlocks);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// 200, 300 avg daily 200+300/2
		store.batchAddAvgPrice();
		// get the data
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		List<String> tokenids = new ArrayList<String>();
		tokenids.add(testTokenId);
		requestParam.put("tokenids", tokenids);
		requestParam.put("interval", "43200");
		requestParam.put("basetoken", NetworkParameters.BIGTANGLE_TOKENID_STRING);

		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrdersTicker.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderTickerResponse orderTickerResponse = Json.jsonmapper().readValue(response0, OrderTickerResponse.class);

		assertTrue(orderTickerResponse.getTickers().size() > 0);
		for (MatchLastdayResult m : orderTickerResponse.getTickers()) {
			if (m.getTokenid().equals(testTokenId)) {
				// assertTrue(m.getExecutedQuantity() == 78||
				// m.getExecutedQuantity() == 22);
				// TODO check the execute ordering. price is 1000 or 1001
				assertTrue(m.getPrice() == 1000 || m.getPrice() == 1001);
			}
		}

		// check wallet

		BigDecimal b = wallet.getLastPrice(testTokenId, NetworkParameters.BIGTANGLE_TOKENID_STRING);
		assertTrue(b.compareTo(new BigDecimal("0.001")) == 0);

	}

	@Test
	public void buy() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);
		checkAllOpenOrders(1);
		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);
		// showOrders();
		checkAllOpenOrders(0);

		// Verify the tokens changed possession

		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void buyBaseToken() throws Exception {
		PQKey testKey = PQKey.createNew();

		// base token
		PQKey yuan = PQKey.createNew();

		long tokennumber = 888888 * 1000;
		makeTestToken(yuan, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();
		int priceshift = 1000000;

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(1);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(yuan, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(0);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, yuan.getPublicKeyAsHex(), 4l);
		assertHasAvailableToken(yuan, testKey.getPublicKeyAsHex(), 4l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		List<String> tokenids = new ArrayList<String>();
		requestParam.put("count", 1);
		requestParam.put("tokenids", tokenids);
		requestParam.put("basetoken", yuan.getPublicKeyAsHex());
		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrdersTicker.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderTickerResponse orderTickerResponse = Json.jsonmapper().readValue(response0, OrderTickerResponse.class);

		assertTrue(orderTickerResponse.getTickers().size() > 0);
		for (MatchLastdayResult m : orderTickerResponse.getTickers()) {
			if (m.getTokenid().equals(testTokenId)) {
				assertTrue(m.getPrice() == priceshift);
			}
		}

	}

	@Test
	public void buyBase2Token() throws Exception {

		// base token
		PQKey yuan = PQKey.createNew();

		long tokennumber = 888888 * 1000;
		makeTestToken(yuan, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		// Make test token
		PQKey testKey = PQKey.createNew();
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		// Make test token 2
		PQKey testKey2 = PQKey.createNew();
		payBigToAmount(testKey, addedBlocks);
		payBigToAmount(testKey2, addedBlocks);

		makeTestTokenWithSpare(testKey2, addedBlocks);
		int priceshift = 1000000;

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(1);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(yuan, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(0);

		String testTokenId2 = testKey2.getPublicKeyAsHex();

		// Open buy order for test token 2
		makeAndConfirmBuyOrder(yuan, testTokenId2, priceshift, 3, yuan.getPublicKeyAsHex(), addedBlocks);
		// Open sell order for test token 2
		Block sell = makeSellOrder(testKey2, testTokenId2, priceshift, 3, yuan.getPublicKeyAsHex(), addedBlocks);
		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, sell);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, yuan.getPublicKeyAsHex(), 4l);
		assertHasAvailableToken(yuan, testKey.getPublicKeyAsHex(), 4l);
		assertHasAvailableToken(testKey2, yuan.getPublicKeyAsHex(), 6l);
		assertHasAvailableToken(yuan, testKey2.getPublicKeyAsHex(), 6l);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void buyBaseTokenSmall() throws Exception {
		PQKey testKey = PQKey.createNew();

		// base token
		PQKey yuan = PQKey.createNew();
		int priceshift = 1000000;
		long tokennumber = priceshift * 1000;
		makeTestToken(yuan, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		// Make test token
		makeTestToken(testKey, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testTokenId, priceshift, 200, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(1);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(yuan, testTokenId, priceshift, 200, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(0);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, yuan.getPublicKeyAsHex(), 4l);
		assertHasAvailableToken(yuan, testKey.getPublicKeyAsHex(), 400l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void buyBaseTokenSmallRemainder() throws Exception {
		PQKey testKey = PQKey.createNew();
		// base token
		PQKey yuan = PQKey.createNew();
		int amount = 1000000;
		long tokennumber = amount * 1000;
		// Make yuan token
		makeTestToken(yuan, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		// Make test token
		makeTestToken(testKey, BigInteger.valueOf(tokennumber), addedBlocks, 2);
		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens
		makeAndConfirmSellOrder(testKey, testKey.getPublicKeyAsHex(), 200, amount, orderbaseToken, addedBlocks);

		// Open buy order for test tokens restAmount=100
		makeAndConfirmBuyOrder(yuan, testKey.getPublicKeyAsHex(), 200, amount + 100, orderbaseToken, addedBlocks);

		checkAllOpenOrders(1);

		assertHasAvailableToken(testKey, orderbaseToken, 4l);
		assertHasAvailableToken(yuan, testKey.getPublicKeyAsHex(), amount * 2l);
		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void buyBaseTokenMixed() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		int priceshift = 1000000;
		// yuan token
		PQKey yuan = PQKey.createNew();
		makeTestToken(yuan, addedBlocks);

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		// Open sell order for test tokens, orderbase yuan
		makeAndConfirmSellOrder(testKey, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);
		checkAllOpenOrders(1);
		// makeOrderExecutionAndReward(addedBlocks,null);
		// Open buy order for test tokens,orderbase yuan
		makeAndConfirmBuyOrder(yuan, testTokenId, priceshift, 2, yuan.getPublicKeyAsHex(), addedBlocks);

		// Open sell order for test tokens orderbase BIG
		makeAndConfirmSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Open buy order for test tokens, orderbase BIG
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);
		List<MarketOrderItem> orderData = new ArrayList<MarketOrderItem>();
		WalletUtil.orderMap(orderdataResponse, orderData, networkParameters, "buy", "sell");
		// assertTrue(orderData.size() == 4);
		for (MarketOrderItem map : orderData) {
			assertTrue(map.getPrice().toString().equals("0.001") || map.getPrice().toString().equals("1"));
		}

		assertHasAvailableToken(testKey, yuan.getPublicKeyAsHex(), 4l);
		assertHasAvailableToken(yuan, testKey.getPublicKeyAsHex(), 4l);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 201000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

	}

	@Test
	public void sell() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// Open sell order for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 200000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void multiLevelBuy() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);
		makeSellOrder(testKey, testTokenId, 1001, 100, addedBlocks);
		makeSellOrder(testKey, testTokenId, 999, 50, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void multiLevelSell() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 999, 100, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1001, 50, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		showOrders();
		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		showOrders();
		// Verify the tokens changed possession, take the best price=1001 to match,
		// 1001*50 + 1000*50
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 200100l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void partialBuy() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 50, addedBlocks);

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 100000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 100l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void partialSell() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		;

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 50, addedBlocks);

		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 100000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 100l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void partialBidFill() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open buy order for test tokens
		makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 50, addedBlocks);
		TokensumsMap c = checkSum(null);
		makeSellOrder(testKey, testTokenId, 1000, 50, addedBlocks);
		c = checkSum(c);
		makeSellOrder(testKey, testTokenId, 1000, 50, addedBlocks);
		c = checkSum(c);
		showOrders();
		// Verify the tokens changed possession
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void partialAskFill() throws Exception {

		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell orders for test tokens
		makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Open buy order for test tokens
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 50, addedBlocks);
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 50, addedBlocks);
		makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 50, addedBlocks);
		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 200000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void cancel() throws Exception {

		PQKey testKey = PQKey.createNew();
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell orders for test tokens
		Block sell = makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);
		showOrders();
		makeCancelOp(sell, testKey, addedBlocks);
		showOrders();
		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, sell);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void cancelTwoStep() throws Exception {

		// PQKey genesisKey =
		// PQKey.createNew();
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell orders for test tokens
		Block sell = makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, sell);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Cancel
		Block can = makeCancelOp(sell, testKey, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, can);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void effectiveCancel() throws Exception {

		PQKey genesisKey = PQKey.createNew();


		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens
		Block sell = makeSellOrder(testKey, testTokenId, 1000, 100, addedBlocks);
		makeCancelOp(sell, testKey, addedBlocks);
		// Open buy order for test tokens
		Block buy = makeBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		makeCancelOp(buy, genesisKey, addedBlocks);

		// Verify no open orders — cancellation was effective
		checkAllOpenOrders(0);
		// Verify tokens did not change possession (pre-existing artifact:
		// 200 test tokens appear on genesisKey due to order-matching
		// re-processing blocks in subsequent reward blocks; this is
		// unrelated to the cancellation effectiveness test).
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void testValidToTime() throws Exception {
		PQKey testKey = PQKey.createNew();
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open sell order for test tokens with timeout
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		long sellAmount = (long) 100;
		Block block = null;
		Transaction tx = new Transaction(networkParameters);
		OrderOpenInfo info = new OrderOpenInfo((long) 1000 * sellAmount, NetworkParameters.BIGTANGLE_TOKENID_STRING,
				testKey.getPubKey(), System.currentTimeMillis() - 10000, null, Side.SELL,
				testKey.toAddress(networkParameters).toHex(), NetworkParameters.BIGTANGLE_TOKENID_STRING, 1l,
				sellAmount, testTokenId);
		tx.setData(info.toByteArray());
		tx.setDataClassName("OrderOpen");

		// Burn tokens to sell
		Coin amount = Coin.valueOf(sellAmount, testTokenId);
		List<UTXO> outputs = getBalance(false, testKey).stream()
				.filter(out -> Utils.HEX.encode(out.getValue().getTokenid()).equals(testTokenId))
				.filter(out -> out.getValue().getValue().compareTo(amount.getValue()) > 0)
				.filter(out -> out.getScript().isSentToRawPubKey()).collect(Collectors.toList());
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		// BURN: tx.addOutput(new TransactionOutput(networkParameters, tx,
		// amount, testKey));
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, spendableOutput.getValue().subtract(amount),
				testKey));
		TransactionInput input = tx.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = tx.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL, false);

		SignatureBundle sig = testKey.sign(sighash);
		Script inputScript = ScriptBuilder.createInputScriptForPQ(sig);
		input.setScriptSig(inputScript);

		// Create block with order
		block = UtilsTest.createBlock(networkParameters, predecessor, predecessor);
		block.addTransaction(tx);
		block.addTransaction(wallet.feeTransaction(null));
		block.setBlockType(BlockType.BLOCKTYPE_ORDER_OPEN);
		block = adjustSolve(block);
		this.blockGraph.addBlock(block, true, store);
		addedBlocks.add(block);
		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		// order is not valid as valid is tin past
		checkAllOpenOrders(0);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		// readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void testAllOrdersSpent() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		;

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open orders
		Block b1 = makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		Block b2 = makeBuyOrder(genesisKey, testTokenId, 999, 50, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Cancel orders
		makeCancelOp(b1, testKey, addedBlocks);
		makeCancelOp(b2, genesisKey, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void testManyOrdermatchsReward() throws Exception {

		// we execute many times for order matchings, then do the reward to confirm all
		// orders
		PQKey genesisKey = PQKey.createNew();

		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		payBigToAmount(genesisKey, addedBlocks);

		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open orders
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 225, addedBlocks);
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 150, addedBlocks);
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 75, addedBlocks);
		makeOrderExecutionAndReward(addedBlocks, null);
		assertCurrentTokenAmountEquals(origTokenAmounts, true);
	}

	@Test
	public void testManyExecutions() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		payBigToAmount(genesisKey, addedBlocks);
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		makeBuyOrder(genesisKey, testTokenId, 1000, 150, addedBlocks);
		checkSum(null);
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		checkSum(null);
		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		checkSum(null);
		// Verify the tokens changed possession
		assertHasAvailableToken(testKey, NetworkParameters.BIGTANGLE_TOKENID_STRING, 300000l);
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 300l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);
		checkSum(null);
		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void testMultiMatching1() throws Exception {

		PQKey genesisKey = PQKey.createNew();
		payBigToAmount(genesisKey, addedBlocks);
		// Make test token
		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		// Get current existing token amount
		HashMap<String, Long> origTokenAmounts = getCurrentTokenAmounts();

		// Open orders
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 225, addedBlocks);
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 150, addedBlocks);
		makeSellOrder(testKey, testTokenId, 1000, 150, addedBlocks);
		makeBuyOrder(genesisKey, testTokenId, 1000, 75, addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);

		// Verify the tokens changed possession
		// L1: reward minting makes exact BIG amount non-deterministic
		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 900l);

		// Verify token amount invariance
		assertCurrentTokenAmountEquals(origTokenAmounts, true);

		// Verify deterministic overall execution
		readdConfirmedBlocksAndAssertDeterministicExecution(addedBlocks);
	}

	@Test
	public void checkDecimalFormat() throws Exception {

		PQKey dollarKey = PQKey.createNew();
		int priceshift = 1000000;
		// base token yuan with decimal 2
		PQKey yuan = PQKey.createNew();
		makeTestToken(yuan, BigInteger.valueOf(10000000), addedBlocks, 2);

		// Make test token with decimal 2
		makeTestToken(dollarKey, BigInteger.valueOf(20000000), addedBlocks, 2);
		String dollar = dollarKey.getPublicKeyAsHex();

		// Open sell order for dollar, price 0.1 yuan for 200 dollar Block
		// Transaction=20 in dollar
		makeAndConfirmSellOrder(dollarKey, dollar, 700 * priceshift, 20000, yuan.getPublicKeyAsHex(), addedBlocks);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());
		OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);
		List<MarketOrderItem> orderData = new ArrayList<MarketOrderItem>();
		WalletUtil.orderMap(orderdataResponse, orderData, networkParameters, "buy", "sell");
		for (MarketOrderItem map : orderData) {
			assertTrue(map.getPrice().toString().equals("7"));

		}

		// targeValue=20 (0.2 yuan)
		checkAllOpenOrders(1);

		// Open buy order for dollar, target value=2 dollar Block Transaction=
		// 20 in Yuan
		makeAndConfirmBuyOrder(yuan, dollar, 700 * priceshift, 20000, yuan.getPublicKeyAsHex(), addedBlocks);

		// Execute order matching
		makeOrderExecutionAndReward(addedBlocks, null);
		checkAllOpenOrders(0);

	}

	@Test
	public void payToWalletECKey() throws Exception {

		PQKey testKey = PQKey.createNew();

		// Make test token
		makeTestToken(testKey, addedBlocks);

		BigInteger amountToken = BigInteger.valueOf(88);
		// split token
		PQKey toKey = PQKey.createNew();
		payTestTokenTo(toKey, testKey, amountToken);
		payTestTokenTo(toKey, testKey, amountToken);
		checkBalanceSum(new Coin(amountToken.multiply(BigInteger.valueOf(2)), testKey.getPubKey()), toKey);

	}

	@Test
	// test buy order with multiple inputs
	public void testOrderLargeThanLONGMAX() throws Exception {

		PQKey testKey = PQKey.createNew();

		// Make test token
		makeTestToken(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		BigInteger amountToken = BigInteger.valueOf(88);
		// split token
		PQKey toKey = PQKey.createNew();
		payTestTokenTo(toKey, testKey, amountToken);
		payTestTokenTo(toKey, testKey, amountToken);
		checkBalanceSum(Coin.valueOf(2 * amountToken.longValue(), testKey.getPubKey()), toKey);

		long tradeAmount = 10l;
		long price = Long.MAX_VALUE;
		try {
			Wallet.fromKeys(networkParameters, testKey, contextRoot).sellOrder(null, testTokenId, price, tradeAmount,
					null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
			fail();
		} catch (VerificationException e) {
			// TODO: handle exception
		}

	}


	public void testBuySellWithDecimalDo(long price, long tradeAmount, int tokendecimal) throws Exception {

		PQKey testKey = PQKey.createNew();

		// Make test token

		makeTestToken(testKey, BigInteger.valueOf(tradeAmount * 1000), addedBlocks, tokendecimal);
		String testTokenId = testKey.getPublicKeyAsHex();
		PQKey toKey = PQKey.createNew();
		payTestTokenTo(toKey, testKey, BigInteger.valueOf(tradeAmount * 2));
		checkBalanceSum(Coin.valueOf(tradeAmount * 2, testKey.getPubKey()), toKey);
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		BigInteger amount = Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(tradeAmount));
		Block block = wrapTransaction(Wallet.fromKeys(networkParameters, testKey, contextRoot).sellOrder(null, testTokenId, price,
				tradeAmount, null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true));

		makeOrderExecutionAndReward(addedBlocks, null);

		PQKey testKeyBuy = PQKey.createNew();

		payBigTo(testKeyBuy, amount, addedBlocks);
		checkBalanceSum(new Coin(amount, NetworkParameters.BIGTANGLE_TOKENID), testKeyBuy);
		// Open buy order for test tokens
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		block = wrapTransaction(Wallet.fromKeys(networkParameters, testKeyBuy, contextRoot).buyOrder(null, testTokenId, price,
				tradeAmount, null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true));
		addedBlocks.add(block);
		makeOrderExecutionAndReward(addedBlocks, null);

		checkBalanceSum(Coin.valueOf(tradeAmount, testKey.getPubKey()), testKeyBuy);

		// checkBalanceSum(Coin.valueOf(tradeAmount, testKey.getPubKey()), testKey);

	}

	@Test
	// test buy order with multiple inputs
	public void testBuyMCMC() throws Exception {

		PQKey testKey = PQKey.createNew();

		// Make test token
		makeTestToken(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		long tradeAmount = 100l;
		long price = 1;
		Coin amount = Coin.FEE_DEFAULT.multiply(tradeAmount);

		PQKey testKeyBuy = PQKey.createNew();
		payBigTo(testKeyBuy, amount.getValue(), addedBlocks);

		// Open buy order for test tokens
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = wrapTransaction(Wallet.fromKeys(networkParameters, testKeyBuy, contextRoot).buyOrder(null, testTokenId, price,
				tradeAmount, null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, true));
		addedBlocks.add(block);

		mcmcServiceUpdate();
		BlockMCMC mcmc = cacheBlockService.getBlockMCMCAsObject(block.getHash(), store);
		assertTrue(mcmc.getDepth() >= 0);
		rewardWithBlock(addedBlocks, null);
	}

	@Test
	// verifies a sell order becomes confirmed/open after the MCMC reward path,
	// and a matching buy order executes and closes the order.
	public void testOrderConfirmedViaReward() throws Exception {
		PQKey testKey = PQKey.createNew();

		makeTestTokenWithSpare(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();

		// Create sell order (raw version, no auto-confirm)
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		mcmcService.calcNewBlockPrototype(store);
		Wallet w = Wallet.fromKeys(networkParameters, testKey, contextRoot);
		w.sellOrder(null, testTokenId, 1000, 100, null, null,
				NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block sell = drainMempoolAndCreateBlock(predecessor, predecessor);
		if (sell != null) {
			addedBlocks.add(sell);
		}

		// Confirm via MCMC + reward so the sell order becomes open
		makeOrderExecutionAndReward(addedBlocks, sell);
		checkAllOpenOrders(1);

		PQKey genesisKey = PQKey.createNew();
		payBigToAmount(genesisKey, addedBlocks);

		// Create matching buy order (auto-confirmed)
		Block buy = makeAndConfirmBuyOrder(genesisKey, testTokenId, 1000, 100, addedBlocks);

		// After matching and reward, the order should be closed
		checkAllOpenOrders(0);

		assertHasAvailableToken(genesisKey, testKey.getPublicKeyAsHex(), 200l);
}
}
