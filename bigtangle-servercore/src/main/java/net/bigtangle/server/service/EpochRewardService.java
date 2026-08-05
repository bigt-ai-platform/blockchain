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

    /** Max reward recipients paid out by a single epoch-start beacon. */
    public static final int MAX_EPOCH_REWARD_RECIPIENTS = 5000;
    /** Reward outputs packed per transaction (bounds tx count / block overhead). */
    public static final int OUTPUTS_PER_REWARD_TX = 50;

    @Autowired
    private NetworkParameters networkParameters;

    /**
     * The deterministic epoch-reward split: one (recipient address, BIG amount)
     * entry per validator, proportional to stake, over the GIVEN validator list
     * (the epoch's selection snapshot — never a node-local live set, so the
     * proposer and every validator compute the identical split). The last
     * recipient in order receives the rounding remainder. Validators with a zero
     * share are skipped. When the validator set exceeds
     * {@link #MAX_EPOCH_REWARD_RECIPIENTS}, the TOP recipients by stake are paid
     * (deterministic tie-break), so an oversized set can never overflow the
     * beacon block size. This is the single source of truth used by both the
     * proposer (to build the reward transactions) and beacon validation (to
     * verify them exactly).
     */
    public static java.util.Map<String, BigInteger> planEpochRewards(BigInteger totalFees,
            List<StakeRecord> validators, NetworkParameters params) {
        java.util.Map<String, BigInteger> plan = new java.util.LinkedHashMap<>();
        if (validators == null || validators.isEmpty() || totalFees == null
                || totalFees.compareTo(BigInteger.ZERO) <= 0) {
            return plan;
        }
        List<StakeRecord> recipients = validators;
        if (validators.size() > MAX_EPOCH_REWARD_RECIPIENTS) {
            // Deterministic cap: highest stake first, pubkey tie-break.
            List<StakeRecord> sorted = new ArrayList<>(validators);
            sorted.sort(java.util.Comparator
                    .comparing((StakeRecord v) -> v.getAmount()).reversed()
                    .thenComparing(v -> Utils.HEX.encode(v.getPubkey())));
            recipients = sorted.subList(0, MAX_EPOCH_REWARD_RECIPIENTS);
        }
        BigInteger totalStake = BigInteger.ZERO;
        for (StakeRecord v : recipients) {
            totalStake = totalStake.add(v.getAmount());
        }
        if (totalStake.compareTo(BigInteger.ZERO) <= 0) {
            return plan;
        }
        BigInteger distributed = BigInteger.ZERO;
        for (int i = 0; i < recipients.size(); i++) {
            StakeRecord v = recipients.get(i);
            BigInteger reward = v.getAmount().multiply(totalFees).divide(totalStake);
            if (i == recipients.size() - 1) {
                reward = totalFees.subtract(distributed); // remainder to last
            }
            if (reward.signum() <= 0) {
                continue;
            }
            plan.put(net.bigtangle.core.Address
                    .fromHash160(params, Utils.sha256hash160(v.getPubkey())).toBase58(), reward);
            distributed = distributed.add(reward);
        }
        return plan;
    }

    /**
     * Builds reward transactions for {@link #planEpochRewards}, packing up to
     * {@link #OUTPUTS_PER_REWARD_TX} outputs per transaction so a large
     * validator set produces few transactions. No block is created here — the
     * caller (the elected proposer) embeds these transactions in its beacon.
     */
    public List<Transaction> buildEpochRewardTransactions(BigInteger totalFees,
            List<StakeRecord> validators) throws Exception {
        java.util.Map<String, BigInteger> plan = planEpochRewards(totalFees, validators, networkParameters);
        List<Transaction> txs = new ArrayList<>();
        Transaction current = null;
        int outputsInCurrent = 0;
        for (java.util.Map.Entry<String, BigInteger> e : plan.entrySet()) {
            if (current == null || outputsInCurrent >= OUTPUTS_PER_REWARD_TX) {
                current = new Transaction(networkParameters);
                txs.add(current);
                outputsInCurrent = 0;
            }
            current.addOutput(new Coin(e.getValue(), NetworkParameters.BIGTANGLE_TOKENID),
                    net.bigtangle.core.Address.fromBase58(networkParameters, e.getKey()));
            outputsInCurrent++;
        }
        return txs;
    }
}
