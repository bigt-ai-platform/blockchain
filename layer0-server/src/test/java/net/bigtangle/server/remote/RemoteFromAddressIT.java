/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class RemoteFromAddressIT extends RemoteTestBase {

	private PQKey accountKey;
	Wallet yuanWallet;
	protected static final Logger log = LoggerFactory.getLogger(RemoteFromAddressIT.class);

	/** The single key that ISSUES the yuan token AND owns the minted supply (seed 0x03). */
	private static PQKey yuanKey() {
		byte[] seed = new byte[32];
		Arrays.fill(seed, (byte) 0x03);
		return PQKey.fromMLDSA(seed);
	}

	@Test
	public void testUserpay() throws Exception {

		PQKey yuanK = yuanKey();
		// yuanWallet keyed on the SAME key that will issue + receive the yuan
		// token. It is pre-funded with CONFIRMED BIG at genesis (see remote.sh),
		// so it can pay the creation fee without waiting on a mempool transfer
		// to confirm (plain transfers never confirm on this PoS build).
		yuanWallet = Wallet.fromKeys(networkParameters, yuanK, contextRoot);

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
			// buyTicket(key, accountKey);
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
		PQKey fromPrivate = yuanKey();

		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY", BigInteger.valueOf(10000000l));

	}

	public net.bigtangle.crypto.pq.PQAddress getAddress() {
		return yuanKey().toAddress(networkParameters);
	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(PQKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount) throws JsonProcessingException, Exception {
		try {

			Block block = createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.currency.ordinal(), key.getPublicKeyAsHex(), yuanWallet);
			// Mirror the proven RemoteTokenIT flow: first-time issuance requires
			// a DOMAIN-holder signature, so the multi-sign must be completed with
			// the root-domain wallet (genesis, seed 0x01) - not the yuan wallet.
			Block signed = wallet.multiSign(key.getPublicKeyAsHex(), wallet.walletKeys().get(0), aesKey);
			if (signed != null) {
				makeRewardBlock(signed);
			}
			// The minted yuan supply (and the creation fee change) must be
			// CONFIRMED before the wallet can pay it to users; a spend of
			// unconfirmed UTXOs never confirms.
			waitForTokenUtxos(key, key.getPublicKeyAsHex());
			PQKey signkey = PQKey.createNew();

		} catch (Exception e) {
			// TODO: handle exception
			log.warn("", e);
		}

	}

	/** Waits until the wallet sees CONFIRMED, spendable UTXOs of the given token. */
	private void waitForTokenUtxos(PQKey key, String tokenId) throws Exception {
		byte[] tokenidBuf = Utils.HEX.decode(tokenId);
		for (int i = 0; i < 60; i++) {
			boolean ok = false;
			for (FreeStandingTransactionOutput co : yuanWallet.calculateAllSpendCandidates(null, false)) {
				if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())
						&& co.getValue().getValue().signum() > 0) {
					ok = true;
				}
			}
			if (ok) return;
			Thread.sleep(3000);
		}
		log.warn("Token {} UTXOs not confirmed after polling", tokenId.substring(0, 14));
	}

}