/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.response.GetTXRewardListResponse;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class PaymentServiceTest extends AbstractIntegrationTest {
	private static final Logger log = LoggerFactory.getLogger(PaymentServiceTest.class);

	@Test
	public void testAllTXReward() throws Exception {

		// get new Block to be used from server
		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.getAllConfirmedReward.name(),
				Json.jsonmapper().writeValueAsString(requestParam));

		GetTXRewardListResponse getBalancesResponse = Json.jsonmapper().readValue(response,
				GetTXRewardListResponse.class);
		assertTrue(getBalancesResponse.getTxReward().size() > 0);
	}

	@Test
	// transfer the coin to address with multisign for spent
	public void testMultiSigns() throws Exception {

		List<PQKey> wallet1Keys_part = new ArrayList<PQKey>();
		wallet1Keys_part.add(PQKey.createNew());
		wallet1Keys_part.add(PQKey.createNew());
		createMultiSigns( wallet1Keys_part);

	}

	// pay to mutilsigns keys wallet1Keys_part
	public void createMultiSigns(List<PQKey> wallet1Keys_part) throws Exception {
		for (PQKey ecKey : wallet1Keys_part)
			log.debug(ecKey.getPublicKeyAsHex());

		Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(2, wallet1Keys_part);

		Coin amount0 = Coin.valueOf(15, NetworkParameters.BIGTANGLE_TOKENID);

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.payToScript(null, amount0, new MemoInfo("multi signs"), scriptPubKey);

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block payBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(payBlock);

		checkBalance(amount0, wallet1Keys_part);
	}

	public void multiSigns(PQKey receiverkey, List<PQKey> wallet1Keys_part) throws Exception {
		payBigTo(wallet1Keys_part.get(0), Coin.FEE_DEFAULT.getValue().multiply(BigInteger.valueOf(2)), null);

		List<UTXO> ulist = getBalance(false, wallet1Keys_part);

		TransactionOutput multisigOutput = new FreeStandingTransactionOutput(this.networkParameters, ulist.get(0));
		Script multisigScript1 = multisigOutput.getScriptPubKey();

		Coin amount1 = Coin.valueOf(3, NetworkParameters.BIGTANGLE_TOKENID);

		Coin outputCoin = multisigOutput.getValue().subtract(amount1).subtract(Coin.FEE_DEFAULT);

		Transaction transaction0 = new Transaction(networkParameters);
		Script scriptPubKey = ScriptBuilder.createMultiSigOutputScript(2, wallet1Keys_part);
		transaction0.addOutput(amount1, scriptPubKey);
		// add remainder back

		// transaction0.addOutput(outputCoin, ulist.get(0).getAddress());

		transaction0.addInput(ulist.get(0).getBlockHash(), multisigOutput);

		Transaction transaction_ = networkParameters.getDefaultSerializer()
				.makeTransaction(transaction0.bitcoinSerialize());
		transaction0 = transaction_;
		TransactionInput input2 = transaction0.getInput(0);

		Sha256Hash sighash = transaction0.hashForSignature(0, multisigScript1, Transaction.SigHash.ALL, false);
		TransactionSignature tsrecsig = new TransactionSignature(wallet1Keys_part.get(0).sign(sighash),
				Transaction.SigHash.ALL, false);
		TransactionSignature tsintsig = new TransactionSignature(wallet1Keys_part.get(1).sign(sighash),
				Transaction.SigHash.ALL, false);
		Script inputScript = ScriptBuilder.createMultiSigInputScript(ImmutableList.of(tsrecsig, tsintsig));
		input2.setScriptSig(inputScript);

		// Ensure tips queue is populated
		try {
			mcmcService.update(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		HashMap<String, String> requestParam = new HashMap<String, String>();
		byte[] data = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
				Json.jsonmapper().writeValueAsString(requestParam));
		Block rollingBlock = networkParameters.getDefaultSerializer().makeBlock(data);
		rollingBlock.addTransaction(transaction0);


		checkResponse(OkHttp3Util.post(contextRoot + ReqCmd.batchBlock.name(), rollingBlock.bitcoinSerialize()));

		checkBalance(amount1, receiverkey);
	}

	@Test
	// transfer the coin to address
	public void testTransferWallet() throws Exception {

		Coin amount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
		Address address = PQKey.createNew().toAddress(networkParameters);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.pay(null, address.toString(), amount,  "" );

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block payBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(payBlock);

		// check the output history
		historyUTXOList(address.toBase58(), amount);
	}

	@Test
	// transfer the coin to address
    public void testPossibleConflict() throws Exception {

		Coin amount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
		Address address = PQKey.createNew().toAddress(networkParameters);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.pay(null, address.toString(), amount,   "" );

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block rollingBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(rollingBlock);

		// check the output history
		historyUTXOList(address.toBase58(), amount);
	}

	@SuppressWarnings("unused")
	private void retryBlocksFix(Block rollingBlock) {
	}

	public void historyUTXOList(String addressString, Coin amount) throws Exception {
		Map<String, String> param = new HashMap<String, String>();

		param.put("toaddress", addressString);

		byte[] response = OkHttp3Util.postString(contextRoot + ReqCmd.getOutputsHistory.name(),
				Json.jsonmapper().writeValueAsString(param));

		GetBalancesResponse balancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

		int my = 0;
		for (UTXO utxo : balancesResponse.getOutputs()) {
			if (utxo.isSpent()) {
				continue;
			}
			if (amount.compareTo(utxo.getValue()) == 0)
				my = 1;
		}
		assertTrue(my == 1);
	}

	@Test
	// coins in wallet to one coin to address
	public void testPartsToOne() throws Exception {

		PQKey to = PQKey.createNew();
		payBigTo(to, Coin.FEE_DEFAULT.getValue(), null);
		Wallet w = Wallet.fromKeys(networkParameters, to, contextRoot);
		Coin aCoin = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
		testPartsToOne(aCoin, to);
		checkBalance(aCoin, to);

		testPartsToOne(aCoin, to);
		testPartsToOne(aCoin, to);
		List<FreeStandingTransactionOutput> uspent = w.calculateAllSpendCandidates(null, false);
		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		w.payPartsToOne(null, to.toAddress(networkParameters).toString(), NetworkParameters.BIGTANGLE_TOKENID, "0,3");

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block payBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(payBlock);

		ArrayList<PQKey> a = new ArrayList<PQKey>();
		a.add(to);
		List<UTXO> ulist = getBalance(false, a);
		assertTrue(ulist.size() == 1);

	}

	public void testPartsToOne(Coin amount, PQKey to) throws Exception {

		// Ensure tips queue is updated before wallet operations
		mcmcService.calcNewBlockPrototype(store);
		wallet.pay(null, to.toAddress(networkParameters).toString(), amount, "");

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block payBlock = drainMempoolAndCreateBlock(predecessor, predecessor);
		makeRewardBlock(payBlock);

	}

}