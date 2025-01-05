package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.base.Stopwatch;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.TokensumsMap;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.VerificationException.InfeasiblePrototypeException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.service.base.ServiceContract;
import net.bigtangle.wallet.Wallet;

public class ContractTest extends AbstractIntegrationTest {

	protected static final Logger log = LoggerFactory.getLogger(ContractTest.class);

	@Autowired
	public NetworkParameters networkParameters;
	public static String yuanTokenPub = "02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a";
	public static String yuanTokenPriv = "8db6bd17fa4a827619e165bfd4b0f551705ef2d549a799e7f07115e5c3abad55";

	// public static String lotteryTokenPub =
	// "039aee4f0291991dd71ea0dd3c0e91ef680e769eca0326f1e36b74107aec4ac1f4";
	public static String lotteryTokenPriv = "6cecae9a820844dac41521ddad4f1b5068fdcac59ce28a6dd1ed01a12f782362";
	public ECKey contractKey = ECKey.fromPrivate(Utils.HEX.decode(lotteryTokenPriv));
	public int usernumber = 10;

	public List<ECKey> ulist;
	String winnerAmount = "10000";
	String contractAmount = "2500";
	public BigInteger payContractAmount = new BigInteger(contractAmount);
	Token contracttoken;

	public void prepare(List<Block> a1) throws Exception {
		prepare("1", a1);

	}

