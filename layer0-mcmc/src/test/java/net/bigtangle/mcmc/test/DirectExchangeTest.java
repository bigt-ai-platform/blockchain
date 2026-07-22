/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBlockEvaluationsResponse;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class DirectExchangeTest extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(DirectExchangeTest.class);

	@Test
	public void testBatchBlock() throws Exception {
		// Ensure tips queue is populated
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(new HashMap<String, String>()));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		store.insertBatchBlock(block);

		List<BatchBlock> batchBlocks = store.getBatchBlockList();
		assertTrue(batchBlocks.size() == 1);

		BatchBlock batchBlock = batchBlocks.get(0);

		// String hex1 = Utils.HEX.encode(block.bitcoinSerialize());
		// String hex2 = Utils.HEX.encode(batchBlock.getBlock());
		// assertEquals(hex1, hex2);

		assertArrayEquals(block.bitcoinSerialize(), batchBlock.getBlock());

		store.deleteBatchBlock(batchBlock.getHash());
		batchBlocks = store.getBatchBlockList();
		assertTrue(batchBlocks.size() == 0);
	}

	@Test
	public void testTransactionResolveSubtangleID() throws Exception {
		Transaction transaction = new Transaction(this.networkParameters);

		byte[] subtangleID = new byte[32];
		new Random().nextBytes(subtangleID);

		transaction.setToAddressInSubtangle(subtangleID);

		// Ensure tips queue is populated
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(new HashMap<String, String>()));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
		block.addTransaction(transaction);
		block.addTransaction(wallet.feeTransaction(null));
		OkHttp3Util.post(contextRoot + ReqCmd.batchBlock.name(), block.bitcoinSerialize());

		Block predecessor2 = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block savedBlock = drainMempoolAndCreateBlock(predecessor2, predecessor2);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("hashHex", Utils.HEX.encode(savedBlock.getHash().getBytes()));
		data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getBlockByHash.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		block = networkParameters.getDefaultSerializer().makeBlock(data);

		Transaction transaction2 = block.getTransactions().get(0);
		assertNotNull(subtangleID);
		assertTrue(Arrays.equals(subtangleID, transaction.getToAddressInSubtangle()));
		assertTrue(Arrays.equals(subtangleID, transaction2.getToAddressInSubtangle()));
	}

	public void createTokenSubtangle() throws Exception {
		PQKey ecKey = PQKey.createNew();
		TokenInfo tokenInfo = new TokenInfo();

		Token tokens = Token.buildSubtangleTokenInfo(false, null, Utils.HEX.encode(pubKey), "subtangle", "", "");
		tokenInfo.setToken(tokens);

		tokenInfo.getMultiSignAddresses().add(new MultiSignAddress(tokens.getTokenid(), "", ecKey.getPublicKeyAsHex()));

		Coin basecoin = Coin.valueOf(0L, pubKey);

		// Ensure tips queue is populated
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block block = networkParameters.getDefaultSerializer().makeBlock(data);
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);
		block.addCoinbaseTransaction(ecKey.getPubKey(), basecoin, tokenInfo, new MemoInfo("coinbase"));

		Transaction transaction = block.getTransactions().get(0);

		Sha256Hash sighash = transaction.getHash();
		SignatureBundle party1Signature = ecKey.sign(sighash);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(Utils.HEX.encode(pubKey));
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(ecKey.toAddress(networkParameters).toHex());
		multiSignBy0.setPublickey(Utils.HEX.encode(ecKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// save block
		block = adjustSolve(block);
		OkHttp3Util.post(contextRoot + ReqCmd.signToken.name(), block.bitcoinSerialize());
	}

	@Test
	public void testGiveMoney() throws Exception {

		// Ensure tips queue is populated before wallet operations
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		PQKey genesiskey = PQKey.createNew();
		List<UTXO> balance1 = getBalance(false, genesiskey);
		log.info("balance1 : " + balance1);
		// two utxo to spent
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
		for (int i = 0; i < 3; i++) {
			PQKey outKey = PQKey.createNew().toHex(), Coin.COIN.getValue());
		}
		wallet.payMoneyToECKeyList(null, giveMoneyResult, "testGiveMoney");
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block payblock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(payblock);

		List<UTXO> balance = getBalance(false, genesiskey);
		log.info("balance : " + balance);
		for (UTXO utxo : balance) {

			assertTrue(utxo.getValue().getValue()
					.equals(NetworkParameters.BigtangleCoinTotal
							.subtract(Coin.COIN.getValue().multiply(BigInteger.valueOf(3)))
							.subtract(Coin.FEE_DEFAULT.getValue())));

		}
	}

	@Test
	public void testRatingRead() throws Exception {

		// Ensure tips queue is populated before wallet operations
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		PQKey genesiskey = PQKey.createNew();
		List<UTXO> balance1 = getBalance(false, genesiskey);
		log.info("balance1 : " + balance1);
		// two utxo to spent
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
		for (int i = 0; i < 3; i++) {
			PQKey outKey = PQKey.createNew().toHex(), Coin.COIN.getValue());
		}
		wallet.payMoneyToECKeyList(null, giveMoneyResult, "testGiveMoney");
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(b);

		Map<String, Object> requestParam = new HashMap<String, Object>();

		List<String> blockhashs = new ArrayList<String>();
		blockhashs.add(b.getHashAsString());
		requestParam.put("blockhashs", blockhashs);

		byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.searchBlockByBlockHashs.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		GetBlockEvaluationsResponse getBlockEvaluationsResponse = Json.jsonmapper().readValue(response,
				GetBlockEvaluationsResponse.class);
		List<BlockEvaluationDisplay> blockEvaluations = getBlockEvaluationsResponse.getEvaluations();

		assertTrue(!blockEvaluations.isEmpty());

	}

	@Test
	public void searchBlock() throws Exception {
		List<PQKey> keys = wallet.walletKeys(null);
		List<String> address = new ArrayList<String>();
		for (PQKey ecKey : keys) {
			address.add(ecKey.toAddress(networkParameters).toHex());
		}
		HashMap<String, Object> request = new HashMap<String, Object>();
		request.put("address", address);

		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.findBlockEvaluation.name(),
				Json.jsonmapper().writeValueAsString(request).getBytes());

		log.info("searchBlock resp : " + response);

	}

	public void exchangeTokenComplete(Transaction tx) throws Exception {
		// Ensure tips queue is populated
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		// get new Block to be used from server
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(data);
		rollingBlock.addTransaction(tx);

		byte[] res = OkHttp3Util.post(contextRoot + ReqCmd.batchBlock.name(), rollingBlock.bitcoinSerialize());
		log.debug(res.toString());
	}

	// Pay BIG
	public void payToken(PQKey outKey, Wallet wallet) throws Exception {
		payToken(100, outKey, NetworkParameters.BIGTANGLE_TOKENID, wallet);
	}

	public void payToken(int amount, PQKey outKey, byte[] tokenbuf, Wallet wallet) throws Exception {
		// Ensure tips queue is populated
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(data);
		log.info("resp block, hex : " + Utils.HEX.encode(data));
		// get other tokenid from wallet
		UTXO utxo = null;
		List<UTXO> ulist = getBalance();

		for (UTXO u : ulist) {
			if (Arrays.equals(u.getTokenidBuf(), tokenbuf)) {
				utxo = u;
			}
		}
		log.debug(utxo.getValue().toString());
		// Coin baseCoin = utxo.getValue().subtract(Coin.parseCoin("10000",
		// utxo.getValue().getTokenid()));
		// log.debug(baseCoin);
		Address destination = outKey.toAddress(networkParameters);

		Coin coinbase = Coin.valueOf(amount, utxo.getValue().getTokenid());
		wallet.pay(null, destination.toString(), coinbase, "");

		log.info("req block, hex : " + Utils.HEX.encode(rollingBlock.bitcoinSerialize()));
		makeRewardBlock();

		checkBalance(coinbase, wallet.walletKeys(null));
	}

	@Test
	public void createTransaction() throws Exception {

		// Ensure tips queue is populated before wallet operations
		try {
			mcmcService.calcNewBlockPrototype(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		Address destination = Address.fromBase58(networkParameters, "1NWN57peHapmeNq1ndDeJnjwPmC56Z6x8j");

		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);

		List<Block> rollingBlock = wrapTransactions(wallet.pay(null, destination.toString(), amount, ""));

		log.info("req block, hex : " + rollingBlock.get(0));

		getBalance();

		// log.info("transaction, tokens : " +
		// Json.jsonmapper().writeValueAsString(transaction.getTokenInfo()));

	}

}
