/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.performance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.Address;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.Wallet;

public class LotteryTests extends AbstractIntegrationTest {
	@Autowired
	public NetworkParameters networkParameters;
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";
	public int usernumber = Math.abs(new Random().nextInt()) % 88;
	public BigInteger winnerAmount = new BigInteger(Math.abs(new Random().nextInt()) % 9999 + "");

	public PQKey accountKey;

	protected static final Logger log = LoggerFactory.getLogger(LotteryTests.class);

	@Test
	public void lotteryM() throws Exception {

		usernumber = 1000;
		winnerAmount = new BigInteger(usernumber + "");

		lotteryDo();

	}

	public void lotteryDo() throws Exception {
		wallet.importKey(PQKey.createNew()Utils.HEX.decode(yuanTokenPriv)));
		accountKey = PQKey.createNew();
		wallet.importKey(accountKey);
		testTokens();
		List<PQKey> ulist = createUserkey();
		payUserKeys(ulist);
		payBigUserKeys(ulist);
		// createUserPay(accountKey, ulist);
		createUserPayNoThread(accountKey, ulist);
		boolean checkPayOK = true;
		Lottery startLottery = startLottery();
		while (checkPayOK) {
			Thread.sleep(1000);
			makeRewardBlock();
			// check pay ok
			Coin sum = accountSum();
			if (startLottery.getWinnerAmount().compareTo(sum.getValue()) > 0) {
				log.debug(sum.toString());
				checkPayOK = false;
			}
		}

		while (!startLottery.isMacthed()) {
			startLottery = startLottery();
			createUserPay(accountKey, ulist);
			makeRewardBlock();
		}

