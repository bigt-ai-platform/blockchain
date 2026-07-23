package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bouncycastle.crypto.InvalidCipherTextException;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.apps.data.Certificate;
import net.bigtangle.apps.data.IdentityCore;
import net.bigtangle.apps.data.IdentityData;
import net.bigtangle.apps.data.Prescription;
import net.bigtangle.apps.data.SignedData;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.DataClassName;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.KeyValueList;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetOutputsResponse;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.response.MultiSignResponse;
import net.bigtangle.response.SearchMultiSignResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.utils.SignedDataWithToken;
import net.bigtangle.utils.WalletUtil;
import net.bigtangle.wallet.Wallet;

/*
 * ## permission of token creation 

### new type of token with domain name
 server configuration parameter defines the root permission for single name as cn, com,  de etc.
 the creation of top name need the signature of root permission and user signature
 domain name is tree of permission
 the other domain need the signature of parent signature and user signature
 domain name is unique in system  -> ValidationService

example
 tokentype:domainname
 tokenname=de
 domainname=""
 signatures: user + root 
 check: tokenname +domainname must be unique    
 
 tokentype:domainname
 tokenname=bund.de
 domainname=de
 
 signatures: user + domainname of de
 check: tokenname +domainname must be unique  
 
 
### other type of token must be have a domain name
   the token must be signed by domain name signature and user signature
example
 tokentype:token
 tokenname=product1
 domainname=bund.de
 signatures: user + domainname token
   

### display with tokenname +"@" + domainname +":"+ tokenid
 */
public class TokenTest extends AbstractIntegrationTest {

	private PQKey userkey = PQKey.createNew();
	private String tokenid;
	private PQKey outKey2 = PQKey.createNew();
	private PQKey outKey4 = PQKey.createNew();

	@BeforeEach
	public void setUpTokenTest() throws Exception {
		tokenid = wallet.walletKeys().get(0).getPublicKeyAsHex();
	}

	@Test
	public void testCreateDomainToken() throws Exception {

		PQKey preKey = PQKey.createNew();

		{
			PQKey key = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			wallet.publishDomainName(key, tokenid, "com", aesKey, "");

			List<PQKey> keys = new ArrayList<PQKey>();
			keys.add(preKey);
			Block lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);
			}

			makeRewardBlock(lastBlock);

