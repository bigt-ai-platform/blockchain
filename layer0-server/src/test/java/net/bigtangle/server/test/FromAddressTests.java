/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.crypto.pq.PQAddress;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class FromAddressTests extends AbstractIntegrationTest {
	@Autowired
	private NetworkParameters networkParameters;
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";

	private PQKey accountKey;
	Wallet yuanWallet;
	protected static final Logger log = LoggerFactory.getLogger(FromAddressTests.class);

	// @Test  // Requires pre-existing token and wallet funding not in standard test setup
	public void testUserpay() throws Exception {

		yuanWallet = Wallet.fromKeys(networkParameters, PQKey.createNew(), null);

		List<Coin> list = getBalanceAccount(false, yuanWallet.walletKeys());
		for (Coin coin : list) {
			if (coin.isBIG()) {
				assertTrue(Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1000)).equals(coin.getValue()));
			}

		}
		List<Coin> adminlist = getBalanceAccount(false, wallet.walletKeys());
		for (Coin coin : adminlist) {
			if (coin.isBIG()) {
				assertTrue(NetworkParameters.BigtangleCoinTotal
						.subtract(Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1001)))
						.equals(coin.getValue()));
			}

		}

		accountKey = PQKey.createNew();

		list = getBalanceAccount(false, yuanWallet.walletKeys());
		for (Coin coin : list) {
			if (coin.isBIG()) {
				assertTrue(Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1000))
						.subtract(Coin.FEE_DEFAULT.getValue()).equals(coin.getValue()));
			} else if (coin.getTokenHex().equals(yuanTokenPub)) {
				assertTrue(BigInteger.valueOf(10000000l).equals(coin.getValue()));
			}
		}

		makeRewardBlock();

		createUserPay(accountKey);
		list = getBalanceAccount(false, yuanWallet.walletKeys());

		List<PQKey> userkeys = new ArrayList<PQKey>();
		userkeys.add(accountKey);
		list = getBalanceAccount(false, userkeys);
		for (Coin coin : list) {
			if (coin.getTokenHex().equals(yuanTokenPub)) {
				assertTrue(coin.getValue().equals(BigInteger.valueOf(200l)));
			}
		}
	}

 
	private void createUserPay(PQKey accountKey) throws Exception {
		List<PQKey> ulist = payKeys();
		for (PQKey key : ulist) {
			buyTicket(key, accountKey);
		}

	}

	/*
	 * pay money to the key and use the key to buy lottery
	 */
	public void buyTicket(PQKey key, PQKey accountKey) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
		log.debug("====ready buyTicket====");
		// Ensure tips queue is populated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		byte[] yuanTokenId = Utils.HEX.decode(yuanTokenPub);
		Coin sendAmount = Coin.valueOf(100, yuanTokenId);
		List<FreeStandingTransactionOutput> candidates = w.calculateAllSpendCandidates(null, false);
		List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : candidates) {
			if (java.util.Arrays.equals(yuanTokenId, co.getUTXO().getTokenidBuf())) {
				tokenUtxos.add(co);
			}
		}
		Coin total = Coin.valueOf(0, yuanTokenId);
		Transaction tx = new Transaction(networkParameters);
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, sendAmount, accountKey));
		for (FreeStandingTransactionOutput co : tokenUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			total = total.add(co.getValue());
			Coin change = total.subtract(sendAmount);
			if (!change.isNegative()) {
				if (change.isPositive()) {
					tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, key));
				}
				break;
			}
		}
		if (total.getValue().compareTo(sendAmount.getValue()) < 0) {
			throw new InsufficientMoneyException(sendAmount + " outputs size= " + tokenUtxos.size());
		}
		w.signTransaction(tx, null);
		w.submitTransaction(tx);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block bs = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(bs);
		blockGraph.updateTransactionOutputSpendPendingDo(bs);
		makeRewardBlock(bs);
		log.debug("====start buyTicket====");
		List<PQKey> userkeys = new ArrayList<PQKey>();
		userkeys.add(key);
		log.debug("====chaeck utxo");
		List<UTXO> utxos = getBalance(false, key);
		for (UTXO utxo : utxos) {
			log.debug("user uxxo==" + utxo.toString());
		}
		List<Coin> coins = getBalanceAccount(false, userkeys);
		for (Coin coin : coins) {

			assertTrue(coin.isZero());

		}

		userkeys = new ArrayList<PQKey>();
		userkeys.add(accountKey);
		for (Coin coin : coins) {

			assertTrue(coin.getValue().equals(BigInteger.valueOf(100l)));

		}
		log.debug("====start check admin wallet====");
		getBalanceAccount(false, wallet.walletKeys());

		// checkResult(accountKey, key.toAddress(networkParameters).toHex());
	}

	public List<PQKey> payKeys() throws Exception {
		List<PQKey> userkeys = new ArrayList<PQKey>();

		PQKey key = PQKey.createNew();
		userkeys.add(key);
		PQKey key2 = PQKey.createNew();
		userkeys.add(key2);

		byte[] yuanTokenId = Utils.HEX.decode(yuanTokenPub);
		// Ensure tips queue is populated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		List<FreeStandingTransactionOutput> coinList = yuanWallet.calculateAllSpendCandidates(null, false);
		List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : coinList) {
			if (java.util.Arrays.equals(yuanTokenId, co.getUTXO().getTokenidBuf())) {
				tokenUtxos.add(co);
			}
		}
		Coin total = Coin.valueOf(0, yuanTokenId);
		Coin totalSend = Coin.valueOf(0, yuanTokenId);
		Transaction tx = new Transaction(networkParameters);
		Coin amount = Coin.valueOf(100, yuanTokenId);
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, key));
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, key2));
		totalSend = totalSend.add(amount).add(amount);
		for (FreeStandingTransactionOutput co : tokenUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			total = total.add(co.getValue());
			Coin change = total.subtract(totalSend);
			if (!change.isNegative()) {
				if (change.isPositive()) {
					PQKey changeKey = yuanWallet.walletKeys(null).get(0);
					tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, changeKey));
				}
				break;
			}
		}
		if (total.getValue().compareTo(totalSend.getValue()) < 0) {
			throw new InsufficientMoneyException(totalSend + " outputs size= " + tokenUtxos.size());
		}
		yuanWallet.signTransaction(tx, null);
		yuanWallet.submitTransaction(tx);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block b = drainMempoolAndCreateBlock(predecessor, predecessor);
		log.debug("block " + (b == null ? "block is null" : b.toString()));
		makeRewardBlock(b);
		blockGraph.updateTransactionOutputSpendPendingDo(b);
		log.debug("====start check yuanWallet wallet====");
		List<Coin> list = getBalanceAccount(false, yuanWallet.walletKeys());
		for (Coin coin : list) {
			if (!coin.isBIG()) {
				assertTrue(coin.getValue().equals(BigInteger.valueOf(10000000l).subtract(BigInteger.valueOf(200l))));
			}
		}
		List<Coin> coins = getBalanceAccount(false, userkeys);
		for (Coin coin : coins) {
			if (!coin.isBIG()) {
				assertTrue(coin.getValue().equals(BigInteger.valueOf(100l)));
			}

		}
