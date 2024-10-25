package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Stopwatch;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.NoBlockException;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.ServiceContract;
import net.bigtangle.wallet.Wallet;

public class ContractTest extends AbstractIntegrationTest {

	protected static final Logger log = LoggerFactory.getLogger(ContractTest.class);

	@Autowired
	public NetworkParameters networkParameters;
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";

	public static String lotteryTokenPub = "039aee4f0291991dd71ea0dd3c0e91ef680e769eca0326f1e36b74107aec4ac1f4";
	public static String lotteryTokenPriv = "6cecae9a820844dac41521ddad4f1b5068fdcac59ce28a6dd1ed01a12f782362";
	public ECKey contractKey = ECKey.fromPrivate(Utils.HEX.decode(lotteryTokenPriv));
	public int usernumber = 10;

	public List<ECKey> ulist;
	String winnerAmount = "10000";
	String contractAmount = "2500";
	public BigInteger payContractAmount = new BigInteger(contractAmount);

	public void prepare(List<Block> a1) throws JsonProcessingException, Exception {
		prepare("1", a1);

	}

	public void prepare(String factor, List<Block> a1) throws JsonProcessingException, Exception {
		wallet.importKey(ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)));
		testToken(a1);
		testContractTokens(a1);
		ulist = createUserkey();
		payUserKeys(ulist, factor, a1);
		payBigUserKeys(ulist, Long.valueOf(factor), a1);

	}

	private void executionAndCheck()
			throws BlockStoreException, NoBlockException, InterruptedException, ExecutionException, Exception {

		Block resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);

		if (resultBlock != null) {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(resultBlock.getTransactions().get(0).getData());

			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
					cacheBlockService).executeContract(resultBlock, store, result.getContracttokenid(),
							store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
			blockSaveService.saveBlock(resultBlock, store);
			makeRewardBlock(resultBlock);
			assertTrue(resultBlock != null);
			if (!check.getOutputTx().getOutputs().isEmpty()) {
				Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
						.getToAddress(networkParameters);
				// check one of user get the winnerAmount
				Map<String, BigInteger> endMap = new HashMap<>();
				check(ulist, endMap);
				// List<UTXO> utxos = getBalance(false, ulist);
				assertTrue(endMap.get(winnerAddress.toString()) != null);

				assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));
			}
		}
	}

	public void check(List<ECKey> ulist, Map<String, BigInteger> map) throws Exception {

		List<UTXO> utxos = getBalance(false, ulist);
		List<UTXO> ylist = utxos.stream().filter(u -> u.getTokenId().equals(yuanTokenPub)).collect(Collectors.toList());
		for (UTXO u : ylist) {
		//	log.debug(u.toString());
			BigInteger p = map.get(u.getAddress());
			if (p != null) {
				map.put(u.getAddress(), p.add(u.getValue().getValue()));
			} else {
				map.put(u.getAddress(), u.getValue().getValue());
			}

		}

	}

	@Test
	public void testlotteryRepeat() throws Exception {
		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		payContract(ulist, blocks, true);
	}

	@Test
	public void lotteryConflict() throws Exception {
		// create two blocks for the ContractExecution and only one is taken

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		boolean win = false;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contractKey.getPublicKeyAsHex());

			if (win) {
				// create conflict
				Block conflictBlock = contractExecutionService.createContractExecution(
						cacheBlockPrototypeService.getBlockPrototype(store), contractKey.getPublicKeyAsHex(), store);
				if (conflictBlock != null) {
					blockSaveService.saveBlock(conflictBlock, store);
					makeRewardBlock(conflictBlock);
				}
			}

			Block resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(),
					store);

			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
						cacheBlockService).executeContract(resultBlock, store, result.getContracttokenid(),
								store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				makeRewardBlock(resultBlock);
				assertTrue(resultBlock != null);
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);
					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					// List<UTXO> utxos = getBalance(false, ulist);
					assertTrue(endMap.get(winnerAddress.toString()) != null);
					assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));
					win = true;

				}
			}
		}

	}

	@Test
	public void multipleExecutions() throws Exception {
		// multiple executions of contract confirms without reward and then do rewards
		// chain to take all.
		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		Block resultBlock = null;
		int count = 0;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contractKey.getPublicKeyAsHex());
			count++;
			log.debug(" count " + count + " payContract " + key.toString());
			resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);

			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
						cacheBlockService).executeContract(resultBlock, store, result.getContracttokenid(),
								store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				// confirm the contract execution
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
						.confirmContractExecute(resultBlock, store);
				mcmcServiceUpdate();
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);

					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);

					// List<UTXO> utxos = getBalance(false, ulist);
					assertTrue(endMap.get(winnerAddress.toString()) != null);
					log.debug("endMap.get(winnerAddress.toString())=" + winnerAddress.toString() + " = "
							+ endMap.get(winnerAddress.toString()));
					assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));

				}
			}
		}
		makeRewardBlock(resultBlock);
		ContractExecutionResult result = new ContractExecutionResult()
				.parse(resultBlock.getTransactions().get(0).getData());

		ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService)
				.executeContract(resultBlock, store, result.getContracttokenid(),
						store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());

		if (!check.getOutputTx().getOutputs().isEmpty()) {
			Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey().getToAddress(networkParameters);
			// check one of user get the winnerAmount
			Map<String, BigInteger> endMap = new HashMap<>();
			check(ulist, endMap);
			// List<UTXO> utxos = getBalance(false, ulist);
			assertTrue(endMap.get(winnerAddress.toString()) != null);
			assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));

		}

	}

	public void ordermatch(List<Block> a1) throws Exception {
		// payMoneyToWallet1(a1);
		sell(a1);
		buy(a1);
		// Generate mining reward block
		makeOrderExecutionAndReward(a1);

	}

	public void contractExecution(List<Block> a1) throws Exception {
		contractExecution(a1, false);
	}

	public void contractExecution(List<Block> a1, boolean confirm) throws Exception {

		Block resultBlock = null;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			a1.add(w.payContract(null, yuanTokenPub, payContractAmount, null, null, contractKey.getPublicKeyAsHex()));

			resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);
			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ServiceContract serviceContract = new ServiceContract(serverConfiguration, networkParameters,
						cacheBlockService);
				ContractExecutionResult check = serviceContract.executeContract(resultBlock, store,
						result.getContracttokenid(), store.getContractresult(result.getPrevblockhash()),
						result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				a1.add(resultBlock);
				assertTrue(resultBlock != null);
				if (confirm) {
					serviceContract.confirmContractExecute(resultBlock, store);
				}
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);
					// confirm the contract execution
					rewardWithBlock(a1, resultBlock);
					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					assertTrue(endMap.get(winnerAddress.toString()) != null);

					// assertTrue(endMap.get(winnerAddress.toString()).equals(new
					// BigInteger(winnerAmount)));
				}

			}
		}

	}

	@Test
	// the switch to longest chain
	public void testReorgMiningReward() throws Exception {
		List<Block> a1 = new ArrayList<Block>();
		List<Block> a2 = new ArrayList<Block>();

		prepare(a1);
		checkSum();
		for (int i = 0; i < 1; i++) {
			contractExecution(a1);
		}
		ordermatch(a1);
		checkSum();
		resetStore();

		// second chain
		prepare("800", a2);
		for (int i = 0; i < 2; i++) {
			contractExecution(a2);
		}
		ordermatch(a1);
		checkSum();

		// replay
		resetStore();

		// replay first chain
		for (Block b : a1) {
			if (b != null)
				blockGraph.add(b, true, true, store);
		}
		checkSum();
		// replay second chain
		for (Block b : a2) {
			if (b != null)
				blockGraph.add(b, true, true, store);

		}

		checkSum();
		// replay second and then replay first
		resetStore();
		for (Block b : a2) {
			if (b != null)
				blockGraph.add(b, true, true, store);

		}
		for (Block b : a1) {
			if (b != null)
				blockGraph.add(b, true, true, store);
		}

		// assertTrue(hash.equals(checkpointService.checkToken(store).hash()));
		checkSum();
		// assertTrue(hash1.equals(hash2));
	}

	@Test
	public void unconfirmEvent() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			Block event = w.payContract(null, yuanTokenPub, payContractAmount, null, null,
					contractKey.getPublicKeyAsHex());
			// List<Contractresult> prev =
			// store.getContractresultUnspent(contractKey.getPublicKeyAsHex());

			Block resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(),
					store);

			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
						cacheBlockService).executeContract(resultBlock, store, result.getContracttokenid(),
								store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				makeRewardBlock(resultBlock);
				assertTrue(resultBlock != null);
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);
					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					// List<UTXO> utxos = getBalance(false, ulist);
					assertTrue(endMap.get(winnerAddress.toString()) != null);
					assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));

					// unconfirm an execution will not lead to unconfirm execution result
					new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
							.unconfirm(resultBlock.getHash(), new HashSet<>(), store);

					endMap = new HashMap<>();
					check(ulist, endMap);

					assertTrue(endMap.get(winnerAddress.toString()) == null);

				}
			}
		}

	}

	@Test
	public void testUnconfirmChain() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		Block resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);

		if (resultBlock != null) {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(resultBlock.getTransactions().get(0).getData());

			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
					cacheBlockService).executeContract(resultBlock, store, result.getContracttokenid(),
							store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
			blockSaveService.saveBlock(resultBlock, store);
			makeRewardBlock(resultBlock);
			assertTrue(resultBlock != null);
			if (!check.getOutputTx().getOutputs().isEmpty()) {
				Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
						.getToAddress(networkParameters);

				makeRewardBlock(resultBlock);
				// check one of user get the winnerAmount
				Map<String, BigInteger> endMap = new HashMap<>();
				check(ulist, endMap);
				// List<UTXO> utxos = getBalance(false, ulist);
				assertTrue(endMap.get(winnerAddress.toString()) != null);

				assertTrue(endMap.get(winnerAddress.toString()).equals(new BigInteger(winnerAmount)));

				// Unconfirm
				new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService)
						.unconfirmRecursive(resultBlock.getHash(), new HashSet<>(), store);
				// Winner Should be unconfirmed now
				endMap = new HashMap<>();
				check(ulist, endMap);
				assertTrue(endMap.get(winnerAddress.toString()) == null);
			}
		}

	}

	@Test
	public void cancelEvent() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		Wallet w = Wallet.fromKeys(networkParameters, ulist.get(0), contextRoot);
		Block event = w.payContract(null, yuanTokenPub, payContractAmount, null, null, contractKey.getPublicKeyAsHex());

		Block resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);
		blockSaveService.saveBlock(resultBlock, store);
		makeRewardBlock(resultBlock);
		ContractExecutionResult result = new ContractExecutionResult()
				.parse(resultBlock.getTransactions().get(0).getData());
		assertTrue(result.getAllRecords().size() == 1);
		assertTrue(result.getCancelRecords().size() == 0);
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		makeContractEventCancel(event, ulist.get(0), new ArrayList<>(), predecessor);

		resultBlock = contractExecutionService.createContractExecution(contractKey.getPublicKeyAsHex(), store);
		blockSaveService.saveBlock(resultBlock, store);
		result = new ContractExecutionResult().parse(resultBlock.getTransactions().get(0).getData());
		assertTrue(result.getAllRecords().size() == 0);
		assertTrue(result.getCancelRecords().size() == 1);

	}

	public void testContractTokens(List<Block> blocksAddedAll) throws JsonProcessingException, Exception {

		String domain = "";

		TokenKeyValues tokenKeyValues = new TokenKeyValues();
		KeyValue kv = new KeyValue();
		kv.setKey("system");
		kv.setValue("java");
		tokenKeyValues.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("classname");
		kv.setValue("net.bigtangle.server.service.LotteryContract");
		tokenKeyValues.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("winnerAmount");

		kv.setValue(winnerAmount);
		tokenKeyValues.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("amount");
		kv.setValue(contractAmount);
		tokenKeyValues.addKeyvalue(kv);
		kv = new KeyValue();
		kv.setKey("token");
		kv.setValue(yuanTokenPub);
		tokenKeyValues.addKeyvalue(kv);

		blocksAddedAll
				.add(createToken(contractKey, "contractlottery", 0, domain, "contractlottery", BigInteger.valueOf(1),
						false, tokenKeyValues, TokenType.contract.ordinal(), contractKey.getPublicKeyAsHex(), wallet));

		ECKey signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));

		blocksAddedAll.add(wallet.multiSign(contractKey.getPublicKeyAsHex(), signkey, null));

		makeRewardBlock(blocksAddedAll);
	}

	/*
	 * pay money to the contract
	 */
	public Block payContract(List<ECKey> userkeys, List<Block> blocks, boolean executecontract) throws Exception {
		Block payContract = null;
		for (ECKey key : userkeys) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			payContract = w.payContract(null, yuanTokenPub, payContractAmount, null, null,
					contractKey.getPublicKeyAsHex());
			blocks.add(payContract);
			if (executecontract)
				executionAndCheck();
			else
				mcmcServiceUpdate();

		}
		return payContract;
	}

	public List<ECKey> createUserkey() {
		List<ECKey> userkeys = new ArrayList<ECKey>();
		for (int i = 0; i < usernumber; i++) {
			ECKey key = new ECKey();
			userkeys.add(key);
		}
		return userkeys;
	}

	public void testToken(List<Block> blocksAddedAll) throws Exception {
		String domain = "";
		ECKey fromPrivate = ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv));
		testCreateMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY",
				payContractAmount.multiply(BigInteger.valueOf(usernumber * 100000000l)), blocksAddedAll);
		makeRewardBlock(blocksAddedAll);
	}

	public Address getAddress() {
		return ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)).toAddress(networkParameters);
	}

	public void payBigUserKeys(List<ECKey> userkeys, Long factor, List<Block> blocksAddedAll) throws Exception {

		List<List<ECKey>> parts = Wallet.chopped(userkeys, 1000);

		for (List<ECKey> list : parts) {
			HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
			for (ECKey key : list) {
				giveMoneyResult.put(key.toAddress(networkParameters).toString(), BigInteger.valueOf(10000 * factor));
			}
			Block b = wallet.payToList(null, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, "pay big to user");
			// log.debug("block " + (b == null ? "block is null" : b.toString()));
			rewardWithBlock(blocksAddedAll, b);
		}

	}

	// create a token with multi sign
	protected void testCreateMultiSigToken(ECKey key, String tokename, int decimals, String domainname,
			String description, BigInteger amount, List<Block> blocksAddedAll)
			throws JsonProcessingException, Exception {
		try {
			wallet.setServerURL(contextRoot);
			blocksAddedAll.add(createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet));

			ECKey signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));

			blocksAddedAll.add(wallet.multiSign(key.getPublicKeyAsHex(), signkey, null));

		} catch (Exception e) {
			// TODO: handle exception
			log.warn("", e);
		}

	}

	public void payUserKeys(List<ECKey> userkeys, String factor, List<Block> blocksAddedAll) throws Exception {

		Stopwatch watch = Stopwatch.createStarted();
		List<List<ECKey>> parts = Wallet.chopped(userkeys, 1000);

		for (List<ECKey> list : parts) {
			HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
			for (ECKey key : list) {
				giveMoneyResult.put(key.toAddress(networkParameters).toString(),
						payContractAmount.multiply(new BigInteger(factor)));
			}
			Block b = wallet.payToList(null, giveMoneyResult, Utils.HEX.decode(yuanTokenPub), "pay yuan to user");
			// log.debug("block " + (b == null ? "block is null" : b.toString()));
			rewardWithBlock(blocksAddedAll, b);
		}
		log.debug("pay user " + usernumber + "  duration minutes " + watch.elapsed(TimeUnit.MINUTES));
		log.debug("rate  " + usernumber * 1.0 / watch.elapsed(TimeUnit.SECONDS));

	}

}
