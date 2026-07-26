package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class SubmitTransactionsIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SubmitTransactionsIT.class);

    @Test
    public void testSubmitTransactionsWithPQP2PK() throws Exception {
        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            walletKeys.add(PQKey.createNew());
        }
        log.info("Created {} PQ wallet keys", walletKeys.size());

        Transaction fundingTx = new Transaction(networkParameters);
        for (PQKey k : walletKeys) {
            fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx,
                    new Coin(BigInteger.valueOf(20000), NetworkParameters.BIGTANGLE_TOKENID), k));
        }
        List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
        Coin need = Coin.valueOf(20000L * walletKeys.size(), NetworkParameters.BIGTANGLE_TOKENID)
                .add(Coin.FEE_DEFAULT);
        Coin totalIn = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);
        PQKey walletKey = wallet.walletKeys(null).get(0);
        for (FreeStandingTransactionOutput co : candidates) {
            if (!java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf()))
                continue;
            fundingTx.addInput(co.getUTXO().getBlockHash(), co);
            totalIn = totalIn.add(co.getValue());
            if (totalIn.getValue().compareTo(need.getValue()) >= 0) {
                Coin change = totalIn.subtract(need);
                if (!change.isNegative() && !change.isZero()) {
                    fundingTx.addOutput(TransactionOutput.fromCoinKey(networkParameters, fundingTx, change, walletKey));
                }
                break;
            }
        }
        wallet.signTransaction(fundingTx, null);

        BlockStoreInterface bs = storeService.getStore();
        Sha256Hash fundBlockHash;
        try {
            Block proto = cacheBlockPrototypeService.getBlockPrototype(bs);
            proto.addTransaction(fundingTx);
            blockSaveService.saveBatchBlock(proto, bs);
            fundBlockHash = proto.getHash();
        } finally {
            bs.close();
        }
        blockGraph.updateChain(false);
        mcmcService.update(store);
        log.info("Funded {} wallets, block={}", walletKeys.size(), fundBlockHash);

        Sha256Hash fundTxHash = fundingTx.getHash();
        List<FreeStandingTransactionOutput> coins = new ArrayList<>();
        for (int i = 0; i < walletKeys.size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            assertTrue(utxo != null, "UTXO at index " + i + " should exist");
            coins.add(new FreeStandingTransactionOutput(networkParameters, utxo));
        }
        log.info("Pre-fetched {} UTXOs", coins.size());

        PQKey recipient = PQKey.createNew();
        String recipientAddr = Address.fromHash160(networkParameters, recipient.getPubKeyHash()).toBase58();
        List<Transaction> spendTxs = new ArrayList<>();
        for (int i = 0; i < walletKeys.size(); i++) {
            PQKey k = walletKeys.get(i);
            Wallet w = Wallet.fromKeys(networkParameters, k, contextRoot);
            Transaction tx = w.payToListTransaction(null,
                    new HashMap<>(java.util.Map.of(recipientAddr, BigInteger.valueOf(15000))),
                    NetworkParameters.BIGTANGLE_TOKENID, "pq-test", List.of(coins.get(i)));
            assertTrue(tx != null, "Transaction should be created");
            spendTxs.add(tx);
        }
        log.info("Created {} spending transactions", spendTxs.size());

        int mempoolBefore = mempoolService.size();
        submitTransactionsToMempool(spendTxs);
        int mempoolAfter = mempoolService.size();
        assertEquals(mempoolBefore + spendTxs.size(), mempoolAfter,
                "All transactions should be accepted into mempool");
        log.info("Submitted {} transactions, mempool size={}", spendTxs.size(), mempoolAfter);

        Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block result = drainMempoolAndCreateBlock(predecessor, predecessor);
        assertTrue(result != null, "A block should be created from mempool");
        assertTrue(result.getTransactions().size() >= spendTxs.size(),
                "Block should contain submitted transactions");
        log.info("Block created with {} transactions", result.getTransactions().size());

        makeRewardBlock(result);
        blockGraph.updateChain(false);
        log.info("Reward block created, chain updated");

        List<UTXO> allUtxos = store.getAllAvailableUTXOsSorted();
        long recipientBalance = 0;
        for (UTXO u : allUtxos) {
            if (u.getAddress().equals(recipientAddr)) {
                recipientBalance += u.getValue().getValue().longValue();
            }
        }
        assertEquals(45000, recipientBalance,
                "Recipient should have 3 x 15000 = 45000 from the spending transactions");
        log.info("Recipient balance: {}", recipientBalance);
    }
}
