package net.bigtangle.server.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Coin;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Epoch-based validator reward distribution.
 *
 * <p>Rewards are NOT minted in a competing beacon by every node. Instead the
 * epoch's fee pool is converted into reward transactions which the single
 * elected proposer embeds in the epoch's first beacon block. That preserves the
 * one-proposer-per-slot invariant.
 */
@Service
public class EpochRewardService {

    private static final Logger log = LoggerFactory.getLogger(EpochRewardService.class);

    @Autowired
    private NetworkParameters networkParameters;

    /**
     * Builds one value-creation transaction per active validator, proportional
     * to stake, for the given accumulated fee pool. No block is created here —
     * the caller (the elected proposer) embeds these transactions in its beacon.
     */
    public List<Transaction> buildEpochRewardTransactions(BigInteger totalFees,
            BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        if (validators.isEmpty() || totalFees.compareTo(BigInteger.ZERO) <= 0) {
            return List.of();
        }

        BigInteger totalStake = BigInteger.ZERO;
        for (StakeRecord v : validators) {
            totalStake = totalStake.add(v.getAmount());
        }
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
            return List.of();
        }

        List<Transaction> txs = new ArrayList<>();
        long pool = totalFees.longValue();
        long distributed = 0;

        for (int i = 0; i < validators.size(); i++) {
            StakeRecord v = validators.get(i);
            long reward = v.getAmount().multiply(BigInteger.valueOf(pool))
                    .divide(totalStake).longValue();
            if (i == validators.size() - 1) {
                reward = pool - distributed; // remainder to last
            }
            if (reward <= 0) {
                continue;
            }
            Transaction tx = new Transaction(networkParameters);
            tx.addOutput(new Coin(BigInteger.valueOf(reward), NetworkParameters.BIGTANGLE_TOKENID),
                    net.bigtangle.core.Address.fromHash160(networkParameters,
                            Utils.sha256hash160(v.getPubkey())));
            txs.add(tx);
            distributed += reward;
        }
        return txs;
    }
}
