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
import net.bigtangle.core.ECKey;
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
import net.bigtangle.exception.ScriptException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.exception.VerificationException.CoinbaseDisallowedException;
import net.bigtangle.exception.VerificationException.DifficultyConsensusInheritanceException;
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
import net.bigtangle.exception.VerificationException.ProofOfWorkException;
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

public class ValidatorServiceTest extends AbstractIntegrationTest {

	@Test
	public void testVerificationFutureTimestamp() throws Exception {

		Pair<BlockWrap, BlockWrap> tipsToApprove = tipsService.getValidatedBlockPair(store);
		Block r1 = tipsToApprove.getLeft().getBlock();
		Block r2 = tipsToApprove.getRight().getBlock();
		Block b = UtilsTest.createBlock(networkParameters, r2, r1);
		b.setTime(1887836800); //
		b.solve();
		try {
			blockSaveService.saveBlock(b, store);
			fail();
		} catch (TimeTravelerException e) {
		}
	}

	@Test
	public void testAdjustTimestamp() throws Exception {

		Block r1 = UtilGeneseBlock.createGenesis(networkParameters);
		Block r2 = UtilGeneseBlock.createGenesis(networkParameters);
		Block b = UtilsTest.createBlock(networkParameters, r2, r1);
		b.setTime(1567836800); //
		b.addTransaction(wallet.feeTransaction(null));
		b.solve();

		// Populate tips queue for adjustPrototype
		try {
			mcmcService.update(store);
		} catch (Exception e) {
			// If update fails, continue anyway
		}

		blockServiceCreate.adjustPrototype(b, store);
		blockSaveService.saveBlock(b, store);
	}

	@Test
	public void testVerificationIncorrectPoW() throws Exception {

		Pair<BlockWrap, BlockWrap> tipsToApprove = tipsService.getValidatedBlockPair(store);
		Block r1 = tipsToApprove.getLeft().getBlock();
		Block r2 = tipsToApprove.getRight().getBlock();
		Block b = UtilsTest.createBlock(networkParameters, r2, r1);
		for (int i = 0; i < 300; i++) {
			b.setNonce(i);
			try {
				b.verifyHeader();
			} catch (ProofOfWorkException e) {
				break;
			}
		}
		try {
			blockSaveService.saveBlock(b, store);
			fail();
		} catch (ProofOfWorkException e) {
		}
	}

	@Test
	public void testUnsolidBlockAllowed() throws Exception {

		Sha256Hash sha256Hash1 = getRandomSha256Hash();
		Sha256Hash sha256Hash2 = getRandomSha256Hash();
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setPrevBlockHash(sha256Hash1);
		block.setPrevBranchBlockHash(sha256Hash2);
		block.solve();
		System.out.println(block.getHashAsString());

		// Send over kafka method to allow unsolids
		blockService.addConnected(block.bitcoinSerialize(), true);
	}

	@Test
	public void testUnsolidBlockDisallowed() throws Exception {

		Sha256Hash sha256Hash1 = getRandomSha256Hash();
		Sha256Hash sha256Hash2 = getRandomSha256Hash();
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setPrevBlockHash(sha256Hash1);
		block.setPrevBranchBlockHash(sha256Hash2);
		block.solve();
		System.out.println(block.getHashAsString());

		// Send over API method to disallow unsolids
		try {
			blockSaveService.saveBlock(block, store);
			fail();
		} catch (VerificationException e) {
			// Expected
		}

		// Should not be added since insolid
		assertNull(store.get(block.getHash()));
	}