//		checkResult(key, yuanWallet.walletKeys().get(0).toAddress(networkParameters).toHex(), memo);
		// fee=1000
		payBigTo(key, Coin.FEE_DEFAULT.getValue(), null);
		makeRewardBlock();
		log.debug("====start check admin wallet====");
		List<Coin> adminCoins = getBalanceAccount(false, wallet.walletKeys());
		BigInteger adminCoin = NetworkParameters.BigtangleCoinTotal
				.subtract(Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(1001)));
		for (Coin coin : adminCoins) {
			if (coin.isBIG()) {
				assertTrue(adminCoin.subtract(Coin.FEE_DEFAULT.getValue()).subtract(BigInteger.valueOf(1000))
						.equals(coin.getValue()));
			}
		}
		// fee=1000
		payBigTo(key2, Coin.FEE_DEFAULT.getValue(), null);
		makeRewardBlock();
		log.debug("====start check admin wallet====");
		adminCoins = getBalanceAccount(false, wallet.walletKeys());
		adminCoin = adminCoin.subtract(Coin.FEE_DEFAULT.getValue()).subtract(BigInteger.valueOf(1000));
		for (Coin coin : adminCoins) {
			if (coin.isBIG()) {
				assertTrue(adminCoin.subtract(Coin.FEE_DEFAULT.getValue()).subtract(BigInteger.valueOf(1000))
						.equals(coin.getValue()));
			}
		}
		coins = getBalanceAccount(false, userkeys);
		for (Coin coin : coins) {
			if (coin.isBIG()) {
				assertTrue(coin.getValue().equals(BigInteger.valueOf(1000)));
			}

		}
		return userkeys;
	}

	public void testTokens() throws JsonProcessingException, Exception {
		String domain = "";
		PQKey fromPrivate = PQKey.createNew();

		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY", BigInteger.valueOf(10000000l));
		makeRewardBlock();
	}

	public PQAddress getAddress() {
		return PQKey.createNew().toAddress(networkParameters);
	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(PQKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount) throws JsonProcessingException, Exception {
		try {

			// Ensure tips queue is populated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.currency.ordinal(), key.getPublicKeyAsHex(),
					Wallet.fromKeys(networkParameters, key, contextRoot));
			PQKey signkey = PQKey.createNew();

			// Ensure tips queue is updated before wallet operations
			mcmcService.calcNewBlockPrototype(store);
			Block signedBlock = wallet.multiSign(key.getPublicKeyAsHex(), signkey, null);
			makeRewardBlock(signedBlock);

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

}
