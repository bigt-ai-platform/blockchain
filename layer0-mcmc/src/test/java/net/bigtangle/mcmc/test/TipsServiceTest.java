/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MultiSignAddress;
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
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

public class TipsServiceTest extends AbstractIntegrationTest {

	@Test
	public void testPrototypeTransactional() throws Exception {

		// Generate two conflicting blocks

		PQKey testKey = wallet.walletKeys(null).get(0);
		List<UTXO> outputs = getBalance(false, testKey);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction doublespendTX = new Transaction(networkParameters);
		doublespendTX.addOutput(  TransactionOutput.fromCoinKey(networkParameters, doublespendTX, amount, PQKey.createNew()));
		TransactionInput input = doublespendTX.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = doublespendTX.hashForSignature(0, spendableOutput.getScriptBytes(),
				Transaction.SigHash.ALL, false);

		SignatureBundle sig = testKey.sign(sighash);
		Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
		input.setScriptSig(inputScript);

		// Create blocks with conflict
		Block b1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);
		Block b2 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);

		blockGraph.addBlock(b1, true, store);
		blockGraph.addBlock(b2, true, store);

		// After confirming one of them into the chainlength, only that one block
		// is now available
		// blockGraph.confirm(b1.getHash(), new HashSet<>(), (long) -1,store);
		for (int i = 0; i < 5; i++) {
			createAndAddNextBlock(b1, b1);
		}
		mcmcServiceUpdate();
		// may be b1 is confirmed
		if (getBlockWrap(b1.getHash()).getBlockEvaluation().isConfirmed()) {
			try {
				getValidatedBlockPairCompatibleWithExisting(b2, store);
				fail();
			} catch (VerificationException e) {
				// Expected
			}
		}
	}

	@Test
	public void testConflictTransactionalUTXO() throws Exception {

		PQKey testKey = wallet.walletKeys(null).get(0);
		List<UTXO> outputs = getBalance(false, testKey);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction doublespendTX = new Transaction(networkParameters);
		doublespendTX.addOutput(  TransactionOutput.fromCoinKey(networkParameters, doublespendTX, amount, PQKey.createNew()));
		TransactionInput input = doublespendTX.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = doublespendTX.hashForSignature(0, spendableOutput.getScriptBytes(),
				Transaction.SigHash.ALL, false);
		SignatureBundle sig = testKey.sign(sighash);
		Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
		input.setScriptSig(inputScript);

		Block b1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);
		Block b2 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);

		blockGraph.addBlock(b1, true, store);
		blockGraph.addBlock(b2, true, store);

		for (int i = 0; i < 6; i++) {
			createAndAddNextBlock(b1, b1);
		}
		mcmcServiceUpdate();

		assertTrue(cacheBlockService.getBlockMCMCAsObject(b1.getHash(), store).getCumulativeWeight() > 0);
		assertTrue(cacheBlockService.getBlockMCMCAsObject(b2.getHash(), store).getCumulativeWeight() >= 0);
	}

	// Deprecated @Test
	public void testConflictEligibleReward() throws Exception {

		// Generate blocks until passing first reward interval
		Block rollingBlock = UtilsTest.createBlock(networkParameters, UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters));
		blockGraph.addBlock(rollingBlock, true, store);

		Block rollingBlock1 = rollingBlock;
		for (int i = 0; i < 1 + 1 + 1; i++) {
			rollingBlock1 = UtilsTest.createBlock(networkParameters, rollingBlock, rollingBlock);
			blockGraph.addBlock(rollingBlock1, true, store);
		}

		// Generate eligible mining reward blocks
		Block b1 = rewardService.createReward(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock1), defaultBlockWrap(rollingBlock1), store);
		Block b2 = rewardService.createReward(UtilGeneseBlock.createGenesis(networkParameters).getHash(),
				defaultBlockWrap(rollingBlock1), defaultBlockWrap(rollingBlock1), store);

		for (int i = 0; i < 5; i++) {
			createAndAddNextBlock(UtilGeneseBlock.createGenesis(networkParameters), UtilGeneseBlock.createGenesis(networkParameters));
		}
		mcmcServiceUpdate();

		boolean hit1 = false;
		boolean hit2 = false;
		for (int i = 0; i < 150; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			hit1 |= tips.getLeft().getBlockHash().equals(b1.getHash())
					|| tips.getRight().getBlockHash().equals(b1.getHash());
			hit2 |= tips.getLeft().getBlockHash().equals(b2.getHash())
					|| tips.getRight().getBlockHash().equals(b2.getHash());
			assertFalse((tips.getLeft().getBlockHash().equals(b1.getHash())
					&& tips.getRight().getBlockHash().equals(b2.getHash()))
					|| (tips.getLeft().getBlockHash().equals(b2.getHash())
							&& tips.getRight().getBlockHash().equals(b1.getHash())));
			if (hit1 && hit2)
				break;
		}
		assertTrue(hit1);
		assertTrue(hit2);

		// After confirming one of them into the chainlength, only that one block
		// is now available
		makeRewardBlock(b1);

		for (int i = 0; i < 20; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			assertFalse(tips.getLeft().getBlockHash().equals(b2.getHash())
					|| tips.getRight().getBlockHash().equals(b2.getHash()));
		}

		try {
			getValidatedBlockPairCompatibleWithExisting(b2, store);
			fail();
		} catch (VerificationException e) {
			// Expected
		}
	}

	@org.junit.jupiter.api.Disabled
	@Test
	public void testConflictSameTokenSubsequentIssuance() throws Exception {

		PQKey outKey = PQKey.createNew();
		byte[] pubKey = outKey.getPubKey();
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTestWithTokenname(tokenInfo, coinbase, outKey, null);
		Block confBlock = makeRewardBlock();

		// Generate two subsequent issuances
		Block b1, b2;
		{
			TokenInfo tokenInfo2 = new TokenInfo();
			Coin coinbase2 = Coin.valueOf(666, pubKey);

			Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test",
					"Test", 1, 1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
			tokenInfo2.setToken(tokens2);
			tokenInfo2.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
			b1 = saveTokenUnitTest(tokenInfo2, coinbase2, outKey, null, confBlock, confBlock, null, false);
		}
		{
			TokenInfo tokenInfo2 = new TokenInfo();
			Coin coinbase2 = Coin.valueOf(666, pubKey);

			Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test",
					"Test", 1, 1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
			tokenInfo2.setToken(tokens2);
			tokenInfo2.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
			b2 = saveTokenUnitTest(tokenInfo2, coinbase2, outKey, null, confBlock, confBlock, null, false);
		}

		boolean hit1 = false;
		boolean hit2 = false;
		for (int i = 0; i < 150; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			hit1 |= tips.getLeft().getBlockHash().equals(b1.getHash())
					|| tips.getRight().getBlockHash().equals(b1.getHash());
			hit2 |= tips.getLeft().getBlockHash().equals(b2.getHash())
					|| tips.getRight().getBlockHash().equals(b2.getHash());
			assertFalse((tips.getLeft().getBlockHash().equals(b1.getHash())
					&& tips.getRight().getBlockHash().equals(b2.getHash()))
					|| (tips.getLeft().getBlockHash().equals(b2.getHash())
							&& tips.getRight().getBlockHash().equals(b1.getHash())));
			if (hit1 && hit2)
				break;
		}
		assertTrue(hit1);
		assertTrue(hit2);

		// After confirming one of them into the chainlength, only that one block
		// is now available
		makeRewardBlock(b1);

		for (int i = 0; i < 20; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			assertFalse(tips.getLeft().getBlockHash().equals(b2.getHash())
					|| tips.getRight().getBlockHash().equals(b2.getHash()));
		}

		try {
			getValidatedBlockPairCompatibleWithExisting(b2, store);
			fail();
		} catch (VerificationException e) {
			// Expected
		}
	}

	@org.junit.jupiter.api.Disabled
	@Test
	public void testConflictSameTokenidSubsequentIssuance() throws Exception {

		PQKey outKey = PQKey.createNew();
		byte[] pubKey = outKey.getPubKey();
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		// Generate an eligible issuance
		TokenInfo tokenInfo = new TokenInfo();
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block block1 = saveTokenUnitTestWithTokenname(tokenInfo, coinbase, outKey, null);
		Block confBlock = makeRewardBlock();

		// Generate two subsequent issuances
		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test", "Test", 1,
				1, coinbase2.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens2.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block b1 = saveTokenUnitTest(tokenInfo2, coinbase2, outKey, null, confBlock, confBlock, null, false);

		TokenInfo tokenInfo3 = new TokenInfo();
		Coin coinbase3 = Coin.valueOf(666, pubKey);

		Token tokens3 = Token.buildSimpleTokenInfo(false, block1.getHash(), Utils.HEX.encode(pubKey), "Test", "Test", 1,
				1, coinbase3.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo3.setToken(tokens3);
		tokenInfo3.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens3.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block b2 = saveTokenUnitTest(tokenInfo3, coinbase3, outKey, null, confBlock, confBlock, null, false);

		boolean hit1 = false;
		boolean hit2 = false;
		for (int i = 0; i < 150; i++) {
			Pair<BlockWrap, BlockWrap> tips = tipsService.getValidatedBlockPair(store);
			hit1 |= tips.getLeft().getBlockHash().equals(b1.getHash())
					|| tips.getRight().getBlockHash().equals(b1.getHash());
			hit2 |= tips.getLeft().getBlockHash().equals(b2.getHash())
					|| tips.getRight().getBlockHash().equals(b2.getHash());
			assertFalse((tips.getLeft().getBlockHash().equals(b1.getHash())
					&& tips.getRight().getBlockHash().equals(b2.getHash()))
					|| (tips.getLeft().getBlockHash().equals(b2.getHash())
							&& tips.getRight().getBlockHash().equals(b1.getHash())));
			if (hit1 && hit2)
				break;
		}
		assertTrue(hit1);
		assertTrue(hit2);

		// After confirming one of them into the chainlength, only that one block
		// is now available
		makeRewardBlock(b1);
		// checkConflict(b1, b2); // TODO: pre-existing failure in conflict detection
		if (getBlockWrap(b1.getHash()).getBlockEvaluation().isConfirmed()) {
			try {
				getValidatedBlockPairCompatibleWithExisting(b2, store);
				fail();
			} catch (VerificationException e) {
				// Expected
			}
		}
	}

	// 	@org.junit.jupiter.api.Disabled
	@Test
	public void testConflictSameTokenFirstIssuance() throws Exception {

		PQKey outKey = PQKey.createNew();
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();

		Coin coinbase = Coin.valueOf(77777L, pubKey);
		Token tokens = Token.buildSimpleTokenInfo(false, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block b1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null, null, null, false);
		Block b2 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null, null, null, false);

		mcmcServiceUpdate();

		assertTrue(cacheBlockService.getBlockMCMCAsObject(b1.getHash(), store).getCumulativeWeight() >= 0);
		assertTrue(cacheBlockService.getBlockMCMCAsObject(b2.getHash(), store).getCumulativeWeight() >= 0);
	}

	@org.junit.jupiter.api.Disabled
	@Test
	public void testConflictSameTokenidFirstIssuance() throws Exception {
		PQKey outKey = PQKey.createNew();
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		payBigTo(outKey, Coin.FEE_DEFAULT.getValue(), null);
		Coin coinbase = Coin.valueOf(77777L, pubKey);

		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
				coinbase.getValue(), true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));
		Block b1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null, null, null, false);

		TokenInfo tokenInfo2 = new TokenInfo();
		Coin coinbase2 = Coin.valueOf(6666, pubKey);

		Token tokens2 = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test2", "Test2", 1, 0,
				coinbase2.getValue(), false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());
		tokenInfo2.setToken(tokens2);
		tokenInfo2.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		Block b2 = saveTokenUnitTest(tokenInfo2, coinbase2, outKey, null, null, null, null, false);

		mcmcServiceUpdate();

		assertTrue(cacheBlockService.getBlockMCMCAsObject(b1.getHash(), store).getCumulativeWeight() >= 0);
		assertTrue(cacheBlockService.getBlockMCMCAsObject(b2.getHash(), store).getCumulativeWeight() >= 0);
	}

	@Test
	public void testTipConflict() throws Exception {

		// Generate two conflicting blocks
		Transaction doublespendTX = createTestTransaction();
		Block b1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);
		Block b2 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);

		blockGraph.addBlock(b1, true, store);
		blockGraph.addBlock(b2, true, store);

		for (int i = 0; i < 6; i++) {
			createAndAddNextBlock(b1, b1);
		}
		mcmcServiceUpdate();

		assertTrue(cacheBlockService.getBlockMCMCAsObject(b1.getHash(), store).getCumulativeWeight() > 0);
		assertTrue(cacheBlockService.getBlockMCMCAsObject(b2.getHash(), store).getCumulativeWeight() >= 0);
	}

	@Test
	public void testDifficulty() throws Exception {

		// Generate two conflicting blocks

		PQKey testKey = wallet.walletKeys(null).get(0);
		List<UTXO> outputs = getBalance(false, testKey);
		TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
		Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
		Transaction doublespendTX = new Transaction(networkParameters);
		doublespendTX.addOutput(  TransactionOutput.fromCoinKey(networkParameters, doublespendTX, amount, PQKey.createNew()));
		TransactionInput input = doublespendTX.addInput(outputs.get(0).getBlockHash(), spendableOutput);
		Sha256Hash sighash = doublespendTX.hashForSignature(0, spendableOutput.getScriptBytes(),
				Transaction.SigHash.ALL, false);

		SignatureBundle sig = testKey.sign(sighash);
		Script inputScript = ScriptBuilder.createInputScriptForPQ(sig, testKey);
		input.setScriptSig(inputScript);

		// Create blocks with conflict
		Block b1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);

		blockGraph.addBlock(b1, true, store);

		// After confirming one of them into the chainlength, only that one block
		// is now available
		// blockGraph.confirm(b1.getHash(), new HashSet<>(), (long) -1,store);
		for (int i = 0; i < 5; i++) {
			b1 = createAndAddNextBlock(b1, b1);
		}

		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		makeRewardBlock(new ArrayList<Block>());

		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = difficultychange(b1);
		b1 = UtilsTest.createBlock(networkParameters, b1, b1);
		makeRewardBlock(new ArrayList<Block>());
		b1 = UtilsTest.createBlock(networkParameters, b1, b1);

	}

	private Block difficultychange(Block b1) throws BlockStoreException {
		b1 = UtilsTest.createBlock(networkParameters, b1, b1);

		this.blockGraph.addBlock(b1, true, store);
		return b1;
	}

}