	public void prepare(String factor, List<Block> a1) throws Exception {
		wallet.importKey(ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)));
		createTestToken(a1);
		createTestContractTokens(a1);
		setcontracttoken();
		ulist = createUserkey();
		payUserKeys(ulist, factor, a1);
		payBigUserKeys(ulist, Long.valueOf(factor), a1);

	}

	private void executionAndCheck() throws Exception {

		Block resultBlock = contractExecutionService.createContractExecution(contracttoken, store);

		if (resultBlock != null) {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(resultBlock.getTransactions().get(0).getData());

			ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters,
					cacheBlockService, jsonmapper).executeContract(resultBlock, store, result.getContracttokenid(),
							store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
			blockSaveService.saveBlock(resultBlock, store);
			makeRewardBlock(resultBlock);
			assertTrue(getBlockWrap(resultBlock.getHash()).getBlockEvaluation().getMilestone() > 0);
			TokensumsMap c = checkSum(null);
			if (!check.getOutputTx().getOutputs().isEmpty()) {
				Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
						.getToAddress(networkParameters);
				// check one of user get the winnerAmount
				Map<String, BigInteger> endMap = new HashMap<>();
				check(ulist, endMap);
				// List<UTXO> utxos = getBalance(false, ulist);
				assertNotNull(endMap.get(winnerAddress.toString()));
				c = checkSum(c);
				assertEquals(endMap.get(winnerAddress.toString()), new BigInteger(winnerAmount));
			}
		}
	}

	private void setcontracttoken() throws BlockStoreException {
		contracttoken = store.getTokenID(contractKey.getPublicKeyAsHex()).get(0);
	}

	public void check(List<ECKey> ulist, Map<String, BigInteger> map) throws Exception {

		List<UTXO> utxos = getBalance(false, ulist);
		List<UTXO> ylist = utxos.stream().filter(u -> u.getTokenId().equals(yuanTokenPub)).toList();
		for (UTXO u : ylist) {
			// log.debug(u.toString());
			map.merge(u.getAddress(), u.getValue().getValue(), BigInteger::add);

		}

	}

	@Test
	public void testRepeat() throws Exception {
		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		payContractAndExecute(ulist, blocks, true);
		checkSum(null);
	}

	@Test
	public void testConflict() throws Exception {
		// create two blocks for the ContractExecution in conflict and only one is taken

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		Block conflictBlock = null;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());

			Block resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
			// create conflict with parallel and not save it
			conflictBlock = contractExecutionService
					.createContractExecution(cacheBlockPrototypeService.getBlockPrototype(store), contracttoken, store);
			TokensumsMap c = checkSum(null);
			if (resultBlock != null && conflictBlock != null) {

				blockSaveService.saveBlock(resultBlock, store);
				makeRewardBlock(resultBlock);

				blockSaveService.saveBlock(conflictBlock, store);
				ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService,
						jsonmapper);
				Set<BlockWrap> allApprovedNewBlocks = new HashSet<>();
				s.getBlockWrap(resultBlock.getHash(), store);
				allApprovedNewBlocks.add(s.getBlockWrap(conflictBlock.getHash(), store));
				assertTrue(s.findBlockWithSpentOrUnconfirmedInputs(allApprovedNewBlocks, store));
				c = checkSum(c);

				makeRewardBlock(conflictBlock);
				c = checkSum(c);

			}
		}

		// makeRewardBlock(conflictBlock);

	}

	@Test
	public void testConflict2() throws Exception {
		// create two blocks for the ContractExecution in conflict

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		Block conflictBlock = null;
		Block resultBlock = null;
		int count = 0;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			count++;
			resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
			// create conflict with parallel and not save it
			conflictBlock = contractExecutionService
					.createContractExecution(cacheBlockPrototypeService.getBlockPrototype(store), contracttoken, store);

			if (resultBlock != null) {
				blockSaveService.saveBlock(resultBlock, store);
				// confirm the contract execution
				confirmDo(s.getBlockWrap(resultBlock.getHash(), store), new HashSet<>(), store);
				checkSum(null);

			}
			if (count % 3 == 0) {
				blockSaveService.saveBlock(conflictBlock, store);
				confirmDo(s.getBlockWrap(conflictBlock.getHash(), store), new HashSet<>(), store);
			}
		}
		blockSaveService.saveBlock(conflictBlock, store);
		TokensumsMap c = checkSum(null);
		makeRewardBlock(conflictBlock);
		//
		c = checkSum(c);
		assertFalse(s.getBlockWrap(conflictBlock.getHash(), store).getBlockEvaluation().isConfirmed()
				&& s.getBlockWrap(resultBlock.getHash(), store).getBlockEvaluation().isConfirmed());
		checkSum(c);
		makeRewardBlock(conflictBlock);
		checkSum(c);
		blockSaveService.saveBlock(resultBlock, store);
		makeRewardBlock(resultBlock);

		checkSum(c);
	}

	@Test
	public void testOrderExecutionConflict() throws Exception {
		// create two blocks for the ContractExecution in conflict

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		Block conflictBlock = null;
		Block resultBlock = null;
		TokensumsMap c = checkSum(null);

		for (ECKey key : ulist) {
			sell(blocks);
			resultBlock = orderExecutionService.createOrderExecution(store);
			conflictBlock = orderExecutionService.createOrderExecution(store);
			if (resultBlock != null) {
				// create conflict with parallel and not save it
				// confirm the contract execution
				confirmDo(s.getBlockWrap(resultBlock.getHash(), store), new HashSet<>(), store);
				c = checkSum(c);

			}
			buy(blocks);
		}
		if (conflictBlock != null) {
			try {
			makeRewardBlock(conflictBlock);
			c = checkSum(c);
			assertFalse(s.getBlockWrap(conflictBlock.getHash(), store).getBlockEvaluation().isConfirmed()
					&& s.getBlockWrap(resultBlock.getHash(), store).getBlockEvaluation().isConfirmed());
			checkSum(c);
			makeRewardBlock(conflictBlock);
			checkSum(c);
			makeRewardBlock(resultBlock);
			checkSum(c);
			}catch (InfeasiblePrototypeException e) {
				// can happan for conflict check
				e.printStackTrace();
			}
		}
	}

	@Test
	public void testConflict3() throws Exception {
		// two execution blocks are in one reward and make conflict execution to first
		// of two and must be consistency

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		Block conflictBlock = null;
		Block resultBlock = null;
		int count = 0;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			count++;
			resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
			// create conflict with parallel and not save it
			conflictBlock = contractExecutionService
					.createContractExecution(cacheBlockPrototypeService.getBlockPrototype(store), contracttoken, store);

			if (resultBlock != null) {
				blockSaveService.saveBlock(resultBlock, store);
				// confirm the contract execution
				confirmDo(s.getBlockWrap(resultBlock.getHash(), store), new HashSet<>(), store);
				checkSum(null);

			}
			if (count % 3 == 0) {
				blockSaveService.saveBlock(conflictBlock, store);
				confirmDo(s.getBlockWrap(conflictBlock.getHash(), store), new HashSet<>(), store);
			}
		}
		blockSaveService.saveBlock(conflictBlock, store);
		TokensumsMap c = checkSum(null);
		makeRewardBlock(conflictBlock);
		//
		c = checkSum(c);
		assertFalse(s.getBlockWrap(conflictBlock.getHash(), store).getBlockEvaluation().isConfirmed()
				&& s.getBlockWrap(resultBlock.getHash(), store).getBlockEvaluation().isConfirmed());
		checkSum(c);
		makeRewardBlock(conflictBlock);
		checkSum(c);
		blockSaveService.saveBlock(resultBlock, store);
		makeRewardBlock(resultBlock);

		checkSum(c);
	}

	@Test
	public void testMultipleExecutions() throws Exception {
		// multiple executions of contract confirms without reward and then do rewards
		// chain to take all.
		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		Block resultBlock = null;
		ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		int count = 0;
		Block checkBlock = null;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			count++;
			log.debug(" count " + count + " payContract " + key.toString());
			resultBlock = contractExecutionService.createContractExecution(contracttoken, store);

			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ContractExecutionResult check = s.executeContract(resultBlock, store, result.getContracttokenid(),
						store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				// confirm the contract execution
				confirmDo(s.getBlockWrap(resultBlock.getHash(), store), new HashSet<>(), store);
				TokensumsMap c = checkSum(null);
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);

					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					c = checkSum(c);
					// List<UTXO> utxos = getBalance(false, ulist);
					assertNotNull(endMap.get(winnerAddress.toString()));
					log.debug("endMap.get(winnerAddress.toString())=" + winnerAddress + " = "
							+ endMap.get(winnerAddress.toString()));
					assertEquals(endMap.get(winnerAddress.toString()), new BigInteger(winnerAmount));

				}
			}
			if (count == ulist.size() - 4) {
				checkBlock = resultBlock;
			}
		}
		TokensumsMap c = checkSum(null);

		// take the check block to reward, all other confirmed execution are
		makeRewardBlock(checkBlock);
		c = checkSum(c);
		ContractExecutionResult result = new ContractExecutionResult()
				.parse(checkBlock.getTransactions().get(0).getData());

		ContractExecutionResult check = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper).executeContract(resultBlock, store, result.getContracttokenid(),
						store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());

		if (!check.getOutputTx().getOutputs().isEmpty()) {
			Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey().getToAddress(networkParameters);
			// check one of user get the winnerAmount
			Map<String, BigInteger> endMap = new HashMap<>();
			check(ulist, endMap);
			// List<UTXO> utxos = getBalance(false, ulist);
			assertNotNull(endMap.get(winnerAddress.toString()));
			assertEquals(endMap.get(winnerAddress.toString()), new BigInteger(winnerAmount));

		}
		c = checkSum(c);
	}

	public void ordermatch(List<Block> a1) throws Exception {
		// payMoneyToWallet1(a1);
		sell(a1);
		// TokensumsMap a = checkSum(null);
		buy(a1);
		// a = checkSum(a);
		// Generate mining reward block
		makeOrderExecutionAndReward(a1);
		// a = checkSum(a);
	}

	public void contractExecution(List<Block> a1) throws Exception {

		Block resultBlock = null;
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			Block event = w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			a1.add(event);
			resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ServiceContract serviceContract = new ServiceContract(serverConfiguration, networkParameters,
						cacheBlockService, jsonmapper);
				ContractExecutionResult check = serviceContract.executeContract(resultBlock, store,
						result.getContracttokenid(), store.getContractresult(result.getPrevblockhash()),
						result.getReferencedBlocks());
				blockGraph.add(resultBlock, false, store);
				rewardWithBlock(a1, resultBlock);
				assertNotNull(resultBlock);
				if (!check.getOutputTx().getOutputs().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					assertNotNull(endMap.get(winnerAddress.toString()));
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
		TokensumsMap a = checkSum(null);
		for (int i = 0; i < 1; i++) {
			contractExecution(a1);
			a = checkSum(a);
		}
		ordermatch(a1);
		// checkSum(a);
		resetStore();

		// second chain
		prepare("80", a2);
		for (int i = 0; i < 2; i++) {
			contractExecution(a2);
			// a=checkSum(a);
		}

		ordermatch(a2);
		a = checkSum(a);

		// replay
		resetStore();

		// replay first chain
		for (Block b : a1) {
			if (b != null)
				blockGraph.add(b, true, true, store);
		}
		a = checkSum(a);
		// replay second chain
		for (Block b : a2) {
			if (b != null)
				blockGraph.add(b, true, true, store);
			a = checkSum(a);
		}

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
		checkSum(a);
		// assertTrue(hash1.equals(hash2));
	}

	@Test
	public void testPay() throws Exception {
		List<Block> blocks = new ArrayList<>();
		prepare("122000", blocks);
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);

			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());

			Block b2 = contractExecutionService
					.createContractExecution(store.getTokenTypeList(TokenType.contract.ordinal()).get(0), store);
			blockSaveService.saveBlock(b2, store);
			mcmcServiceUpdate();

			boolean hit2 = false;
			for (int i = 0; i < 10; i++) {
				Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
				log.debug(tips.getLeft().toString());
				log.debug(tips.getRight().toString());
				hit2 |= tips.getLeft().getBlockHash().equals(b2.getHash())
						|| tips.getRight().getBlockHash().equals(b2.getHash());

				if (hit2)
					break;
			}
			assertTrue(hit2);

			makeRewardBlock(b2);

			// createDAG("testPay");
		}
	}

	@Test
	public void testUnconfirmExecution() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		TokensumsMap c = checkSum(null);
		ServiceContract s = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		for (ECKey key : ulist) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			c = checkSum(c);
			Block resultBlock = contractExecutionService.createContractExecution(contracttoken, store);

			if (resultBlock != null) {
				ContractExecutionResult result = new ContractExecutionResult()
						.parse(resultBlock.getTransactions().get(0).getData());

				ContractExecutionResult check = s.executeContract(resultBlock, store, result.getContracttokenid(),
						store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
				blockSaveService.saveBlock(resultBlock, store);
				confirmDo(s.getBlockWrap(resultBlock.getHash(), store), new HashSet<>(), store);

				c = checkSum(c);
				assertNotNull(resultBlock);

				if (!check.getOutputTx().getOutputs().isEmpty() && !check.getReferencedBlocks().isEmpty()) {
					Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
							.getToAddress(networkParameters);
					// check one of user get the winnerAmount
					Map<String, BigInteger> endMap = new HashMap<>();
					check(ulist, endMap);
					// List<UTXO> utxos = getBalance(false, ulist);
					assertNotNull(endMap.get(winnerAddress.toString()));
					assertEquals(new BigInteger(winnerAmount), endMap.get(winnerAddress.toString()));
					c = checkSum(c);
					// unconfirm an execution will not lead to unconfirm execution result
					unconfirmDo(resultBlock.getHash(), new HashSet<>(), store);
					c = checkSum(c);
					endMap = new HashMap<>();
					check(ulist, endMap);
					// unconfirm check the winner
			//		assertTrue(!new BigInteger(winnerAmount).equals(endMap.get(winnerAddress.toString())));

				
				}
			}
		}

	}

	@Test
	public void testUnconfirmChain() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);
		ServiceContract serviceBase = new ServiceContract(serverConfiguration, networkParameters, cacheBlockService,
				jsonmapper);

		Block resultBlock = contractExecutionService.createContractExecution(contracttoken, store);

		if (resultBlock != null) {
			ContractExecutionResult result = new ContractExecutionResult()
					.parse(resultBlock.getTransactions().get(0).getData());

			ContractExecutionResult check = serviceBase.executeContract(resultBlock, store, result.getContracttokenid(),
					store.getContractresult(result.getPrevblockhash()), result.getReferencedBlocks());
			blockSaveService.saveBlock(resultBlock, store);
			makeRewardBlock(resultBlock);
			assertNotNull(resultBlock);
			if (!check.getOutputTx().getOutputs().isEmpty()) {
				Address winnerAddress = check.getOutputTx().getOutput(0).getScriptPubKey()
						.getToAddress(networkParameters);

				makeRewardBlock(resultBlock);
				// check one of user get the winnerAmount
				Map<String, BigInteger> endMap = new HashMap<>();
				check(ulist, endMap);
				// List<UTXO> utxos = getBalance(false, ulist);
				assertNotNull(endMap.get(winnerAddress.toString()));

				assertEquals(endMap.get(winnerAddress.toString()), new BigInteger(winnerAmount));

				// Unconfirm
				unconfirmDo(resultBlock.getHash(), new HashSet<>(), store);
				// Winner Should be unconfirmed now
				endMap = new HashMap<>();
				check(ulist, endMap);
				assertNull(endMap.get(winnerAddress.toString()));
			}
		}
		checkSum(null);
	}

	@Test
	public void testCancelEvent() throws Exception {

		List<Block> blocks = new ArrayList<>();
		prepare(blocks);

		Wallet w = Wallet.fromKeys(networkParameters, ulist.get(0), contextRoot);
		Block event = w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());

		Block resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
		blockSaveService.saveBlock(resultBlock, store);
		makeRewardBlock(resultBlock);
		ContractExecutionResult result = new ContractExecutionResult()
				.parse(resultBlock.getTransactions().get(0).getData());
		assertEquals(1, result.getToBeSpent().size());
		assertEquals(0, result.getCancelRecords().size());
		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		makeContractEventCancel(event, ulist.get(0), new ArrayList<>(), predecessor);

		resultBlock = contractExecutionService.createContractExecution(contracttoken, store);
		blockSaveService.saveBlock(resultBlock, store);
		result = new ContractExecutionResult().parse(resultBlock.getTransactions().get(0).getData());
		assertEquals(0, result.getToBeSpent().size());
		assertEquals(1, result.getCancelRecords().size());
		checkSum(null);
	}

	public void createTestContractTokens(List<Block> blocksAddedAll) throws Exception {

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

		createToken(contractKey, "contractlottery", 0, domain, "contractlottery", BigInteger.valueOf(1), false,
				tokenKeyValues, TokenType.contract.ordinal(), contractKey.getPublicKeyAsHex(), wallet);

		ECKey signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));

		rewardWithBlock(blocksAddedAll, wallet.multiSign(contractKey.getPublicKeyAsHex(), signkey, null));
	}

	/*
	 * pay money to the contract
	 */
	public Block payContractAndExecute(List<ECKey> userkeys, List<Block> blocks, boolean executecontract)
			throws Exception {
		Block payContract = null;
		for (ECKey key : userkeys) {
			Wallet w = Wallet.fromKeys(networkParameters, key, contextRoot);
			payContract = w.payContract(null, yuanTokenPub, payContractAmount, null, null, contracttoken.getTokenid());
			blocks.add(payContract);
			if (executecontract)
				executionAndCheck();
			else
				mcmcServiceUpdate();

		}
		return payContract;
	}

	public List<ECKey> createUserkeyNew() {
		List<ECKey> userkeys = new ArrayList<ECKey>();
		for (int i = 0; i < usernumber; i++) {
			ECKey key = new ECKey();
			userkeys.add(key);
			log.debug(key.getPrivateKeyAsHex());
		}
		return userkeys;
	}

	public void createTestToken(List<Block> blocksAddedAll) throws Exception {
		String domain = "";
		ECKey fromPrivate = ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv));
		createMultiSigToken(fromPrivate, "人民币", 2, domain, "人民币 CNY",
				payContractAmount.multiply(BigInteger.valueOf(usernumber * 100000000L)), blocksAddedAll);

	}

	public Address getAddress() {
		return ECKey.fromPrivate(Utils.HEX.decode(yuanTokenPriv)).toAddress(networkParameters);
	}

	public void payBigUserKeys(List<ECKey> userkeys, Long factor, List<Block> blocksAddedAll) throws Exception {

		List<List<ECKey>> parts = Wallet.chopped(userkeys, 1000);

		for (List<ECKey> list : parts) {
			HashMap<String, BigInteger> giveMoneyResult = new HashMap<>();
			for (ECKey key : list) {
				giveMoneyResult.put(key.toAddress(networkParameters).toString(), BigInteger.valueOf(123456 * factor));
			}
			Block b = wallet.payToList(null, giveMoneyResult, NetworkParameters.BIGTANGLE_TOKENID, "pay big to user");
			// log.debug("block " + (b == null ? "block is null" : b.toString()));
			rewardWithBlock(blocksAddedAll, b);
		}

	}

	// create a token with multi sign
	protected void createMultiSigToken(ECKey key, String tokename, int decimals, String domainname, String description,
			BigInteger amount, List<Block> blocksAddedAll) throws Exception {
		try {
			wallet.setServerURL(contextRoot);
			createToken(key, tokename, decimals, domainname, description, amount, true, null,
					TokenType.identity.ordinal(), key.getPublicKeyAsHex(), wallet);

			ECKey signkey = ECKey.fromPrivate(Utils.HEX.decode(testPriv));

			rewardWithBlock(blocksAddedAll, wallet.multiSign(key.getPublicKeyAsHex(), signkey, null));
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
