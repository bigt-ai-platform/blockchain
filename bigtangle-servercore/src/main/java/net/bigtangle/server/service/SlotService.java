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
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SlotData;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.EpochRewardService;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.store.BlockStoreInterface;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.bigtangle.utils.Json;

@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    public static final long SLOT_DURATION_MS = 12_000L;
    public static final long SLOTS_PER_EPOCH = 32L;
    public static final long EPOCH_DURATION_MS = SLOT_DURATION_MS * SLOTS_PER_EPOCH;

    /** Configurable slot-tick interval (pos.slotIntervalMs); the EPOCH length is fixed at 32 slots. */
    @org.springframework.beans.factory.annotation.Value("${pos.slotIntervalMs:12000}")
    private long slotIntervalMs = SLOT_DURATION_MS;

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
        return (System.currentTimeMillis() - 1532896109000L) / slotIntervalMs;
    }

    /** The wall-clock slot's position within its epoch, for the given interval. */
    public static long currentSlotInEpoch(long slotIntervalMs) {
        long slot = (System.currentTimeMillis() - 1532896109000L) / slotIntervalMs;
        return slot % SLOTS_PER_EPOCH;
    }

    public long getCurrentEpoch() {
        return getCurrentSlot() / SLOTS_PER_EPOCH;
    }

    /**
     * The CHAIN-derived current epoch (max confirmed reward chainlength / 32).
     * Deterministic from chain state (unlike the wall-clock {@link #getCurrentEpoch()}),
     * used for activation/weighting comparisons so all nodes agree.
     */
    public static long currentChainEpoch(BlockStoreInterface store) {
        try {
            TXReward tip = store.getMaxConfirmedReward();
            return tip != null ? tip.getChainLength() / SLOTS_PER_EPOCH : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Chain-derived epoch for an absolute wall-clock time (genesis-aligned).
     * Uses the CONFIGURED slot interval ({@code pos.slotIntervalMs}) so it stays
     * consistent with {@link #getCurrentSlot()}: when the interval is shorter
     * than the canonical {@link #SLOT_DURATION_MS} (e.g. 2000ms in test), slots
     * advance faster and their epochs must use the same base, otherwise every
     * attestation looks like it targets a far-future epoch and is rejected.
     */
    public long epochAt(long timeMs) {
        return ((timeMs - 1532896109000L) / slotIntervalMs) / SLOTS_PER_EPOCH;
    }

    /** Static variant for consumers that inject the slot interval themselves. */
    public static long epochAt(long timeMs, long slotIntervalMs) {
        return ((timeMs - 1532896109000L) / slotIntervalMs) / SLOTS_PER_EPOCH;
    }

    public long getSlotInEpoch(long slot) {
        return slot % SLOTS_PER_EPOCH;
    }

    public long getEpochForSlot(long slot) {
        return slot / SLOTS_PER_EPOCH;
    }

    /** Epoch-start (rewarding) beacons are proposed at slot % SLOTS_PER_EPOCH == 0. */
    public static boolean isEpochStartSlot(long slot) {
        return slot % SLOTS_PER_EPOCH == 0;
    }

    /**
     * Chain-derived slot sanity for a beacon: the declared epoch must equal
     * slot/32 (self-consistent signed data) and slots must strictly increase
     * along the reward chain ({@code prevSlot} of the prev beacon, -1 when the
     * prev beacon carries no SlotData, e.g. legacy or genesis). This anchors
     * the declared slot to the chain WITHOUT binding it to the reward
     * chainlength — a missed slot must never make the next epoch's beacons
     * invalid (chainlength lags behind the slot after any miss).
     */
    public static boolean slotSequenceValid(long slot, long epoch, long prevSlot) {
        if (epoch != slot / SLOTS_PER_EPOCH) {
            return false;
        }
        return prevSlot < 0 || slot > prevSlot;
    }

    /**
     * Epoch-start classification is SLOT-based: a beacon with a signed SlotData
     * is epoch-start iff its slot % SLOTS_PER_EPOCH == 0. Legacy beacons
     * without SlotData fall back to the chainlength position
     * (chainlength % SLOTS_PER_EPOCH == 1), which coincides with slot % 32 == 0
     * on a drift-free chain, so historical beacons classify identically.
     */
    public static boolean isEpochStartBeacon(Block beacon, RewardInfo ri) {
        if (beacon == null || beacon.getTransactions() == null) {
            return false;
        }
        try {
            for (Transaction tx : beacon.getTransactions()) {
                if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
                    SlotData sd = Json.jsonmapper().readValue(tx.getData(), SlotData.class);
                    if (sd != null) {
                        return isEpochStartSlot(sd.getSlot());
                    }
                }
            }
        } catch (Exception e) {
            // fall through to the legacy chainlength classification
        }
        return ri != null && ri.getChainlength() > 0
                && ri.getChainlength() % SLOTS_PER_EPOCH == 1;
    }

    /**
     * The validator set governing {@code slot}: the SNAPSHOTTED active set from
     * two epochs earlier (same boundary discipline as the RANDAO mixfinal),
     * falling back to the live set only before the snapshot exists (bootstrap).
     * Proposer selection, beacon validation and the epoch-reward split must all
     * use this exact list so they agree on every node.
     */
    public static List<StakeRecord> selectionValidators(long slot, BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = getValidatorSnapshot(slot / 32 - 2, store);
        // An EMPTY snapshot is treated as missing: freezing an empty set would
        // select no proposer for every slot of the epoch — and since snapshots
        // are only written by confirming beacons, the chain could never recover.
        if (validators == null || validators.isEmpty()) {
            validators = store.getActiveStakeDeposits();
        }
        return validators;
    }

    public long selectProposer(long slot, BlockStoreInterface store) throws Exception {
        // Selection uses the SNAPSHOTTED active validator set from two epochs
        // earlier (same boundary discipline as the RANDAO mixfinal), never the
        // node's live, locally-confirmed set — otherwise nodes at different
        // confirmation heights derive different proposers for the same slot.
        return selectProposerForSlot(slot, selectionValidators(slot, store),
                randaoService.getSelectionMix(slot, store));
    }

    /**
     * Snapshots the active validator set into an immutable {@code validators_}
     * record at the epoch boundary (first beacon of the following epoch
     * confirms), exactly like the RANDAO mixfinal. Selection and validation read
     * it two epochs later, so the expected proposer for a slot is a fixed chain
     * fact, identical on every node.
     */
    public static void snapshotValidatorsForEpoch(long epoch, BlockStoreInterface store) throws BlockStoreException {
        if (epoch < 0) {
            return;
        }
        if (store.getPosState("posvalidators", "validators_" + epoch) == null) {
            List<StakeRecord> active = store.getActiveStakeDeposits();
            // Never freeze an EMPTY set: with no fallback the epoch two epochs
            // later would have no proposer for any slot, no beacons would
            // confirm, no new snapshot would be written — an unrecoverable halt.
            if (active.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (StakeRecord v : active) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(Utils.HEX.encode(v.getPubkey())).append(':').append(v.getAmount().longValue());
            }
            store.savePosState("posvalidators", "validators_" + epoch,
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** The immutable active-validator snapshot for {@code epoch}, or null if not yet written. */
    public static List<StakeRecord> getValidatorSnapshot(long epoch, BlockStoreInterface store) {
        try {
            byte[] raw = epoch >= 0 ? store.getPosState("posvalidators", "validators_" + epoch) : null;
            if (raw == null) {
                return null;
            }
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            List<StakeRecord> list = new java.util.ArrayList<>();
            if (s.isEmpty()) {
                return list;
            }
            for (String part : s.split(";")) {
                int idx = part.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                StakeRecord v = new StakeRecord(Utils.HEX.decode(part.substring(0, idx)),
                        java.math.BigInteger.valueOf(Long.parseLong(part.substring(idx + 1))), null);
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure, deterministic stake-weighted proposer selection over the full
     * 32-byte RANDAO mix mixed with the slot. Also used by beacon validation to
     * verify that a beacon's signer is the actual slot proposer.
     */
    public static long selectProposerForSlot(long slot, List<StakeRecord> validators, byte[] mix) {
        if (validators.isEmpty()) return -1;

        BigInteger totalStake = BigInteger.ZERO;
        for (StakeRecord v : validators) {
            totalStake = totalStake.add(StakeService.effectiveBalance(v));
        }
        if (totalStake.signum() <= 0) return -1;

        byte[] mixBytes = mix != null ? mix : new byte[32];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(mixBytes.length + 8);
        buf.put(mixBytes);
        buf.putLong(slot);
        byte[] seedBytes = Sha256Hash.of(buf.array()).getBytes();
        BigInteger seed = new BigInteger(1, seedBytes);

        // Weighted selection: the proposer is chosen with probability
        // proportional to effective (capped) stake, not uniformly per validator.
        BigInteger pick = seed.mod(totalStake);
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < validators.size(); i++) {
            acc = acc.add(StakeService.effectiveBalance(validators.get(i)));
            if (pick.compareTo(acc) < 0) {
                return i;
            }
        }
        return validators.size() - 1;
    }

    /**
     * Deterministic fee surplus of a block: sum over non-coinbase transactions
     * of (BIG input value − BIG output value) where positive. Used to compute
     * the epoch reward from the CONFIRMED blocks a beacon references, so both
     * the proposer and every validator derive the same expected payout from
     * chain state instead of trusting a declaration.
     */
    public static java.math.BigInteger computeFeeSurplus(Block block, BlockStoreInterface store) throws Exception {        java.math.BigInteger surplus = java.math.BigInteger.ZERO;
        if (block == null || block.getTransactions() == null) {
            return surplus;
        }
        for (Transaction tx : block.getTransactions()) {
            if (tx.isCoinBase() || tx.getInputs() == null) {
                continue;
            }
            java.math.BigInteger txIn = java.math.BigInteger.ZERO;
            java.math.BigInteger txOut = java.math.BigInteger.ZERO;
            for (TransactionOutput out : tx.getOutputs()) {
                if (out.getValue().isBIG()) {
                    txOut = txOut.add(out.getValue().getValue());
                }
            }
            for (TransactionInput in : tx.getInputs()) {
                Coin inValue = null;
                TransactionOutput connected = in.getOutpoint().getConnectedOutput();
                if (connected != null) {
                    inValue = connected.getValue();
                } else {
                    UTXO utxo = store.getTransactionOutput(
                            in.getOutpoint().getBlockHash(), in.getOutpoint().getTxHash(),
                            in.getOutpoint().getIndex());
                    if (utxo == null) {
                        // An unresolvable input must PROPAGATE (fail closed),
                        // never contribute zero — otherwise nodes with different
                        // UTXO availability would compute different fee totals
                        // and disagree on the same beacon.
                        throw new net.bigtangle.exception.BlockStoreException(
                                "Cannot resolve input UTXO for fee computation: " + in.getOutpoint());
                    }
                    inValue = utxo.getValue();
                }
                if (inValue != null && inValue.isBIG()) {
                    txIn = txIn.add(inValue.getValue());
                }
            }
            java.math.BigInteger s = txIn.subtract(txOut);
            if (s.signum() > 0) {
                surplus = surplus.add(s);
            }
        }
        return surplus;
    }

    /**
     * The set of blocks already rewarded by a previous EPOCH-START beacon within
     * the reward window, walked back the reward chain. Only epoch-start beacons
     * (signed slot % SLOTS_PER_EPOCH == 0, see {@link #isEpochStartBeacon})
     * reward blocks, so only their reference sets count — a block
     * referenced/confirmed by a mid-epoch beacon is NOT yet rewarded and remains
     * eligible for this epoch's payout.
     */
    private java.util.Set<Sha256Hash> previousEpochRewarded(Sha256Hash prevRewardHash, BlockStoreInterface store)
            throws BlockStoreException {
        java.util.Set<Sha256Hash> rewarded = new java.util.HashSet<>();
        Sha256Hash cursor = prevRewardHash;
        java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
        int walkCount = 0;
        while (cursor != null && visited.add(cursor)
                && walkCount < net.bigtangle.params.NetworkParameters.CHAINLENGTH_CUTOFF) {
            walkCount++;
            Block prevBeacon = store.get(cursor);
            if (prevBeacon == null || prevBeacon.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                break;
            }
            try {
                RewardInfo prevRi = new RewardInfo().parseChecked(prevBeacon.getTransactions().get(0).getData());
                if (prevRi != null) {
                    if (prevRi.getBlocks() != null && isEpochStartBeacon(prevBeacon, prevRi)) {
                        rewarded.addAll(prevRi.getBlocks());
                    }
                    cursor = prevRi.getPrevRewardHash();
                } else {
                    break;
                }
            } catch (Exception e) {
                break;
            }
        }
        return rewarded;
    }

    public Block proposeBeaconBlock(long slot, PQKey proposerKey, BlockStoreInterface store) throws Exception {
        long epoch = getEpochForSlot(slot);
        long proposerIdx = selectProposer(slot, store);
        if (proposerIdx < 0) return null;

        // The proposer index refers to the SELECTION SNAPSHOT list — looking it
        // up in the live set would pick the wrong validator (or fail) whenever
        // the two differ.
        List<StakeRecord> validators = selectionValidators(slot, store);
        StakeRecord proposer = validators.get((int) proposerIdx);

        byte[] reveal = proposerKey != null ? randaoService.computeReveal(proposerKey, slot) : null;

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
                    BlockType.BLOCKTYPE_CROSSTANGLE, BlockType.BLOCKTYPE_STAKE, BlockType.BLOCKTYPE_SLASHING,
                    BlockType.BLOCKTYPE_EXIT, BlockType.BLOCKTYPE_ORDER_OPEN,
                    BlockType.BLOCKTYPE_ORDER_CANCEL, BlockType.BLOCKTYPE_CONTRACT_EVENT,
                    BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL, BlockType.BLOCKTYPE_EVM_DEPLOY,
                    BlockType.BLOCKTYPE_EVM_CALL);
            // The epoch-start beacon must REWARD every block confirmed during its
            // epoch (a block may already be confirmed by a mid-epoch beacon that
            // referenced it), so it collects already-confirmed blocks too and then
            // subtracts the blocks a previous epoch-start beacon already rewarded.
            boolean epochStart = getSlotInEpoch(slot) == 0;
            serviceBase.dagBlockHashesFrom(blocks, serviceBase.getBlockWrap(trunk.getHash(), store), cutoffheight,
                    prevChainLength, ordertypes, true, true, epochStart, store);
            serviceBase.dagBlockHashesFrom(blocks, serviceBase.getBlockWrap(branch.getHash(), store), cutoffheight,
                    prevChainLength, ordertypes, true, true, epochStart, store);
            if (epochStart) {
                java.util.Set<Sha256Hash> prevRewarded = previousEpochRewarded(prevRewardHash, store);
                blocks.removeIf(bw -> prevRewarded.contains(bw.getBlock().getHash()));
            }
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
        // of the epoch — never in a competing beacon from every node. The pool
        // is recomputed from the CONFIRMED blocks this beacon references
        // (RewardInfo.getBlocks), so validators derive the same expected payout
        // deterministically from chain state instead of trusting a declaration.
        java.math.BigInteger epochFeePool = java.math.BigInteger.ZERO;
        if (getSlotInEpoch(slot) == 0) {
            for (Sha256Hash h : rewardInfo.getBlocks()) {
                Block referenced = store.get(h);
                if (referenced != null) {
                    epochFeePool = epochFeePool.add(computeFeeSurplus(referenced, store));
                }
            }
            if (epochFeePool.compareTo(java.math.BigInteger.ZERO) > 0) {
                // The split is computed over the epoch's SELECTION SNAPSHOT,
                // restricted to the validators that actually attested the
                // rewarded epoch (two epochs back — fully confirmed, matching the
                // snapshot/RANDAO lag), so the outputs are a deterministic
                // function of chain state.
                for (Transaction rewardTx : epochRewardService.buildEpochRewardTransactions(
                        epochFeePool, selectionValidators(slot, store),
                        CasperService.votersForEpoch(epoch - 2, store))) {
                    beaconBlock.addTransaction(rewardTx);
                }
                log.info("Epoch reward pool of {} embedded in proposer beacon at slot {}", epochFeePool, slot);
            }
        }

        SlotData slotData = new SlotData(slot, epoch, proposerIdx, trunk.getHash());
        slotData.setRandaoReveal(reveal);
        slotData.setDagStateRoot(ghostService.getDagRoot(store));
        // Inclusion commitment: commit the full attestation set the proposer
        // includes, plus a deterministic root over it in the signed SlotData.
        List<AttestationData> includedAttestations = casperService.getAttestationsForSlot(slot, store);
        slotData.setAttestationRoot(casperService.computeAttestationRoot(includedAttestations));
        slotData.setAttestations(includedAttestations);
        if (getSlotInEpoch(slot) == 0) {
            // Snapshot the fee pool that funded the reward outputs so validators
            // can recompute the exact expected payout deterministically.
            slotData.setFeePool(epochFeePool.longValue());
        }
        if (proposerKey != null) {
            // The proposer signs the slot data so the declared slot, fee pool
            // and RANDAO reveal are authenticated, not self-declared.
            slotData.setProposerSignature(proposerKey.sign(slotData.getMessageHash()).serialize());
        }

        // Commit the slot data (incl. the RANDAO reveal) on-chain so validators
        // can scope minting to epoch-start beacons and mix the reveal.
        Transaction slotTx = new Transaction(networkParameters);
        slotTx.setDataClassName("SlotData");
        slotTx.setData(Json.jsonmapper().writeValueAsBytes(slotData));
        beaconBlock.addTransaction(slotTx);

        blockSaveService.saveBlock(beaconBlock, store);

        casperService.processSlot(slot, beaconBlock.getHash(), attestations, store);

        log.info("Beacon block proposed at slot {} by validator {} (epoch {}, chainlength {})",
                slot, proposerIdx, epoch, chainlength);
        return beaconBlock;
    }

    public void processEpoch(long epoch, BlockStoreInterface store) throws Exception {
        casperService.finalizeCheckpoint(epoch, store);

        // Withdrawals are NOT processed here: bond release must happen at the
        // same chain position on every node, so it is driven by the confirm
        // batch (BlockStoreService.confirmDo), never by this wall-clock tick —
        // bond-spend validation reads the stake table, and a wall-clock release
        // would diverge between nodes that ticked at different times.

        prunePosState(store);

        log.info("Epoch {} ({}) processed: finality updated", epoch, networkParameters.getChainId());
    }

    /**
     * Bounds pos_state growth: per-epoch records whose epoch is below the
     * finalized floor (finalizedEpoch - margin) can no longer influence
     * selection, finality or slashing and are deleted. Runs at each epoch tick,
     * so the node-local side tables (mix/mixfinal/validator snapshots/used
     * slots/checkpoints) never grow unboundedly with chain age.
     */
    private void prunePosState(BlockStoreInterface store) throws Exception {
        long floor = 0;
        if (casperService != null) {
            net.bigtangle.server.service.CasperService.Checkpoint fin = casperService.getLastFinalizedCheckpoint();
            if (fin != null) {
                floor = Math.max(0, fin.epoch - 1); // keep one epoch of slack
            }
        }
        // randao: per-epoch live mixes and immutable selection snapshots.
        pruneEpochKeys(store, "randao", "mix_", floor);
        pruneEpochKeys(store, "randao", "mixfinal_", floor);
        // posvalidators: per-epoch active-validator snapshots.
        pruneEpochKeys(store, "posvalidators", "validators_", floor);
        // pos: per-slot used-slot bindings (proposal equivocation) + proposer-boost
        // sighting registry below the floor.
        pruneSlotKeys(store, "pos", "usedslot_", floor);
        pruneSlotKeys(store, "pos", "slotsight_", floor);
        // casper: per-epoch checkpoints below the floor (genesis is retained).
        pruneEpochKeys(store, "casper", "ckpt_", floor);
        // attestation: full signed attestations persisted per slot (chain-read).
        pruneAttestationKeys(store, floor);
    }

    /** Prunes persisted full attestations whose slot is below the finalized floor. */
    private void pruneAttestationKeys(BlockStoreInterface store, long floor) throws Exception {
        for (String key : store.getPosStateByService("attestation").keySet()) {
            if (!key.startsWith("att_")) {
                continue;
            }
            try {
                // key format: "att_<slot>_<pubkeyhex>"
                int secondUnderscore = key.indexOf('_', "att_".length());
                if (secondUnderscore < 0) {
                    continue;
                }
                long slot = Long.parseLong(key.substring("att_".length(), secondUnderscore));
                if (slot / SLOTS_PER_EPOCH < floor) {
                    store.deletePosState("attestation", key);
                }
            } catch (NumberFormatException ignored) {
                // malformed key — leave it
            }
        }
    }

    private void pruneEpochKeys(BlockStoreInterface store, String service, String prefix, long floor)
            throws Exception {
        for (String key : store.getPosStateByService(service).keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            try {
                long epoch = Long.parseLong(key.substring(prefix.length()));
                if (epoch < floor) {
                    store.deletePosState(service, key);
                }
            } catch (NumberFormatException ignored) {
                // non-numeric key — leave it
            }
        }
    }

    private void pruneSlotKeys(BlockStoreInterface store, String service, String prefix, long floor)
            throws Exception {
        for (String key : store.getPosStateByService(service).keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            try {
                long slot = Long.parseLong(key.substring(prefix.length()));
                if (slot / SLOTS_PER_EPOCH < floor) {
                    store.deletePosState(service, key);
                }
            } catch (NumberFormatException ignored) {
                // non-numeric key — leave it
            }
        }
    }
}