		checkResult(startLottery);
	}

	public Lottery startLottery()
			throws Exception, JsonProcessingException, InterruptedException, ExecutionException, BlockStoreException {
		Lottery startLottery = new Lottery();
		startLottery.setTokenid(yuanTokenPub);
		startLottery.setContextRoot(contextRoot);
		startLottery.setParams(networkParameters);

		startLottery.setWinnerAmount(winnerAmount);
		startLottery.setAccountKey(accountKey);
		startLottery.start();

		mcmc();
		return startLottery;
	}

	public void checkResult(Lottery startLottery) throws Exception {
		Coin sum = lotterySum(startLottery);

		assertTrue(sum != null);
		if (startLottery.getWinnerAmount().compareTo(sum.getValue()) > 0) {
			log.debug(sum.toString());
		}
		assertTrue(startLottery.getWinnerAmount().compareTo(sum.getValue()) <= 0,
				" " + startLottery.getWinnerAmount() + " sum=" + sum.getValue());

	}

	public Coin accountSum() throws Exception {
		List<UTXO> users = getBalance(accountKey.toAddress(networkParameters).toString());

		Coin sum = Coin.valueOf(0, Utils.HEX.decode(yuanTokenPub));
		for (UTXO u : users) {
			sum = sum.add(u.getValue());

		}
		return sum;
	}

	private Coin lotterySum(Lottery startLottery) throws Exception {
		Coin coin = new Coin(startLottery.sum(), yuanTokenPub);

		List<UTXO> users = getBalance(startLottery.getWinner());

		Coin sum = Coin.valueOf(0, Utils.HEX.decode(yuanTokenPub));
		for (UTXO u : users) {
			if (coin.getTokenHex().equals(u.getTokenId())
					&& u.getFromaddress().equals(accountKey.toAddress(networkParameters).toBase58())) {
				sum = sum.add(u.getValue());

			}
		}
		return sum;
	}

	public void createUserPay(PQKey accountKey, List<PQKey> ulist) throws Exception {

		List<List<PQKey>> parts = Wallet.chopped(ulist, 1000);
		List<Thread> threads = new ArrayList<Thread>();
		for (List<PQKey> list : parts) {
			Runnable myRunnable = new Runnable() {
				@Override
				public void run() {
					List<Transaction> txs = new ArrayList<Transaction>();
					for (PQKey key : list) {
						try {
							txs.add(buyTicketTransaction(key, accountKey));
						} catch (Exception e) {
							log.error("buyTicketTransaction==", e);
						}
					}
					try {
						wallet.payTransaction(txs);
					} catch (Exception e) {
						log.error("payTransaction==", e);
					}
				}
			};
			Thread thread = new Thread(myRunnable);
			thread.start();
			threads.add(thread);
		}

		int running = 0;
		do {
			running = 0;
			for (Thread thread : threads) {
				if (thread.isAlive()) {
					running++;
				}
			}
			Thread.sleep(2000);
			log.debug("There are  " + running + " running threads. ");
		} while (running > 0);

	}

	public Transaction buyTicketTransaction(PQKey key, PQKey accountKey) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);

		int satoshis = 1000;
		return w.createTransaction(null, accountKey.toAddress(networkParameters).toString(),
				Coin.valueOf(satoshis, Utils.HEX.decode(yuanTokenPub)), new MemoInfo("buy ticket"));

	}

	public void createUserPayNoThread(PQKey accountKey, List<PQKey> ulist) throws Exception {
		List<List<PQKey>> parts = Wallet.chopped(ulist, 1000);
		for (List<PQKey> list : parts) {

			List<Transaction> txs = new ArrayList<Transaction>();
			for (PQKey key : list) {
				try {
					txs.add(buyTicketTransaction(key, accountKey));
				} catch (Exception e) {
					log.error("buyTicketTransaction==", e);
				}
			}
			try {
				wallet.payTransaction(txs);
			} catch (Exception e) {
				log.error("payTransaction==", e);
			}

		}

	}

	/*
	 * pay money to the key and use the key to buy lottery
	 */
	public void buyTicket(PQKey key, PQKey accountKey) throws Exception {
		Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);

		try {
			int satoshis = Math.abs(new Random().nextInt()) % 1000;
			w.pay(null, accountKey.toAddress(networkParameters).toString(),
					Coin.valueOf(satoshis, Utils.HEX.decode(yuanTokenPub)), new MemoInfo(" buy ticket"));
		} catch (InsufficientMoneyException e) {
			// TODO: handle exception
		}
	}

	public void payUserKeys(List<PQKey> userkeys) throws Exception {

		Stopwatch watch = Stopwatch.createStarted();
		List<List<PQKey>> parts = Wallet.chopped(userkeys, 1000);

		for (List<PQKey> list : parts) {
			HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
			for (PQKey key : list) {
				giveMoneyResult.put(key.toAddress(networkParameters).toString(), winnerAmount);
			}
			// log.debug("block " + (b == null ? "block is null" : b.toString()));
			makeRewardBlock();
		}
		log.debug("pay user " + usernumber + "  duration minutes " + watch.elapsed(TimeUnit.MINUTES));
		log.debug("rate  " + usernumber * 1.0 / watch.elapsed(TimeUnit.SECONDS));

	}

	public void payBigUserKeys(List<PQKey> userkeys) throws Exception {

		List<List<PQKey>> parts = Wallet.chopped(userkeys, 1000);

		for (List<PQKey> list : parts) {
			HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
			for (PQKey key : list) {
				giveMoneyResult.put(key.toAddress(networkParameters).toString(), BigInteger.valueOf(10000));
			}
			// log.debug("block " + (b == null ? "block is null" : b.toString()));
			makeRewardBlock();
		}

	}

	public List<PQKey> createUserkey() {
		List<PQKey> userkeys = new ArrayList<PQKey>();
		for (int i = 0; i < usernumber; i++) {
			PQKey key = PQKey.createNew();
			userkeys.add(key);
		}
		return userkeys;
	}

	public void testTokens() throws JsonProcessingException, Exception {

		String domain = "";

		PQKey fromPrivate = PQKey.createNew()Utils.HEX.decode(yuanTokenPriv));

		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY",
				winnerAmount.multiply(BigInteger.valueOf(usernumber * 10000l)));
		makeRewardBlock();
	}

	public Address getAddress() {
		return PQKey.createNew()Utils.HEX.decode(yuanTokenPriv)).toAddress(networkParameters);
	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(PQKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount) throws JsonProcessingException, Exception {
		try {
			wallet.setServerURL(contextRoot);

			// pay fee to PQKey key

			createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet);

			PQKey signkey = PQKey.createNew()Utils.HEX.decode(testPriv));

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

}
