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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.crypto.params.KeyParameter;

import com.fasterxml.jackson.core.JsonProcessingException;

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
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.params.TestParams;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.OrderdataResponse;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

  
public abstract class RemoteTest {


	protected static final Logger log = LoggerFactory.getLogger(RemoteTest.class);
	public String contextRoot = System.getProperty("server.url", "http://localhost:8089/");

	public Wallet wallet;

	protected final KeyParameter aesKey = null;

	private static String pqKeyHex(byte mlDsaFill, byte slhDsaFill) {
		byte[] mlDsaSeed = new byte[32];
		byte[] slhDsaSeed = new byte[32];
		Arrays.fill(mlDsaSeed, mlDsaFill);
		Arrays.fill(slhDsaSeed, slhDsaFill);
		return PQKey.fromSeeds(mlDsaSeed, slhDsaSeed).getPublicKeyAsHex();
	}

	public static String genesisPub = pqKeyHex((byte) 0x01, (byte) 0x02);
	public static String yuanTokenPub = pqKeyHex((byte) 0x03, (byte) 0x04);
 
 
	public NetworkParameters networkParameters = TestParams.get();

	@BeforeEach
	public void setUp() throws Exception {
		byte[] mlDsaSeed = new byte[32];
		byte[] slhDsaSeed = new byte[32];
		Arrays.fill(mlDsaSeed, (byte) 0x01);
		Arrays.fill(slhDsaSeed, (byte) 0x02);
		wallet = Wallet.fromKeys(networkParameters, PQKey.fromSeeds(mlDsaSeed, slhDsaSeed), contextRoot);
	}

	protected Block payBigTo(PQKey beneficiary, BigInteger amount, List<Block> addedBlocks) throws Exception {
		List<FreeStandingTransactionOutput> coinList = wallet.calculateAllSpendCandidates(null, false);
		List<FreeStandingTransactionOutput> bigUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : coinList) {
			if (Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
				bigUtxos.add(co);
			}
		}
		if (bigUtxos.isEmpty()) {
			throw new InsufficientMoneyException(
					new Coin(amount, NetworkParameters.BIGTANGLE_TOKENID) + " outputs size= 0");
		}
		Coin total = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction tx = new Transaction(networkParameters);
		tx.setVersion(PQConstants.TX_PQ_VERSION);
		Coin sendAmount = new Coin(amount, NetworkParameters.BIGTANGLE_TOKENID);
		PQKey walletKey = wallet.walletKeys(null).get(0);
		for (FreeStandingTransactionOutput co : bigUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			tx.getInputs().get(tx.getInputs().size() - 1).getOutpoint().connectedOutput = co;
			total = total.add(co.getValue());
			tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, sendAmount, beneficiary));
			Coin change = total.subtract(sendAmount).subtract(Coin.FEE_DEFAULT);
			if (!change.isNegative()) {
				tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, walletKey));
				break;
			}
		}
		if (total.getValue().compareTo(sendAmount.getValue()) < 0) {
			throw new InsufficientMoneyException(sendAmount + " outputs size= " + bigUtxos.size());
		}
		wallet.signTransaction(tx, null);
		wallet.submitTransaction(tx);
		Block b = Block.setBlock2(networkParameters, NetworkParameters.BLOCK_VERSION_GENESIS);
		b.addTransaction(tx);
		if (addedBlocks != null) {
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
		// Use the wallet's createToken which internally calls saveToken → signToken
		return w.createToken(key, domainname, increment, token, addresses, key.getPubKey(), new MemoInfo("coinbase"));
	}

	public Block createTokenWallet(PQKey key, String domainname, boolean increment, Token token,
			List<MultiSignAddress> addresses, Wallet w) throws Exception {
		return w.createToken(key, domainname, increment, token, addresses, key.getPubKey(), new MemoInfo("coinbase"));
	}

	protected Block fetchTip() throws Exception {
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(new HashMap<String, String>()));
		return networkParameters.getDefaultSerializer().makeBlock(data);
	}

	/**
	 * Wait for the server to process pending transactions from the mempool.
	 * The server runs micro-batch every 100ms, so a short wait should suffice.
	 */
	protected void makeRewardBlock() throws Exception {
		Thread.sleep(2000);
	}

	protected void makeRewardBlock(Block predecessor) throws Exception {
		Thread.sleep(2000);
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

	protected List<UTXO> getBalance(boolean withZero, List<PQKey> keys) throws Exception {
		List<UTXO> listUTXO = new ArrayList<UTXO>();
		List<String> keyStrHex000 = new ArrayList<String>();

		for (PQKey key : keys) {
			keyStrHex000.add(Utils.HEX.encode(key.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

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

		for (PQKey key : keys) {
			keyStrHex000.add(Utils.HEX.encode(key.getPubKeyHash()));
		}
		byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getAccountBalances.name(),
				Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

		GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

		listCoin.addAll(getBalancesResponse.getBalance());
		for (Coin coin : listCoin) {
			log.debug("coin:" + coin.toString());
		}
		return listCoin;
	}

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

	protected List<UTXO> getBalance(boolean withZero, PQKey key) throws Exception {
		List<PQKey> keys = new ArrayList<PQKey>();
		keys.add(key);
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