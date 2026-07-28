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

public class ValidatorServiceTest extends AbstractIntegrationTest {

	@Test
	public void testVerificationFutureTimestamp() throws Exception {

		Pair<BlockWrap, BlockWrap> tipsToApprove = tipsService.getValidatedBlockPair(store);
		Block r1 = tipsToApprove.getLeft().getBlock();
		Block r2 = tipsToApprove.getRight().getBlock();
		Block b = UtilsTest.createBlock(networkParameters, r2, r1);
		b.setTime(1887836800); //
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
    public void testVerificationPoWNonceAcceptedAfterPoSConversion() throws Exception {

		Pair<BlockWrap, BlockWrap> tipsToApprove = tipsService.getValidatedBlockPair(store);
		Block r1 = tipsToApprove.getLeft().getBlock();
		Block r2 = tipsToApprove.getRight().getBlock();
		Block b = UtilsTest.createBlock(networkParameters, r2, r1);
		b.addTransaction(wallet.feeTransaction(null));
		b.verifyHeader();
		blockSaveService.saveBlock(b, store);
	}

	@Test
	public void testUnsolidBlockAllowed() throws Exception {

		Sha256Hash sha256Hash1 = getRandomSha256Hash();
		Sha256Hash sha256Hash2 = getRandomSha256Hash();
		Block block = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		block.setPrevBlockHash(sha256Hash1);
		block.setPrevBranchBlockHash(sha256Hash2);
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
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
		blockService.addConnected(block.bitcoinSerialize(), true);

		// Should not be solid
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		// Add missing dependency
		blockService.addConnected(depBlock.bitcoinSerialize(), true);

		// After adding the missing dependency, should be solid

		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);
		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 2);
		assertTrue(store.getBlockWrap(depBlock.getHash()) != null);
	}

	@Test
	public void testUnsolidMissingPredecessor1() throws Exception {

		Block depBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		depBlock.addTransaction(wallet.feeTransaction(null));
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
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
		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		block.addTransaction(wallet.feeTransaction(null));
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

		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

		Block depBlock = UtilsTest.createBlock(networkParameters, genesis, genesis);
		depBlock.addTransaction(wallet.feeTransaction(null));
		blockGraph.addBlock(depBlock, true, store);

		Block block = UtilsTest.createBlock(networkParameters, depBlock, depBlock);
		blockGraph.addBlock(block, true, store);

		resetStore();

		blockService.addConnected(block.bitcoinSerialize(), true);

		assertTrue(store.getBlockWrap(block.getHash()).getBlockEvaluation().getSolid() == 0);

		blockSaveService.saveBlock(depBlock, store);

		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.solidifyWaiting(block, store);

		assertTrue(store.getBlockWrap(depBlock.getHash()).getBlockEvaluation().getSolid() == 2);
	}

	@Test
	public void testSolidityPredecessorConsensusInheritance() throws Exception {
		// PoW difficulty/consensus inheritance was removed in PoS migration.
		// Casper/GHOST handle fork choice independently of lastMiningRewardBlock.
		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);
		Block block = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
		block.setLastMiningRewardBlock(2);
		block.addTransaction(wallet.feeTransaction(null));
		blockGraph.addBlock(block, false, store);
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
		blockGraph.addBlock(rollingBlock, false, store);
		makeRewardBlock(rollingBlock);
		// The time is not allowed to move backwards
		try {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			rollingBlock.setTime(946684800); // 01/01/2000 @ 12:00am (UTC)
			rollingBlock.addTransaction(wallet.feeTransaction(null));
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
					tx.addOutput(Coin.COIN.times(2), PQKey.createNew());

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
					blockGraph.addBlock(rollingBlock, false, store);

					fail();
				} catch (CoinbaseDisallowedException | UnsolidException e) {
				}
		}
	}

	@Test
    public void testSolidityTXDoubleSpend() throws Exception {

		Transaction tx1 = createTestTransaction();
		Block spenderBlock1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);
		makeRewardBlock(spenderBlock1);

		Block spenderBlock2 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);

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

			PQKey testKey = PQKey.createNew();
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
			SignatureBundle sig = testKey.sign(sighash);
			Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
			input.setScriptSig(inputScript);
			createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
					UtilGeneseBlock.createGenesis(networkParameters), tx2);
		}

		resetStore();

		// Again but with more output coins
		try {

			PQKey testKey = PQKey.createNew();
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
			SignatureBundle sig = testKey.sign(sighash);
			Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
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

			PQKey testKey = wallet.walletKeys(null).get(0);
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
			SignatureBundle sig = testKey.sign(sighash);
			Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
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
			blockGraph.addBlock(b, false, store);
			fail();
		} catch (GenesisBlockDisallowedException e) {
		}
	}

	@Test
	public void testSoliditySigOps() throws Exception {

		// Create block with outputs
		try {

			PQKey testKey = wallet.walletKeys(null).get(0);
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
    public void testSolidityRewardTxDifficultyTargetAcceptedAfterPoSConversion() throws Exception {

		Block rollingBlock = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate blocks until passing first reward interval
		for (int i = 0; i < 20; i++) {
			rollingBlock = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock, true, store);
		}

		// Generate mining reward block with spending inputs
		Block rewardBlock = rewardService.createMiningRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock), defaultBlockWrap(rollingBlock), false, store);
		blockGraph.addBlock(rewardBlock, false, store);

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

		PQKey testKey = wallet.walletKeys(null).get(0);
		List<UTXO> outputs = getBalance(false, testKey);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, testKey));
		tx.addOutput(
				TransactionOutput.fromCoinKey(networkParameters, tx, spendableOutput.getValue().subtract(amount), testKey));
		TransactionInput input = tx.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = tx.hashForSignature(0, spendableOutput.getScriptBytes(), Transaction.SigHash.ALL, false);

		SignatureBundle sig = testKey.sign(sighash);
		Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
		input.setScriptSig(inputScript);

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

		try {
			 add(testBlock3, true, true, store);
			fail();
		} catch (VerificationException e) {
		}
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

}
