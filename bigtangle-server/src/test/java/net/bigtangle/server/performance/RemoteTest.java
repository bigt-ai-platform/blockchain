/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.math.LongMath;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.InsufficientMoneyException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.response.GetBalancesResponse;
import net.bigtangle.core.response.GetBlockEvaluationsResponse;
import net.bigtangle.core.response.GetTokensResponse;
import net.bigtangle.core.response.MultiSignByRequest;
import net.bigtangle.core.response.MultiSignResponse;
import net.bigtangle.core.response.OrderdataResponse;
import net.bigtangle.core.response.PermissionedAddressesResponse;
import net.bigtangle.core.response.TokenIndexResponse;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.UUIDUtil;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

  
public abstract class RemoteTest {

	 
	protected static final Logger log = LoggerFactory.getLogger(RemoteTest.class);
	public String contextRoot="http://localhost:8088/";

	/*
	 * default wallet which has key testpriv and yuanTokenPriv
	 */
	public Wallet wallet;

	protected final KeyParameter aesKey = null;
 

	public static String testPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
	public static String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";
 
 
	NetworkParameters networkParameters= TestParams.get() ;
	
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
 
		wallet = Wallet.fromKeys(networkParameters, ECKey.fromPrivate(Utils.HEX.decode(testPriv)), contextRoot);
 
	} 

	 

	
	protected void payTestTokenTo(ECKey beneficiary, ECKey testKey, BigInteger amount) throws Exception {
		payTestTokenTo(beneficiary, testKey, amount, new ArrayList<>());
	}

	protected Block payBigTo(ECKey beneficiary, BigInteger amount, List<Block> addedBlocks) throws Exception {
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<String, BigInteger>();

		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), amount);

		return payList(addedBlocks, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID);
	}

	private Block payList(List<Block> addedBlocks, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid)
			throws JsonProcessingException, IOException, InsufficientMoneyException, Exception {
		Block b = wallet.payMoneyToECKeyList(null, giveMoneyResult, tokenid, "payList");
		// log.debug("block " + (b == null ? "block is null" : b.toString()));
		if (addedBlocks != null) {
			addedBlocks.add(b);
		}
	 
		return b;
	}

	protected void payTestTokenTo(ECKey beneficiary, ECKey testKey, BigInteger amount, List<Block> addedBlocks)
			throws Exception {
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
	 
		HashMap<String, BigInteger> giveMoneyTestToken = new HashMap<String, BigInteger>();

		giveMoneyTestToken.put(beneficiary.toAddress(networkParameters).toString(), amount);
		Wallet w = Wallet.fromKeys(networkParameters, testKey, contextRoot);

		Block b = w.payToList(null, giveMoneyTestToken, testKey.getPubKey(), "");
		// log.debug("block " + (b == null ? "block is null" : b.toString()));

		addedBlocks.add(b);
		 
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
 
		return block;
	}

	protected void payBigToAmount(ECKey beneficiary, List<Block> addedBlocks)
			throws JsonProcessingException, Exception, BlockStoreException {

		HashMap<String, BigInteger> giveMoneyResult = new HashMap<String, BigInteger>();

		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), BigInteger.valueOf(500000000));
		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), BigInteger.valueOf(400000000));
		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), BigInteger.valueOf(300000000));
		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), BigInteger.valueOf(200000000));
		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), BigInteger.valueOf(100000000));
		payList(addedBlocks, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID);

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
				"", 1, 0, amount, true, decimal, networkParameters.getGenesisBlock().getHashAsString());

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", testKey.getPublicKeyAsHex()));

		block = saveTokenUnitTest(tokenInfo, coinbase, testKey, null, addedBlocks);
		addedBlocks.add(block);
 

		return block;
	}

 
  Block makeBuyOrder(ECKey beneficiary, String tokenId, long buyPrice, long buyAmount, String basetoken,
			List<Block> addedBlocks) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, beneficiary, contextRoot);
		w.setServerURL(contextRoot);
		payBigTo(beneficiary, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		Block block = w.buyOrder(null, tokenId, buyPrice, buyAmount, null, null, basetoken, true);
		addedBlocks.add(block);

		return block;
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

	protected Transaction createTestTransaction() throws Exception {

		ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
				Utils.HEX.decode(testPub));
		List<UTXO> outputs = getBalance(false, genesiskey);
		UTXO output = getLargeUTXO(outputs);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, output);
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction tx = new Transaction(networkParameters);
		tx.addOutput(new TransactionOutput(networkParameters, tx, amount, genesiskey));
		tx.addOutput(new TransactionOutput(networkParameters, tx,
				spendableOutput.getValue().subtract(amount).subtract(Coin.FEE_DEFAULT), genesiskey));
		TransactionInput input = tx.addInput(output.getBlockHash(), spendableOutput);
		Sha256Hash sighash = tx.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL, false);

		TransactionSignature tsrecsig = new TransactionSignature(genesiskey.sign(sighash), Transaction.SigHash.ALL,
				false);
		Script inputScript = ScriptBuilder.createInputScript(tsrecsig);
		input.setScriptSig(inputScript);
		return tx;
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

	protected Block testCreateToken(ECKey outKey, String tokennameName, List<Block> blocksAddedAll)
			throws JsonProcessingException, Exception {
		return testCreateToken(outKey, tokennameName, networkParameters.getGenesisBlock().getHashAsString(),
				blocksAddedAll);
	}

	protected Block testCreateToken(ECKey outKey, String tokennameName) throws JsonProcessingException, Exception {
		return testCreateToken(outKey, tokennameName, networkParameters.getGenesisBlock().getHashAsString(), null);
	}

	protected Block testCreateToken(ECKey outKey, String tokennameName, String domainpre, List<Block> blocksAddedAll)
			throws JsonProcessingException, Exception {
		return testCreateToken(outKey, tokennameName, domainpre, 77777L, blocksAddedAll);
	}

	protected Block testCreateToken(ECKey outKey, String tokennameName, String domainpre, Long amountgiven,
			List<Block> blocksAddedAll) throws JsonProcessingException, Exception {
		// ECKey outKey = walletKeys.get(0);
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();

		String tokenid = Utils.HEX.encode(pubKey);

		Coin basecoin = Coin.valueOf(amountgiven, pubKey);
		BigInteger amount = basecoin.getValue();

		Token token = Token.buildSimpleTokenInfo(true, null, tokenid, tokennameName, "", 1, 0, amount, true, 0,
				domainpre);

		tokenInfo.setToken(token);

		// add MultiSignAddress item
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(token.getTokenid(), "", outKey.getPublicKeyAsHex()));

		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this
				.getPrevTokenMultiSignAddressList(tokenInfo.getToken());
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex, 0));
			}
		}

		Block b = wallet.saveToken(tokenInfo, basecoin, outKey, null);
		if (blocksAddedAll != null)
			blocksAddedAll.add(b);

		Block re = this.pullBlockDoMultiSign(tokenid, outKey, aesKey);
		if (blocksAddedAll != null)
			blocksAddedAll.add(re);
		return re;
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

	// create a token with multi sign
	protected void testCreateMultiSigToken(List<ECKey> keys, TokenInfo tokenInfo)
			throws JsonProcessingException, Exception {
		// First issuance cannot be multisign but instead needs the signature of
		// the token id
		// Hence we first create a normal token with multiple permissioned, then
		// we can issue via multisign

		String tokenid = createFirstMultisignToken(keys, tokenInfo);
 

		BigInteger amount = new BigInteger("200000");
		Coin basecoin = new Coin(amount, tokenid);

		// TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();
		;

		Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid, "test", "test", 3,
				tokenindex_, amount, false, 0, networkParameters.getGenesisBlock().getHashAsString());
		KeyValue kv = new KeyValue();
		kv.setKey("testkey");
		kv.setKey("testvalue");
		tokens.addKeyvalue(kv);

		tokenInfo.setToken(tokens);

		ECKey key1 = keys.get(1);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key1.getPublicKeyAsHex()));

		ECKey key2 = keys.get(2);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key2.getPublicKeyAsHex()));

		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this
				.getPrevTokenMultiSignAddressList(tokenInfo.getToken());
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex));
			}
		}

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		block.addCoinbaseTransaction(keys.get(2).getPubKey(), basecoin, tokenInfo, new MemoInfo("coinbase"));
		block = adjustSolve(block);

		log.debug("block hash : " + block.getHashAsString());

		// save block, but no signature and is not saved as block, but in a
		// table for signs
		OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());

		List<ECKey> ecKeys = new ArrayList<ECKey>();
		ecKeys.add(key1);
		ecKeys.add(key2);

		for (ECKey ecKey : ecKeys) {
			HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
			requestParam0.put("address", ecKey.toAddress(networkParameters).toBase58());
			byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
					Json.jsonmapper().writeValueAsString(requestParam0));
			System.out.println(resp);

			MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);

			if (multiSignResponse.getMultiSigns().isEmpty())
				continue;

			String blockhashHex = multiSignResponse.getMultiSigns().get((int) tokenindex_).getBlockhashHex();
			byte[] payloadBytes = Utils.HEX.decode(blockhashHex);

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
			ECKey.ECDSASignature party1Signature = ecKey.sign(sighash);
			byte[] buf1 = party1Signature.encodeToDER();

			MultiSignBy multiSignBy0 = new MultiSignBy();
			multiSignBy0.setTokenid(tokenid);
			multiSignBy0.setTokenindex(tokenindex_);
			multiSignBy0.setAddress(ecKey.toAddress(networkParameters).toBase58());
			multiSignBy0.setPublickey(Utils.HEX.encode(ecKey.getPubKey()));
			multiSignBy0.setSignature(Utils.HEX.encode(buf1));
			multiSignBies.add(multiSignBy0);
			MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
			transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
			checkResponse(OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block0.bitcoinSerialize()));

		}

		checkBalance(basecoin, key1);
	}

	private String createFirstMultisignToken(List<ECKey> keys, TokenInfo tokenInfo)
			throws Exception, JsonProcessingException, IOException, JsonParseException, JsonMappingException {
		String tokenid = keys.get(1).getPublicKeyAsHex();

		Coin basecoin = MonetaryFormat.FIAT.noCode().parse("678900000");

		// TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();
		Sha256Hash prevblockhash = tokenIndexResponse.getBlockhash();

		Token tokens = Token.buildSimpleTokenInfo(true, prevblockhash, tokenid, "test", "test", 3, tokenindex_,
				basecoin.getValue(), false, 0, networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		ECKey key1 = keys.get(1);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key1.getPublicKeyAsHex()));

		ECKey key2 = keys.get(2);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key2.getPublicKeyAsHex()));

		List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();
		PermissionedAddressesResponse permissionedAddressesResponse = this
				.getPrevTokenMultiSignAddressList(tokenInfo.getToken());
		if (permissionedAddressesResponse != null && permissionedAddressesResponse.getMultiSignAddresses() != null
				&& !permissionedAddressesResponse.getMultiSignAddresses().isEmpty()) {
			for (MultiSignAddress multiSignAddress : permissionedAddressesResponse.getMultiSignAddresses()) {
				final String pubKeyHex = multiSignAddress.getPubKeyHex();
				multiSignAddresses.add(new MultiSignAddress(tokenid, "", pubKeyHex));
			}
		}

		wallet.saveToken(tokenInfo, basecoin, keys.get(1), null);
		return tokenid;
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

		PermissionedAddressesResponse permissionedAddressesResponse = this
				.getPrevTokenMultiSignAddressList(tokenInfo.getToken());
		MultiSignAddress multiSignAddress = permissionedAddressesResponse.getMultiSignAddresses().get(0);

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
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);

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

	private List<BlockEvaluationDisplay> getBlockInfos() throws Exception {

		String lastestAmount = "200";
		Map<String, Object> requestParam = new HashMap<String, Object>();

		requestParam.put("lastestAmount", lastestAmount);
		byte[] response = OkHttp3Util.postString(contextRoot + "/" + ReqCmd.findBlockEvaluation.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetBlockEvaluationsResponse getBlockEvaluationsResponse = Json.jsonmapper().readValue(response,
				GetBlockEvaluationsResponse.class);
		return getBlockEvaluationsResponse.getEvaluations();
	}

	public Block createToken(ECKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, boolean increment, TokenKeyValues tokenKeyValues, int tokentype, String tokenid,
			Wallet w) throws Exception {
		w.importKey(key);
		Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
				amount, !increment, decimals, "");
		token.setTokenKeyValues(tokenKeyValues);
		token.setTokentype(tokentype);
		List<MultiSignAddress> addresses = new ArrayList<MultiSignAddress>();
		addresses.add(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
		return w.createToken(key, domainname, increment, token, addresses);

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

	public BlockEvaluation getBlockEvaluation(Sha256Hash hash, BlockStoreInterface store) throws BlockStoreException {
		return store.getBlockWrap(hash).getBlockEvaluation();
	}

 

	public void balance(Tokensums a) throws Exception {

		Map<String, BigInteger> totalMapValue = new HashMap<String, BigInteger>();

		for (UTXO utxo : a.getUtxos()) {
			String address = utxo.getAddress();
			BigInteger amount = utxo.getValue().getValue();
			if (totalMapValue.containsKey(address)) {
				BigInteger temp = totalMapValue.get(address);
				totalMapValue.put(address, temp.add(amount));
			} else {
				totalMapValue.put(address, amount);
			}

		}
		log.debug(totalMapValue.toString());
	}

 
	public void sell(List<Block> blocksAddedAll) throws Exception {

		List<String> keyStrHex000 = new ArrayList<String>();

		for (ECKey ecKey : wallet.walletKeys()) {
			keyStrHex000.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
		}

		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);
		List<UTXO> utxos = getBalancesResponse.getOutputs();
		Collections.shuffle(utxos);
		// long q = 8;
		for (UTXO utxo : utxos) {
			if (!NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(utxo.getTokenId())
					&& utxo.getValue().isGreaterThan(Coin.ZERO)) {
				wallet.setServerURL(contextRoot);
				try {
					Block sellOrder = wallet.sellOrder(null, utxo.getTokenId(), 100, 1000, null, null,
							NetworkParameters.BIGTANGLE_TOKENID_STRING, true);
					blocksAddedAll.add(sellOrder);

				} catch (InsufficientMoneyException e) {
					// ignore: handle exception
				}catch (Exception e) {
					e.printStackTrace();
				}
				// break;
			}

		}
	}

	public void payMoneyToWallet1(List<Block> blocksAddedAll) throws Exception {

		HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();

		for (int i = 0; i < 10; i++) {
			giveMoneyResult.put(new ECKey().toAddress(networkParameters).toString(),
					BigInteger.valueOf(3333000000l / LongMath.pow(2, 1)));
		}

		Block b = wallet.payMoneyToECKeyList(null, giveMoneyResult, "payMoneyToWallet1");
		blocksAddedAll.add(b);
		// makeRewardBlock(blocksAddedAll);
	}

	public void buy(List<Block> blocksAddedAll) throws Exception {

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		byte[] response0 = OkHttp3Util.post(contextRoot + ReqCmd.getOrders.name(),
				Json.jsonmapper().writeValueAsString(requestParam).getBytes());

		OrderdataResponse orderdataResponse = Json.jsonmapper().readValue(response0, OrderdataResponse.class);

		for (OrderRecord orderRecord : orderdataResponse.getAllOrdersSorted()) {
			try {
				buy(orderRecord, blocksAddedAll);
			} catch (InsufficientMoneyException e) {
				Thread.sleep(4000);
			} catch (Exception e) {
				log.debug("", e);
			}
		}
	}

	public void buy(OrderRecord orderRecord, List<Block> blocksAddedAll) throws Exception {

		if (!NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(orderRecord.getOfferTokenid())) {
			// sell order and make buy
			long price = orderRecord.getTargetValue() / orderRecord.getOfferValue();

			Block buyOrder = wallet.buyOrder(null, orderRecord.getOfferTokenid(), price, orderRecord.getOfferValue(),
					null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, false);
			blocksAddedAll.add(buyOrder);
			// makeOrderExecutionAndReward(blocksAddedAll);

		}

	}
 
 

	public List<ECKey> createUserkey() {
		List<ECKey> userkeys = new ArrayList<ECKey>();
		String[] s = new String[] { "0927cf94d82b0a0f1c8f06f127844034820aecd0adbaaf67c962d3eb6b0a6ea8",
				"a2ba304ed68e2835ba3282e10380e31c8fe605fc232b88e497846654193ba38a",
				"b96358b80bbf822fea87f2a5eea33dcffbf15e7f1c9691b3cd643cbb24ea6821",
				"256f4faea34cbec71ae22d6f6b4ea80bddd5d7ef7c70530be78506b83bed7aea",
				"6d2538a814150fb28d086dec83a1389d1f4f5583d996883c1cd0972c21d773c1",
				"8ee39e7c10e31d7cfcf31d99d469b107e78120d84cff23aa38224504413e6b52",

				"0d59be5cafdf76f40be223c818d7ed61c9c374a973f6356c4a87cc13d610a2e2",
				"f42955011b4848fd6d26f898f937176a8549f3641000845223cef81078c8b92b",
				"2212ea2b6bb6479021f994632fa66f891b5953e04db0f5316347de2a45e1d6c2",
				"0b3451d9dd2d411a177ca3131e0e90c3f028c1534ca886f13af52ac442edd6fa"

		};
		for (String priv : s) {
			ECKey key = ECKey.fromPrivate(Utils.HEX.decode(priv));
			userkeys.add(key);
		}
		return userkeys;
	}
 
 
	/**
	 * Selects two blocks to approve via MCMC for the given prototype block such
	 * that the two approved blocks are not conflicting with the prototype block
	 * itself
	 *
	 * @param prototype Existing solid block that is considered when walking
	 * @return
	 * @throws VerificationException if the given prototype is not compatible with
	 *                               the current milestone
	 */
 
	public UTXO getUTXO(TransactionOutPoint out, BlockStoreInterface store) throws BlockStoreException {
		return store.getTransactionOutput(out.getBlockHash(), out.getTxHash(), out.getIndex());
	}

 
  
}