			assertTrue(getToken(tokenid).getTokenname().equals("com"));
		}

		{
			PQKey key = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			wallet.publishDomainName(key, tokenid, "金", aesKey, "金");

			List<PQKey> keys = new ArrayList<PQKey>();
			keys.add(preKey);
			Block lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);

			}

			makeRewardBlock(lastBlock);
			// check uft8
			log.debug(getToken(tokenid).toString());
			assertTrue(getToken(tokenid).getTokenname().equals("金"));
		}
		{
			PQKey keyShop = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			wallet.publishDomainName(keyShop, tokenid, "shop", aesKey, "");

			List<PQKey> keys = new ArrayList<PQKey>();
			keys.add(preKey);
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			Block lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);

			}

			makeRewardBlock(lastBlock);
			assertTrue(getToken(tokenid).getTokenname().equals("shop"));

			PQKey key = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			wallet.publishDomainName(key, tokenid, "myshopname.shop", aesKey, "");
			keys = new ArrayList<PQKey>();
			keys.add(keyShop);
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);

			}

			makeRewardBlock(lastBlock);
			assertTrue(getToken(tokenid).getTokenname().equals("myshopname.shop"));
		}

	}

	@Test
	public void testWrongDomainname() throws Exception {

		PQKey preKey = PQKey.createNew();

		{
			PQKey key = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			wallet.publishDomainName(key, tokenid, "de/de", aesKey, "");

			List<PQKey> keys = new ArrayList<PQKey>();
			keys.add(preKey);
			Block lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);
			}
			// sendEmpty(10);
			makeRewardBlock(lastBlock);

		}
	}

	public void testWrongSignnumber() throws Exception {

		PQKey preKey = PQKey.createNew();

		{
			PQKey key = PQKey.createNew();
			wallet.publishDomainName(key, tokenid, "de/de", aesKey, "");

			List<PQKey> keys = new ArrayList<PQKey>();
			keys.add(preKey);
			Block lastBlock = null;
			for (int i = 0; i < keys.size(); i++) {
				lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);
			}
			// sendEmpty(10);
			makeRewardBlock(lastBlock);

		}
	}

	@Test
	public void testCreateTokenWithDomain() throws Exception {

		PQKey shopkey=	createShopToken();

		PQKey key = PQKey.createNew();
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.publishDomainName(key, tokenid, "myshopname.shop", aesKey, "");
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(tokenid, shopkey, aesKey);
 
		makeRewardBlock(lastBlock);

		{

			PQKey productkey = PQKey.createNew();
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			Block block = createToken(productkey, "product", 0, "myshopname.shop", "test", BigInteger.ONE, true, null,
					TokenType.token.ordinal(), productkey.getPublicKeyAsHex(), wallet);
			TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), key, aesKey);

			makeRewardBlock(lastBlock);

			HashMap<String, Object> requestParam = new HashMap<String, Object>();
			requestParam.put("tokenid", currentToken.getToken().getTokenid());
			byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
					Json.jsonmapper().writeValueAsString(requestParam));
			GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

			assertTrue(getTokensResponse.getTokens().size() == 1);
			assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
					.equals(currentToken.getToken().getTokenname() + "@myshopname.shop")
					|| getTokensResponse.getTokens().get(1).getTokennameDisplay()
							.equals(currentToken.getToken().getTokenname() + "@myshopname.shop"));

		}

	}

	@Test
	public void testCreateIdentityTokenWithDomain() throws Exception {

		PQKey key = prepareIdentity();

		PQKey issuer = PQKey.createNew();
		TokenKeyValues kvs = getTokenKeyValues(issuer, userkey);
		wallet.importKey(issuer);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(issuer, userkey.getPublicKeyAsHex(), 0, "id.shop", "test", BigInteger.ONE, true, kvs,
				TokenType.identity.ordinal(), issuer.getPublicKeyAsHex(), wallet);
		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), key, aesKey);
		// sendEmpty(10);
		makeRewardBlock(lastBlock);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", currentToken.getToken().getTokenid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens().size() == 1);
		assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
				.equals(currentToken.getToken().getTokenname() + "@id.shop"));
		Token token = getTokensResponse.getTokens().get(0);
		byte[] decryptedPayload = null;
		for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
			if (kvtemp.getKey().equals(userkey.getPublicKeyAsHex())) {
				decryptedPayload = ECIESCoder.decrypt(userkey.getPrivKey(), Utils.HEX.decode(kvtemp.getValue()));
				SignedData identity = new SignedData().parse(decryptedPayload);
				IdentityData id = new IdentityData().parse(Utils.HEX.decode(identity.getSerializedData()));
				assertTrue(id.getIdentificationnumber().equals("120123456789012345"));
				identity.verify();
			}
		}

	}

	@Test
	public void testCreateCertificate() throws Exception {

		PQKey domainkey = prepareIdentity();
		String domainAddress = domainkey.toAddress(networkParameters).toHex();
		// issuer create the token for user public key and domain key must sign
		// the token
		PQKey issuer = PQKey.createNew();
		SignedData signedata = signeddata(issuer);
		TokenKeyValues kvs = signedata.toTokenKeyValues(issuer, userkey);
		wallet.importKey(issuer);
		List<PQKey> keys = wallet.walletKeys();
		List<String> addresses = keys.stream().map(key -> key.toAddress(networkParameters).toHex())
				.collect(Collectors.toList());
		String localTokenid = PQKey.createNew().getPublicKeyAsHex();
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(issuer, userkey.getPublicKeyAsHex(), 0, "id.shop", "test", BigInteger.ONE, true, kvs,
				TokenType.identity.ordinal(), localTokenid, wallet, userkey.getPubKey(), signedata.encryptToMemo(userkey));
		String isserAddress = issuer.toAddress(networkParameters).toHex();
		log.info("domain sign before : " + localTokenid + "," + isserAddress);
		querySign(localTokenid, isserAddress, true);
		querySignByTokenid(localTokenid, addresses, true);
		List<String> tempList = new ArrayList<String>();
		tempList.add(domainAddress);
		querySign(localTokenid, domainAddress, false);
		querySignByTokenid(localTokenid, tempList, false);
		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), domainkey, aesKey);

		log.info("domain sign end : " + localTokenid + "," + domainAddress);
		querySign(localTokenid, isserAddress, true);
		querySign(localTokenid, domainAddress, true);
		querySignByTokenid(localTokenid, tempList, true);
		// sendEmpty(10);
		makeRewardBlock(lastBlock);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", currentToken.getToken().getTokenid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens().size() == 1);
		assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
				.equals(currentToken.getToken().getTokenname() + "@id.shop"));
		Token token = getTokensResponse.getTokens().get(0);
		byte[] decryptedPayload = null;
		for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
			if (kvtemp.getKey().equals(userkey.getPublicKeyAsHex())) {
				decryptedPayload = ECIESCoder.decrypt(userkey.getPrivKey(), Utils.HEX.decode(kvtemp.getValue()));
				SignedData sdata = new SignedData().parse(decryptedPayload);
				sdata.verify();
				if (DataClassName.KeyValueList.name().equals(sdata.getDataClassName())) {
					KeyValueList id = new KeyValueList().parse(Utils.HEX.decode(sdata.getSerializedData()));
					assertTrue(id.getKeyvalues().size() == 2);
				}
			}
		}
		List<UTXO> ulist = getBalance(false, userkey);
		assertTrue(ulist.size() == 1);
		// assertTrue(ulist.size()==1);

	}

	@Test
	public void testSigneddata() throws Exception {

		PQKey domainkey = prepareIdentity();
		String domainAddress = domainkey.toAddress(networkParameters).toHex();
		// issuer create the token for user public key and domain key must sign
		// the token
		PQKey issuer = PQKey.createNew();
		SignedData signedata = signeddata(issuer);
		TokenKeyValues kvs = signedata.toTokenKeyValues(issuer, userkey);
		wallet.importKey(issuer);
		String localTokenid = PQKey.createNew().getPublicKeyAsHex();
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(issuer, userkey.getPublicKeyAsHex(), 0, "id.shop", "test", BigInteger.ONE, true, kvs,
				TokenType.certificate.ordinal(), localTokenid, wallet, userkey.getPubKey(),
				signedata.encryptToMemo(userkey));

		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), domainkey, aesKey);

		// sendEmpty(10);
		makeRewardBlock(lastBlock);

		List<PQKey> keys = new ArrayList<PQKey>();
		keys.add(userkey);
		List<SignedDataWithToken> data = WalletUtil.signedTokenList(keys, TokenType.certificate, contextRoot);
		assertTrue(data.size() > 0);
		for (SignedDataWithToken sdata : data) {
			Certificate certificate = new Certificate()
					.parse(Utils.HEX.decode(sdata.getSignedData().getSerializedData()));
			assertTrue(certificate != null);
		}

	}

	@Test
	public void testPrescription() throws Exception {

		PQKey issuer = PQKey.createNew();
		PQKey pharmacy = PQKey.createNew();
		payBigTo(userkey, Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(3)), null);
		PQKey key = prepareIdentity();


		SignedData signedata = signeddata(key);
		TokenKeyValues kvs = signedata.toTokenKeyValues(key, userkey);

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(issuer, userkey.getPublicKeyAsHex(), 0, "id.shop", "test", BigInteger.ONE, true, kvs,
				TokenType.identity.ordinal(), PQKey.createNew().getPublicKeyAsHex(), wallet);
		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), key, aesKey);
		

		makeRewardBlock(lastBlock);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", currentToken.getToken().getTokenid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens().size() == 1);
		assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
				.equals(currentToken.getToken().getTokenname() + "@id.shop"));
		Token token = getTokensResponse.getTokens().get(0);
		SignedData p = prescription(userkey, token);
		List<UTXO> ulist = getBalance(false, userkey);
		assertTrue(ulist.size() == 2);
		// pay the token to pharmacy

		// encrypt data as memo or
		Wallet userWallet = Wallet.fromKeys(networkParameters, userkey, contextRoot);

		MemoInfo memoInfo = p.encryptToMemo(pharmacy);

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		userWallet.pay(null, pharmacy.toAddress(networkParameters).toHex(),Coin.SATOSHI,
				memoInfo);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
		// sendEmpty(10);
		makeRewardBlock(b);

		List<UTXO> pharmalist = getBalance(false, pharmacy);
		String jsonString = pharmalist.get(0).getMemo();
		MemoInfo m = MemoInfo.parse(jsonString);
		SignedData sdata = SignedData.decryptFromMemo(pharmacy, m);
		if (DataClassName.Prescription.name().equals(sdata.getDataClassName())) {
			Prescription pre = new Prescription().parse(Utils.HEX.decode(sdata.getSerializedData()));
			assertTrue(pre.getFilename() != null);

		}
	}

	public void querySign(String tokenid, String address, boolean sign) throws Exception {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();

		requestParam.put("address", address);
		// requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByAddress.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		MultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, MultiSignResponse.class);
		List<MultiSign> multiSigns = multiSignResponse.getMultiSigns();
		assertTrue(multiSigns != null);
		for (MultiSign multiSign : multiSigns) {
			if (sign)
				assertTrue(multiSign.getSign() == 1);
			else {
				assertTrue(multiSign.getSign() == 0);
			}
		}

	}

	public void querySignByTokenid(String tokenid, List<String> addresses, boolean sign) throws Exception {
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		// requestParam.put("addresses", addresses);
		requestParam.put("isSign", sign);
		requestParam.put("tokenid", tokenid);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenSignByTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		SearchMultiSignResponse multiSignResponse = Json.jsonmapper().readValue(resp, SearchMultiSignResponse.class);

	}

	private SignedData prescription(PQKey userkey, Token token)
			throws IOException, InvalidCipherTextException, SignatureException {
		byte[] decryptedPayload = null;
		for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
			if (kvtemp.getKey().equals(userkey.getPublicKeyAsHex())) {
				decryptedPayload = ECIESCoder.decrypt(userkey.getPrivKey(), Utils.HEX.decode(kvtemp.getValue()));
				SignedData sdata = new SignedData().parse(decryptedPayload);
				sdata.verify();
				return sdata;
			}
		}
		return null;
	}

	public List<Prescription> prescriptionList(PQKey ecKey) throws Exception {
		List<Prescription> prescriptionlist = new ArrayList<Prescription>();
		Map<String, String> param = new HashMap<String, String>();
		param.put("toaddress", ecKey.toAddress(networkParameters).toHex());

		byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.getOutputsHistory.name(),
				Json.jsonmapper().writeValueAsString(param));

		GetBalancesResponse balancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);
		Map<String, Token> tokennames = new HashMap<String, Token>();
		tokennames.putAll(balancesResponse.getTokennames());
		for (UTXO utxo : balancesResponse.getOutputs()) {
			if (checkPrescription(utxo, tokennames)) {
				Token token = tokennames.get(utxo.getTokenId());
				for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
					byte[] decryptedPayload = ECIESCoder.decrypt(ecKey.getPrivKey(),
							Utils.HEX.decode(kvtemp.getValue()));
					SignedData sdata = new SignedData().parse(decryptedPayload);
					prescriptionlist.add(new Prescription().parse(Utils.HEX.decode(sdata.getSerializedData())));
				}
			}
		}
		return prescriptionlist;
	}

	private boolean checkPrescription(UTXO utxo, Map<String, Token> tokennames) {
		return TokenType.prescription.ordinal() == tokennames.get(utxo.getTokenId()).getTokentype();

	}

	@Test
	public void testTokenidNotInWallet() throws Exception {

		PQKey key = prepareIdentity();

		PQKey issuer = PQKey.createNew();
		TokenKeyValues kvs = certificateTokenKeyValues(issuer, userkey);
		wallet.importKey(issuer);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(issuer, userkey.getPublicKeyAsHex(), 0, "id.shop", "test", BigInteger.ONE, true, kvs,
				TokenType.identity.ordinal(), PQKey.createNew().getPublicKeyAsHex(), wallet);
		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), key, aesKey);
	 
		makeRewardBlock(lastBlock);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", currentToken.getToken().getTokenid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens().size() == 1);
		assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
				.equals(currentToken.getToken().getTokenname() + "@id.shop"));
		Token token = getTokensResponse.getTokens().get(0);
		byte[] decryptedPayload = null;
		for (KeyValue kvtemp : token.getTokenKeyValues().getKeyvalues()) {
			if (kvtemp.getKey().equals(userkey.getPublicKeyAsHex())) {
				decryptedPayload = ECIESCoder.decrypt(userkey.getPrivKey(), Utils.HEX.decode(kvtemp.getValue()));
				SignedData identity = new SignedData().parse(decryptedPayload);
				identity.verify();
				if (DataClassName.KeyValueList.name().equals(identity.getDataClassName())) {
					KeyValueList id = new KeyValueList().parse(Utils.HEX.decode(identity.getSerializedData()));
					assertTrue(id.getKeyvalues().size() == 2);
				}
			}
		}

	}

	private PQKey prepareIdentity()
			throws Exception, JsonProcessingException, InterruptedException, ExecutionException, BlockStoreException {
		PQKey shopKey = createShopToken();

		PQKey key = PQKey.createNew();
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.publishDomainName(key, tokenid, "id.shop", aesKey, "");
		// check signs list?
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = wallet.multiSign(tokenid, shopKey, aesKey);

		makeRewardBlock(lastBlock);

		return key;
	}

	private TokenKeyValues getTokenKeyValues(PQKey key, PQKey userkey)
			throws InvalidCipherTextException, IOException, SignatureException {
		SignedData signeddata = new SignedData();
		IdentityCore identityCore = new IdentityCore();
		identityCore.setSurname("zhang");
		identityCore.setForenames("san");
		identityCore.setSex("man");
		identityCore.setDateofissue("20200101");
		identityCore.setDateofexpiry("20201231");
		IdentityData identityData = new IdentityData();
		identityData.setIdentityCore(identityCore);
		identityData.setIdentificationnumber("120123456789012345");
		identityData.uniqueNameIdentity();
		byte[] photo = "readFile".getBytes();
		// readFile(new File("F:\\img\\cc_aes1.jpg"));
		identityData.setPhoto(photo);
		signeddata.signData(key, identityData.toByteArray(), DataClassName.IdentityData.name());
		return signeddata.toTokenKeyValues(key, userkey);
	}

	private TokenKeyValues certificateTokenKeyValues(PQKey key, PQKey userkey)
			throws InvalidCipherTextException, IOException, SignatureException {
		SignedData signeddata = new SignedData();
		KeyValueList kvs = new KeyValueList();

		byte[] first = "my first file".getBytes();
		KeyValue kv = new KeyValue();
		kv.setKey("myfirst.txt");
		kv.setValue(Utils.HEX.encode(first));
		kvs.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("second.pdf");
		kv.setValue(Utils.HEX.encode("second.pdf".getBytes()));
		kvs.addKeyvalue(kv);

		signeddata.signData(key, kvs.toByteArray(), DataClassName.KeyValueList.name());
		return signeddata.toTokenKeyValues(key, userkey);
	}

	private SignedData signeddata(PQKey key) throws SignatureException {
		SignedData signedata = new SignedData();
		Prescription p = new Prescription();
		p.setPrescription("my first prescription");
		p.setFilename("second.pdf");
		p.setFile("second.pdf".getBytes());
		p.getCoins().add(new Coin(10, key.getPubKey()));
		signedata.signData(key, p.toByteArray(), DataClassName.Prescription.name());
		return signedata;
	}

	@Test
	public void testGetTokenById() throws Exception {

		Block tokenBlock = testCreateToken(wallet.walletKeys().get(0), "test");

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", wallet.walletKeys().get(0).getPublicKeyAsHex());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		log.info("getTokenById resp : " + resp);
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
		log.info("getTokensResponse : " + getTokensResponse);
		assertTrue(getTokensResponse.getTokens().size() > 0);

		makeRewardBlock(tokenBlock);

		resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		log.info("getOutputsResponse : " + getOutputsResponse);

		assertTrue(getOutputsResponse.getOutputs().size() > 0);
		assertTrue(getOutputsResponse.getOutputs().get(0).getValue()
				.equals(Coin.valueOf(77777L, wallet.walletKeys().get(0).getPubKey())));
	}

	public List<PQKey> payKeys() throws Exception {
		List<PQKey> userkeys = new ArrayList<PQKey>();

		for (int i = 1; i <= 10; i++) {
			userkeys.add(PQKey.createNew());
		}

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		List<FreeStandingTransactionOutput> coinList = wallet.calculateAllSpendCandidates(null, false);
		List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : coinList) {
			if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
				tokenUtxos.add(co);
			}
		}
		Coin total = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
		Coin totalSend = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction tx = new Transaction(networkParameters);
		for (int i = 0; i < 10; i++) {
			Coin amount = Coin.valueOf((i + 1) * 10000, NetworkParameters.BIGTANGLE_TOKENID);
			tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, userkeys.get(i)));
			totalSend = totalSend.add(amount);
		}
		Coin sendWithFee = totalSend.add(Coin.FEE_DEFAULT);
		for (FreeStandingTransactionOutput co : tokenUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			total = total.add(co.getValue());
			if (total.getValue().compareTo(sendWithFee.getValue()) >= 0) {
				Coin change = total.subtract(sendWithFee);
				if (!change.isNegative() && !change.isZero()) {
					PQKey changeKey = wallet.walletKeys(null).get(0);
					tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, changeKey));
				}
				break;
			}
		}
		if (total.getValue().compareTo(totalSend.getValue()) < 0) {
			throw new InsufficientMoneyException(totalSend + " outputs size= " + tokenUtxos.size());
		}
		wallet.signTransaction(tx, null);
		wallet.submitTransaction(tx);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
		log.debug("block " + (b == null ? "block is null" : b.toString()));
		makeRewardBlock(b);

		return userkeys;
	}

	@Test
	public void testPayTokenById() throws Exception {

		payKeys();
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", NetworkParameters.BIGTANGLE_TOKENID_STRING);
		mcmcService.calcNewBlockPrototype(store);
		makeRewardBlock();

		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		log.info("getOutputsResponse : " + getOutputsResponse);
		List<UTXO> outputs = getOutputsResponse.getOutputs();
		Map<String, Token> tokennames = getOutputsResponse.getTokennames();
		BigInteger sendValue = BigInteger.ZERO;
		for (UTXO utxo : outputs) {
			sendValue = sendValue.add(utxo.getValue().getValue().multiply(BigInteger.valueOf(3)).divide(BigInteger.valueOf(1000)));
		}
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		List<FreeStandingTransactionOutput> coinList = wallet.calculateAllSpendCandidates(null, false);
		List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : coinList) {
			if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
				tokenUtxos.add(co);
			}
		}
		Coin total = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
		Coin sendAmount = new Coin(sendValue, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction tx = new Transaction(networkParameters);
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, sendAmount, wallet.walletKeys().get(0)));
		for (FreeStandingTransactionOutput co : tokenUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			total = total.add(co.getValue());
			Coin change = total.subtract(sendAmount).subtract(Coin.FEE_DEFAULT);
			if (!change.isNegative()) {
				if (change.isPositive()) {
					PQKey changeKey = wallet.walletKeys().get(0);
					tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, changeKey));
				}
				break;
			}
		}
		if (total.getValue().compareTo(sendAmount.getValue()) < 0) {
			throw new InsufficientMoneyException(sendAmount + " outputs size= " + tokenUtxos.size());
		}
		wallet.signTransaction(tx, null);
		wallet.submitTransaction(tx);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
		log.debug("block " + (b == null ? "block is null" : b.toString()));

		makeRewardBlock(b);

	}

	@Test
	public void testGetTokennameConflict() throws Exception {

		List<PQKey> keys = wallet.walletKeys();

		PQKey outKey = PQKey.createNew();
		payBigTo(outKey2, Coin.FEE_DEFAULT.getValue(), null);

		String tokenid = outKey.getPublicKeyAsHex();
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.publishDomainName(outKey, tokenid, "de", aesKey, "");

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = null;
		for (int i = 0; i < keys.size(); i++) {
			lastBlock = wallet.multiSign(tokenid, keys.get(i), aesKey);
		}

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Wallet.fromKeys(networkParameters, outKey2,contextRoot).publishDomainName(outKey2, outKey2.getPublicKeyAsHex(), "de",
				aesKey, "");

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		for (int i = 0; i < keys.size(); i++) {
			Block b = wallet.multiSign(tokenid, keys.get(i), aesKey);
			if (b != null) lastBlock = b;
		}

		makeRewardBlock(lastBlock);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", outKey2.getPublicKeyAsHex());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		log.info("getOutputsResponse : " + getOutputsResponse);

		 requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", outKey.getPublicKeyAsHex());
		  resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse2 = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		log.info("getOutputsResponse : " + getOutputsResponse2);

		
		assertTrue(getOutputsResponse.getOutputs().size() == 1
				||getOutputsResponse2.getOutputs().size() ==1);
		
		assertFalse(getOutputsResponse.getOutputs().size() == 1
				 && getOutputsResponse2.getOutputs().size() ==1);
	}

    @Test
    public void testGetTokenConflict() throws Exception {
    	PQKey  testkey= wallet.walletKeys().get(0);
    	payBigTo(testkey, Coin.FEE_DEFAULT.getValue(),    null);
        Block b1 = testCreateToken(testkey , "test");
        // same token id and index
        Block b2 = testCreateToken(testkey, "test");

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        requestParam.put("tokenid", testkey.getPublicKeyAsHex());
        byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        log.info("getTokenById resp : " + resp);
        GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);
        log.info("getTokensResponse : " + getTokensResponse);
        assertTrue(getTokensResponse.getTokens().size() > 0);

        makeRewardBlock(b1);

        resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
        log.info("getOutputsResponse : " + getOutputsResponse);

        assertTrue(getOutputsResponse.getOutputs().size() > 0);
        assertTrue(getOutputsResponse.getOutputs().get(0).getValue()
                .equals(Coin.valueOf(77777L, testkey.getPubKey())));

    }
	@Test
	public void walletCreateDomain() throws Exception {
		 resetStore();

		List<PQKey> keys = new ArrayList<PQKey>();
		PQKey outKey3 = PQKey.createNew();
		PQKey signKey = PQKey.createNew();
		keys.add(outKey4);
		keys.add(signKey);

		final String localTokenid = PQKey.createNew().getPublicKeyAsHex();
		final String tokenname = "bigtangle.de";

		// don't use the first key which is in the wallet

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		this.wallet.publishDomainName(keys, signKey, localTokenid, tokenname, Token.genesisToken(networkParameters), aesKey,
				"", 3);

		this.wallet.multiSign(localTokenid, outKey3, aesKey);

		this.wallet.multiSign(localTokenid, outKey4, aesKey);

		PQKey genesiskey = PQKey.createNew();
		this.wallet.multiSign(localTokenid, genesiskey, null);
	}

	@Test
	public void testCreateTokenMulti() throws Exception {

		createShopToken();
		PQKey key = PQKey.createNew();
		TokenInfo currentToken = createProductToken(key);

		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", currentToken.getToken().getTokenid());
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetTokensResponse getTokensResponse = Json.jsonmapper().readValue(resp, GetTokensResponse.class);

		assertTrue(getTokensResponse.getTokens().size() == 2);
		assertTrue(getTokensResponse.getTokens().get(0).getTokennameDisplay()
				.equals(currentToken.getToken().getTokenname() + "@shop")
				|| getTokensResponse.getTokens().get(1).getTokennameDisplay()
						.equals(currentToken.getToken().getTokenname() + "@shop"));

		resp = OkHttp3Util.postString(contextRoot + ReqCmd.outputsOfTokenid.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		GetOutputsResponse getOutputsResponse = Json.jsonmapper().readValue(resp, GetOutputsResponse.class);
		log.info("getOutputsResponse : " + getOutputsResponse);

		assertTrue(getOutputsResponse.getOutputs().size() == 2);
		assertTrue(getOutputsResponse.getOutputs().get(0).getValue()
				.equals(Coin.valueOf(1, currentToken.getToken().getTokenid())));
		assertTrue(getOutputsResponse.getOutputs().get(1).getValue()
				.equals(Coin.valueOf(1, currentToken.getToken().getTokenid())));

	}

	private PQKey createShopToken()
			throws Exception, JsonProcessingException, InterruptedException, ExecutionException, BlockStoreException {

		PQKey shopKey = PQKey.createNew();
		String tokenname = "shop";
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.publishDomainName(shopKey, tokenid, tokenname, aesKey, "");

		PQKey preKey = PQKey.createNew();
		Block lastBlock = wallet.multiSign(tokenid, preKey, aesKey);

		makeRewardBlock(lastBlock);
		assertTrue(getToken(tokenid).getTokenname().equals(tokenname));
		return shopKey;
	}

	private TokenInfo createProductToken(PQKey key)
			throws Exception, JsonProcessingException, InterruptedException, ExecutionException, BlockStoreException {

		wallet.importKey(key);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block block = createToken(key, "product", 0, "shop", "test", BigInteger.ONE, true, null,
				TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet);
		TokenInfo currentToken = new TokenInfo().parseChecked(block.getTransactions().get(0).getData());
		List<PQKey> keys = new ArrayList<PQKey>();
		keys.add(wallet.walletKeys().get(0));
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		Block lastBlock = null;
		for (int i = 0; i < keys.size(); i++) {
			lastBlock = wallet.multiSign(currentToken.getToken().getTokenid(), keys.get(i), aesKey);
		}
		makeRewardBlock(lastBlock);

		return currentToken;
	}

	public Token getToken(String idcom) throws Exception {
		// String idcom=
		// "02ffa8c71c0dd200c82fb07323147b4aca5c3ac7b93c6bf53730a42008b72bffa3";
		// idcom: "0365cc54778405323781041a791a1048d3742234fe07e6cce041419d8038ab26ed";
		// String tokenid =
		// "03d109174d7b8aaab67d4090e58cde8a69906f85a292d26333f04ac81d99371798";
		HashMap<String, Object> requestParam = new HashMap<String, Object>();
		requestParam.put("tokenid", idcom);
		byte[] resp = OkHttp3Util.postString(contextRoot + ReqCmd.getTokenById.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		return Json.jsonmapper().readValue(resp, GetTokensResponse.class).getTokens().get(0);

	}

	public byte[] readFile(File file) {
		byte[] buf = null;
		if (file != null) {
			ByteArrayOutputStream byteArrayOutputStream = null;
			BufferedInputStream bufferedInputStream = null;
			byteArrayOutputStream = new ByteArrayOutputStream((int) file.length());
			try {
				bufferedInputStream = new BufferedInputStream(new FileInputStream(file));
				int buffSize = 1024;
				byte[] buffer = new byte[buffSize];
				int len = 0;
				while (-1 != (len = bufferedInputStream.read(buffer, 0, buffSize))) {
					byteArrayOutputStream.write(buffer, 0, len);
				}
				buf = byteArrayOutputStream.toByteArray();
			} catch (Exception e) {
			} finally {
				if (bufferedInputStream != null) {
					try {
						bufferedInputStream.close();
						if (byteArrayOutputStream != null) {
							byteArrayOutputStream.close();
						}
					} catch (IOException e) {
					}
				}
			}
		}
		return buf;
	}
}
