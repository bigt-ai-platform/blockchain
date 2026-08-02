/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.attacktest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TokenType;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class DoubleSpentAttackTest extends AbstractIntegrationTest {

    /**
     * Number of attack transactions/tokens submitted per attack test. The
     * token-creation attack is the dominant cost of this class (each iteration
     * does an HTTP submit + MCMC prototype calc), so the default is kept low;
     * scale up with -Dnet.bigtangle.attackCount=1000 for a full stress run.
     */
    private static final int ATTACK_COUNT = Integer.getInteger("net.bigtangle.attackCount", 200);


    /**
     * Builds a funded wallet for attack testing. Uses an ML-DSA-only key so the
     * 1000-tx attack tests sign fast (~5ms) instead of paying SLH-DSA (~1.7s)
     * per tx. The test targets mempool double-spend logic, not the crypto.
     */
    private Wallet fundedAttackWallet() throws Exception {
        PQKey attackKey = PQKey.createNew();
        payBigTo(attackKey, BigInteger.valueOf(1_000_000_000L), null);
        return Wallet.fromKeys(networkParameters, attackKey, contextRoot);
    }

    private Transaction createDoubleSpendTx(Wallet w, List<FreeStandingTransactionOutput> candidates, PQKey recipient, Coin amount, String memo) throws InsufficientMoneyException {
        Transaction tx = new Transaction(networkParameters);
        tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, amount, recipient));
        Coin restAmount = amount.negate().subtract(Coin.FEE_DEFAULT);
        for (FreeStandingTransactionOutput co : candidates) {
            if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
                restAmount = co.getValue().add(restAmount);
                tx.addInput(co.getUTXO().getBlockHash(), co);
                if (!restAmount.isNegative()) {
                    if (restAmount.isPositive()) {
                        tx.addOutput(TransactionOutput.fromCoinKey(networkParameters, tx, restAmount, w.walletKeys(null).get(0)));
                    }
                    break;
                }
            }
        }
        if (restAmount.isNegative()) {
            throw new InsufficientMoneyException(amount + " not enough funds");
        }
        w.signTransaction(tx, null);
        return tx;
    }

    @Autowired
    private NetworkParameters networkParameters;

    private static final Logger log = LoggerFactory.getLogger(DoubleSpentAttackTest.class);

    @Test
    public void testMempoolRejectsDoubleSpend() throws Exception {
        PQKey alice = PQKey.createNew();
        PQKey bob = PQKey.createNew();

        Wallet w = fundedAttackWallet();

        List<FreeStandingTransactionOutput> candidates = w.calculateAllSpendCandidates(null, false);
        assertTrue(candidates.stream().anyMatch(c -> c.getValue().isBIG()
                && c.getValue().getValue().compareTo(BigInteger.valueOf(2000)) >= 0),
                "Need a BIG UTXO >= 2000 for the test");

        Coin sendAmount = Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID);
        Transaction tx1 = createDoubleSpendTx(w, candidates, alice, sendAmount, "double-spend 1");
        Transaction tx2 = createDoubleSpendTx(w, candidates, bob, sendAmount, "double-spend 2");

        assertTrue(tx1.getInputs().stream().anyMatch(i -> !i.getOutpoint().isCoinBase()),
                "tx1 should have real inputs");
        assertTrue(tx2.getInputs().stream().anyMatch(i -> !i.getOutpoint().isCoinBase()),
                "tx2 should have real inputs");

        log.info("tx1 inputs: {}", tx1.getInputs().size());
        log.info("tx2 inputs: {}", tx2.getInputs().size());

        mempoolService.submitTransaction(tx1);
        assertEquals(1, mempoolService.size(), "First tx should be accepted");
        assertTrue(mempoolService.getSpentOutpointsCount() > 0,
                "At least one outpoint should be tracked after first tx");

        VerificationException.ConflictPossibleException ex = assertThrows(
                VerificationException.ConflictPossibleException.class,
                () -> mempoolService.submitTransaction(tx2),
                "Second tx spending same UTXO should be rejected");
        log.info("Got expected conflict exception: {}", ex.getMessage());

        assertEquals(1, mempoolService.size(), "Mempool must still have only the first tx");

        mempoolService.drainAll();
        assertEquals(0, mempoolService.size(), "Mempool should be empty after drain");
        assertEquals(0, mempoolService.getSpentOutpointsCount(),
                "All outpoints released after drain");
    }

    @Test
    public void testThousandDoubleSpendAttack() throws Exception {
        Wallet w = fundedAttackWallet();

        List<FreeStandingTransactionOutput> candidates = w.calculateAllSpendCandidates(null, false);
        assertTrue(candidates.stream().anyMatch(c -> c.getValue().isBIG()
                && c.getValue().getValue().compareTo(BigInteger.valueOf(10000)) >= 0),
                "Need a BIG UTXO >= 10000 for the 1000-attack test");

        Coin sendAmount = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);

        List<Transaction> attackTxs = new ArrayList<>();
        List<PQKey> dummyKeys = new ArrayList<>();
        for (int i = 0; i < ATTACK_COUNT; i++) {
            dummyKeys.add(PQKey.createNew());
        }

        for (int i = 0; i < ATTACK_COUNT; i++) {
            Transaction tx = createDoubleSpendTx(w, candidates, dummyKeys.get(i), sendAmount, "attack tx " + i);
            attackTxs.add(tx);
        }

        long startNs = System.nanoTime();
        int accepted = 0;
        int rejected = 0;

        for (int i = 0; i < ATTACK_COUNT; i++) {
            try {
                mempoolService.submitTransaction(attackTxs.get(i));
                accepted++;
            } catch (VerificationException.ConflictPossibleException e) {
                rejected++;
            }
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        log.info("Double-spend attack results: accepted={}, rejected={}, mempoolSize={}, elapsedMs={}",
                accepted, rejected, mempoolService.size(), elapsedMs);

        assertEquals(1, accepted, "Exactly one tx should be accepted");
        assertEquals(ATTACK_COUNT - 1, rejected, "All other " + (ATTACK_COUNT - 1) + " should be rejected");
        assertEquals(1, mempoolService.size(), "Mempool size should be 1 after attack");

        mempoolService.drainAll();
        assertEquals(0, mempoolService.getSpentOutpointsCount(), "All outpoints released after drain");
    }

    @Test
    public void testThousandTokenCreationAttack() throws Exception {
        Wallet w = fundedAttackWallet();
        PQKey testKey = w.walletKeys(null).get(0);
        String domain = "";
        String tokenHex = Utils.HEX.encode(Sha256Hash.hash(testKey.getPubKey()));
        int tokentype = TokenType.currency.ordinal();

        List<Block> tokenBlocks = new ArrayList<>();
        for (int i = 0; i < ATTACK_COUNT; i++) {
            mcmcService.calcNewBlockPrototype(store);
            Block b = createToken(testKey, "attack-token-" + i, 2, domain,
                    "attack token " + i, BigInteger.valueOf(1000), true,
                    null, tokentype, tokenHex, w);
            tokenBlocks.add(b);
        }

        assertEquals(ATTACK_COUNT, tokenBlocks.size(), "All " + ATTACK_COUNT + " tokens should be created");
        log.info("Created {} token blocks, running MCMC consensus...", tokenBlocks.size());

        mcmcService.update(store);
        blockGraph.confirmDo(store);
        log.info("MCMC consensus updated");

        for (int i = 0; i < 5; i++) {
            Block reward = rewardService.createReward(
                    cacheBlockService.getMaxConfirmedReward(store).getBlockHash(), store);
            if (reward != null) {
                blockGraph.updateChain(false);
                log.info("Reward block #{}: {}", i, reward.getHashAsString());
            }
        }

        blockGraph.updateChain(false);
        log.info("Chain updated with reward blocks");
    }
}