	@Test
	public void testUnsolidBlockReconnectBlock() throws Exception {

		Block depBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		depBlock.addTransaction(wallet.feeTransaction(null));
		depBlock.solve();
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
		block.solve();
		blockService.addConnected(block.bitcoinSerialize(), true);

		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockSaveService.saveBlock(depBlock, store);

		// After adding the missing dependency, should be solid

		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testUnsolidMissingPredecessor1() throws Exception {

		Block depBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		depBlock.addTransaction(wallet.feeTransaction(null));
		depBlock.solve();
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
		block.solve();
		blockService.addConnected(block.bitcoinSerialize(), true);
		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockSaveService.saveBlock(depBlock, store);

		// After adding the missing dependency, should be solid
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testUnsolidMissingPredecessor2() throws Exception {

		Block depBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		depBlock.addTransaction(wallet.feeTransaction(null));
		depBlock.solve();
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
		block.solve();
		blockService.addConnected(block.bitcoinSerialize(), true);

		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockSaveService.saveBlock(depBlock, store);

		// After adding the missing dependency, should be solid

		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testSameUTXOInput() throws Exception {

		// use the same input data for other transaction in a block double spent
		Transaction tx1 = createTestTransaction();
		Block block1 = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block1.addTransaction(tx1);
		block1.addTransaction(wallet.feeTransaction(null));
		block1 = adjustSolve(block1);
		try {
			this.blockGraph.addBlock(block1, false, store);
			fail();
		} catch (Exception e) {
			// TODO: handle exception
		}

	}

	@Test
	public void testUnsolidMissingUTXO() throws Exception {

		// Create block with UTXO
		Transaction tx1 = createTestTransaction();
		Block depBlock = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);
		Block confBlock = makeRewardBlock();

		// Create block with dependency

		Transaction tx2 = createTestTransaction();
		Block block = createAndAddNextBlockWithTransaction(confBlock, confBlock, tx2);

		resetStore();

		// Add block allowing unsolids
		blockService.addConnected(confBlock.bitcoinSerialize(), false);
		blockService.addConnected(block.bitcoinSerialize(), true);

		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockSaveService.saveBlock(depBlock, store);
		blockSaveService.saveBlock(confBlock, store);

		// After adding the missing dependency, should be solid
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);

		// TODO
		// assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid()
		// == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testUnsolidMissingReward() throws Exception {

		List<Block> blocksAddedAll = new ArrayList<Block>();
		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate eligible mining reward block
		Block rewardBlock1 = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), null, false, store);
		blockSaveService.saveBlock(rewardBlock1, store);
		blockGraph.updateChain();

		// Mining reward block should go through
		assertTrue(getBlockEvaluation(rewardBlock1.getHash(), store).isConfirmed());

		// Generate eligible second mining reward block
 
