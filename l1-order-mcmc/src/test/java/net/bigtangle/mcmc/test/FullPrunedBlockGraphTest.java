/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;


public class FullPrunedBlockGraphTest extends AbstractIntegrationTest {

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
	public void testConfirmOrderMatchUTXOs2() throws Exception {

		ECKey testKey = ECKey.fromPrivateAndPrecalculatedPublic(Utils.HEX.decode(testPriv), Utils.HEX.decode(testPub));
		// Make the "test" token
		List<Block> addedBlocks = new ArrayList<>();
		makeTestToken(testKey, addedBlocks);
		String testTokenId = testKey.getPublicKeyAsHex();
		// Make a buy order for testKey.getPubKey()s
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		payBigTo(testKey, Coin.FEE_DEFAULT.getValue(), addedBlocks);
		Block block1 = makeAndConfirmBuyOrder(testKey, Utils.HEX.encode(testKey.getPubKey()), 2, 2, addedBlocks);

		// Make a sell order for testKey.getPubKey()s
		// Open sell order for test tokens
		Block block3 = makeAndConfirmSellOrder(testKey, testTokenId, 2, 2, addedBlocks);

		// Ensure all consumed order records are now spent
		OrderRecord order = store.getOrder(block1.getHash(), Sha256Hash.ZERO_HASH);
		assertNotNull(order);
		assertTrue(order.isConfirmed());
		assertTrue(order.isSpent());

		OrderRecord order2 = store.getOrder(block3.getHash(), Sha256Hash.ZERO_HASH);
		assertNotNull(order2);
		assertTrue(order2.isConfirmed());
		assertTrue(order2.isSpent());

	}

}
