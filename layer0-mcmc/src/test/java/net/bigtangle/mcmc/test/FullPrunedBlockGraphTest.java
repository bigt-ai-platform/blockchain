/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.server.service.base.ServiceBaseConnect;


public class FullPrunedBlockGraphTest extends AbstractIntegrationTest {

	@Test
	public void testConnectTransactionalUTXOs() throws Exception {

		// Create block with UTXOs
		Transaction tx1 = createTestTransaction();
		Block block1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1, false);

		// Should exist now
		final UTXO utxo1 = getUTXO(tx1.getOutput(0).getOutPointFor(block1.getHash()), store);
		final UTXO utxo2 = getUTXO(tx1.getOutput(1).getOutPointFor(block1.getHash()), store);
		assertNotNull(utxo1);
		assertNotNull(utxo2);
		assertFalse(utxo1.isConfirmed());
		assertFalse(utxo2.isConfirmed());
		assertFalse(utxo1.isSpent());
		assertFalse(utxo2.isSpent());
	}

	@Test
	public void testConnectRewardUTXOs() throws Exception {

		// Generate blocks until passing first reward interval

		Block rollingBlock1 = UtilGeneseBlock.createGenesis(networkParameters);

		// Generate mining reward block
		Block rewardBlock1 = makeRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(), rollingBlock1.getHash(),
				rollingBlock1.getHash());

		// Should exist now
		assertTrue(store.getRewardConfirmed(rewardBlock1.getHash()));
		assertFalse(store.getRewardSpent(rewardBlock1.getHash()));
	}

	@Test
	public void testConnectTokenUTXOs() throws Exception {

		ECKey ecKey1 = new ECKey();
		byte[] pubKey = ecKey1.getPubKey();
		// System.out.println(Utils.HEX.encode(pubKey));

		// Generate an eligible issuance
		Sha256Hash firstIssuance;
		{
			TokenInfo tokenInfo = new TokenInfo();

			Coin coinbase = Coin.valueOf(77777L, pubKey);
			BigInteger amount = coinbase.getValue();
			Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0,
					amount, false, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

			tokenInfo.setToken(tokens);
			tokenInfo.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens.getTokenid(), "", ecKey1.getPublicKeyAsHex()));

			// This (saveBlock) calls milestoneUpdate currently, that's why we
			// need other blocks beforehand.
			Block block1 = saveTokenUnitTestWithTokenname(tokenInfo, coinbase, ecKey1, null, null);
			firstIssuance = block1.getHash();

			// Should exist now

			assertFalse(store.getTokenSpent(block1.getHash()).isSpent());
		}

		// Generate a subsequent issuance
		{
			TokenInfo tokenInfo = new TokenInfo();

			Coin coinbase = Coin.valueOf(77777L, pubKey);
			BigInteger amount = coinbase.getValue();
			Token tokens = Token.buildSimpleTokenInfo(true, firstIssuance, Utils.HEX.encode(pubKey), "Test", "Test", 1,
					1, amount, true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

			tokenInfo.setToken(tokens);
			ECKey ecKey = new ECKey();
			tokenInfo.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens.getTokenid(), "", ecKey.getPublicKeyAsHex()));
			ECKey ecKey2 = new ECKey();
			tokenInfo.getMultiSignAddresses()
					.add(new MultiSignAddress(tokens.getTokenid(), "", ecKey2.getPublicKeyAsHex()));

			;

			// This (saveBlock) calls milestoneUpdate currently, that's why we
			// need other blocks beforehand.
			ECKey outKey3 = new ECKey();
			wallet.importKey(ecKey);
			wallet.importKey(ecKey2);
			wallet.importKey(outKey3);
			Block block1 = saveTokenUnitTestWithTokenname(tokenInfo, coinbase, outKey3, null);

			// block1 = pullBlockDoMultiSign(tokens.getTokenid(), ecKey1, null);

			assertFalse(store.getTokenSpent(block1.getHash()).isSpent());
		}
	}

	@Test
	public void testConfirmTransactionalUTXOs() throws Exception {

		// Create block with UTXOs
		Transaction tx1 = createTestTransaction();
		Block spenderBlock = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx1);

		// Confirm
		makeRewardBlock();

		// Should be confirmed now
		final UTXO utxo1 = getUTXO(tx1.getOutput(0).getOutPointFor(spenderBlock.getHash()), store);
		final UTXO utxo2 = getUTXO(tx1.getOutput(1).getOutPointFor(spenderBlock.getHash()), store);
		assertTrue(utxo1.isConfirmed());
		assertTrue(utxo2.isConfirmed());
		assertFalse(utxo1.isSpent());
		assertFalse(utxo2.isSpent());

		// Further manipulations on prev UTXOs
		final UTXO origUTXO = getUTXO(UtilGeneseBlock.createGenesis(networkParameters).getTransactions().get(0).getOutput(0)
				.getOutPointFor(UtilGeneseBlock.createGenesis(networkParameters).getHash()), store);
		assertTrue(origUTXO.isConfirmed());
		assertTrue(origUTXO.isSpent());
		assertEquals(
				store.getTransactionOutputSpender(origUTXO.getBlockHash(), origUTXO.getTxHash(), origUTXO.getIndex())
						.getBlockHash(),
				spenderBlock.getHash());
	}

	@Test
	public void testConfirmRewardUTXOs() throws Exception {

		// Generate blocks until passing first reward interval
		List<Block> blocksAddedAll = new ArrayList<Block>();
		Block rollingBlock1 = addFixedBlocks(UtilGeneseBlock.createGenesis(networkParameters), blocksAddedAll);

		// Generate mining reward block
		Block rewardBlock1 = makeRewardBlock(UtilGeneseBlock.createGenesis(networkParameters).getHash(), rollingBlock1.getHash(),
				rollingBlock1.getHash());

		// Should be confirmed now
		assertTrue(store.getRewardConfirmed(rewardBlock1.getHash()));
		assertFalse(store.getRewardSpent(rewardBlock1.getHash()));

		// Further manipulations on prev UTXOs
		assertTrue(store.getRewardConfirmed(UtilGeneseBlock.createGenesis(networkParameters).getHash()));
		assertTrue(store.getRewardSpent(UtilGeneseBlock.createGenesis(networkParameters).getHash()));
		assertEquals(store.getRewardSpender(UtilGeneseBlock.createGenesis(networkParameters).getHash()), rewardBlock1.getHash());

		// Virtual mining reward TX check removed (epoch-based rewards)
		// The reward block itself contains the reward outputs now.
	}

	// PoS conversion: reward path confirmation needs PoS upgrade
	// @Test
	public void disabled_testConfirmTokenUTXOs() throws Exception {

		// Generate an eligible issuance
		ECKey outKey = new ECKey();
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();

		Coin coinbase = Coin.valueOf(77777L, pubKey);
		BigInteger amount = coinbase.getValue();
		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0, amount,
				true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

		tokenInfo.setToken(tokens);

		// This (saveBlock) calls milestoneUpdate currently
		Block block1 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null);
		makeRewardBlock();

		// Should be confirmed now
		assertTrue(store.getTokenSpent(block1.getHash()).isConfirmed());
		assertFalse(store.getTokenSpent(block1.getHash()).isSpent());
	}

	@Test
	public void testUnconfirmTransactionalUTXOs() throws Exception {

		// Create block with UTXOs
		Transaction tx11 = createTestTransaction();
		Block block = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
				UtilGeneseBlock.createGenesis(networkParameters), tx11);

		// Confirm
		ServiceBaseConnect s = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		confirmDo(s.getBlockWrap(block.getHash(), store), new HashSet<>(), store);

		// Should be confirmed now
		final UTXO utxo11 = getUTXO(tx11.getOutput(0).getOutPointFor(block.getHash()), store);
		final UTXO utxo21 = getUTXO(tx11.getOutput(1).getOutPointFor(block.getHash()), store);
		assertNotNull(utxo11);
		assertNotNull(utxo21);
		assertTrue(utxo11.isConfirmed());
		assertTrue(utxo21.isConfirmed());
		assertFalse(utxo11.isSpent());
		assertFalse(utxo21.isSpent());

		// Unconfirm
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.unconfirm(getBlockWrap(block.getHash()), new HashSet<>(), -1, store);

		// Should be unconfirmed now
		final UTXO utxo1 = getUTXO(tx11.getOutput(0).getOutPointFor(block.getHash()), store);
		final UTXO utxo2 = getUTXO(tx11.getOutput(1).getOutPointFor(block.getHash()), store);
		assertNotNull(utxo1);
		assertNotNull(utxo2);
		assertFalse(utxo1.isConfirmed());
		assertFalse(utxo2.isConfirmed());
		assertFalse(utxo1.isSpent());
		assertFalse(utxo2.isSpent());

		// Further manipulations on prev UTXOs
		final UTXO origUTXO = getUTXO(UtilGeneseBlock.createGenesis(networkParameters).getTransactions().get(0).getOutput(0)
				.getOutPointFor(UtilGeneseBlock.createGenesis(networkParameters).getHash()), store);
		assertTrue(origUTXO.isConfirmed());
		assertFalse(origUTXO.isSpent());
		assertNull(
				store.getTransactionOutputSpender(origUTXO.getBlockHash(), origUTXO.getTxHash(), origUTXO.getIndex()));
	}

	@Test
	public void testUnconfirmRewardUTXOs() throws Exception {

		List<Block> blocksAddedAll = new ArrayList<Block>();
		Block rollingBlock = addFixedBlocks(UtilGeneseBlock.createGenesis(networkParameters), blocksAddedAll);

		// Generate mining reward block
		Block rewardBlock11 = makeRewardBlock(rollingBlock);

		// Should be confirmed now
		assertTrue(store.getRewardConfirmed(rewardBlock11.getHash()));
		assertFalse(store.getRewardSpent(rewardBlock11.getHash()));

		// Unconfirm
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.unconfirm(getBlockWrap(rewardBlock11.getHash()), new HashSet<>(), -1, store);

		// Should be unconfirmed now
		assertFalse(store.getRewardConfirmed(rewardBlock11.getHash()));
		assertFalse(store.getRewardSpent(rewardBlock11.getHash()));

		// Further manipulations on prev UTXOs
		assertTrue(store.getRewardConfirmed(UtilGeneseBlock.createGenesis(networkParameters).getHash()));
		assertFalse(store.getRewardSpent(UtilGeneseBlock.createGenesis(networkParameters).getHash()));
		assertNull(store.getRewardSpender(UtilGeneseBlock.createGenesis(networkParameters).getHash()));

		// Virtual mining reward TX check removed (epoch-based rewards)
	}

	@Test
	public void testUnconfirmTokenUTXOs() throws Exception {

		ServiceBaseConnect s = new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
		// Generate an eligible issuance
		ECKey outKey = new ECKey();
		byte[] pubKey = outKey.getPubKey();
		TokenInfo tokenInfo = new TokenInfo();

		Coin coinbase = Coin.valueOf(77777L, pubKey);
		BigInteger amount = coinbase.getValue();
		Token tokens = Token.buildSimpleTokenInfo(true, null, Utils.HEX.encode(pubKey), "Test", "Test", 1, 0, amount,
				true, 0, UtilGeneseBlock.createGenesis(networkParameters).getHashAsString());

		tokenInfo.setToken(tokens);
		tokenInfo.getMultiSignAddresses()
				.add(new MultiSignAddress(tokens.getTokenid(), "", outKey.getPublicKeyAsHex()));

		// This (saveBlock) calls milestoneUpdate currently
		Block block11 = saveTokenUnitTest(tokenInfo, coinbase, outKey, null, null);
		confirmDo(s.getBlockWrap(block11.getHash(), store), new HashSet<>(), store);

		// Should be confirmed now
		assertTrue(store.getTokenSpent(block11.getHash()).isConfirmed());
		assertFalse(store.getTokenSpent(block11.getHash()).isSpent());

		// Unconfirm
		new ServiceBaseConnect(serverConfiguration, networkParameters, cacheBlockService, jsonmapper)
				.unconfirm(getBlockWrap(block11.getHash()), new HashSet<>(), -1, store);

		// Should be unconfirmed now
		assertFalse(store.getTokenSpent(block11.getHash()).isConfirmed());
		assertFalse(store.getTokenSpent(block11.getHash()).isSpent());
	}

}