/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;

import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;

import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.exception.ScriptException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.CoinbaseDisallowedException;
import net.bigtangle.exception.VerificationException.GenesisBlockDisallowedException;
import net.bigtangle.exception.VerificationException.IncorrectTransactionCountException;
import net.bigtangle.exception.VerificationException.InvalidDependencyException;

import net.bigtangle.exception.VerificationException.InvalidTransactionDataException;
import net.bigtangle.exception.VerificationException.InvalidTransactionException;
import net.bigtangle.exception.VerificationException.MalformedTransactionDataException;
import net.bigtangle.exception.VerificationException.MissingTransactionDataException;
import net.bigtangle.exception.VerificationException.NegativeValueOutput;
import net.bigtangle.exception.VerificationException.NotCoinbaseException;
import net.bigtangle.exception.VerificationException.PreviousTokenDisallowsException;
import net.bigtangle.exception.VerificationException.SigOpsException;
import net.bigtangle.exception.VerificationException.TimeReversionException;
import net.bigtangle.exception.VerificationException.TimeTravelerException;
import net.bigtangle.exception.VerificationException.TransactionOutputsDisallowedException;
import net.bigtangle.exception.VerificationException.UnsolidException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.utils.Json;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
public class ValidatorService2Test extends AbstractIntegrationTest {

	interface TestCase {
		public boolean expectsException();
		public void preApply(TokenInfo info);
	}

	@Test
	public void testSolidityTokenMalformedData1() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase without data
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
		block.getTransactions().get(0).setData(null);

