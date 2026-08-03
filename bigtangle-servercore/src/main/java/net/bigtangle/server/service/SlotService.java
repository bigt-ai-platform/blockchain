package net.bigtangle.server.service;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.EpochRewardService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    public static final long SLOT_DURATION_MS = 12_000L;
    public static final long SLOTS_PER_EPOCH = 32L;
    public static final long EPOCH_DURATION_MS = SLOT_DURATION_MS * SLOTS_PER_EPOCH;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    @Autowired
    private EpochRewardService epochRewardService;

    @Autowired
    private BlockSaveService blockSaveService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private RandaoService randaoService;

    @Autowired
    private GhostService ghostService;

    @Autowired
    private CasperService casperService;

    @Autowired
    private StakeService stakeService;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private ObjectMapper jsonmapper;

    public long getCurrentSlot() {
        return (System.currentTimeMillis() - 1532896109000L) / SLOT_DURATION_MS;
    }

    public long getCurrentEpoch() {
        return getCurrentSlot() / SLOTS_PER_EPOCH;
    }

    /** Chain-derived epoch for an absolute wall-clock time (genesis-aligned). */
    public static long epochAt(long timeMs) {
        return ((timeMs - 1532896109000L) / SLOT_DURATION_MS) / SLOTS_PER_EPOCH;
    }

    public long getSlotInEpoch(long slot) {
        return slot % SLOTS_PER_EPOCH;
    }

    public long getEpochForSlot(long slot) {
        return slot / SLOTS_PER_EPOCH;
    }

    public long selectProposer(long slot, BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = store.getActiveStakeDeposits();
        if (validators.isEmpty()) return -1;

        byte[] mix = randaoService.getRandaoMix(slot);
        long seed = (mix[0] & 0xFF) | ((mix[1] & 0xFF) << 8) | ((mix[2] & 0xFF) << 16) | ((long)(mix[3] & 0xFF) << 24);
        seed = seed ^ slot;

        return (seed & Long.MAX_VALUE) % validators.size();
    }

    public Block proposeBeaconBlock(long slot, BlockStoreInterface store) throws Exception {
        long epoch = getEpochForSlot(slot);
        long proposerIdx = selectProposer(slot, store);
        if (proposerIdx < 0) return null;

        List<StakeRecord> validators = store.getActiveStakeDeposits();
        StakeRecord proposer = validators.get((int) proposerIdx);

        byte[] reveal = randaoService.computeReveal(proposer.getPubkey(), slot);

        List<AttestationData> attestations = ghostService.collectAttestations(slot, store);

        // Use the MCMC-validated tip pair (prototype) so the referenced DAG
        // blocks carry a proper block evaluation. Fall back to the ghost
        // fork-choice if the prototype is unavailable.
        Block trunk;
        Block branch;
        try {
            Block prototype = cacheBlockPrototypeService.getBlockPrototype(store);
            trunk = store.get(prototype.getPrevBlockHash());
            branch = store.get(prototype.getPrevBranchBlockHash());
        } catch (Exception e) {
            List<Sha256Hash> tips = ghostService.getTwoTips(store);
            if (tips.isEmpty()) return null;
            trunk = store.get(tips.get(0));
            branch = tips.size() > 1 ? store.get(tips.get(1)) : trunk;
        }
        if (trunk == null || branch == null) return null;

        Block beaconBlock = Block.createBlock(networkParameters, trunk, branch);
        beaconBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);

        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        Sha256Hash prevRewardHash = maxConfirmedReward.getBlockHash();
        long chainlength = maxConfirmedReward.getChainLength() + 1;

        RewardInfo rewardInfo = new RewardInfo();
        rewardInfo.setChainlength(chainlength);
        rewardInfo.setPrevRewardHash(prevRewardHash);
        try {
            // Reference the DAG blocks (token creation, orders, transfers, ...)
            // so they are confirmed on the reward chain — matching the reward
            // service's block collection.
            ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
                    cacheBlockService, jsonmapper);
            long prevChainLength = store.getRewardChainLength(prevRewardHash);
            long cutoffheight = serviceBase.getRewardCutoffHeight(prevRewardHash, store);
            Set<net.bigtangle.server.core.BlockWrap> blocks = new HashSet<>();
            List<BlockType> ordertypes = List.of(
                    BlockType.BLOCKTYPE_INITIAL, BlockType.BLOCKTYPE_TRANSFER, BlockType.BLOCKTYPE_TOKEN_CREATION,
                    BlockType.BLOCKTYPE_FILE, BlockType.BLOCKTYPE_USERDATA, BlockType.BLOCKTYPE_GOVERNANCE,
                    BlockType.BLOCKTYPE_CROSSTANGLE, BlockType.BLOCKTYPE_STAKE, BlockType.BLOCKTYPE_ORDER_OPEN,
                    BlockType.BLOCKTYPE_ORDER_CANCEL, BlockType.BLOCKTYPE_CONTRACT_EVENT,
                    BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL, BlockType.BLOCKTYPE_EVM_DEPLOY,
                    BlockType.BLOCKTYPE_EVM_CALL);
            serviceBase.dagBlockHashesFrom(blocks, serviceBase.getBlockWrap(trunk.getHash(), store), cutoffheight,
                    prevChainLength, ordertypes, true, true, store);
            serviceBase.dagBlockHashesFrom(blocks, serviceBase.getBlockWrap(branch.getHash(), store), cutoffheight,
                    prevChainLength, ordertypes, true, true, store);
            rewardInfo.setBlocks(serviceBase.getHashSet(blocks));
        } catch (Exception e) {
            log.debug("Beacon block reference collection failed, using empty set: {}", e.getMessage());
            rewardInfo.setBlocks(new HashSet<>());
        }

        Transaction tx = new Transaction(networkParameters);
        tx.setData(rewardInfo.toByteArray());
        beaconBlock.setLastMiningRewardBlock(rewardInfo.getChainlength());
        beaconBlock.addTransaction(tx);

        // The epoch's fee pool is paid out by the PROPOSER in the first beacon
        // of the epoch — never in a competing beacon from every node.
        if (getSlotInEpoch(slot) == 0) {
            String chainId = networkParameters.getChainId();
            byte[] poolBytes = store.getPosState("fee", chainId);
            if (poolBytes != null) {
                java.math.BigInteger pool = new java.math.BigInteger(poolBytes);
                if (pool.compareTo(java.math.BigInteger.ZERO) > 0) {
                    for (Transaction rewardTx : epochRewardService.buildEpochRewardTransactions(pool, store)) {
                        beaconBlock.addTransaction(rewardTx);
                    }
                    store.deletePosState("fee", chainId);
                    log.info("Epoch reward pool of {} embedded in proposer beacon at slot {}", pool, slot);
                }
            }
        }

        SlotData slotData = new SlotData(slot, epoch, proposerIdx, trunk.getHash());
        slotData.setRandaoReveal(reveal);
        slotData.setDagStateRoot(ghostService.getDagRoot(store));

        blockSaveService.saveBlock(beaconBlock, store);

        casperService.processSlot(slot, beaconBlock.getHash(), attestations, store);

        log.info("Beacon block proposed at slot {} by validator {} (epoch {}, chainlength {})",
                slot, proposerIdx, epoch, chainlength);
        return beaconBlock;
    }

    public void processEpoch(long epoch, BlockStoreInterface store) throws Exception {
        casperService.finalizeCheckpoint(epoch, store);

        stakeService.processWithdrawals(epoch, store);

        log.info("Epoch {} ({}) processed: finality updated", epoch, networkParameters.getChainId());
    }
}
