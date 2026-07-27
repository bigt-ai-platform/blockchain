/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Utils;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class RemoteTokenTests    {
	String contextRoot;
	Wallet wallet ;
	protected static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);
	public static String testPub = "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975";
	public static String testPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";
	
	@BeforeEach
	public void setUp() throws Exception {
		contextRoot = System.getProperty("server.url", "http://localhost:8089/");
		wallet = Wallet.fromKeys(TestParams.get(), PQKey.createNew());

	}

	@AfterEach
	public void close() throws Exception {

	}

	@Test
	public void testTokens() throws Exception {
		wallet.setServerURL(contextRoot);
		wallet.setFee(false);

		String tokenid = PQKey.createNew().getPublicKeyAsHex();

		// Fund the key with BIG for fee
		String address = PQKey.createNew().toAddress(TestParams.get()).toHex();
		HashMap<String, Object> fundReq = new HashMap<>();
		List<HashMap<String, Object>> addresses = new ArrayList<>();
		HashMap<String, Object> addr = new HashMap<>();
		addr.put("address", address);
		addr.put("value", 10000000000L);
		addr.put("pubkey", "050102010a20" + "00".repeat(40));
		addresses.add(addr);
		fundReq.put("addresses", addresses);
		OkHttp3Util.postString(contextRoot + ReqCmd.fundAddresses.name(),
				Json.jsonmapper().writeValueAsString(fundReq));

		// Create token via wallet.createToken (uses signToken HTTP endpoint)
		PQKey key = PQKey.createNew();
		Block block = createToken(key, "人民币", 2, "", "人民币 CNY",
				BigInteger.valueOf(1000000000L), true, null,
				TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet);

		assertNotNull(block, "wallet.createToken should return a block");

		// Verify token exists via searchTokens
		HashMap<String, Object> searchReq = new HashMap<>();
		byte[] searchResp = OkHttp3Util.postString(contextRoot + ReqCmd.searchTokens.name(),
				Json.jsonmapper().writeValueAsString(searchReq));
		GetTokensResponse tokensResponse = Json.jsonmapper().readValue(searchResp, GetTokensResponse.class);
		boolean found = false;
		if (tokensResponse.getTokens() != null) {
			for (Token t : tokensResponse.getTokens()) {
				if (key.getPublicKeyAsHex().equals(t.getTokenid())) {
					found = true;
					break;
				}
			}
		}
		assertTrue(found, "Token should exist on server after wallet.createToken");
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
		return createToken(key, domainname, increment, token, addresses,w);

	}

		public Block createToken(PQKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses,Wallet w) throws Exception {
		return w.createToken(key, domainname, increment, token, addresses, key.getPubKey(), new MemoInfo("coinbase"));
	}


	public Block createToken(PQKey key, String tokename, int decimals, String domainname, String description,
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
}
