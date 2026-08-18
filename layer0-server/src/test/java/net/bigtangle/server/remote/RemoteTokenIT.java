/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.response.GetTokensResponse;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

public class RemoteTokenIT extends RemoteTestBase {

	protected static final Logger log = LoggerFactory.getLogger(RemoteTokenIT.class);

	@Test
	public void testServer() throws Exception {
		log.info("Server root: {}", contextRoot);
		assertTrue(wallet != null);
	}

	@Test
	public void testGenesisToken() throws Exception {
		byte[] response = null;
		GetTokensResponse tokensResponse = wallet.searchTokens(null);
		assertNotNull(tokensResponse, "searchTokens response should not be null");
		assertTrue(tokensResponse.getTokens() != null && !tokensResponse.getTokens().isEmpty(),
				"Genesis bigtoken must exist in the token list");
		boolean found = false;
		for (Token t : tokensResponse.getTokens()) {
			log.debug("token: {} {}", t.getTokenid(), t.getTokenname());
			if ("bc".equals(t.getTokenid()) || "BIG".equals(t.getTokenname())) {
				found = true;
				break;
			}
		}
		assertTrue(found, "Genesis token 'bc'/'BIG' not found in token list");
	}

	@Test
	public void testGetTokenByHash() throws Exception {
		List<Token> tokens = new ArrayList<Token>();
		GetTokensResponse tokensResponse = wallet.searchTokens(null);
		assertNotNull(tokensResponse.getTokens(), "tokens list should not be null");
		assertTrue(!tokensResponse.getTokens().isEmpty(), "At least one token must exist");
		tokens.addAll(tokensResponse.getTokens());
		boolean foundConfirmed = false;
		for (Token token : tokens) {
			if (token.getTokenid() != null) {
				String tokenId = token.getTokenid();
				Token tokenById = wallet.checkTokenId(tokenId);
				assertNotNull(tokenById, "Token " + tokenId + " should be fetched by id");
				foundConfirmed = true;
				break;
			}
		}
		assertTrue(foundConfirmed, "No token could be fetched by id");
	}

	@Test
	public void testCreateToken() throws Exception {
		PQKey key = PQKey.createNew();
		String tokenId = key.getPublicKeyAsHex();
		String tokenName = "testtoken";
		Block block = createToken(key, tokenName, 2, "", "test token", BigInteger.valueOf(10000000), true, null,
				TokenType.token.ordinal(), tokenId, wallet);
		assertNotNull(block, "createToken should return a block");

		Block signed = wallet.multiSign(key.getPublicKeyAsHex(), wallet.walletKeys().get(0), aesKey);
		if (signed != null) {
			makeRewardBlock(signed);
		}

		Token foundToken = null;
		for (int i = 0; i < 20; i++) {
			foundToken = getToken(tokenId);
			if (foundToken != null)
				break;
			Thread.sleep(3000);
		}
		assertNotNull(foundToken, "Token " + tokenId + " should exist after creation");
		assertEquals(tokenName, foundToken.getTokenname());
		log.info("Token created and verified: {} ({})", foundToken.getTokenname(), tokenId);
	}

