/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.PayMultiSign;
import net.bigtangle.core.PayMultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.response.MultiSignResponse;
import net.bigtangle.response.OutputsDetailsResponse;
import net.bigtangle.response.PayMultiSignAddressListResponse;
import net.bigtangle.response.PayMultiSignDetailsResponse;
import net.bigtangle.response.PayMultiSignResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.response.TokenIndexResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.UUIDUtil;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

 
public class TokenAndPayTests extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(TokenAndPayTests.class);

	public void testMultiSignByJson() throws Exception {
		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid("111111");
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress("222222");
		multiSignBy0.setPublickey("33333");
		multiSignBy0.setSignature("44444");
		multiSignBies.add(multiSignBy0);

		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);

		String jsonStr = Json.jsonmapper().writeValueAsString(multiSignByRequest);
		log.info(jsonStr);

		multiSignByRequest = Json.jsonmapper().readValue(jsonStr, MultiSignByRequest.class);
		for (MultiSignBy multiSignBy : multiSignByRequest.getMultiSignBies()) {
			log.info(Json.jsonmapper().writeValueAsString(multiSignBy));
		}
	}

	// FIXME @Test
	public void testBlockDamage() throws Exception {
		ECKey outKey = new ECKey();
		ECKey genesiskey = ECKey.fromPublicOnly(Utils.HEX.decode(testPub));
		List<UTXO> outputs = getBalance(false, genesiskey);
		TransactionOutput transactionOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction tx = new Transaction(networkParameters);
		tx.addOutput(new TransactionOutput(networkParameters, tx, amount, outKey));
		TransactionInput input = tx.addInput(outputs.get(0).getBlockHash(), transactionOutput);
		Sha256Hash sighash = tx.hashForSignature(0, transactionOutput.getScriptBytes(), Transaction.SigHash.ALL, false);
		TransactionSignature tsrecsig = new TransactionSignature(genesiskey.sign(sighash), Transaction.SigHash.ALL,
				false);
		Script inputScript = ScriptBuilder.createInputScript(tsrecsig);
		input.setScriptSig(inputScript);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		byte[] buf = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(buf);
		rollingBlock.addTransaction(tx);
		rollingBlock.solve();
		OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), rollingBlock.bitcoinSerialize());

		Transaction tx_ = rollingBlock.getTransactions().get(0);
		buf = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		rollingBlock = networkParameters.getDefaultSerializer().makeBlock(buf);
		rollingBlock.addTransaction(tx_);
		rollingBlock.solve();
		OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), rollingBlock.bitcoinSerialize());
	}

	// @Test
	public void testPayMultiSignToStore() throws BlockStoreException {
		ECKey outKey = new ECKey();
		PayMultiSign payMultiSign = new PayMultiSign();
		payMultiSign.setOrderid(UUIDUtil.randomUUID());
		payMultiSign.setTokenid(Utils.HEX.encode(outKey.getPubKey()));
		payMultiSign.setToaddress(outKey.toAddress(networkParameters).toBase58());
		// payMultiSign.setBlockhash(outKey.getPubKey());
		payMultiSign.setAmount(new BigInteger("1000"));
		payMultiSign.setMinsignnumber(3);
		this.store.insertPayPayMultiSign(payMultiSign);

		PayMultiSign payMultiSign_ = this.store.getPayMultiSignWithOrderid(payMultiSign.getOrderid());
		assertEquals(payMultiSign.getOrderid(), payMultiSign_.getOrderid());

		PayMultiSignAddress payMultiSignAddress = new PayMultiSignAddress();
		payMultiSignAddress.setOrderid(payMultiSign.getOrderid());
		payMultiSignAddress.setPubKey(outKey.getPublicKeyAsHex());
		payMultiSignAddress.setSign(0);
		this.store.insertPayMultiSignAddress(payMultiSignAddress);

		List<PayMultiSignAddress> payMultiSignAddresses = this.store
				.getPayMultiSignAddressWithOrderid(payMultiSign.getOrderid());
		assertEquals(payMultiSignAddresses.get(0).getPubKey(), payMultiSignAddress.getPubKey());

		this.store.updatePayMultiSignAddressSign(payMultiSign.getOrderid(), outKey.getPublicKeyAsHex(), 1,
				outKey.getPubKey());
		payMultiSignAddresses = this.store.getPayMultiSignAddressWithOrderid(payMultiSign.getOrderid());
		assertEquals(payMultiSignAddresses.get(0).getSign(), 1);

		ECKey ecKey = new ECKey();
		this.store.updatePayMultiSignBlockhash(payMultiSign.getOrderid(), ecKey.getPubKey());

		payMultiSign_ = this.store.getPayMultiSignWithOrderid(payMultiSign.getOrderid());
		// assertArrayEquals(payMultiSign_.getBlockhash(), ecKey.getPubKey());
	}

	// @Before
	public Block getRollingBlock(ECKey outKey) throws Exception {

		Block rollingBlock =UtilsTest.createBlock(networkParameters, networkParameters.getGenesisBlock(), networkParameters.getGenesisBlock());
		blockGraph.addBlock(rollingBlock, true, store);
		for (int i = 1; i < networkParameters.getSpendableCoinbaseDepth(); i++) {
			rollingBlock =UtilsTest.createBlock(networkParameters,rollingBlock, networkParameters.getGenesisBlock());
			blockGraph.addBlock(rollingBlock, true, store);
		}
		return rollingBlock;
	}

	@Test
	public void testCreateTransaction() throws Exception {
		assertThrows(RuntimeException.class, () -> {
			byte[] data = getAskTransactionBlock();
			Block block = networkParameters.getDefaultSerializer().makeBlock(data);
			// no solve get error code

			OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(),
					Json.jsonmapper().writeValueAsString(block.bitcoinSerialize()).getBytes("UTF-8"));

		});

	}

	// TODO fix @Test
	public void testCreateMultiSigList() throws Exception {

		List<ECKey> signKeys = new LinkedList<ECKey>();
		signKeys.add(wallet.walletKeys(null).get(0));

		TokenInfo tokenInfo = new TokenInfo();
		testCreateMultiSigToken(signKeys, tokenInfo);
		ECKey toKey = new ECKey();

		String tokenid = tokenInfo.getToken().getTokenid();
		Coin amount = Coin.valueOf(1200, Utils.HEX.decode(tokenid));
		// pay to address toKey, remainder return to signKeys with multi signs
		PayMultiSign payMultiSign = launchPayMultiSign(toKey, signKeys, tokenInfo, amount);

		paymultisign(signKeys, payMultiSign);

		checkBalance(amount, toKey);

		// repeat the multi signs
		Coin amount1 = Coin.valueOf(1100, Utils.HEX.decode(tokenid));
		PayMultiSign payMultiSign1 = launchPayMultiSign(toKey, signKeys, tokenInfo, amount1);

		paymultisign(signKeys, payMultiSign1);
		checkBalance(amount1, toKey);
	}

	private void paymultisign(List<ECKey> signKeys, PayMultiSign payMultiSign)
			throws Exception, JsonProcessingException, IOException, JsonParseException, JsonMappingException {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("orderid", payMultiSign.getOrderid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getPayMultiSignAddressList.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		PayMultiSignAddressListResponse payMultiSignAddressListResponse = Json.jsonmapper().readValue(resp,
				PayMultiSignAddressListResponse.class);
		List<PayMultiSignAddress> payMultiSignAddresses = payMultiSignAddressListResponse.getPayMultiSignAddresses();
		assertTrue(payMultiSignAddresses.size() > 0);
		// KeyParameter aesKey = null;
		ECKey currentECKey = null;
		for (PayMultiSignAddress payMultiSignAddress : payMultiSignAddresses) {
			if (payMultiSignAddress.getSign() == 1) {
				continue;
			}
			for (ECKey ecKey : signKeys) {
				if (ecKey.toAddress(networkParameters).toString().equals(payMultiSignAddress.getPubKey())) {
					currentECKey = ecKey;
					this.payMultiSign(currentECKey, payMultiSign.getOrderid(), networkParameters, contextRoot);
					break;
				}
			}

		}
	}

	private PayMultiSign launchPayMultiSign(ECKey toKey, List<ECKey> signKeys, TokenInfo tokenInfo, Coin amount)
			throws Exception, JsonProcessingException {
		List<ECKey> ecKeys = new ArrayList<ECKey>();
		// ecKeys.add(walletKeys.get(0));
		ecKeys.add(signKeys.get(1));
		ecKeys.add(signKeys.get(2));

		UTXO output = getBalance(amount.getTokenHex(), false, ecKeys);
		log.debug(output.toString());
		// filter
		TransactionOutput multisigOutput = new FreeStandingTransactionOutput(this.networkParameters, output);
		Transaction transaction = new Transaction(networkParameters);
		transaction.addOutput(amount, toKey);
		// remainder of utxo goes here with multisign keys ecKeys
		Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(2, ecKeys);
		Coin amount2 = multisigOutput.getValue().subtract(amount);
		transaction.addOutput(amount2, scriptPubKey);

		transaction.addInput(output.getBlockHash(), multisigOutput);

		PayMultiSign payMultiSign = createPayMultiSign(toKey, amount, output, transaction);

		OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.launchPayMultiSign.name(),
				Json.jsonmapper().writeValueAsString(payMultiSign));
		return payMultiSign;
	}

	private PayMultiSign createPayMultiSign(ECKey toKey, Coin amount, UTXO output, Transaction transaction) {
		PayMultiSign payMultiSign = new PayMultiSign();
		payMultiSign.setOrderid(UUIDUtil.randomUUID());
		payMultiSign.setTokenid(amount.getTokenHex());
		payMultiSign.setBlockhashHex(Utils.HEX.encode(transaction.bitcoinSerialize()));
		payMultiSign.setToaddress(toKey.toAddress(networkParameters).toBase58());
		payMultiSign.setAmount(amount.getValue());
		payMultiSign.setMinsignnumber(2);
		payMultiSign.setOutputHashHex(output.getHashHex());
		payMultiSign.setOutputindex(output.getIndex());
		return payMultiSign;
	}

	public void payMultiSign(ECKey ecKey, String orderid, NetworkParameters networkParameters, String contextRoot)
			throws Exception {
		List<String> pubKeys = new ArrayList<String>();
		pubKeys.add(ecKey.getPublicKeyAsHex());

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.clear();
		requestParam.put("orderid", orderid);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.payMultiSignDetails.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		PayMultiSignDetailsResponse payMultiSignDetailsResponse = Json.jsonmapper().readValue(resp,
				PayMultiSignDetailsResponse.class);
		PayMultiSign payMultiSign_ = payMultiSignDetailsResponse.getPayMultiSign();

		requestParam.clear();
		requestParam.put("hexStr", payMultiSign_.getOutputHashHex() + ":" + payMultiSign_.getOutputindex());
		resp = OkHttp3Util.postString(contextRoot + ReqCmd.getOutputByKey.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		// log.debug(resp);

		OutputsDetailsResponse outputsDetailsResponse = Json.jsonmapper().readValue(resp, OutputsDetailsResponse.class);
		UTXO u = outputsDetailsResponse.getOutputs();

		TransactionOutput multisigOutput_1 = new FreeStandingTransactionOutput(networkParameters, u);
		Script multisigScript_1 = multisigOutput_1.getScriptPubKey();

		byte[] payloadBytes = Utils.HEX.decode((String) payMultiSign_.getBlockhashHex());
		Transaction transaction0 = networkParameters.getDefaultSerializer().makeTransaction(payloadBytes);

		Sha256Hash sighash = transaction0.hashForSignature(0, multisigScript_1, Transaction.SigHash.ALL, false);
		TransactionSignature transactionSignature = new TransactionSignature(ecKey.sign(sighash),
				Transaction.SigHash.ALL, false);

		ECKey.ECDSASignature party1Signature = ecKey.sign(transaction0.getHash());
		byte[] buf1 = party1Signature.encodeToDER();

		requestParam.clear();
		requestParam.put("orderid", (String) payMultiSign_.getOrderid());
		requestParam.put("pubKey", ecKey.getPublicKeyAsHex());
		requestParam.put("signature", Utils.HEX.encode(buf1));
		requestParam.put("signInputData", Utils.HEX.encode(transactionSignature.encodeToBitcoin()));
		resp = OkHttp3Util.postString(contextRoot + ReqCmd.payMultiSign.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		// log.debug(resp);

		PayMultiSignResponse payMultiSignResponse = Json.jsonmapper().readValue(resp, PayMultiSignResponse.class);
		boolean success = payMultiSignResponse.isSuccess();
		if (success) {
			payMultiSave(networkParameters, contextRoot, requestParam, payMultiSign_, transaction0);
		}
	}

	private void payMultiSave(NetworkParameters networkParameters, String contextRoot,
			HashMap<String, Object> requestParam, PayMultiSign payMultiSign_, Transaction transaction0)
			throws Exception, JsonProcessingException, IOException, JsonParseException, JsonMappingException {
		byte[] resp;
		requestParam.clear();
		requestParam.put("orderid", (String) payMultiSign_.getOrderid());
		resp = OkHttp3Util.postString(contextRoot + ReqCmd.getPayMultiSignAddressList.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		// log.debug(resp);

		PayMultiSignAddressListResponse payMultiSignAddressListResponse = Json.jsonmapper().readValue(resp,
				PayMultiSignAddressListResponse.class);
		List<PayMultiSignAddress> payMultiSignAddresses = payMultiSignAddressListResponse.getPayMultiSignAddresses();

		List<TransactionSignature> sigs = new ArrayList<TransactionSignature>();
		for (PayMultiSignAddress payMultiSignAddress : payMultiSignAddresses) {
			String signInputDataHex = payMultiSignAddress.getSignInputDataHex();
			TransactionSignature sig = TransactionSignature.decodeFromBitcoin(Utils.HEX.decode(signInputDataHex), true,
					false);
			sigs.add(sig);
		}

		Script inputScript = ScriptBuilder.createMultiSigInputScript(sigs);
		transaction0.getInput(0).setScriptSig(inputScript);

		byte[] buf = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(buf);
		rollingBlock.addTransaction(transaction0);
		rollingBlock = adjustSolve(rollingBlock);
		checkResponse(OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), rollingBlock.bitcoinSerialize()));
	}

	@SuppressWarnings("unchecked")
	// @Test(expected = RuntimeException.class)
	// TODO @Test
	public void testMultiSigTokenIsNull() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);

		String tokenid = Utils.HEX.encode(keys.get(0).getPubKeyHash());
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);
		final TokenInfo tokenInfo = null;
		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int err = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertNotEquals(err, 101);
	}

	@SuppressWarnings("unchecked")
	// @Test
	public void testMultiSigTokenInfoIsNull() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = Utils.HEX.encode(keys.get(0).getPubKeyHash());
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);
		final TokenInfo tokenInfo = new TokenInfo();
		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int duration = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertEquals(duration, 101);
	}

	@SuppressWarnings("unchecked")
	// @Test
	public void testMultiSigTokenInfoSignnumberZero() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = Utils.HEX.encode(keys.get(0).getPubKeyHash());
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		TokenInfo tokenInfo = new TokenInfo();
		Token tokens = Token.buildSimpleTokenInfo(true, null, tokenid, UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), -1, 0, basecoin.getValue(), false, 0,
				networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int duration = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertEquals(duration, 101);
	}

	@SuppressWarnings("unchecked")
	// @Test
	public void testMultiSigTokenSerialIsNull() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = Utils.HEX.encode(keys.get(0).getPubKeyHash());
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		TokenInfo tokenInfo = new TokenInfo();

		Token tokens = Token.buildSimpleTokenInfo(true, null, tokenid, UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), 3, 0, basecoin.getValue(), false, 0,
				networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int duration = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertEquals(duration, 101);
	}

	@SuppressWarnings("unchecked")
	// @Test
	public void testMultiSigMultiSignAddressSizeZero() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = Utils.HEX.encode(keys.get(0).getPubKeyHash());
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();

		Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
				UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(), false,
				0, networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int duration = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertEquals(duration, 101);
	}

	@SuppressWarnings("unchecked")
	// @Test
	public void testMultiSigMultiSignAddressSizeSignnumber() throws Exception {
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = keys.get(0).getPublicKeyAsHex();
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();

		Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
				UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(), false,
				0, networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(0).getPublicKeyAsHex()));
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(1).getPublicKeyAsHex()));

		block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
		block.solve();
		byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
		int duration = (Integer) result2.get("errorcode");
		log.debug("resp : " + resp);
		assertEquals(duration, 101);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMultiSigMultiSignDatasignatureAddress() throws Exception {
		assertThrows(RuntimeException.class, () -> {
			List<ECKey> keys = wallet.walletKeys(null);
			HashMap<String, String> requestParam = new HashMap<String, String>();
			byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
					Json.jsonmapper().writeValueAsString(requestParam));
			Block block = networkParameters.getDefaultSerializer().makeBlock(data);
			block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
			// Tokenid with keys.get(0) is already used in setup
			String tokenid = keys.get(5).getPublicKeyAsHex();
			int amount = 100000000;
			Coin basecoin = Coin.valueOf(amount, tokenid);

			TokenInfo tokenInfo = new TokenInfo();

			HashMap<String, String> requestParam00 = new HashMap<String, String>();
			requestParam00.put("tokenid", tokenid);
			byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
					Json.jsonmapper().writeValueAsString(requestParam00));

			TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
			long tokenindex_ = tokenIndexResponse.getTokenindex();

			Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
					UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(),
					false, 0, networkParameters.getGenesisBlock().getHashAsString());
			tokenInfo.setToken(tokens);

			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(0).getPublicKeyAsHex()));
			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(1).getPublicKeyAsHex()));
			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(2).getPublicKeyAsHex()));

			block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, new MemoInfo("test"));
			block.solve();
			byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
			HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
			// int duration = (Integer) result2.get("errorcode");
			// log.debug("resp : " + resp);
			// assertEquals(duration, 0);

			HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
			requestParam0.put("address", keys.get(0).toAddress(networkParameters).toBase58());
			resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
					Json.jsonmapper().writeValueAsString(requestParam0));

			MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
			String blockhashHex = multiSignResponse.getMultiSigns().get((int) tokenindex_ - 1).getBlockhashHex();
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
			ECKey.ECDSASignature party1Signature = keys.get(3).sign(sighash);
			byte[] buf1 = party1Signature.encodeToDER();

			MultiSignBy multiSignBy0 = new MultiSignBy();
			multiSignBy0.setTokenid(tokenid);
			multiSignBy0.setTokenindex(tokenindex_);
			multiSignBy0.setAddress(keys.get(3).toAddress(networkParameters).toBase58());
			multiSignBy0.setPublickey(Utils.HEX.encode(keys.get(3).getPubKey()));
			multiSignBy0.setSignature(Utils.HEX.encode(buf1));
			multiSignBies.add(multiSignBy0);
			MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
			transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
			resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block0.bitcoinSerialize());

			result2 = Json.jsonmapper().readValue(resp, HashMap.class);
			int duration = (Integer) result2.get("errorcode");
			log.debug("resp : " + resp);
			assertEquals(duration, 101);
		});

	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMultiSigMultiSignSignatureError() throws Exception {
		assertThrows(RuntimeException.class, () -> {
			List<ECKey> keys = wallet.walletKeys(null);
			HashMap<String, String> requestParam = new HashMap<String, String>();
			byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
					Json.jsonmapper().writeValueAsString(requestParam));
			Block block = networkParameters.getDefaultSerializer().makeBlock(data);
			block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);

			String tokenid = keys.get(5).getPublicKeyAsHex();
			int amount = 100000000;
			Coin basecoin = Coin.valueOf(amount, tokenid);

			TokenInfo tokenInfo = new TokenInfo();
			HashMap<String, String> requestParam00 = new HashMap<String, String>();
			requestParam00.put("tokenid", tokenid);
			byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
					Json.jsonmapper().writeValueAsString(requestParam00));

			TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
			long tokenindex_ = tokenIndexResponse.getTokenindex();

			Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
					UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(),
					false, 0, networkParameters.getGenesisBlock().getHashAsString());
			tokenInfo.setToken(tokens);

			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(0).getPublicKeyAsHex()));
			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(1).getPublicKeyAsHex()));
			tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(2).getPublicKeyAsHex()));

			block.addCoinbaseTransaction(keys.get(0).getPubKey(), basecoin, tokenInfo, null);
			block.solve();
			byte[] resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
			HashMap<String, Object> result2 = Json.jsonmapper().readValue(resp, HashMap.class);
			// int duration = (Integer) result2.get("errorcode");
			// log.debug("resp : " + resp);
			// assertEquals(duration, 0);

			HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
			requestParam0.put("address", keys.get(0).toAddress(networkParameters).toBase58());
			resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
					Json.jsonmapper().writeValueAsString(requestParam0));

			MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
			byte[] payloadBytes = Utils.HEX
					.decode((String) multiSignResponse.getMultiSigns().get((int) tokenindex_ - 1).getBlockhashHex());
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
			byte[] buf1 = new byte[] { (byte) 0, (byte) 1, (byte) 2, (byte) 3 };

			MultiSignBy multiSignBy0 = new MultiSignBy();
			multiSignBy0.setTokenid(tokenid);
			multiSignBy0.setTokenindex(tokenindex_);
			multiSignBy0.setAddress(keys.get(0).toAddress(networkParameters).toBase58());
			multiSignBy0.setPublickey(Utils.HEX.encode(keys.get(0).getPubKey()));
			multiSignBy0.setSignature(Utils.HEX.encode(buf1));
			multiSignBies.add(multiSignBy0);
			MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
			transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

			resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block0.bitcoinSerialize());

			result2 = Json.jsonmapper().readValue(resp, HashMap.class);
			int duration = (Integer) result2.get("errorcode");
			log.debug("resp : " + resp);
			assertTrue(duration == 101);
		});

	}

	@Test
	public void testMultiSigMultiSignSignatureSuccess() throws Exception {
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		List<ECKey> keys = wallet.walletKeys(null);
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		String tokenid = keys.get(5).getPublicKeyAsHex();
		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();

		Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
				UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(), false,
				0, networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(0).getPublicKeyAsHex()));
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(1).getPublicKeyAsHex()));
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", keys.get(2).getPublicKeyAsHex()));

		wallet.saveToken(tokenInfo, basecoin, keys.get(3), null);

		HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
		requestParam0.put("address", keys.get(0).toAddress(networkParameters).toBase58());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam0));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		byte[] payloadBytes = Utils.HEX
				.decode((String) multiSignResponse.getMultiSigns().get((int) tokenindex_).getBlockhashHex());
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
		ECKey.ECDSASignature party1Signature = keys.get(0).sign(sighash);
		byte[] buf1 = party1Signature.encodeToDER();

		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenid);
		multiSignBy0.setTokenindex(tokenindex_);
		multiSignBy0.setAddress(keys.get(0).toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(keys.get(0).getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
		wallet.saveToken(tokenInfo, basecoin, keys.get(3), null);
	}

	@Test
	public void testCreateSingleTokenIndexCheckTokenExist() throws Exception {
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		List<ECKey> keys = wallet.walletKeys(null);
		ECKey outKey = keys.get(6);
		byte[] pubKey = outKey.getPubKey();
		String tokenid = Utils.HEX.encode(pubKey);
		for (int i = 1; i <= 2; i++) {
			TokenInfo tokenInfo = new TokenInfo();

			Coin basecoin = Coin.valueOf(100000L, pubKey);

			Token tokens = Token.buildSimpleTokenInfo(true, null, tokenid, "test", "test", 2, 0, basecoin.getValue(),
					false, 0, networkParameters.getGenesisBlock().getHashAsString());
			tokenInfo.setToken(tokens);

			// add MultiSignAddress item
			tokenInfo.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

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
			block.addCoinbaseTransaction(outKey.getPubKey(), basecoin, tokenInfo, null);

			Transaction transaction = block.getTransactions().get(0);

			Sha256Hash sighash = transaction.getHash();
			ECKey.ECDSASignature party1Signature = outKey.sign(sighash);
			byte[] buf1 = party1Signature.encodeToDER();

			List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
			MultiSignBy multiSignBy0 = new MultiSignBy();
			multiSignBy0.setTokenid(Utils.HEX.encode(pubKey));
			multiSignBy0.setTokenindex(0);
			multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
			multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
			multiSignBy0.setSignature(Utils.HEX.encode(buf1));
			multiSignBies.add(multiSignBy0);
			MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
			transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

			// save block
			block.solve();
			OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());

		}
	}

	// @Test(expected = RuntimeException.class)

	public void testCreateMultiSigTokenIndexCheckTokenExist() throws JsonProcessingException, Exception {

		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		List<ECKey> keys = wallet.walletKeys(null);
		String tokenid = keys.get(3).getPublicKeyAsHex();

		TokenInfo tokenInfo = new TokenInfo();

		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		long tokenindex1 = 1;
		Token tokens = Token.buildSimpleTokenInfo(true, null, tokenid, UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), 3, tokenindex1, basecoin.getValue(), false, 0,
				networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		ECKey key1 = keys.get(0);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key1.getPublicKeyAsHex()));

		ECKey key2 = keys.get(1);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key2.getPublicKeyAsHex()));

		ECKey key3 = keys.get(2);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key3.getPublicKeyAsHex()));

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(Block.Type.BLOCKTYPE_TOKEN_CREATION);
		block.addCoinbaseTransaction(key1.getPubKey(), basecoin, tokenInfo, null);
		block.solve();

		log.debug("block hash : " + block.getHashAsString());

		// save block
		byte[] resp000 = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
		// HashMap<String, Object> result000 =
		// Json.jsonmapper().readValue(resp000, HashMap.class);
		// int duration = (Integer) result000.get("errorcode");
		log.debug("resp : " + resp000);

		List<ECKey> ecKeys = new ArrayList<ECKey>();
		ecKeys.add(key1);
		ecKeys.add(key2);
		ecKeys.add(key3);

		for (ECKey ecKey : ecKeys) {
			HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
			requestParam0.put("address", ecKey.toAddress(networkParameters).toBase58());
			byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
					Json.jsonmapper().writeValueAsString(requestParam0));
			// log.debug(resp);

			MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
			String blockhashHex = multiSignResponse.getMultiSigns().get(0).getBlockhashHex();
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
			multiSignBy0.setTokenindex(tokenindex1);
			multiSignBy0.setAddress(ecKey.toAddress(networkParameters).toBase58());
			multiSignBy0.setPublickey(Utils.HEX.encode(ecKey.getPubKey()));
			multiSignBy0.setSignature(Utils.HEX.encode(buf1));
			multiSignBies.add(multiSignBy0);
			MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
			transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

			resp = OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block0.bitcoinSerialize());

		}

	}

	@Test
	public void testECKey() {
		ECKey outKey = new ECKey();
		log.debug("pubkey= " + Utils.HEX.encode(outKey.getPubKey()));
		// ECKey ecKey = ECKey.fromPublicOnly(outKey.getPubKey());
		log.debug("privkey= " + outKey.getPrivateKeyAsHex());
	}

	public void testRequestBlock(Block block) throws Exception {

		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		requestParam.put("hashHex", Utils.HEX.encode(block.getHash().getBytes()));

		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getBlockByHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block re = networkParameters.getDefaultSerializer().makeBlock(data);
		log.info("resp : " + re);

	}

	public Block nextBlockSerializer(ByteBuffer byteBuffer) {
		int len = byteBuffer.getInt();
		byte[] data = new byte[len];
		byteBuffer.get(data);
		Block r1 = networkParameters.getDefaultSerializer().makeBlock(data);
		log.debug("block len : " + len + " conv : " + r1.getHashAsString());
		return r1;
	}

	public byte[] getAskTransactionBlock() throws JsonProcessingException, Exception {
		final Map<String, Object> request = new HashMap<String, Object>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(request));
		return data;
	}

	public void reqCmdSaveBlock(Block block) throws Exception, UnsupportedEncodingException {
		byte[] data = OkHttp3Util.post(contextRoot + ReqCmd.saveBlock.name(), block.bitcoinSerialize());
		log.info("testSaveBlock resp : " + data);
		checkResponse(data);
	}

	@Test
	public void testUpdateMultiSig() throws JsonProcessingException, Exception {
		// Setup transaction and signatures
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		wallet.importKey(new ECKey());
		List<ECKey> keys = wallet.walletKeys(null);
		String tokenid = new ECKey().getPublicKeyAsHex();
		TokenInfo tokenInfo = new TokenInfo();

		HashMap<String, String> requestParam00 = new HashMap<String, String>();
		requestParam00.put("tokenid", tokenid);
		byte[] resp2 = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenIndex.name(),
				Json.jsonmapper().writeValueAsString(requestParam00));

		TokenIndexResponse tokenIndexResponse = Json.jsonmapper().readValue(resp2, TokenIndexResponse.class);
		long tokenindex_ = tokenIndexResponse.getTokenindex();

		int amount = 100000000;
		Coin basecoin = Coin.valueOf(amount, tokenid);

		Token tokens = Token.buildSimpleTokenInfo(true, tokenIndexResponse.getBlockhash(), tokenid,
				UUID.randomUUID().toString(), UUID.randomUUID().toString(), 3, tokenindex_, basecoin.getValue(), false,
				0, networkParameters.getGenesisBlock().getHashAsString());
		tokenInfo.setToken(tokens);

		ECKey key1 = keys.get(0);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key1.getPublicKeyAsHex()));

		ECKey key2 = keys.get(1);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key2.getPublicKeyAsHex()));

		ECKey key3 = keys.get(2);
		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key3.getPublicKeyAsHex()));

		wallet.saveToken(tokenInfo, basecoin, keys.get(3), null);

		HashMap<String, Object> requestParam0 = new HashMap<String, Object>();
		requestParam0.put("address", key1.toAddress(networkParameters).toBase58());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam0));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		byte[] payloadBytes = Utils.HEX
				.decode((String) multiSignResponse.getMultiSigns().get((int) tokenindex_).getBlockhashHex());
		Block block0 = networkParameters.getDefaultSerializer().makeBlock(payloadBytes);

		Transaction transaction = block0.getTransactions().get(0);

		TokenInfo updateTokenInfo = new TokenInfo().parse(transaction.getData());
		updateTokenInfo.getToken().setTokenname("UPDATE_TOKEN");
		ECKey key4 = keys.get(3);
		updateTokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokenid, "", key4.getPublicKeyAsHex()));
		wallet.saveToken(tokenInfo, basecoin, key4, null);

		// save block
		// OkHttp3Util.post(contextRoot + ReqCmd.updateTokenInfo.name(),
		// block_.bitcoinSerialize());

	}

}
