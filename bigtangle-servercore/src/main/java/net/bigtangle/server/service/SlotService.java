package net.bigtangle.server.service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.BlockStoreInterface;

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
    private BlockSaveService blockSaveService;

    @Autowired
    private RandaoService randaoService;

    @Autowired
    private GhostService ghostService;

    @Autowired
    private CasperService casperService;

    @Autowired
    private StakeService stakeService;

    public long getCurrentSlot() {
        return (System.currentTimeMillis() - 1532896109000L) / SLOT_DURATION_MS;
    }

    public long getCurrentEpoch() {
        return getCurrentSlot() / SLOTS_PER_EPOCH;
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

        return Math.abs(seed) % validators.size();
    }

    public Block proposeBeaconBlock(long slot, BlockStoreInterface store) throws Exception {
        long epoch = getEpochForSlot(slot);
        long proposerIdx = selectProposer(slot, store);
        if (proposerIdx < 0) return null;

        List<StakeRecord> validators = store.getActiveStakeDeposits();
        StakeRecord proposer = validators.get((int) proposerIdx);

        byte[] reveal = randaoService.computeReveal(proposer.getPubkey(), slot);

        List<AttestationData> attestations = ghostService.collectAttestations(slot, store);

        Sha256Hash dagRoot = ghostService.getDagRoot(store);

        Block beaconBlock = cacheBlockPrototypeService.getBlockPrototype(store);
        beaconBlock.setBlockType(BlockType.BLOCKTYPE_BEACON);

        SlotData slotData = new SlotData(slot, epoch, proposerIdx, beaconBlock.getPrevBlockHash());
        slotData.setRandaoReveal(reveal);
        slotData.setDagStateRoot(dagRoot);

        beaconBlock.solve();
        blockSaveService.saveBlock(beaconBlock, store);

        casperService.processSlot(slot, beaconBlock.getHash(), attestations, store);

        log.info("Beacon block proposed at slot {} by validator {} (epoch {})",
                slot, proposerIdx, epoch);
        return beaconBlock;
    }

    public void processEpoch(long epoch, BlockStoreInterface store) throws Exception {
        casperService.finalizeCheckpoint(epoch, store);
        log.info("Epoch {} processed: finality updated", epoch);
    }
}