	@Test
	public void testCreateAndPayToken() throws Exception {
		PQKey issuer = PQKey.createNew();
		String tokenName = "paytoken";
		BigInteger supply = BigInteger.valueOf(10000000L);

		Block block = createToken(issuer, tokenName, 0, "", "token for payment test",
				supply, true, null,
				TokenType.token.ordinal(), issuer.getPublicKeyAsHex(), wallet);
		assertNotNull(block, "createToken should return a block");

		Block signed = wallet.multiSign(issuer.getPublicKeyAsHex(),
				wallet.walletKeys().get(0), aesKey);
		if (signed != null) {
			makeRewardBlock(signed);
		}

		String tokenId = issuer.getPublicKeyAsHex();
		Token foundToken = null;
		for (int i = 0; i < 20; i++) {
			foundToken = getToken(tokenId);
			if (foundToken != null) break;
			Thread.sleep(3000);
		}
		assertNotNull(foundToken, "Token should exist after creation");
		log.info("Token {} created, id={}", tokenName, tokenId);

		// Wait until the token's minted UTXOs are CONFIRMED (spendable), not a
		// fixed sleep: the live chain confirms on its own schedule. Confirmation
		// also confirms this creation's fee change, so the next token-creating
		// test has a fresh confirmed BIG fee source instead of the same spent one.
		waitForTokenUtxos(issuer, tokenId);

		byte[] tokenidBuf = Utils.HEX.decode(tokenId);
		List<FreeStandingTransactionOutput> allCandidates = wallet.calculateAllSpendCandidates(null, false);
		log.info("Wallet has {} total UTXOs", allCandidates.size());

		List<FreeStandingTransactionOutput> tokenUtxos = new ArrayList<>();
		for (FreeStandingTransactionOutput co : allCandidates) {
			if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())) {
				tokenUtxos.add(co);
			}
		}

		assertTrue(!tokenUtxos.isEmpty(), "Issuer should have token UTXOs (supply=" + supply + ")");
		log.info("Issuer has {} token UTXOs", tokenUtxos.size());

		PQKey recipient = PQKey.createNew();
		BigInteger total = BigInteger.ZERO;
		Transaction tx = new Transaction(networkParameters);
		tx.setVersion(PQConstants.TX_PQ_VERSION);
		Coin sendAmount = new Coin(BigInteger.valueOf(1000L), tokenidBuf);

		for (FreeStandingTransactionOutput co : tokenUtxos) {
			tx.addInput(co.getUTXO().getBlockHash(), co);
			tx.getInputs().get(tx.getInputs().size() - 1).getOutpoint().connectedOutput = co;
			total = total.add(co.getValue().getValue());
			tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, sendAmount, recipient));
			Coin change = new Coin(total, tokenidBuf).subtract(sendAmount);
			if (!change.isNegative()) {
				tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, change, issuer));
				break;
			}
		}
		assertTrue(total.compareTo(BigInteger.valueOf(1000L)) >= 0, "Insufficient token balance");
		wallet.signTransaction(tx, null);
		wallet.submitTransaction(tx);
		makeRewardBlock();
		log.info("Paid 1000 {} tokens to recipient", tokenName);

		Thread.sleep(6000);
		List<FreeStandingTransactionOutput> after = wallet.calculateAllSpendCandidates(null, false);
		long tokenUtxoCount = after.stream()
				.filter(co -> java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf()))
				.count();
		log.info("Wallet token UTXOs after payment: {}", tokenUtxoCount);
		assertTrue(tokenUtxoCount > 0, "Wallet should still have token UTXOs after payment");
	}

	private Token getToken(String idcom) throws Exception {
		try {
			return wallet.checkTokenId(idcom);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Waits until the token's minted UTXOs are CONFIRMED (spendable). Confirmation
	 * also confirms the token creation's fee change, so the next token creation
	 * can reuse a fresh confirmed fee source instead of the same one (which
	 * would produce a conflicting block that never confirms).
	 */
	private void waitForTokenUtxos(PQKey key, String tokenId) throws Exception {
		byte[] tokenidBuf = Utils.HEX.decode(tokenId);
		for (int i = 0; i < 60; i++) {
			boolean ok = false;
			for (FreeStandingTransactionOutput co : wallet.calculateAllSpendCandidates(null, false)) {
				if (java.util.Arrays.equals(tokenidBuf, co.getUTXO().getTokenidBuf())
						&& co.getValue().getValue().signum() > 0) {
					ok = true;
				}
			}
			if (ok) return;
			Thread.sleep(3000);
		}
		log.warn("Token {} UTXOs not confirmed after polling", tokenId.substring(0, 14));
	}
}