package net.bigtangle.server.service;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Epoch-based validator reward distribution.
 *
 * At each epoch boundary, distributes accumulated rewards to active
 * validators proportionally to their stake. Rewards come from the
 * existing block reward pool (no new token minting per block).
 *
 * Currently uses a fixed epoch reward amount. In the future this
 * can be replaced with fee-pool distribution when the fee market
 * (EIP-1559) is fully wired.
 */
@Service
public class EpochRewardService {

    private static final Logger log = LoggerFactory.getLogger(EpochRewardService.class);

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    @Autowired
    private StakeService stakeService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private NetworkParameters networkParameters;

    /**
     * Distribute rewards to all active validators for the completed epoch.
     * Creates a single BLOCKTYPE_BEACON block with reward outputs.
     *
     * @param epoch the epoch that just completed
     * @param totalFees collected during the epoch
     * @param store block store
     */
    public Sha256Hash distributeEpochRewards(long epoch, BigInteger totalFees,
            BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        if (validators.isEmpty() || totalFees.compareTo(BigInteger.ZERO) <= 0) {
            return null;
        }

        BigInteger totalStake = validators.stream()
                .map(StakeRecord::getAmount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block rewardBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        rewardBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);

        // Every chain-connected BLOCKTYPE_BEACON must carry a RewardInfo in its
        // first transaction (BlockStoreService.saveChainConnected parses it to
        // solidify referenced DAG blocks). Without it the reward block crashes
        // chain confirmation with a NullPointerException, stalling the chain.
        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        Sha256Hash prevRewardHash = maxConfirmedReward.getBlockHash();
        long chainlength = maxConfirmedReward.getChainLength() + 1;

        RewardInfo rewardInfo = new RewardInfo();
        rewardInfo.setChainlength(chainlength);
        rewardInfo.setPrevRewardHash(prevRewardHash);
        rewardInfo.setBlocks(new HashSet<>());

        Transaction rewardTx = new Transaction(networkParameters);
        rewardTx.setData(rewardInfo.toByteArray());
        rewardBlock.setLastMiningRewardBlock(chainlength);
        rewardBlock.addTransaction(rewardTx);

        long distributed = 0;
        long pool = totalFees.longValue();

        for (int i = 0; i < validators.size(); i++) {
            StakeRecord v = validators.get(i);
            long reward = v.getAmount().multiply(BigInteger.valueOf(pool))
                    .divide(totalStake).longValue();
            if (reward <= 0) continue;
            if (i == validators.size() - 1) {
                reward = pool - distributed; // remainder to last
            }
            Transaction tx = new Transaction(networkParameters);
            tx.addOutput(new Coin(reward, NetworkParameters.BIGTANGLE_TOKENID),
                    net.bigtangle.core.Address.fromHash160(networkParameters,
                            Utils.sha256hash160(v.getPubkey())));
            rewardBlock.addTransaction(tx);
            distributed += reward;
        }

        if (distributed > 0) {
            store.put(rewardBlock);
            log.info("Epoch {}: distributed {} to {} validators",
                    epoch, distributed, validators.size());
            return rewardBlock.getHash();
        }
        return null;
    }
}
