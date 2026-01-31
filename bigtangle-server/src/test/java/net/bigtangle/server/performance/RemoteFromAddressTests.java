/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.server.test.FromAddressTests;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class RemoteFromAddressTests extends RemoteTest {

	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";

	private ECKey accountKey;
	Wallet yuanWallet;
	protected static final Logger log = LoggerFactory.getLogger(FromAddressTests.class);

	@Test
	public void testUserpay() throws Exception {

		yuanWallet = Wallet.fromKeys(networkParameters, ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)),
				contextRoot);

	//	payBigTo(accountKey,
	//			Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1000)), null);

		payBigTo(ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)),
				Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1000)), null);

		testTokens();

		// Verify token was created
		verifyTokenCreated(yuanTokenPub);

 
		List<Coin> list = getBalanceAccount(false, yuanWallet.walletKeys());

		createUserPay( );
		list = getBalanceAccount(false, yuanWallet.walletKeys());
 
	}

	private void createUserPay( ) throws Exception {
		List<ECKey> ulist = payKeys();
		for (ECKey key : ulist) {
	 		buyTicket(key );
		}

	}

	/*
	 * pay money to the key and use the key to buy lottery
	 */
	public void buyTicket(ECKey key  ) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
		log.debug("====ready buyTicket====");
		 
		log.debug("====start buyTicket====");
		List<ECKey> userkeys = new ArrayList<ECKey>();
		userkeys.add(key);
 
		List<UTXO> utxos = getBalance(false, key);
		for (UTXO utxo : utxos) {
			log.debug("user uxxo==" + utxo.toString());
		}
 
	  
		getBalanceAccount(false, wallet.walletKeys());

		// checkResult(accountKey, key.toAddress(networkParameters).toBase58());
	}

	public List<ECKey> payKeys() throws Exception {
		List<ECKey> userkeys = new ArrayList<ECKey>();
		HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();

		ECKey key = ECKey.fromPrivateString( "9c845f50a809cf6bb3ff7a3679195141dc97bd62e237a2ced3d6373735a38891");
		   
		giveMoneyResult.put(key.toAddress(networkParameters).toString(), BigInteger.valueOf(100));
		userkeys.add(key);
		ECKey key2 =  ECKey.fromPrivateString(  "88c8383183d9db0a5fdbd8d862709f729e055d8981b8515044f28d4cf12d3f27");
		  
		giveMoneyResult.put(key2.toAddress(networkParameters).toString(), BigInteger.valueOf(100));
		userkeys.add(key2);

		String memo = "pay to user";
		Block b = yuanWallet.payToList(null, giveMoneyResult, Utils.HEX.decode(yuanTokenPub), memo);
		log.debug("block " + (b == null ? "block is null" : b.toString()));

 
		payBigTo(key, Coin.FEE_DEFAULT.getValue(), null);

	 
		// fee=1000
		payBigTo(key2, Coin.FEE_DEFAULT.getValue(), null);

	 
		return userkeys;
	}

	public void testTokens() throws JsonProcessingException, Exception {
		String domain = "";
		ECKey fromPrivate = ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv));

		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY", BigInteger.valueOf(10000000l));

	}

	public Address getAddress() {
		return ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)).toAddress(networkParameters);
	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(ECKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount) throws JsonProcessingException, Exception {
		try {

			createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.currency.ordinal(), key.getPublicKeyAsHex(),
					Wallet.fromKeys(networkParameters, key, contextRoot));
			ECKey signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));

			wallet.multiSign(key.getPublicKeyAsHex(), signkey, null);

		} catch (Exception e) {
			// TODO: handle exception
			log.warn("", e);
		}

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

	// Verify that a token was created successfully
	protected void verifyTokenCreated(String tokenid) throws Exception {
		log.debug("=== Verifying token creation for tokenid: " + tokenid + " ===");

		HashMap<String, Object> requestParam = new HashMap<>();
		requestParam.put("tokenid", tokenid);
		byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		GetTokensResponse getTokensResponse =
				Json.jsonmapper().readValue(response, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens() != null && getTokensResponse.getTokens().size() > 0,
				"Token was not created: " + tokenid);

		Token token = getTokensResponse.getTokens().get(0);
		log.debug("Token found: " + token.getTokenname());
		log.debug("Token confirmed: " + token.isConfirmed());
		log.debug("Token amount: " + token.getAmount());
		log.debug("Token id: " + token.getTokenid());
	}

}
