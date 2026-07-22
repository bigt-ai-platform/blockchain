/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.attacktest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.service.EpochRewardService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class FeePoolRewardTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(FeePoolRewardTest.class);

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private EpochRewardService epochRewardService;

    @Autowired
    private StakeService stakeService;

    @Test
    public void testFeeAccumulationAndDistribution() throws Exception {
        PQKey testKey = PQKey.createNew();
        Wallet w = Wallet.fromKeys(networkParameters, testKey, contextRoot);

        String chainId = networkParameters.getChainId();
        byte[] poolBefore = store.getPosState("fee", chainId);
        BigInteger poolBeforeVal = poolBefore == null ? BigInteger.ZERO : new BigInteger(poolBefore);
        log.info("Fee pool before ({}): {}", chainId, poolBeforeVal);

        PQKey receiver = PQKey.createNew();
        List<FreeStandingTransactionOutput> candidates =
                w.calculateAllSpendCandidates(null, false);

        Transaction tx = w.createTransaction(null, candidates,
                receiver.toAddress(networkParameters).toString(),
                Coin.valueOf(5000, NetworkParameters.BIGTANGLE_TOKENID),
                "fee-pool-test");

        BigInteger txIn = BigInteger.ZERO;
        BigInteger txOut = BigInteger.ZERO;
        BlockStoreInterface store2 = storeService.getStore();
        try {
            for (TransactionOutput out : tx.getOutputs()) {
                if (out.getValue().isBIG()) {
                    txOut = txOut.add(out.getValue().getValue());
                }
            }
            for (TransactionInput in : tx.getInputs()) {
                if (in.getOutpoint().isCoinBase()) continue;
                UTXO utxo = store2.getTransactionOutput(
                        in.getOutpoint().getBlockHash(),
                        in.getOutpoint().getTxHash(),
                        in.getOutpoint().getIndex());
                if (utxo != null && utxo.getValue().isBIG()) {
                    txIn = txIn.add(utxo.getValue().getValue());
                }
            }
        } finally {
            store2.close();
        }
        BigInteger expectedFee = txIn.subtract(txOut);
        assertTrue(expectedFee.compareTo(Coin.FEE_DEFAULT.getValue()) >= 0,
                "Fee surplus must be at least FEE_DEFAULT: " + expectedFee);
        log.info("Transaction fee surplus: {}", expectedFee);

        Block tip = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
        Block block = Block.createBlock(networkParameters, tip, tip);
        block.addTransaction(tx);

        blockSaveService.saveBlock(block, store);

        byte[] poolAfter = store.getPosState("fee", chainId);
        BigInteger poolAfterVal = poolAfter == null ? BigInteger.ZERO : new BigInteger(poolAfter);
        BigInteger accumulated = poolAfterVal.subtract(poolBeforeVal);
        log.info("Fee pool after: {}, accumulated: {}", poolAfterVal, accumulated);

        assertEquals(expectedFee, accumulated,
                "Accumulated fee should equal the transaction fee surplus");

        PQKey validator = PQKey.createNew();
        BigInteger stakeAmount = StakeService.MIN_STAKE;
        fundAndStake(validator, stakeAmount);
        stakeService.activateValidator(validator.getPubKey(), 0, store);

        BigInteger totalStake = stakeService.getTotalActiveStake(store);
        assertTrue(totalStake.compareTo(BigInteger.ZERO) > 0, "Should have active stake");

        long epoch = 1;
        BigInteger poolForDistribution = poolAfterVal;
        net.bigtangle.core.Sha256Hash rewardHash = epochRewardService.distributeEpochRewards(
                epoch, poolForDistribution, store);
        assertNotNull(rewardHash, "distributeEpochRewards should return a block hash");

        Block rewardBlock = store.get(rewardHash);
        assertNotNull(rewardBlock, "Reward block should exist");
        assertEquals(BlockType.BLOCKTYPE_BEACON, rewardBlock.getBlockType());

        long totalBigOutput = 0;
        for (Transaction rtx : rewardBlock.getTransactions()) {
            for (TransactionOutput out : rtx.getOutputs()) {
                if (out.getValue().isBIG()) {
                    totalBigOutput += out.getValue().getValue().longValue();
                }
            }
        }
        assertTrue(totalBigOutput > 0, "Reward block should distribute BIG to validators");
        assertTrue(BigInteger.valueOf(totalBigOutput).compareTo(poolForDistribution) <= 0,
                "Total distributed must not exceed pool: " + totalBigOutput + " <= " + poolForDistribution);
        log.info("Distributed {} to validators from pool of {}",
                totalBigOutput, poolForDistribution);

        byte[] poolAfterDist = store.getPosState("fee", chainId);
        store.deletePosState("fee", chainId);
        log.info("Fee pool after distribution (before delete): {}",
                poolAfterDist == null ? "null" : new BigInteger(poolAfterDist).toString());

        log.info("=== TEST PASSED ===");
    }

    private void fundAndStake(PQKey key, BigInteger amount) throws Exception {
        java.util.HashMap<String, BigInteger> fund = new java.util.HashMap<>();
        fund.put(key.toAddress(networkParameters).toString(),
                amount.add(BigInteger.valueOf(100000)));
        Block fb = wrapTransaction(wallet.payMoneyToECKeyList(null, fund,
                NetworkParameters.BIGTANGLE_TOKENID, "fund"));
        if (fb != null) {
            makeRewardBlock(fb);
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }

        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block depositBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        depositBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);
        store.put(depositBlock);
        store.saveStakeDeposit(new StakeRecord(key.getPubKey(), amount, null));
    }
}