		// solve block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (MissingTransactionDataException e) {
		}
	}

	@Test
	public void testSolidityTokenMalformedData2() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase without data
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
		block.getTransactions().get(0).setData(new byte[] { 1, 2 });

		// solve block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (MalformedTransactionDataException e) {
		}
	}

	@Test
	public void testSolidityTokenMalformedDataSignature1() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokens.setDomainName("bc");

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase without data
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
		block.getTransactions().get(0).setDataSignature(null);

		// solve block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (MissingTransactionDataException e) {
		}
	}

	@Test
	public void testSolidityTokenMalformedDataSignature2() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokens.setDomainName("bc");

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase without data
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
		block.getTransactions().get(0).setDataSignature(new byte[] { 1, 2 });

		// solve block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (MalformedTransactionDataException e) {
		}
	}

	@Test
	public void testSolidityTokenMutatedData() throws Exception {

		PQKey testKey = PQKey.createNew();

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo0 = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokens.setDomainName("bc");

		tokenInfo0.setToken(tokens);
		tokenInfo0.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		tokens.setDomainName("bc");

		TestCase[] executors = new TestCase[] {
				// 1
				new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.setToken(null);
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 2
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.setMultiSignAddresses(null);
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 3
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.getToken().setAmount(new BigInteger("-1"));
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 4
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setBlockHash(null);
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 5
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setDescription(null);
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 6
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken()
								.setDescription(new String(new char[Token.TOKEN_MAX_DESC_LENGTH]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 7
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setDescription(
								new String(new char[Token.TOKEN_MAX_DESC_LENGTH + 1]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 8
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenstop(false);
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 9
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.getToken().setPrevblockhash(null);
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 10
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setPrevblockhash(getRandomSha256Hash());
						tokenInfo5.getToken().setTokenindex(1);
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 11
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.getToken().setTokenindex(1);
						tokenInfo5.getToken().setPrevblockhash(UtilGeneseBlock.createGenesis(networkParameters).getHash());
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 12
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setSignnumber(-1);
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() { // 13
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setSignnumber(0);
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 14
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenid(null);
					}

					@Override
					public boolean expectsException() {
						return true;
					}
				}, new TestCase() {
					// 15
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenid("");
					}

					@Override
					public boolean expectsException() {
						return false;
					}
				}, new TestCase() {
					// 16
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenid("test");
					}

					@Override
					public boolean expectsException() {
						return true;// TODO add check
					}
				}, new TestCase() {
					// 17
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenid(Utils.HEX.encode(Sha256Hash.hash(testKey.getPubKey())));
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					// 18
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenindex(-1);
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					// 19
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenindex(5);
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					// 21
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenname(null);
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					// 22
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenname("");
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					// 23
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken()
								.setTokenname(new String(new char[Token.TOKEN_MAX_NAME_LENGTH]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					// 24
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken()
								.setTokenname(new String(new char[Token.TOKEN_MAX_NAME_LENGTH + 1]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() { // 25
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokenstop(false);
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					// 26
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setTokentype(-1);
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {
						tokenInfo5.getToken().setDomainName(null);
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					// 28
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken().setDomainName("");
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getToken()
								.setDomainName(new String(new char[Token.TOKEN_MAX_URL_LENGTH]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) { // 30

						tokenInfo5.getToken()
								.setDomainName(new String(new char[Token.TOKEN_MAX_URL_LENGTH + 1]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().remove(0);
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setAddress(null);
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setAddress("");
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0)
								.setAddress(new String(new char[222]).replace("\0", "A"));
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {// 35

						tokenInfo5.getMultiSignAddresses().get(0).setBlockhash(null);
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setBlockhash(getRandomSha256Hash());
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0)
								.setBlockhash(UtilGeneseBlock.createGenesis(networkParameters).getHash());
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setPosIndex(-1);
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) { // 40

						tokenInfo5.getMultiSignAddresses().get(0).setPosIndex(0);
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setPosIndex(4);
					}

					@Override
					public boolean expectsException() {
						return false; // these do not matter, they are
										// overwritten

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0)
								.setPubKeyHex(Utils.HEX.encode(PQKey.createNew().getPublicKeyBytes()));
					}

					@Override
					public boolean expectsException() {
						return true;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setTokenid(null);
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setTokenid("");
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) { // 45
						tokenInfo5.getMultiSignAddresses().get(0).setTokenid("test");
					}

					@Override
					public boolean expectsException() {
						return false;

					}
				}, new TestCase() {
					@Override
					public void preApply(TokenInfo tokenInfo5) {

						tokenInfo5.getMultiSignAddresses().get(0).setTokenid(Utils.HEX.encode(Sha256Hash.hash(testKey.getPubKey())));
					}

					@Override
					public boolean expectsException() {
						return false;

					}
					}

};

		for (int i = 0; i < executors.length; i++) {
			// Modify the tokenInfo
			TokenInfo tokenInfo = new TokenInfo().parse(tokenInfo0.toByteArray());
			executors[i].preApply(tokenInfo);

			// Make block including it
			Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters));
			block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

			// Coinbase with signatures
			if (tokenInfo.getMultiSignAddresses() != null) {

				block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
				Transaction transaction = block.getTransactions().get(0);
				Sha256Hash sighash1 = transaction.getHash();
				SignatureBundle party1Signature = outKey.sign(sighash1, null);
				byte[] buf1 = party1Signature.serialize();

				List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
				MultiSignBy multiSignBy0 = new MultiSignBy();
				if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
					multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
				else
					multiSignBy0.setTokenid(Utils.HEX.encode(Sha256Hash.hash(outKey.getPubKey())));
				multiSignBy0.setTokenindex(0);
				multiSignBy0.setAddress(outKey.toAddress(networkParameters).toHex());
				multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
				multiSignBy0.setSignature(Utils.HEX.encode(buf1));
				multiSignBies.add(multiSignBy0);

				PQKey genesiskey = PQKey.createNew();
				SignatureBundle party2Signature = genesiskey.sign(sighash1, aesKey);
				byte[] buf2 = party2Signature.serialize();
				multiSignBy0 = new MultiSignBy();
				if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
					multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
				else
					multiSignBy0.setTokenid(Utils.HEX.encode(Sha256Hash.hash(outKey.getPubKey())));
				multiSignBy0.setTokenindex(0);
				multiSignBy0.setAddress(genesiskey.toAddress(networkParameters).toHex());
				multiSignBy0.setPublickey(Utils.HEX.encode(genesiskey.getPubKey()));
				multiSignBy0.setSignature(Utils.HEX.encode(buf2));
				multiSignBies.add(multiSignBy0);

				MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
				transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
			}

			// solve block

			// Should not go through
			if (executors[i].expectsException()) {
				try {
					blockGraph.addBlock(block, false, store);
					fail("Number " + i + " failed");
				} catch (VerificationException e) {
				}
			} else {
				// always add
				blockGraph.addBlock(block, true, store);
			}
		}
	}

	@Test
	public void testSolidityTokenMutatedDataSignatures() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		PQKey outKey2 = PQKey.createNew();
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokens.setDomainName("bc");
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase with signatures
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, null);
		Transaction transaction = block.getTransactions().get(0);

		Sha256Hash sighash1 = transaction.getHash();
		SignatureBundle party1Signature = outKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.serialize();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toHex());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);

		PQKey genesiskey = PQKey.createNew();
		SignatureBundle party2Signature = genesiskey.sign(sighash1, aesKey);
		byte[] buf2 = party2Signature.serialize();
		multiSignBy0 = new MultiSignBy();
		if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
			multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		else
			multiSignBy0.setTokenid(Utils.HEX.encode(Sha256Hash.hash(outKey.getPubKey())));
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(genesiskey.toAddress(networkParameters).toHex());
		multiSignBy0.setPublickey(Utils.HEX.encode(genesiskey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf2));
		multiSignBies.add(multiSignBy0);

		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
		block.addTransaction(wallet.feeTransaction(null));
		// Mutate signatures
		Block block1 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block2 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block3 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block4 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block5 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block6 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block7 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block8 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block9 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block10 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block11 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block12 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block13 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block14 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block15 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block16 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block17 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());
		Block block18 = networkParameters.getDefaultSerializer().makeBlock(block.bitcoinSerialize());

		MultiSignByRequest multiSignByRequest1 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest2 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest3 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest4 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest5 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest6 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest7 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest8 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest9 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest10 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest11 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest12 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest13 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest14 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest15 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest16 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest17 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);
		MultiSignByRequest multiSignByRequest18 = Json.jsonmapper().readValue(transaction.getDataSignature(),
				MultiSignByRequest.class);

		multiSignByRequest1.setMultiSignBies(null);
		multiSignByRequest2.setMultiSignBies(new ArrayList<>());
		multiSignByRequest3.getMultiSignBies().get(0).setAddress(null);
		multiSignByRequest4.getMultiSignBies().get(0).setAddress("");
		multiSignByRequest5.getMultiSignBies().get(0).setAddress("test");
		multiSignByRequest6.getMultiSignBies().get(0).setPublickey(null);
		multiSignByRequest7.getMultiSignBies().get(0).setPublickey("");
		multiSignByRequest8.getMultiSignBies().get(0).setPublickey("test");
		multiSignByRequest9.getMultiSignBies().get(0).setPublickey(Utils.HEX.encode(outKey2.getPubKey()));
		multiSignByRequest10.getMultiSignBies().get(0).setSignature(null);
		multiSignByRequest11.getMultiSignBies().get(0).setSignature("");
		multiSignByRequest12.getMultiSignBies().get(0).setSignature("test");
		multiSignByRequest13.getMultiSignBies().get(0).setSignature(Utils.HEX.encode(outKey2.getPubKey()));
		multiSignByRequest14.getMultiSignBies().get(0).setTokenid(null);
		multiSignByRequest15.getMultiSignBies().get(0).setTokenid("");
		multiSignByRequest16.getMultiSignBies().get(0).setTokenid("test");
		multiSignByRequest17.getMultiSignBies().get(0).setTokenindex(-1);
		multiSignByRequest18.getMultiSignBies().get(0).setTokenindex(1);

		block1.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest1));
		block2.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest2));
		block3.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest3));
		block4.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest4));
		block5.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest5));
		block6.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest6));
		block7.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest7));
		block8.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest8));
		block9.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest9));
		block10.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest10));
		block11.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest11));
		block12.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest12));
		block13.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest13));
		block14.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest14));
		block15.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest15));
		block16.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest16));
		block17.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest17));
		block18.getTransactions().get(0).setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest18));


		// Test
		try {
			blockGraph.addBlock(block1, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block2, false, store);
			fail();
		} catch (VerificationException e) {
		}

		try {
			blockGraph.addBlock(block3, false, store);
			// fail();
		} catch (VerificationException e) {

		}
		try {
			blockGraph.addBlock(block4, false, store);
			// TODO fail();
		} catch (VerificationException e) {

		}
		try {
			blockGraph.addBlock(block5, false, store);
			// TODO fail();
		} catch (VerificationException e) {

		}
		try {
			blockGraph.addBlock(block6, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block7, false, store);
			// TODO fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block8, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block9, false, store);
			// TODO fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block10, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block11, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block12, false, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			blockGraph.addBlock(block13, false, store);
			fail();
		} catch (VerificationException e) {
		}

		try {
			blockGraph.addBlock(block14, false, store);
		} catch (VerificationException e) {
			fail();
		}
		try {
			blockGraph.addBlock(block15, false, store);
		} catch (VerificationException e) {
			fail();
		}
		try {
			blockGraph.addBlock(block16, false, store);
		} catch (VerificationException e) {
			fail();
		}
		try {
			blockGraph.addBlock(block17, false, store);
		} catch (VerificationException e) {
			fail();
		}
		try {
			blockGraph.addBlock(block18, false, store);
		} catch (VerificationException e) {
			fail();
		}
	}

	@Test
	public void testSolidityTokenNoTransaction() throws Exception {

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// save block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (VerificationException e) {
		}
	}

	@Test
	public void testSolidityTokenTransferTransaction() throws Exception {

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Add transfer transaction
		Transaction tx = createTestTransaction();
		block.addTransaction(tx);

		// save block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);

			fail();
		} catch (NotCoinbaseException e) {
		}
	}

	@Test
    public void testSolidityTokenPredecessorWrongTokenid() throws JsonProcessingException, Exception {

		// Generate an eligible issuance
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		PQKey pubKey2 = PQKey.createNew();
		Coin coinbase2 = Coin.valueOf(666, Sha256Hash.hash(pubKey2.getPubKey()));

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(Sha256Hash.hash(pubKey2.getPubKey())), "Test", "Test",
				1, 1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		TokenInfo tokenInfo2 = new TokenInfo();
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", PQKey.createNew().getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (InvalidDependencyException e) {
		}
	}

	@Test
    public void testSolidityTokenWrongTokenindex() throws JsonProcessingException, Exception {

		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());

		// Generate an eligible issuance
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test", "Test", 1,
				2, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (InvalidDependencyException e) {
		}
	}

	@Test
    public void testSolidityTokenPredecessorStopped() throws JsonProcessingException, Exception {

		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());

		// Generate an eligible issuance
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test", "Test", 1,
				1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (PreviousTokenDisallowsException e) {
		}
	}

	@Test
    public void testSolidityTokenPredecessorConflictingType() throws JsonProcessingException, Exception {

		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());

		// Generate an eligible issuance
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test", "Test", 1,
				1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokens2.setTokentype(123);
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (PreviousTokenDisallowsException e) {
		}
	}

	@Test
    public void testSolidityTokenPredecessorConflictingName() throws JsonProcessingException, Exception {

		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());

		// Generate an eligible issuance
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test2", "Test",
				1, 1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (PreviousTokenDisallowsException e) {
		}
	}

	@Test
	public void testSolidityTokenWrongTokenCoinbase() throws Exception {

		// Generate an eligible issuance tokenInfo
		PQKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = Sha256Hash.hash(outKey.getPubKey());
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// Make block including it
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setBlockType(BlockType.BLOCKTYPE_TOKEN_CREATION);

		// Coinbase with signatures
		block.addCoinbaseTransaction(outKey.getPubKey(), coinbase, tokenInfo, new MemoInfo("coinbase"));
		Transaction transaction = block.getTransactions().get(0);

		// Add another output for other tokens
		block.getTransactions().get(0).addOutput(Coin.COIN.times(2), outKey);

		Sha256Hash sighash1 = transaction.getHash();
		SignatureBundle party1Signature = outKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.serialize();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toHex());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// save block

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);

			fail();
		} catch (InvalidTransactionDataException e) {
		}
	}

}