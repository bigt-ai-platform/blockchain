/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.math.LongMath;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Tokensums;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.MonetaryFormat;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.UUIDUtil;
import net.bigtangle.wallet.Wallet;

  
public abstract class RemoteTest {


	protected static final Logger log = LoggerFactory.getLogger(RemoteTest.class);
	public String contextRoot = System.getProperty("server.url", "http://localhost:8089/");

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
	
	@BeforeEach
	public void setUp() throws Exception { 
 
		wallet = Wallet.fromKeys(networkParameters, PQKey.createNew();
 
	} 

	protected Block payBigTo(PQKey beneficiary, BigInteger amount, List<Block> addedBlocks) throws Exception {
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<String, BigInteger>();

		giveMoneyResult.put(beneficiary.toAddress(networkParameters).toString(), amount);

		return payList(addedBlocks, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID);
	}

	private Block payList(List<Block> addedBlocks, HashMap<String, BigInteger> giveMoneyResult, byte[] tokenid)
			throws JsonProcessingException, IOException, InsufficientMoneyException, Exception {
		Transaction tx = wallet.payMoneyToECKeyList(null, giveMoneyResult, tokenid, "payList");
		Block b = tx == null ? null : Block.setBlock2(networkParameters, NetworkParameters.BLOCK_VERSION_GENESIS);
		if (b != null) b.addTransaction(tx);
		if (addedBlocks != null && b != null) {
			addedBlocks.add(b);
		}
	 
		return b;
	}

	
	public Block createToken(PQKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, boolean increment, TokenKeyValues tokenKeyValues, int tokentype, String tokenid,
			Wallet w) throws Exception {
		w.importKey(key);
		Token token = Token.buildSimpleTokenInfo(true, Sha256Hash.ZERO_HASH, tokenid, tokename, description, 1, 0,
				amount, !increment, decimals, "");
		token.setTokenKeyValues(tokenKeyValues);
		token.setTokentype(tokentype);
		List<MultiSignAddress> addresses = new ArrayList<MultiSignAddress>();
		addresses.add(new MultiSignAddress(tokenid, "", key.getPublicKeyAsHex()));
		return createTokenWallet(key, domainname, increment, token, addresses, w);

	}
 
	public Block createTokenWallet(PQKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses,Wallet w) throws Exception {
		return w.createToken(key, domainname, increment, token, addresses, key.getPubKey(), new MemoInfo("coinbase"));
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

	protected List<UTXO> getBalance() throws Exception {
		return getBalance(false);
	}

	// get balance for the walletKeys
	protected List<UTXO> getBalance(boolean withZero) throws Exception {
		return getBalance(withZero, wallet.walletKeys(null));
	}

	// get balance for the walletKeys
	protected List<UTXO> getBalance(boolean withZero, List<PQKey> keys) throws Exception {
		List<UTXO> listUTXO = new ArrayList<UTXO>();
		List<String> keyStrHex000 = new ArrayList<String>();

		for (PQKey ecKey : keys) {
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

	protected List<Coin> getBalanceAccount(boolean withZero, List<PQKey> keys) throws Exception {
		List<Coin> listCoin = new ArrayList<Coin>();
		List<String> keyStrHex000 = new ArrayList<String>();

		for (PQKey ecKey : keys) {
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

	protected List<UTXO> getBalance(boolean withZero, PQKey ecKey) throws Exception {
		List<PQKey> keys = new ArrayList<PQKey>();
		keys.add(ecKey);
		return getBalance(withZero, keys);
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

			Transaction buyOrderTx = wallet.buyOrder(null, orderRecord.getOfferTokenid(), price, orderRecord.getOfferValue(),
					null, null, NetworkParameters.BIGTANGLE_TOKENID_STRING, false);
			Block buyOrder = Block.setBlock2(networkParameters, NetworkParameters.BLOCK_VERSION_GENESIS);
			buyOrder.addTransaction(buyOrderTx);
			blocksAddedAll.add(buyOrder);
			// makeOrderExecutionAndReward(blocksAddedAll);

		}

	}
 
 

}