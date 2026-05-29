/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.subtangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockitoTestExecutionListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.response.MultiSignResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.response.TokenIndexResponse;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.BlockService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.server.service.SyncBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.UUIDUtil;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {})

@TestExecutionListeners(value = { DependencyInjectionTestExecutionListener.class, MockitoTestExecutionListener.class,
		DirtiesContextTestExecutionListener.class

})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

	private static final String CONTEXT_ROOT_TEMPLATE = "http://localhost:%s/";
	protected static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);
	public String contextRoot;

	/*
	 * default wallet which has key testpriv and yuanTokenPriv
	 */
	public Wallet wallet;

	protected final KeyParameter aesKey = null;

	@Autowired
	protected BlockStoreService blockGraph;
	@Autowired
	protected BlockService blockService;
 
	 

	@Autowired
	protected NetworkParameters networkParameters;

	@Autowired
	protected StoreService storeService;

 
	@Autowired
	protected SyncBlockService syncBlockService;

	@Autowired
	protected ServerConfiguration serverConfiguration;
     @Autowired
    protected CacheBlockService cacheBlockService;
	@Autowired
	private ScheduleConfiguration scheduleConfiguration;
	@Autowired
	protected void prepareContextRoot(@Value("${local.server.port}") int port) {
		contextRoot = String.format(CONTEXT_ROOT_TEMPLATE, port);
	}

	public static String testPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
	public static String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";

	protected static ObjectMapper objectMapper = new ObjectMapper();
	public BlockStoreInterface store;

 

	public void checkTokenAssertTrue(String tokenid, String domainname) throws Exception {
		HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
		requestParam0.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam0));

		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
		Token token_ = getTokensResponse.getTokens().get(0);
		assertTrue(token_.getDomainName().equals(domainname));
	}

	@BeforeEach
	public void setUp() throws Exception {
		Utils.unsetMockClock();
		scheduleConfiguration.setInitSync(false);
		store = storeService.getStore();
		resetStore();
		wallet = Wallet.fromKeys(networkParameters, ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
		serverConfiguration.setServiceReady(true);

	}

	@AfterEach
	public void close() throws Exception {
		store.close();
	}
	
	/**
	 * Resets the store by deleting the contents of the tables and reinitialising
	 * them.
	 * 
	 * @throws BlockStoreException If the tables couldn't be cleared and
	 *                             initialised.
	 */
	public void resetStore() throws BlockStoreException {
	 
		store.resetStore();
		cacheBlockService.evictOutputs();
		cacheBlockService.evictBlock(); 
		cacheBlockService.evictAccountBalance(); 
		cacheBlockService.evictMaxConfirmedReward(); 
		 
	}


	protected void payTestTokenTo(ECKey beneficiary, ECKey testKey, BigInteger amount) throws Exception {
		payTestTokenTo(beneficiary, testKey, amount, new ArrayList<>());
	}

	protected Block payBigTo(ECKey beneficiary, BigInteger amount, List<Block> addedBlocks) throws Exception {
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<String, BigInteger>();

		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), amount);

		Block b = wallet.payMoneyToECKeyList(null, giveMoneyResult, "payBig");
		// log.debug("block " + (b == null ? "block is null" : b.toString()));
		if (addedBlocks != null)
			addedBlocks.add(b);
	 
		// makeRewardBlock(addedBlocks);
		return b;
	}

	protected void payTestTokenTo(ECKey beneficiary, ECKey testKey, BigInteger amount, List<Block> addedBlocks)
			throws Exception {
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
	//	makeRewardBlock(addedBlocks);
		HashMap<String, BigInteger> giveMoneyTestToken = new HashMap<String, BigInteger>();

		giveMoneyTestToken.put(beneficiary.toAddress(networkParameters).toString(), amount);
		Wallet w = Wallet.fromKeys(networkParameters, testKey, contextRoot);

		Block b = w.payToList(null, giveMoneyTestToken, testKey.getPubKey(), "");
		// log.debug("block " + (b == null ? "block is null" : b.toString()));

		addedBlocks.add(b);
		//makeRewardBlock(addedBlocks);
		// Open sell order for test tokens
	}

	protected Block makeTestToken(ECKey testKey, List<Block> addedBlocks)
			throws JsonProcessingException, Exception, BlockStoreException {
		Block block = makeTestToken(testKey, BigInteger.valueOf(77777L), addedBlocks, 0);
		return block;
	}

	protected Block makeTestTokenWithSpare(ECKey testKey, List<Block> addedBlocks)
			throws JsonProcessingException, Exception, BlockStoreException {

		Block block = makeTestToken(testKey, BigInteger.valueOf(77777L), addedBlocks, 0);
		payTestTokenTo(testKey, testKey, BigInteger.valueOf(50000), addedBlocks);
		payTestTokenTo(testKey, testKey, BigInteger.valueOf(40000), addedBlocks);
		payTestTokenTo(testKey, testKey, BigInteger.valueOf(30000), addedBlocks);
		payTestTokenTo(testKey, testKey, BigInteger.valueOf(20000), addedBlocks);
		payTestTokenTo(testKey, testKey, BigInteger.valueOf(10000), addedBlocks);
		return block;
	}

	protected void payBigToAmount(ECKey beneficiary, List<Block> addedBlocks)
			throws JsonProcessingException, Exception, BlockStoreException {
		payBigTo(beneficiary, BigInteger.valueOf(500000), addedBlocks);
		payBigTo(beneficiary, BigInteger.valueOf(400000), addedBlocks);
		payBigTo(beneficiary, BigInteger.valueOf(300000), addedBlocks);
		payBigTo(beneficiary, BigInteger.valueOf(200000), addedBlocks);
		payBigTo(beneficiary, BigInteger.valueOf(100000), addedBlocks);
	}

	protected Block resetAndMakeTestToken(ECKey testKey, BigInteger amount, List<Block> addedBlocks)
			throws JsonProcessingException, Exception, BlockStoreException {
		return makeTestToken(testKey, amount, addedBlocks, 0);
	}

	protected Block makeTestToken(ECKey testKey, BigInteger amount, List<Block> addedBlocks, int decimal)
			throws JsonProcessingException, Exception, BlockStoreException {

		// Make the "test" token
		Block block = null;
		TokenInfo tokenInfo = new TokenInfo();

		Coin coinbase = new Coin(amount, testKey.getPubKey());
		// BigInteger amount = coinbase.getValue();
		Token tokens = Token.buildSimpleTokenInfo(true, null, testKey.getPublicKeyAsHex(), testKey.getPublicKeyAsHex(),
				"", 1, 0, amount, true, decimal, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", testKey.getPublicKeyAsHex()));

		block = saveTokenUnitTest(tokenInfo, coinbase, testKey, null, addedBlocks);
		addedBlocks.add(block);
		// makeRewardBlock(addedBlocks);
	 
		return block;
	}

 

	protected Block makeSellOrder(ECKey beneficiary, String tokenId, long sellPrice, long sellAmount,
			List<Block> addedBlocks) throws Exception {

		return makeSellOrder(beneficiary, tokenId, sellPrice, sellAmount, NetworkParameters.BIGTANGLE_TOKENID_STRING,
				addedBlocks);
	}

	 
	protected Block makeSellOrder(ECKey beneficiary, String tokenId, long sellPrice, long sellAmount, String basetoken,
			List<Block> addedBlocks) throws Exception {
		payBigTo(beneficiary, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		Wallet w = Wallet.fromKeys(networkParameters, beneficiary, contextRoot);
		Block block = w.sellOrder(null, tokenId, sellPrice, sellAmount, null, null, basetoken, true);
		addedBlocks.add(block);
		return block;

	}

 
	protected Block makeAndConfirmPayContract(ECKey beneficiary, String tokenId, BigInteger buyAmount,
			String contractTokenid, List<Block> addedBlocks) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, beneficiary, contextRoot);

		Block block = w.payContract(null, tokenId, buyAmount, null, null, contractTokenid);
		addedBlocks.add(block);
	//	makeRewardBlock(addedBlocks);
		return block;

	}

	protected Block makeBuyOrder(ECKey beneficiary, String tokenId, long buyPrice, long buyAmount,
			List<Block> addedBlocks) throws Exception {

		Block block = makeBuyOrder(beneficiary, tokenId, buyPrice, buyAmount,
				NetworkParameters.BIGTANGLE_TOKENID_STRING, addedBlocks);
		return block;
	}

 

	protected Block makeBuyOrder(ECKey beneficiary, String tokenId, long buyPrice, long buyAmount, String basetoken,
			List<Block> addedBlocks) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, beneficiary, contextRoot);
		w.setServerURL(contextRoot);
		payBigTo(beneficiary, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		Block block = w.buyOrder(null, tokenId, buyPrice, buyAmount, null, null, basetoken, true);
		addedBlocks.add(block);
		return block;
	}

  
 
 
 
	 
	protected void assertCurrentTokenAmountEquals(HashMap<String, Long> origTokenAmounts) throws BlockStoreException {
		assertCurrentTokenAmountEquals(origTokenAmounts, true);
	}

	protected void assertCurrentTokenAmountEquals(HashMap<String, Long> origTokenAmounts, boolean skipBig)
			throws BlockStoreException {
		// Asserts that the current token amounts are equal to the given token
		// amounts
		HashMap<String, Long> currTokenAmounts = getCurrentTokenAmounts();
		for (Entry<String, Long> origTokenAmount : origTokenAmounts.entrySet()) {
			if (skipBig && origTokenAmount.getKey().equals(NetworkParameters.BIGTANGLE_TOKENID_STRING))
				continue;

			assertTrue(currTokenAmounts.containsKey(origTokenAmount.getKey()));
			assertEquals(origTokenAmount.getValue(), currTokenAmounts.get(origTokenAmount.getKey()));
		}
		for (Entry<String, Long> currTokenAmount : currTokenAmounts.entrySet()) {
			if (skipBig && currTokenAmount.getKey().equals(NetworkParameters.BIGTANGLE_TOKENID_STRING))
				continue;

	 		assertTrue(origTokenAmounts.containsKey(currTokenAmount.getKey()));
			assertEquals(origTokenAmounts.get(currTokenAmount.getKey()), currTokenAmount.getValue());
		}
	}

	protected void assertHasAvailableToken(ECKey testKey, String tokenId_, Long amount) throws Exception {
		// Asserts that the given ECKey possesses the given amount of tokens
		List<UTXO> balance = getBalance(false, testKey);
		HashMap<String, Long> hashMap = new HashMap<>();
		for (UTXO o : balance) {
			String tokenId = Utils.HEX.encode(o.getValue().getTokenid());
			if (!hashMap.containsKey(tokenId))
				hashMap.put(tokenId, 0L);
			hashMap.put(tokenId, hashMap.get(tokenId) + o.getValue().getValue().longValue());
		}

		assertEquals(amount == 0 ? null : amount, hashMap.get(tokenId_));
	}

	protected HashMap<String, Long> getCurrentTokenAmounts() throws BlockStoreException {
		// Adds the token values of open orders and UTXOs to a hashMap
		HashMap<String, Long> hashMap = new HashMap<>();
		addCurrentUTXOTokens(hashMap);
	   addCurrentOrderTokens(hashMap);
		return hashMap;
	}

	protected void addCurrentOrderTokens(HashMap<String, Long> hashMap) throws BlockStoreException {
		// Adds the token values of open orders to the hashMap
		List<OrderRecord> orders = store.getAllOpenOrdersSorted(null, null);
		for (OrderRecord o : orders) {
			log.debug(o.toString());
			String tokenId = o.getOfferTokenid();
			if (!hashMap.containsKey(tokenId))
				hashMap.put(tokenId, 0L);
			hashMap.put(tokenId, hashMap.get(tokenId) + o.getOfferValue());
		}
	}

	protected void addCurrentUTXOTokens(HashMap<String, Long> hashMap) throws BlockStoreException {
		// Adds the token values of open UTXOs to the hashMap
		List<UTXO> utxos = store.getAllAvailableUTXOsSorted();
		for (UTXO o : utxos) {
			String tokenId = Utils.HEX.encode(o.getValue().getTokenid());
			if (!hashMap.containsKey(tokenId))
				hashMap.put(tokenId, 0L);
			hashMap.put(tokenId, hashMap.get(tokenId) + o.getValue().getValue().longValue());
		}
	}

	protected void showOrders() throws BlockStoreException {
		// Snapshot current state
		List<OrderRecord> allOrdersSorted = store.getAllOpenOrdersSorted(null, null);
		for (OrderRecord o : allOrdersSorted) {
			log.debug(o.toString());
		}
	}

	protected void checkAllOpenOrders(int ordersize) throws BlockStoreException {
		// Snapshot current state
		List<OrderRecord> allOpenOrdersSorted = store.getAllOpenOrdersSorted(null, null);
		for(OrderRecord o: allOpenOrdersSorted ) log.debug(o.toString());
		assertTrue(allOpenOrdersSorted.size() == ordersize);

	}

	 
 
	protected Sha256Hash getRandomSha256Hash() {
		byte[] rawHashBytes = new byte[32];
		new Random().nextBytes(rawHashBytes);
		Sha256Hash sha256Hash = Sha256Hash.wrap(rawHashBytes);
		return sha256Hash;
	}
 

	public Block adjustSolve(Block block) throws IOException, JsonParseException, JsonMappingException {
		// save block
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.adjustHeight.name(), block.bitcoinSerialize());
		@SuppressWarnings("unchecked")
		HashMap<String, Object> result = Json.jsonmapper().readValue(resp, HashMap.class);
		String dataHex = (String) result.get("dataHex");

		Block adjust = networkParameters.getDefaultSerializer().makeBlock(Utils.HEX.decode(dataHex));
		adjust.solve();
		return adjust;
	}

 

	protected UTXO getLargeUTXO(List<UTXO> outputs) {
		UTXO a = outputs.get(0);
		for (UTXO b : outputs) {
			if (b.getValue().isGreaterThan(a.getValue())) {
				a = b;
			}
		}
		return a;
	}

	protected List<UTXO> getBalance() throws Exception {
		return getBalance(false);
	}

	// get balance for the walletKeys
	protected List<UTXO> getBalance(boolean withZero) throws Exception {
		return getBalance(withZero, wallet.walletKeys(null));
	}

	protected UTXO getBalance(String tokenid, boolean withZero, List<ECKey> keys) throws Exception {
		List<UTXO> ulist = getBalance(withZero, keys);

		for (UTXO u : ulist) {
			if (tokenid.equals(u.getTokenId())) {
				return u;
			}
		}

		throw new RuntimeException();
	}

	// get balance for the walletKeys
	protected List<UTXO> getBalance(boolean withZero, List<ECKey> keys) throws Exception {
		List<UTXO> listUTXO = new ArrayList<UTXO>();
		List<String> keyStrHex000 = new ArrayList<String>();

		for (ECKey ecKey : keys) {
			// keyStrHex000.add(ecKey.toAddress(networkParameters).toString());
			keyStrHex000.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

		// byte[] response = mvcResult.getResponse().getContentAsString();
		for (UTXO utxo : getBalancesResponse.getOutputs()) {
			if (withZero) {
				listUTXO.add(utxo);
			} else if (utxo.getValue().getValue().signum() > 0) {
				listUTXO.add(utxo);
			}
		}

		return listUTXO;
	}

	protected List<Coin> getBalanceAccount(boolean withZero, List<ECKey> keys) throws Exception {
		List<Coin> listCoin = new ArrayList<Coin>();
		List<String> keyStrHex000 = new ArrayList<String>();

		for (ECKey ecKey : keys) {
			// keyStrHex000.add(ecKey.toAddress(networkParameters).toString());
			keyStrHex000.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getAccountBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

		// byte[] response = mvcResult.getResponse().getContentAsString();
		listCoin.addAll(getBalancesResponse.getBalance());
		for (Coin coin : listCoin) {
			log.debug("coin:" + coin.toString());
		}
		return listCoin;
	}
 

	// get balance for the walletKeys
	protected List<UTXO> getBalance(String address) throws Exception {
		List<UTXO> listUTXO = new ArrayList<UTXO>();
		List<String> keyStrHex000 = new ArrayList<String>();

		keyStrHex000.add(Utils.HEX.encode(Address.fromBase58(networkParameters, address).getHash160()));
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

		for (UTXO utxo : getBalancesResponse.getOutputs()) {
			listUTXO.add(utxo);
		}

		return listUTXO;
	}

	protected List<UTXO> getBalance(boolean withZero, ECKey ecKey) throws Exception {
		List<ECKey> keys = new ArrayList<ECKey>();
		keys.add(ecKey);
		return getBalance(withZero, keys);
	}

  
 
	protected void checkResponse(byte[] resp) throws JsonParseException, JsonMappingException, IOException {
		checkResponse(resp, 0);
	}

	protected void checkResponse(byte[] resp, int code) throws JsonParseException, JsonMappingException, IOException {
		@SuppressWarnings("unchecked")
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int error = (Integer) result2.get("errorcode");
		assertTrue(error == code);
	}

	protected void checkBalance(Coin coin, ECKey ecKey) throws Exception {
		ArrayList<ECKey> a = new ArrayList<ECKey>();
		a.add(ecKey);
		checkBalance(coin, a);
	}

	protected void checkBalance(Coin coin, List<ECKey> a) throws Exception {
		List<UTXO> ulist = getBalance(false, a);
		UTXO myutxo = null;
		for (UTXO u : ulist) {
			if (coin.getTokenHex().equals(u.getTokenId()) && coin.getValue().equals(u.getValue().getValue())) {
				myutxo = u;
				break;
			}
		}
		assertTrue(myutxo != null);
		assertTrue(myutxo.getAddress() != null && !myutxo.getAddress().isEmpty());
		log.debug(myutxo.toString());
	}

	protected void checkBalanceSum(Coin coin, ECKey a) throws Exception {
		List<ECKey> keys = new ArrayList<>();
		keys.add(a);
		checkBalanceSum(coin, keys);
	}

	protected void checkBalanceSum(Coin coin, List<ECKey> a) throws Exception {
		List<UTXO> ulist = getBalance(false, a);

		Coin sum = new Coin(0, coin.getTokenid());
		for (UTXO u : ulist) {
			if (coin.getTokenHex().equals(u.getTokenId())) {
				sum = sum.add(u.getValue());

			}
		}
		if (coin.getValue().compareTo(sum.getValue()) != 0) {
			log.error(" expected: " + coin + " got: " + sum);
		}
		assertTrue(coin.getValue().compareTo(sum.getValue()) == 0, coin + " != " + sum);

	}
 
	public Block saveTokenUnitTest(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey)
			throws Exception {
		return saveTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, null);
	}

	// for unit tests
	public Block saveTokenUnitTest(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey,
			List<Block> addedBlocks) throws Exception {

		tokenInfo.getToken().setTokenname(UUIDUtil.randomUUID());
		return saveTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, null, null, addedBlocks, true);
	}

	public Block saveTokenUnitTestWithTokenname(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey)
			throws Exception {
		return saveTokenUnitTestWithTokenname(tokenInfo, basecoin, outKey, aesKey, null);
	}

	public Block saveTokenUnitTestWithTokenname(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey,
			List<Block> addedBlocks) throws Exception {
		return saveTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, null, null, addedBlocks, true);
	}

	public Block saveTokenUnitTestWithTokenname(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey,
			List<Block> addedBlocks, boolean feepay) throws Exception {
		return saveTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, null, null, addedBlocks, feepay);
	}

	public Block saveTokenUnitTest(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey,
			Block overrideHash1, Block overrideHash2, List<Block> addedBlocks, boolean feepay)
			throws IOException, Exception {
		if (feepay)
			payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		Block block = makeTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, overrideHash1, overrideHash2);
		OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());

		pullBlockDoMultiSign(tokenInfo.getToken().getTokenid(), outKey, aesKey);
		ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
				Utils.HEX.decode(testPub));
		pullBlockDoMultiSign(tokenInfo.getToken().getTokenid(), genesiskey, null);

		return block;
	}

	public Block makeTokenUnitTest(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey)
			throws JsonProcessingException, IOException, Exception {
		return makeTokenUnitTest(tokenInfo, basecoin, outKey, aesKey, null, null);
	}

	public Block makeTokenUnitTest(TokenInfo tokenInfo, Coin basecoin, ECKey outKey, KeyParameter aesKey,
			Block overrideHash1, Block overrideHash2) throws JsonProcessingException, IOException, Exception {

		final String tokenid = tokenInfo.getToken().getTokenid();
		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this
				.getPrevTokenMultiSignAddressList(tokenInfo.getToken());
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			if (Utils.isBlank(tokenInfo.getToken().getDomainName())) {
				tokenInfo.getToken().setDomainName(permissionedAddressesResponse.getDomainName());
			}
			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex, 0));
			}
		}

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		if (overrideHash1 != null && overrideHash2 != null) {
			block.setPrevBlockHash(overrideHash1.getHash());

			block.setPrevBranchBlockHash(overrideHash2.getHash());

			block.setHeight(Math.max(overrideHash2.getHeight(), overrideHash1.getHeight()) + 1);
		}

		block.addCoinbaseTransaction(outKey.getPubKey(), basecoin, tokenInfo, new MemoInfo("coinbase"));

		Transaction transaction = block.getTransactions().get(0);

		Sha256Hash sighash = transaction.getHash();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();

		ECKey.ECDSASignature party1Signature = outKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);

		ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
				Utils.HEX.decode(testPub));
		ECKey.ECDSASignature party2Signature = genesiskey.sign(sighash, aesKey);
		byte[] buf2 = party2Signature.encodeToDER();
		multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(genesiskey.toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(genesiskey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf2));
		multiSignBies.add(multiSignBy0);

		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// add fee
		Wallet w = Wallet.fromKeys(networkParameters, outKey, contextRoot);
		block.addTransaction(w.feeTransaction(aesKey));
		// save block
		block = adjustSolve(block);
		//

		return block;
	}

	public TokenIndexResponse getServerCalTokenIndex(String tokenid) throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp, TokenIndexResponse.class);
		return tokenIndexResponse;
	}

	public Block pullBlockDoMultiSign(final String tokenid, ECKey outKey, KeyParameter aesKey) throws Exception {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		String address = outKey.toAddress(networkParameters).toBase58();
		requestParam.put("address", address);
		requestParam.put("tokenid", tokenid);

		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		if (multiSignResponse.getMultiSigns().isEmpty())
			return null;
		MultiSign multiSign = multiSignResponse.getMultiSigns().get(0);

		byte[] payloadBytes = Utils.HEX.decode((String) multiSign.getBlockhashHex());
		Block block0 = networkParameters.getDefaultSerializer().makeBlock(payloadBytes);
		Transaction transaction = block0.getTransactions().get(0);

		List<MultiSignBy> multiSignBies = null;
		if (transaction.getDataSignature() == null) {
			multiSignBies = new ArrayList<MultiSignBy>();
		} else {
			MultiSignByRequest multiSignByRequest = Json.jsonmapper().readValue(transaction.getDataSignature(),
					MultiSignByRequest.class);
			multiSignBies = multiSignByRequest.getMultiSignBies();
		}
		Sha256Hash sighash = transaction.getHash();
		ECKey.ECDSASignature party1Signature = outKey.sign(sighash, aesKey);
		byte[] buf1 = party1Signature.encodeToDER();

		MultiSignBy multiSignBy0 = new MultiSignBy();

		multiSignBy0.setTokenid(multiSign.getTokenid());
		multiSignBy0.setTokenindex(multiSign.getTokenindex());
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
		OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block0.bitcoinSerialize());
		return block0;
	}

	public PermissionedAddressesResponse getPrevTokenMultiSignAddressList(Token token) throws Exception {
		HashMap<String, String> requestParam = new HashMap<String, String>();
		requestParam.put("domainNameBlockHash", token.getDomainNameBlockHash());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenPermissionedAddresses.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		PermissionedAddressesResponse permissionedAddressesResponse = Json.jsonmapper().readValue(resp,
				PermissionedAddressesResponse.class);
		return permissionedAddressesResponse;
	}
 
 

	public void send() throws JsonProcessingException, Exception {

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(data);
		rollingBlock.solve();

		OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), rollingBlock.bitcoinSerialize());

	}
 

	public Block createToken(ECKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, boolean increment, TokenKeyValues tokenKeyValues, int tokentype, String tokenid,
			Wallet w, byte[] pubkeyTo, MemoInfo memoInfo) throws Exception {

		Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
				amount, !increment, decimals, "");
		token.setTokenKeyValues(tokenKeyValues);
		token.setTokentype(tokentype);
		List<MultiSignAddress> addresses = new ArrayList<MultiSignAddress>();
		addresses.add(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
		return w.createToken(key, domainname, increment, token, addresses, pubkeyTo, memoInfo);

	}

 
 

	public BlockWrap defaultBlockWrap(Block block) throws Exception {
		return new BlockWrap(block, BlockEvaluation.buildInitial(block), null, networkParameters);
	}
}