		Block rewardBlock2 = rewardService.createMiningRewardBlock(rewardBlock1.getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), null, false, store);
	
		blockSaveService.saveBlock(rewardBlock1, store);
		blockGraph.updateChain();


		resetStore();
		for (Block b : blocksAddedAll) {
			blockGraph.addBlock(b, true, store);
		}
		// Add block allowing unsolids
		blockService.addConnected(rewardBlock2.bitcoinSerialize(), true);
		blockGraph.updateChain();
		// Should not be solid
		assertTrue(store.getBlockWrap(rewardBlock2.getHash()) == null);

		// Add missing dependency
		blockSaveService.saveBlock(rewardBlock1, store);

		blockGraph.updateChain();
		// After adding the missing dependency, should be solid
	 add(rewardBlock2, true, true, store);
		syncBlockService.connectingOrphans(store);
		blockGraph.updateChain();
		assertTrue(store.getBlockWrap(rewardBlock2.getHash()).getBlockEvaluation().getSolid() == 2);
		assertTrue(store.getBlockWrap(rewardBlock1.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testUnsolidMissingToken() throws Exception {

		// Generate an eligible issuance
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		TokenInfo tokenInfo = new TokenInfo();
		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		Block depBlock = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null, null, null, false);
		mcmcServiceUpdate();
		// Generate second eligible issuance
		TokenInfo tokenInfo2 = new TokenInfo();
		Token tokens2 = Token.buildSimpleTokenInfo(true, depBlock.getHash(), Utils.HEX.encode(pubKey), "Test", "Test",
				1, 1, coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));

		Block block = saveTokenUnitTestWithTokenname(tokenInfo2, coinbase, outKey, null);

		resetStore();

		// Add block allowing unsolids
		blockService.addConnected(block.bitcoinSerialize(), true);

		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockSaveService.saveBlock(depBlock, store);

		// After adding the missing dependency, should be solid

		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);

		// There are prev not there TODO
		// assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid()
		// == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testSolidityPredecessorConsensusInheritance() throws Exception {

		// Generate blocks until passing first reward interval and second reward
		// interval
		List<Block> blocksAddedAll = new ArrayList<Block>();
		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate eligible mining reward block
		Block rewardBlock1 = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), null, false, store);

		// The consensus number should now be equal to the previous number + 1
		assertEquals(rollingBlock.getLastMiningRewardBlock() + 1, rewardBlock1.getLastMiningRewardBlock());

		try {
			Block failingBlock = UtilsTest.createBlock(networkParameters, rollingBlock,
					UtilGeneseBlock.createGenesis(networkParameters));
			failingBlock.setLastMiningRewardBlock(2);
			failingBlock.addTransaction(wallet.feeTransaction(null));
			failingBlock.solve();
			blockGraph.addBlock(failingBlock, false, store);
			fail();
		} catch (DifficultyConsensusInheritanceException e) {
			// Expected
		}

		try {
			Block failingBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
					rollingBlock);
			failingBlock.setLastMiningRewardBlock(2);
			failingBlock.addTransaction(wallet.feeTransaction(null));
			failingBlock.solve();
			blockGraph.addBlock(failingBlock, false, store);
			fail();
		} catch (DifficultyConsensusInheritanceException e) {
			// Expected
		}

	}

	@Test
	public void testSolidityPredecessorTimeInheritance() throws Exception {

		// Generate blocks until passing first reward interval and second reward
		// interval
		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// The time is allowed to stay the same
		rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
		rollingBlock.setTime(rollingBlock.getTimeSeconds()); // 01/01/2000 @
		rollingBlock.addTransaction(wallet.feeTransaction(null)); // 12:00am (UTC)
		rollingBlock.solve();
		blockGraph.addBlock(rollingBlock, false, store);
		makeRewardBlock(rollingBlock);
		// The time is not allowed to move backwards
		try {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			rollingBlock.setTime(946684800); // 01/01/2000 @ 12:00am (UTC)
			rollingBlock.addTransaction(wallet.feeTransaction(null));
			rollingBlock.solve();
			blockGraph.addBlock(rollingBlock, false, store);
			fail();
		} catch (TimeReversionException e) {
		}
	}

	@Test
	public void testSolidityCoinbaseDisallowed() throws Exception {

		final Block genesisBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// For disallowed types: coinbases are not allowed
		for (BlockType type : BlockType.values()) {
			if (!type.allowCoinbaseTransaction())
				try {
					// Build transaction
					Transaction tx = new Transaction(networkParameters);
					tx.addOutput(Coin.COIN.times(2), new ECKey().toAddress(networkParameters));

					// The input does not really need to be a valid signature,
					// as long
					// as it has the right general form and is slightly
					// different for
					// different tx
					TransactionInput input =   TransactionInput.fromScriptBytes(networkParameters, tx, Script
							.createInputScript(genesisBlock.getHash().getBytes(), genesisBlock.getHash().getBytes()));
					tx.addInput(input);

					// Check it fails
					Block rollingBlock = UtilsTest.createBlock(networkParameters, genesisBlock, genesisBlock);
					rollingBlock.setBlockType(type);
					rollingBlock.addTransaction(tx);
					rollingBlock.solve();
					blockGraph.addBlock(rollingBlock, false, store);

					fail();
				} catch (CoinbaseDisallowedException | UnsolidException e) {
				}
		}
	}

	@Test
	public void testSolidityTXDoubleSpend() throws Exception {

		// Create block with UTXOs
		Transaction tx1 = createTestTransaction();
		Block spenderBlock1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);
		// Confirm 1
		makeRewardBlock(spenderBlock1);
		Block spenderBlock2 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);
		// 1 should be confirmed now
		UTXO utxo1 = getUTXO(tx1.getOutput(0).getOutPointFor(spenderBlock1.getHash()), store);
		UTXO utxo2 = getUTXO(tx1.getOutput(1).getOutPointFor(spenderBlock1.getHash()), store);
		assertTrue(utxo1.isConfirmed() || utxo2.isConfirmed());

		assertFalse(utxo1.isSpent());
		assertFalse(utxo2.isSpent());

		// 2 should be unconfirmed
		utxo1 = getUTXO(tx1.getOutput(0).getOutPointFor(spenderBlock2.getHash()), store);
		utxo2 = getUTXO(tx1.getOutput(1).getOutPointFor(spenderBlock2.getHash()), store);
		assertFalse(utxo1.isConfirmed());
		assertFalse(utxo2.isConfirmed());
		assertFalse(utxo1.isSpent());
		assertFalse(utxo2.isSpent());

		// Further manipulations on prev UTXOs
		UTXO origUTXO = store.getTransactionOutput(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				UtilGeneseBlock.createGenesis(networkParameters).getTransactions().get(0).getHash(), 0L);
		assertTrue(origUTXO.isConfirmed());
		assertTrue(origUTXO.isSpent());
		assertEquals(
				store.getTransactionOutputSpender(origUTXO.getBlockHash(), origUTXO.getTxHash(), origUTXO.getIndex())
						.getBlockHash(),
				spenderBlock1.getHash());

	}

	@Test
	public void testSolidityTXInputScriptsCorrect() throws Exception {

		// Create block with UTXO
		Transaction tx1 = createTestTransaction();
		createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters), UtilGeneseBlock.createGenesis(networkParameters),
				tx1);

		resetStore();

		// Again but with incorrect input script
		try {
			tx1.getInput(0).setScriptSig(new Script(new byte[0]));
			Block block1 = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters));
			block1.addTransaction(tx1);
			block1 = adjustSolve(block1);
			this.blockGraph.addBlock(block1, false, store);
			fail();
		} catch (ScriptException e) {
		}
	}

	// TODO @Test
	public void testSolidityTXOutputSumCorrect() throws Exception {

		// Create block with UTXO
		{
			Transaction tx1 = createTestTransaction();
			createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters), tx1);
		}

		resetStore();

		// Again but with less output coins
		{

			ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
					Utils.HEX.decode(testPub));
			List<UTXO> outputs = getBalance(false, testKey);
			TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters,
					outputs.get(0));
			Coin amount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
			Transaction tx2 = new Transaction(networkParameters);
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2, amount, testKey));
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2,
					spendableOutput.getValue().subtract(amount).subtract(amount), testKey));
			TransactionInput input = tx2.addInput(outputs.get(0).getBlockHash(), spendableOutput);
			Sha256Hash sighash = tx2.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
					false);
			TransactionSignature sig = new TransactionSignature(testKey.sign(sighash), Transaction.SigHash.ALL, false);
			Script inputScript = ScriptBuilder.createInputScript(sig);
			input.setScriptSig(inputScript);
			createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters), tx2);
		}

		resetStore();

		// Again but with more output coins
		try {

			ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
					Utils.HEX.decode(testPub));
			List<UTXO> outputs = getBalance(false, testKey);
			TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters,
					outputs.get(0));
			Coin amount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
			Transaction tx2 = new Transaction(networkParameters);
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2, amount, testKey));
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2,
					spendableOutput.getValue().subtract(Coin.FEE_DEFAULT), testKey));
			TransactionInput input = tx2.addInput(outputs.get(0).getBlockHash(), spendableOutput);
			Sha256Hash sighash = tx2.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
					false);
			TransactionSignature sig = new TransactionSignature(testKey.sign(sighash), Transaction.SigHash.ALL, false);
			Script inputScript = ScriptBuilder.createInputScript(sig);
			input.setScriptSig(inputScript);
			// tx2.getOutput(0).getValue().setValue(tx2.getOutput(0).getValue().getValue().add(BigInteger.valueOf(1)));
			Block block1 = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters));
			block1.addTransaction(tx2);

			block1 = adjustSolve(block1);
			this.blockGraph.addBlock(block1, false, store);
			fail();
		} catch (InvalidTransactionException e) {
		}
	}

	@Test
	public void testSolidityTXOutputNonNegative() throws Exception {

		// Create block with negative outputs
		try {

			ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
					Utils.HEX.decode(testPub));
			List<UTXO> outputs = getBalance(false, testKey);
			TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters,
					outputs.get(0));
			Coin amount = Coin.valueOf(-1, NetworkParameters.BIGTANGLE_TOKENID);
			Transaction tx2 = new Transaction(networkParameters);
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2, amount, testKey));
			tx2.addOutput(
					TransactionOutput.fromCoinKey(networkParameters, tx2, spendableOutput.getValue().minus(amount), testKey));
			TransactionInput input = tx2.addInput(outputs.get(0).getBlockHash(), spendableOutput);
			Sha256Hash sighash = tx2.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL,
					false);
			TransactionSignature sig = new TransactionSignature(testKey.sign(sighash), Transaction.SigHash.ALL, false);
			Script inputScript = ScriptBuilder.createInputScript(sig);
			input.setScriptSig(inputScript);
			createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters), tx2);
			fail();
		} catch (NegativeValueOutput e) {
			// Expected
		}
		  catch (VerificationException e) {
			// Expected
		}
	}

	@Test
	public void testSolidityNewGenesis() throws Exception {

		// Create genesis block
		try {
			Block b = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters));
			b.setBlockType(BlockType.BLOCKTYPE_INITIAL);
			b.solve();
			blockGraph.addBlock(b, false, store);
			fail();
		} catch (GenesisBlockDisallowedException e) {
		}
	}

	@Test
	public void testSoliditySigOps() throws Exception {

		// Create block with outputs
		try {

			ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
					Utils.HEX.decode(testPub));
			List<UTXO> outputs = getBalance(false, testKey);
			TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters,
					outputs.get(0));
			Coin amount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);
			Transaction tx2 = new Transaction(networkParameters);
			tx2.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx2, amount, testKey));
			tx2.addOutput(
					TransactionOutput.fromCoinKey(networkParameters, tx2, spendableOutput.getValue().minus(amount), testKey));
			TransactionInput input = tx2.addInput(outputs.get(0).getBlockHash(), spendableOutput);

			ScriptBuilder scriptBuilder = new ScriptBuilder();
			for (int i = 0; i < NetworkParameters.MAX_BLOCK_SIGOPS + 1; i++)
				scriptBuilder.op(0xac);

			Script inputScript = scriptBuilder.build();
			input.setScriptSig(inputScript);
			createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters), tx2);
			fail();
		} catch (SigOpsException e) {
		}
	}

	@Test
	public void testSolidityRewardTxWrongDifficulty() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 20; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with spending inputs
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		rewardBlock.setDifficultyTarget(rollingBlock.getDifficultyTarget() * 2);

		// Should not go through
		try {
			rewardBlock.solve();
			blockGraph.addBlock(rewardBlock, false, store);
			fail();
		} catch (VerificationException e) {

		}

	}

	@Test
	public void testSolidityRewardTxWithTransfers1() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 1 + 1 + 1; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with spending inputs
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		Transaction tx = rewardBlock.getTransactions().get(0);

		ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv), Utils.HEX.decode(testPub));
		List<UTXO> outputs = getBalance(false, testKey);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, testKey));
		tx.addOutput(
				TransactionOutput.fromCoinKey(networkParameters, tx, spendableOutput.getValue().subtract(amount), testKey));
		TransactionInput input = tx.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = tx.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL, false);

		TransactionSignature sig = new TransactionSignature(testKey.sign(sighash), Transaction.SigHash.ALL, false);
		Script inputScript = ScriptBuilder.createInputScript(sig);
		input.setScriptSig(inputScript);
		rewardBlock.solve();

		// Should not go through
		try {
			 add(rewardBlock, false, true, store);

			fail();
		} catch (TransactionOutputsDisallowedException e) {
		}
	}

	@Test
	public void testSolidityRewardTxWithTransfers2() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 1 + 1 + 1; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with additional tx
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		Transaction tx = createTestTransaction();
		rewardBlock.addTransaction(tx);
		rewardBlock.solve();

		// Should not go through
		try {
			 add(rewardBlock, false, true, store);

			fail();
		} catch (IncorrectTransactionCountException e) {
		}
	}

	@Test
	public void testSolidityRewardTxWithMissingRewardInfo() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 1 + 1 + 1; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with malformed tx data
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		rewardBlock.getTransactions().get(0).setData(null);
		rewardBlock.solve();

		// Should not go through
		try {
			assertFalse( add(rewardBlock, false, true, store));
			fail();
		} catch (RuntimeException e) {

		}

	}

	@Test
	public void testSolidityRewardTxMalformedData1() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate mining reward block with malformed tx data
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		rewardBlock.getTransactions().get(0).setData(new byte[] { 2, 3, 4 });

		rewardBlock.solve();

		// Should not go through
		try {
			 add(rewardBlock, false, true, store);
			fail();
		} catch (RuntimeException e) {
		}
	}

	@Test
	public void testSolidityRewardTxMalformedData2() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 1 + 1 + 1; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with malformed fields
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		blockGraph.updateChain();
		Block testBlock1 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock2 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock3 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock4 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock5 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock6 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		Block testBlock7 = networkParameters.getDefaultSerializer().makeBlock(rewardBlock.bitcoinSerialize());
		RewardInfo rewardInfo1 = new RewardInfo().parse(testBlock1.getTransactions().get(0).getData());
		RewardInfo rewardInfo2 = new RewardInfo().parse(testBlock2.getTransactions().get(0).getData());
		RewardInfo rewardInfo3 = new RewardInfo().parse(testBlock3.getTransactions().get(0).getData());
		RewardInfo rewardInfo4 = new RewardInfo().parse(testBlock4.getTransactions().get(0).getData());
		RewardInfo rewardInfo5 = new RewardInfo().parse(testBlock5.getTransactions().get(0).getData());
		RewardInfo rewardInfo6 = new RewardInfo().parse(testBlock6.getTransactions().get(0).getData());
		RewardInfo rewardInfo7 = new RewardInfo().parse(testBlock7.getTransactions().get(0).getData());
		rewardInfo3.setPrevRewardHash(getRandomSha256Hash());
		rewardInfo4.setPrevRewardHash(rollingBlock.getHash());
		rewardInfo5.setPrevRewardHash(rollingBlock.getHash());
		testBlock1.getTransactions().get(0).setData(rewardInfo1.toByteArray());
		testBlock2.getTransactions().get(0).setData(rewardInfo2.toByteArray());
		testBlock3.getTransactions().get(0).setData(rewardInfo3.toByteArray());
		testBlock4.getTransactions().get(0).setData(rewardInfo4.toByteArray());
		testBlock5.getTransactions().get(0).setData(rewardInfo5.toByteArray());
		testBlock6.getTransactions().get(0).setData(rewardInfo6.toByteArray());
		testBlock7.getTransactions().get(0).setData(rewardInfo7.toByteArray());
		testBlock1.solve();
		testBlock2.solve();
		testBlock3.solve();
		testBlock4.solve();
		testBlock5.solve();
		testBlock6.solve();
		testBlock7.solve();

		 add(testBlock3, true, true, store);
		try {
			 add(testBlock4, false, true, store);
			fail();
		} catch (VerificationException e) {
		}
		try {
			 add(testBlock5, false, true, store);
			fail();
		} catch (VerificationException e) {
		}
	}

	interface TestCase {
		public boolean expectsException();

		public void preApply(TokenInfo info);
	}

	@Test
	public void testSolidityTokenMalformedData1() throws Exception {

		// Generate an eligible issuance tokenInfo
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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
		block.solve();

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
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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
		block.solve();

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
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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
		block.solve();

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
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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
		block.solve();

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (MalformedTransactionDataException e) {
		}
	}

	@Test
	public void testSolidityTokenMutatedData() throws Exception {

		ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv), Utils.HEX.decode(testPub));

		// Generate an eligible issuance tokenInfo
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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

						tokenInfo5.getToken().setTokenid(Utils.HEX.encode(testKey.getPubKey()));
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
								.setPubKeyHex(Utils.HEX.encode(new ECKey().getPubKey()));
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

						tokenInfo5.getMultiSignAddresses().get(0).setTokenid(Utils.HEX.encode(testKey.getPubKey()));
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
				ECKey.ECDSASignature party1Signature = outKey.sign(sighash1, null);
				byte[] buf1 = party1Signature.encodeToDER();

				List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
				MultiSignBy multiSignBy0 = new MultiSignBy();
				if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
					multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
				else
					multiSignBy0.setTokenid(Utils.HEX.encode(outKey.getPubKey()));
				multiSignBy0.setTokenindex(0);
				multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
				multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
				multiSignBy0.setSignature(Utils.HEX.encode(buf1));
				multiSignBies.add(multiSignBy0);

				ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
						Utils.HEX.decode(testPub));
				ECKey.ECDSASignature party2Signature = genesiskey.sign(sighash1, aesKey);
				byte[] buf2 = party2Signature.encodeToDER();
				multiSignBy0 = new MultiSignBy();
				if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
					multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
				else
					multiSignBy0.setTokenid(Utils.HEX.encode(outKey.getPubKey()));
				multiSignBy0.setTokenindex(0);
				multiSignBy0.setAddress(genesiskey.toAddress(networkParameters).toBase58());
				multiSignBy0.setPublickey(Utils.HEX.encode(genesiskey.getPubKey()));
				multiSignBy0.setSignature(Utils.HEX.encode(buf2));
				multiSignBies.add(multiSignBy0);

				MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
				transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));
			}

			// solve block
			block.solve();

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
		ECKey outKey = wallet.walletKeys().get(0);
		ECKey outKey2 = new ECKey();
		byte[] pubKey = outKey.getPubKey();
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
		ECKey.ECDSASignature party1Signature = outKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);

		ECKey genesiskey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv),
				Utils.HEX.decode(testPub));
		ECKey.ECDSASignature party2Signature = genesiskey.sign(sighash1, aesKey);
		byte[] buf2 = party2Signature.encodeToDER();
		multiSignBy0 = new MultiSignBy();
		if (tokenInfo.getToken() != null && tokenInfo.getToken().getTokenid() != null)
			multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		else
			multiSignBy0.setTokenid(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(genesiskey.toAddress(networkParameters).toBase58());
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

		block1.solve();
		block2.solve();
		block3.solve();
		block4.solve();
		block5.solve();
		block6.solve();
		block7.solve();
		block8.solve();
		block9.solve();
		block10.solve();
		block11.solve();
		block12.solve();
		block13.solve();
		block14.solve();
		block15.solve();
		block16.solve();
		block17.solve();
		block18.solve();

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
		block.solve();

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
		block.solve();

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
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null);

		// Generate a subsequent issuance that does not work
		byte[] pubKey2 = new ECKey().getPubKey();
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey2);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey2), "Test", "Test",
				1, 1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", new ECKey().getPublicKeyAsHex()));
		try {

			Block block = makeTokenUnitTest(tokenInfo2, coinbase2, outKey, null);
			blockGraph.addBlock(block, false, store);
			fail();
		} catch (InvalidDependencyException e) {
		}
	}

	@Test
	public void testSolidityTokenWrongTokenindex() throws JsonProcessingException, Exception {

		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();

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

		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();

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

		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();

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

		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();

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
		ECKey outKey = wallet.walletKeys().get(0);
		byte[] pubKey = outKey.getPubKey();
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
		block.getTransactions().get(0).addOutput(Coin.COIN.times(2), outKey.toAddress(networkParameters));

		Sha256Hash sighash1 = transaction.getHash();
		ECKey.ECDSASignature party1Signature = outKey.sign(sighash1, null);
		byte[] buf1 = party1Signature.encodeToDER();

		List<MultiSignBy> multiSignBies = new ArrayList<MultiSignBy>();
		MultiSignBy multiSignBy0 = new MultiSignBy();
		multiSignBy0.setTokenid(tokenInfo.getToken().getTokenid().trim());
		multiSignBy0.setTokenindex(0);
		multiSignBy0.setAddress(outKey.toAddress(networkParameters).toBase58());
		multiSignBy0.setPublickey(Utils.HEX.encode(outKey.getPubKey()));
		multiSignBy0.setSignature(Utils.HEX.encode(buf1));
		multiSignBies.add(multiSignBy0);
		MultiSignByRequest multiSignByRequest = MultiSignByRequest.create(multiSignBies);
		transaction.setDataSignature(Json.jsonmapper().writeValueAsBytes(multiSignByRequest));

		// save block
		block.solve();

		// Should not go through
		try {
			blockGraph.addBlock(block, false, store);

			fail();
		} catch (InvalidTransactionDataException e) {
		}
	}

}