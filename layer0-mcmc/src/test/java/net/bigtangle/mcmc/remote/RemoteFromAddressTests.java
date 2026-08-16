/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.crypto.pq.PQAddress;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;
import net.bigtangle.mcmc.test.FromAddressTests;
import net.bigtangle.wallet.Wallet;

public class RemoteFromAddressTests extends RemoteTest {

	private PQKey accountKey;
	Wallet yuanWallet;
	protected static final Logger log = LoggerFactory.getLogger(FromAddressTests.class);

	@Test
	public void testUserpay() throws Exception {

		yuanWallet = Wallet.fromKeys(networkParameters, PQKey.createNew(), contextRoot);

	//	payBigTo(accountKey,
	//			Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1000)), null);

		payBigTo(PQKey.createNew(), Coin.FEE_DEFAULT.getValue(), null);

		testTokens();

		accountKey = PQKey.createNew();
		List<Coin> list = getBalanceAccount(false, yuanWallet.walletKeys());

		createUserPay(accountKey);
		list = getBalanceAccount(false, yuanWallet.walletKeys());

		List<PQKey> userkeys = new ArrayList<PQKey>();
		userkeys.add(accountKey);
		list = getBalanceAccount(false, userkeys);
		for (Coin coin : list) {
			log.debug(coin.toString());

		}
	}

	private void createUserPay(PQKey accountKey) throws Exception {
		List<PQKey> ulist = payKeys();
		for (PQKey key : ulist) {
	//		buyTicket(key, accountKey);
		}

	}

	/*
	 * pay money to the key and use the key to buy lottery
	 */
	public void buyTicket(PQKey key, PQKey accountKey) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
		log.debug("====ready buyTicket====");
		List<Transaction> bs = w.pay(null, accountKey.toAddress(networkParameters).toHex(),
				Coin.valueOf(100, Utils.HEX.decode(yuanTokenPub)), " buy ticket");

		log.debug("====start buyTicket====");
		List<PQKey> userkeys = new ArrayList<PQKey>();
		userkeys.add(key);
 
		List<UTXO> utxos = getBalance(false, key);
		for (UTXO utxo : utxos) {
			log.debug("user uxxo==" + utxo.toString());
		}
	 

		userkeys = new ArrayList<PQKey>();
		userkeys.add(accountKey);
	 
 
		getBalanceAccount(false, wallet.walletKeys());

		// checkResult(accountKey, key.toAddress(networkParameters).toHex());
	}

	public List<PQKey> payKeys() throws Exception {
		List<PQKey> userkeys = new ArrayList<PQKey>();
		PQKey key = PQKey.createNew();
		userkeys.add(key);
		PQKey key2 = PQKey.createNew();
		userkeys.add(key2);

		// PQ-aware payment: payToList() only accepts legacy base58 Address, but
		// these are PQ keys — use the Key overload of Wallet.pay() (the primary
		// EC/PQ migration path) once per recipient.
		String memo = "pay to user";
		byte[] yuanToken = Utils.HEX.decode(yuanTokenPub);
		yuanWallet.pay(null, key, Coin.valueOf(100, yuanToken), memo);
		yuanWallet.pay(null, key2, Coin.valueOf(100, yuanToken), memo);

		payBigTo(key, Coin.FEE_DEFAULT.getValue(), null);

		// fee=1000
		payBigTo(key2, Coin.FEE_DEFAULT.getValue(), null);

		return userkeys;
	}

	public void testTokens() throws JsonProcessingException, Exception {
		String domain = "";
		PQKey fromPrivate = PQKey.createNew();

		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY", BigInteger.valueOf(10000000l));

	}

	public PQAddress getAddress() {
		return PQKey.createNew().toAddress(networkParameters);
	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(PQKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount) throws JsonProcessingException, Exception {
		try {

			createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.currency.ordinal(), key.getPublicKeyAsHex(),
					Wallet.fromKeys(networkParameters, key, contextRoot));
			PQKey signkey = PQKey.createNew();

		} catch (Exception e) {
			// TODO: handle exception
			log.warn("", e);
		}

	}

}
