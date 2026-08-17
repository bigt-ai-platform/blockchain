package net.bigtangle.server.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UTXO;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

@Service
public class StakeService {

    private static final Logger log = LoggerFactory.getLogger(StakeService.class);

    public static final BigInteger MIN_STAKE = BigInteger.valueOf(32_000_000L);
    /**
     * Cap on the EFFECTIVE balance used for proposer selection, attestation
     * weight, rewards and the justification denominator. Bonds the influence of
     * any single validator (Ethereum's MAX_EFFECTIVE_BALANCE = 32 ETH analog;
     * here 32 BIG). The full bonded amount stays on-chain; only its effective
     * weight is capped.
     */
    public static final BigInteger MAX_EFFECTIVE_BALANCE = MIN_STAKE;
    public static final long WITHDRAWAL_DELAY_EPOCHS = 256;
    /** Activation delay (Ethereum MAX_SEED_LOOKAHEAD): a deposit becomes active
     *  this many epochs + 1 after it is registered. */
    public static final long MAX_SEED_LOOKAHEAD = 4;
    /** Minimum validators entering/exiting per epoch (Ethereum churn floor). */
    public static final int MIN_PER_EPOCH_CHURN_LIMIT = 4;
    /** Ethereum CHURN_LIMIT_QUOTIENT. */
    public static final int CHURN_LIMIT_QUOTIENT = 65536;

    /** Validators that may enter/exit per epoch (bounds validator-set churn). */
    public static long churnLimit(long activeCount) {
        return Math.max(MIN_PER_EPOCH_CHURN_LIMIT, activeCount / CHURN_LIMIT_QUOTIENT);
    }
    /**
     * Graded slash (Ethereum's minimum penalty): 1/32 of the bond is
     * confiscated — 1/512 of that (the "slashed" amount) goes to the
     * whistleblower who proposed the proof, the rest is burned. The remaining
     * 31/32 is minted back to the slashed validator as a reorg-aware refund
     * UTXO (see {@link #mintSlashingRefund}). Enabled by the refund-mint
     * lifecycle wired in alongside {@link #applySlashingBlock} /
     * {@link #applySlashingConfirmed} / {@link #revertSlashingBlock}.
     */
    public static final int SLASH_PENALTY_DIVISOR = 32;
    /** Whistleblower share of the slashed penalty (Ethereum's WHISTLEBLOWER_REWARD_QUOTIENT). */
    public static final int WHISTLEBLOWER_REWARD_DIVISOR = 512;
    /** Synthetic output index for the store-level slashing refund UTXO mint. */
    private static final long SLASHING_REFUND_OUTPUT_INDEX = 1L;
    /** Synthetic output index for the store-level whistleblower reward UTXO mint. */
    private static final long SLASHING_REPORTER_OUTPUT_INDEX = 2L;
    public static final String STAKE_DATA_CLASS = "StakeDeposit";
    public static final String SLASHING_DATA_CLASS = "SlashingProof";
    public static final String EXIT_DATA_CLASS = "ExitRequest";

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private BlockSaveService blockSaveService;

    /** Slot-tick interval (pos.slotIntervalMs); must match SlotService's epoch base. */
    @org.springframework.beans.factory.annotation.Value("${pos.slotIntervalMs:12000}")
    private long slotIntervalMs = SlotService.SLOT_DURATION_MS;

    /** This node's configured validator key (pos.validatorKey), hex. When set,
     *  SLASHING blocks proposed by this node carry it as the whistleblower
     *  identity and receive the 1/512 reporter reward. */
    @org.springframework.beans.factory.annotation.Value("${pos.validatorKey:}")
    private String configuredValidatorKeyHex = "";

    public long getEffectiveStake(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        // The activation delay is enforced here too: a deposit is not yet active
        // (cannot attest) until the CURRENT CHAIN epoch reaches its activation
        // epoch. Same chain-derived domain on every node.
        if (stake == null || stake.isSlashed() || stake.getActivatedEpoch() < 0) return 0L;
        if (stake.getActivatedEpoch() > SlotService.currentChainEpoch(store)) return 0L;
        return effectiveBalance(stake.getAmount()).longValue();
    }

    /**
     * Chain-derived activation epoch for a STAKE deposit block: the deposit's
     * chain epoch (parent beacon chainlength / SLOTS_PER_EPOCH) plus
     * {@link #MAX_SEED_LOOKAHEAD} + 1. The FIRST epoch (chain epoch 0) is the
     * genesis bootstrap window — those deposits activate immediately so the
     * initial validator set can start producing beacons (no validators yet
     * means no beacons, so no chain position to delay against). Fully
     * deterministic: derived from the block's parent chain, never from wall
     * clock or gossip save order.
     */
    private long depositActivationEpoch(Block block, BlockStoreInterface store) {
        long depositEpoch;
        try {
            depositEpoch = chainEpochOf(block, store);
        } catch (Exception e) {
            // Parent not a beacon/genesis (e.g. during early sync before the
            // parent chain is available). Fall back to the current confirmed
            // chain epoch; the block's own position is re-derived on
            // confirmation.
            log.debug("Deposit block {} has no beacon parent, using current chain epoch: {}",
                    block.getHashAsString(), e.getMessage());
            depositEpoch = SlotService.currentChainEpoch(store);
        }
        if (depositEpoch <= 0) {
            return 0L; // genesis bootstrap window — activate immediately
        }
        return depositEpoch + MAX_SEED_LOOKAHEAD + 1;
    }

    /** Capped effective balance for a stake amount (bounded influence). */
    public static BigInteger effectiveBalance(BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        return amount.min(MAX_EFFECTIVE_BALANCE);
    }

    /** Capped effective balance of a validator record. */
    public static BigInteger effectiveBalance(StakeRecord stake) {
        return stake == null ? BigInteger.ZERO : effectiveBalance(stake.getAmount());
    }

    /**
     * Serializes {@code pubkey} + {@code blsPubkey} + {@code blsProofOfPossession}
     * + {@code withdrawalCredentials} into the STAKE tx payload. The BLS public
     * key (derived deterministically from the depositor's ML-DSA private key, see
     * RandaoService.blsPubkey) is what validators use to verify the depositor's
     * unique RANDAO reveals; the proof of possession binds the BLS key to the
     * ML-DSA identity so a rogue key can never be registered.
     */
    public static byte[] buildStakeDepositData(byte[] pubkey, byte[] blsPubkey, byte[] blsProofOfPossession,
            byte[] withdrawalCredentials) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(pubkey.length);
            dos.write(pubkey);
            if (blsPubkey != null) {
                dos.writeInt(blsPubkey.length);
                dos.write(blsPubkey);
            } else {
                dos.writeInt(0);
            }
            if (blsProofOfPossession != null) {
                dos.writeInt(blsProofOfPossession.length);
                dos.write(blsProofOfPossession);
            } else {
                dos.writeInt(0);
            }
            if (withdrawalCredentials != null) {
                dos.writeInt(withdrawalCredentials.length);
                dos.write(withdrawalCredentials);
            } else {
                dos.writeInt(0);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses {@link #buildStakeDepositData}; returns
     * {@code {pubkey, blsPubkey, blsProofOfPossession, withdrawalCredentials}}.
     */
    public static byte[][] parseStakeDepositData(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int plen = dis.readInt();
        byte[] pubkey = new byte[plen];
        dis.readFully(pubkey);
        int blen = dis.readInt();
        byte[] blsPubkey = null;
        if (blen > 0) {
            blsPubkey = new byte[blen];
            dis.readFully(blsPubkey);
        }
        int popLen = dis.readInt();
        byte[] blsProofOfPossession = null;
        if (popLen > 0) {
            blsProofOfPossession = new byte[popLen];
            dis.readFully(blsProofOfPossession);
        }
        int clen = dis.readInt();
        byte[] creds = null;
        if (clen > 0) {
            creds = new byte[clen];
            dis.readFully(creds);
        }
        return new byte[][] { pubkey, blsPubkey, blsProofOfPossession, creds };
    }

    public void processDeposit(UTXO utxo, byte[] withdrawalCredentials,
            PQKey depositKey, BlockStoreInterface store) throws Exception {
        if (utxo.getValue().getValue().compareTo(MIN_STAKE) < 0) {
            throw new IllegalArgumentException("Stake must be at least " + MIN_STAKE);
        }

        // Build the STAKE block on a known-valid block (the max confirmed
        // reward) rather than the MCMC prototype, whose predecessor tips may
        // not be persisted yet (NoBlockException on saveBlock).
        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        Block head = store.get(maxConfirmedReward.getBlockHash());
        Block b = Block.createBlock(networkParameters, head, head);
        b.setBlockType(BlockType.BLOCKTYPE_STAKE);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(STAKE_DATA_CLASS);
        byte[] blsPubkey = net.bigtangle.server.service.RandaoService.blsPubkey(depositKey);
        byte[] pop = net.bigtangle.server.service.RandaoService.blsProofOfPossession(depositKey);
        tx.setData(buildStakeDepositData(depositKey.getPubKey(), blsPubkey, pop, withdrawalCredentials));

        Script script = utxo.getScript();
        if (script == null) {
            script = new Script(new byte[0]);
        }
        TransactionInput input = tx.addInput(utxo.getBlockHash(), utxo.getTxHash(), utxo.getIndex(), script);
        Coin stakeOutput = utxo.getValue().subtract(Coin.FEE_DEFAULT);
        tx.addOutput(stakeOutput, depositKey);

        // Sign the stake input with the depositor key (P2PKH: sig + pubkey).
        Sha256Hash sighash = tx.hashForSignature(0, script.getProgram(), Transaction.SigHash.ALL, false);
        SignatureBundle sig = depositKey.sign(sighash);
        input.setScriptSig(ScriptBuilder.createInputScriptForPQ(sig, depositKey));

        b.addTransaction(tx);

        // Saving the STAKE block triggers applyStakeBlock via BlockSaveService,
        // which derives the validator-set record from the block (chain-derived)
        // rather than this local HTTP call.
        blockSaveService.saveBlock(b, store);

        log.info("Stake deposit: {} BIG from pubkey={}", utxo.getValue(),
                Utils.HEX.encode(depositKey.getPubKey()));
    }

    /**
     * Chain-derived validator-set application: derives a deposit from a
     * (validated) STAKE block and records it in the stake table, then locks the
     * bonded output so the funds are not freely spendable. Supports top-ups:
     * a new STAKE block for an already-deposited pubkey ACCUMULATES its amount.
     * Idempotent (re-save of the same block is a no-op).
     */
    public void applyStakeBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_STAKE || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!STAKE_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        byte[][] parts;
        try {
            parts = parseStakeDepositData(tx.getData());
        } catch (IOException e) {
            log.debug("STAKE block {} has malformed deposit data, skipping: {}",
                    block.getHashAsString(), e.getMessage());
            return;
        }
        byte[] pubkey = parts[0];
        byte[] blsPubkey = parts[1];
        byte[] pop = parts[2];
        byte[] creds = parts[3];

        // The registered BLS key must be a well-formed G1 point AND proven held
        // by the depositor (proof of possession over the ML-DSA pubkey). A
        // deposit with a missing, malformed or unproven BLS key is REJECTED so a
        // bad key can never join the active set and create an unfillable slot.
        if (!net.bigtangle.server.service.RandaoService.isValidBlsPubkey(blsPubkey)
                || !net.bigtangle.server.service.RandaoService.verifyProofOfPossession(blsPubkey, pubkey, pop)) {
            return;
        }

        Coin amount = null;
        for (TransactionOutput out : tx.getOutputs()) {
            if (out.getValue().isBIG()) {
                amount = out.getValue();
                break;
            }
        }
        if (amount == null || amount.getValue().compareTo(MIN_STAKE) < 0) {
            return;
        }

        // Reject duplicate BLS registration by a DIFFERENT validator: two
        // validators sharing a BLS key would fold identical reveals that cancel
        // in the XOR mix. The depositor's own record is excluded so a top-up or
        // re-apply of its own key is never mistaken for a duplicate.
        for (StakeRecord other : store.getAllStakeDeposits()) {
            if (java.util.Arrays.equals(other.getPubkey(), pubkey)) {
                continue; // own record — top-up / reorg re-apply
            }
            if (other.getBlsPubkey() != null && java.util.Arrays.equals(other.getBlsPubkey(), blsPubkey)) {
                return;
            }
        }

        // Activation delay: the deposit only becomes active (selectable/weighted)
        // MAX_SEED_LOOKAHEAD + 1 epochs after it is registered, so a new deposit
        // is visible to every node before it can influence consensus. The epoch is
        // CHAIN-derived from the deposit block's own position (the parent beacon's
        // reward chainlength / SLOTS_PER_EPOCH), identical on every node — NOT the
        // wall-clock slot epoch of the block timestamp. Mixing the two domains was
        // the root of the original activation bug: a wall-clock epoch
        // (thousands) compared against a chain epoch (chainlength/32, small)
        // meant a fresh deposit was never active. A hard per-epoch activation
        // churn cap needs a chain-ordered activation queue (tracked as a
        // follow-up); the delay itself bounds the rate a new deposit can join.
        long activatedEpoch = depositActivationEpoch(block, store);
        StakeRecord existing = store.getStakeDeposit(pubkey);
        if (existing != null && existing.getBlockHash() != null
                && existing.getBlockHash().equals(block.getHash())) {
            if (existing.getActivatedEpoch() >= 0) {
                return; // this exact block already applied
            }
            // Re-apply after a reorg reverted this block: restore its amount.
            store.updateStakeDepositAmount(pubkey, amount.getValue().longValue(),
                    block.getHash(), tx.getHash(), activatedEpoch);
            log.info("Re-applied stake deposit for pubkey={} amount={} block={}",
                    Utils.HEX.encode(pubkey), amount, block.getHashAsString());
        } else if (existing != null) {
            // Top-up: accumulate onto the existing deposit.
            store.updateStakeDepositAmount(pubkey, existing.getAmount().add(amount.getValue()).longValue(),
                    block.getHash(), tx.getHash(), activatedEpoch);
            log.info("Stake top-up for pubkey={}: now {} (block {})",
                    Utils.HEX.encode(pubkey), existing.getAmount().add(amount.getValue()), block.getHashAsString());
        } else {
            StakeRecord stake = new StakeRecord(pubkey, amount.getValue(), creds);
            stake.setBlsPubkey(blsPubkey);
            stake.setBlockHash(block.getHash());
            stake.setTxHash(tx.getHash());
            stake.setActivatedEpoch(activatedEpoch);
            store.saveStakeDeposit(stake);
            log.info("Applied chain-derived stake deposit for pubkey={} amount={} block={}",
                    Utils.HEX.encode(pubkey), amount, block.getHashAsString());
        }

        lockBondedOutput(block, tx, store);
    }

    /** Marks the deposit output spend-pending so the depositor's wallet treats it as locked. */
    private void lockBondedOutput(Block block, Transaction tx, BlockStoreInterface store) throws Exception {
        if (tx.getOutputs().isEmpty()) {
            return;
        }
        TransactionOutput out = tx.getOutput(0);
        if (!out.getValue().isBIG()) {
            return;
        }
        UTXO utxo = new UTXO();
        utxo.setBlockHash(block.getHash());
        utxo.setHash(tx.getHash());
        utxo.setIndex(0);
        utxo.setValue(out.getValue());
        store.updateTransactionOutputSpendPending(List.of(utxo));
    }

    /**
     * The expected proposer's ML-DSA pubkey for {@code slot}: deterministic
     * selection over the epoch's validator snapshot with the immutable mix —
     * the same inputs beacon validation uses.
     */
    public static byte[] expectedProposerPubkey(long slot, BlockStoreInterface store) throws Exception {
        List<StakeRecord> validators = SlotService.selectionValidators(slot, store);
        if (validators.isEmpty()) {
            return null;
        }
        long mixEpoch = slot / 32 - 2;
        byte[] mix = mixEpoch >= 0 ? store.getPosState("randao", "mixfinal_" + mixEpoch) : null;
        if (mix == null) {
            mix = sha256(String.valueOf(mixEpoch).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        long idx = SlotService.selectProposerForSlot(slot, validators, mix);
        if (idx < 0 || idx >= validators.size()) {
            return null;
        }
        return validators.get((int) idx).getPubkey();
    }

    private static byte[] sha256(byte[] in) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Extracts the SlotData carried by a beacon, or null. */
    public static net.bigtangle.core.SlotData slotDataOfBeacon(Block block) {
        if (block == null || block.getBlockType() != BlockType.BLOCKTYPE_BEACON
                || block.getTransactions() == null) {
            return null;
        }
        for (Transaction tx : block.getTransactions()) {
            if ("SlotData".equals(tx.getDataClassName()) && tx.getData() != null) {
                try {
                    return Json.jsonmapper().readValue(tx.getData(), net.bigtangle.core.SlotData.class);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Proposal equivocation (slashable, like Ethereum's proposer slashing): two
     * DIFFERENT signed SlotDatas for the SAME slot, both authentic under the
     * elected proposer's key. The signature covers every SlotData field, so the
     * evidence is self-authenticating and cannot be framed onto an honest key.
     */
    public static boolean isProposalEquivocation(net.bigtangle.core.SlotData sd1,
            net.bigtangle.core.SlotData sd2, byte[] proposerPubkey) {
        if (sd1 == null || sd2 == null || proposerPubkey == null) {
            return false;
        }
        if (sd1.getSlot() != sd2.getSlot()) {
            return false;
        }
        if (sd1.getProposerSignature() == null || sd2.getProposerSignature() == null) {
            return false;
        }
        if (sd1.getMessageHash().equals(sd2.getMessageHash())) {
            return false; // identical content — not an equivocation
        }
        return PQScriptUtils.verifyPQ(proposerPubkey, sd1.getProposerSignature(), sd1.getMessageHash())
                && PQScriptUtils.verifyPQ(proposerPubkey, sd2.getProposerSignature(), sd2.getMessageHash());
    }

    /**
     * Records a beacon sighting for its slot (at ingest) and, when a DIFFERENT
     * beacon was already sighted for the same slot, submits proposal-equivocation
     * evidence as a consensus slashing block. Sightings are evidence, not chain
     * state — they are never unrecorded by reorgs. Only sightings AUTHENTICATED
     * under the elected proposer's key are recorded (a forged-signature beacon
     * can neither pollute the registry nor trigger slash attempts), and the
     * registry is capped per slot (two sightings already prove an equivocation).
     */
    private static final int MAX_SLOT_SIGHTINGS = 4;

    public void checkSlotSightingForEquivocation(Block block, BlockStoreInterface store) {
        try {
            net.bigtangle.core.SlotData sd = slotDataOfBeacon(block);
            if (sd == null || sd.getProposerSignature() == null) {
                return;
            }
            byte[] proposer = expectedProposerPubkey(sd.getSlot(), store);
            if (proposer == null
                    || !PQScriptUtils.verifyPQ(proposer, sd.getProposerSignature(), sd.getMessageHash())) {
                return; // not signed by the elected proposer — ignore
            }
            String key = "slotsight_" + sd.getSlot();
            byte[] existing = store.getPosState("pos", key);
            String hashes = existing != null ? new String(existing, java.nio.charset.StandardCharsets.UTF_8) : "";
            String self = block.getHashAsString();
            boolean known = false;
            int count = 0;
            for (String h : hashes.split(",")) {
                if (h.isEmpty()) {
                    continue;
                }
                count++;
                if (h.equals(self)) {
                    known = true;
                    continue;
                }
                Block prior = store.get(Sha256Hash.wrap(Utils.HEX.decode(h)));
                net.bigtangle.core.SlotData priorSd = slotDataOfBeacon(prior);
                if (priorSd != null) {
                    submitProposalSlashing(priorSd, sd, store);
                }
            }
            if (!known && count < MAX_SLOT_SIGHTINGS) {
                String updated = hashes.isEmpty() ? self : hashes + "," + self;
                store.savePosState("pos", key, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.debug("slot sighting check failed for {}: {}", block.getHashAsString(), e.getMessage());
        }
    }

    /**
     * Proposes a PROPOSAL-equivocation slashing as a consensus BLOCKTYPE_SLASHING
     * block carrying the two conflicting signed SlotDatas. Verified locally first
     * (no evidence spam), then validated/applied by every node (see
     * applySlashingBlock).
     */
    public void submitProposalSlashing(net.bigtangle.core.SlotData sd1, net.bigtangle.core.SlotData sd2,
            BlockStoreInterface store) throws Exception {
        if (sd1 == null || sd2 == null || sd1.getSlot() != sd2.getSlot()) {
            return;
        }
        byte[] proposer = expectedProposerPubkey(sd1.getSlot(), store);
        if (proposer == null || !isProposalEquivocation(sd1, sd2, proposer)) {
            return;
        }
        StakeRecord stake = store.getStakeDeposit(proposer);
        if (stake == null || stake.isSlashed()) {
            return; // nothing to slash / already slashed
        }
        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        Block head = store.get(maxConfirmedReward.getBlockHash());
        Block b = Block.createBlock(networkParameters, head, head);
        b.setBlockType(BlockType.BLOCKTYPE_SLASHING);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(SLASHING_DATA_CLASS);
        Map<String, Object> data = new HashMap<>();
        data.put("proposal", Boolean.TRUE);
        data.put("slotData1", sd1);
        data.put("slotData2", sd2);
        byte[] reporter = localReporterPubkey();
        if (reporter != null) {
            data.put("reporter", Utils.HEX.encode(reporter));
        }
        tx.setData(Json.jsonmapper().writeValueAsBytes(data));
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);
        log.info("Proposal-equivocation slashing block proposed for slot {}: {}",
                sd1.getSlot(), b.getHashAsString());
    }

    /**
     * Proposes a slashing as a consensus BLOCKTYPE_SLASHING block carrying the
     * two conflicting attestations. The actual slash + confiscation is applied
     * by every node when the block is validated/saved (see applySlashingBlock),
     * so nodes never diverge by slashing on locally-observed attestations.
     */
    public void submitSlashing(AttestationData att1, AttestationData att2, BlockStoreInterface store)
            throws Exception {
        if (att1 == null || att2 == null || !Arrays.equals(att1.getValidatorPubkey(), att2.getValidatorPubkey())) {
            return;
        }
        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        Block head = store.get(maxConfirmedReward.getBlockHash());
        Block b = Block.createBlock(networkParameters, head, head);
        b.setBlockType(BlockType.BLOCKTYPE_SLASHING);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(SLASHING_DATA_CLASS);
        tx.setData(buildSlashingData(att1, att2));
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);
        log.info("Slashing block proposed for pubkey={}: {}",
                Utils.HEX.encode(att1.getValidatorPubkey()), b.getHashAsString());
    }

    private byte[] buildSlashingData(AttestationData att1, AttestationData att2) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("attestation1", att1);
        data.put("attestation2", att2);
        byte[] reporter = localReporterPubkey();
        if (reporter != null) {
            data.put("reporter", Utils.HEX.encode(reporter));
        }
        return Json.jsonmapper().writeValueAsBytes(data);
    }

    /** The node's configured validator ML-DSA pubkey (whistleblower identity),
     *  or null when no validator key is configured (test/sync-only nodes). */
    private byte[] localReporterPubkey() {
        if (configuredValidatorKeyHex == null || configuredValidatorKeyHex.isEmpty()) {
            return null;
        }
        try {
            PQKey key = PQKey.fromPrivateKeyHex(configuredValidatorKeyHex);
            return key != null ? key.getPubKey() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Consensus application of a BLOCKTYPE_SLASHING block: verifies the two
     * embedded attestations really form a slashable pattern, then marks the
     * validator slashed and CONFISCATES the bonded output. Runs on every node
     * that accepts the block, so the UTXO-set change is consensus-driven.
     */
    public void applySlashingBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_SLASHING || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!SLASHING_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        if (Boolean.TRUE.equals(data.get("proposal"))) {
            // Proposal equivocation: two different signed SlotDatas, same slot,
            // by the elected proposer — verified here by every node.
            net.bigtangle.core.SlotData sd1 = Json.jsonmapper().convertValue(data.get("slotData1"),
                    net.bigtangle.core.SlotData.class);
            net.bigtangle.core.SlotData sd2 = Json.jsonmapper().convertValue(data.get("slotData2"),
                    net.bigtangle.core.SlotData.class);
            if (sd1 == null || sd2 == null) {
                return;
            }
            byte[] proposer = expectedProposerPubkey(sd1.getSlot(), store);
            if (proposer == null || !isProposalEquivocation(sd1, sd2, proposer)) {
                return; // forged / unauthenticated proposal proof — ignore
            }
            StakeRecord stake = store.getStakeDeposit(proposer);
            if (stake == null || stake.isSlashed()) {
                return;
            }
            store.updateStakeSlashing(proposer, -1L);
            confiscateBond(proposer, stake, store);
            mintSlashingRefund(block, proposer, stake, store);
            mintWhistleblowerReward(block, data, stake, store);
            log.info("Validator slashed for proposal equivocation via consensus block {}: slot={}",
                    block.getHashAsString(), sd1.getSlot());
            return;
        }
        AttestationData att1 = Json.jsonmapper().convertValue(data.get("attestation1"), AttestationData.class);
        AttestationData att2 = Json.jsonmapper().convertValue(data.get("attestation2"), AttestationData.class);
        if (att1 == null || att2 == null
                || !Arrays.equals(att1.getValidatorPubkey(), att2.getValidatorPubkey())
                || !att1.verifySignature() || !att2.verifySignature()) {
            return; // forged / unauthenticated slashing proof — ignore
        }
        boolean doubleVote = SlashingService.isDoubleVote(att1, att2);
        boolean surround = SlashingService.isSurroundVote(att1, att2);
        if (!doubleVote && !surround) {
            return;
        }

        byte[] pubkey = att1.getValidatorPubkey();
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) {
            return;
        }
        store.updateStakeSlashing(pubkey, -1L);
        confiscateBond(pubkey, stake, store);
        mintSlashingRefund(block, pubkey, stake, store);
        mintWhistleblowerReward(block, data, stake, store);
        log.info("Validator slashed via consensus block {}: pubkey={} (withdrawable set at confirmation)",
                block.getHashAsString(), Utils.HEX.encode(pubkey));
    }

    /**
     * The validator identified by a slashing payload of EITHER kind
     * (attestation pair or proposal equivocation), or null when it cannot be
     * determined.
     */
    private byte[] slashingEvidencePubkey(Map<String, Object> data, BlockStoreInterface store) {
        try {
            if (data.get("attestation1") != null) {
                AttestationData att1 = Json.jsonmapper().convertValue(data.get("attestation1"),
                        AttestationData.class);
                return att1 != null ? att1.getValidatorPubkey() : null;
            }
            if (data.get("slotData1") != null) {
                net.bigtangle.core.SlotData sd1 = Json.jsonmapper().convertValue(data.get("slotData1"),
                        net.bigtangle.core.SlotData.class);
                return sd1 != null ? expectedProposerPubkey(sd1.getSlot(), store) : null;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * Confirm-time variant: sets the withdrawable epoch from the CONFIRMING
     * beacon's chain epoch (passed in), which is fixed once confirmed, current,
     * and not chosen by the submitter. Called by confirmDo alongside the
     * save-time flag application. Always overwrites so the value mirrors the
     * currently-confirmed referencing beacon; a stale value from an unconfirmed
     * beacon is corrected on the next confirmation instead of becoming permanent.
     */
    public void applySlashingConfirmed(Block block, long chainEpoch, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_SLASHING || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!SLASHING_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        byte[] pubkey = slashingEvidencePubkey(data, store);
        if (pubkey == null) {
            return;
        }
        StakeRecord stake = store.getStakeDeposit(pubkey);
        // The record is ALREADY slashed (set at save time). The withdrawable
        // epoch is ALWAYS (re)derived from the confirming beacon: a value left
        // by an unconfirmed beacon must be overwritten on the next confirmation,
        // never frozen by a keep-first guard. Idempotency is the beacon-derived
        // value, not a stored flag.
        if (stake == null) {
            return;
        }
        store.updateStakeSlashing(pubkey, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
        // The slashing block confirmed: its refund/reporter mints ride the
        // normal confirm lifecycle (restored even after a prior revert).
        confirmSlashingMints(block, store);
        log.info("Slash withdrawable set at confirmation: pubkey={}, withdrawable at epoch={}",
                Utils.HEX.encode(pubkey), chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
    }

    /**
     * Reverts a STAKE block on reorg: deactivates the deposit(s) it created so
     * a reorged-out deposit no longer counts as active stake.
     */
    public void revertStakeBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_STAKE) {
            return;
        }
        List<StakeRecord> affected = store.getStakeDepositsByBlockHash(block.getHash());
        for (StakeRecord stake : affected) {
            store.releaseStakeDeposit(stake.getPubkey());
            log.info("Reorg: deactivated stake deposit for pubkey={} (block {})",
                    Utils.HEX.encode(stake.getPubkey()), block.getHashAsString());
        }
    }

    /**
     * Reverts a SLASHING block on reorg: clears the slashed flag so a
     * reorged-out slash does not permanently disable the validator.
     */
    public void revertSlashingBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_SLASHING || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!SLASHING_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        byte[] evidencePubkey = slashingEvidencePubkey(data, store);
        if (evidencePubkey == null) {
            return;
        }
        // Restore the confiscated bond so a reorged-out slash does not leave
        // the validator permanently penalised.
        StakeRecord stake = store.getStakeDeposit(evidencePubkey);
        if (stake != null && stake.getBlockHash() != null) {
            Sha256Hash txHash = stake.getTxHash();
            if (txHash == null) {
                Block stakeBlock = store.get(stake.getBlockHash());
                if (stakeBlock != null && !stakeBlock.getTransactions().isEmpty()) {
                    txHash = stakeBlock.getTransactions().get(0).getHash();
                }
            }
            if (txHash != null) {
                store.updateTransactionOutputSpent(stake.getBlockHash(), txHash, 0, false, null);
            }
        }
        store.clearStakeSlashing(evidencePubkey);
        // Cancel the refund/reporter mints the slashing block created so they
        // cannot be claimed after the slash is reverted.
        cancelSlashingMints(block, store);
        log.info("Reorg: un-slashed validator for pubkey={} (block {})",
                Utils.HEX.encode(evidencePubkey), block.getHashAsString());
    }

    public void activateValidator(byte[] pubkey, long epoch, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null) {
            throw new IllegalArgumentException("No stake deposit for pubkey");
        }
        if (stake.getActivatedEpoch() >= 0) {
            return; // already activated (chain-derived) — idempotent
        }
        store.updateStakeActivation(pubkey, epoch);
        log.info("Validator activated at epoch {}: pubkey={}", epoch, Utils.HEX.encode(pubkey));
    }

    /**
     * Legacy flag-only slashing used by store-level tests; consensus slashing
     * goes through applySlashingBlock.
     */
    public void slashValidator(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) return;

        // Flag-only path (tests); derive the chain epoch from the confirmed
        // tip, not the wall clock.
        long chainEpoch = 0;
        TXReward tip = cacheBlockService.getMaxConfirmedReward(store);
        if (tip != null) {
            chainEpoch = tip.getChainLength() / net.bigtangle.server.service.SlotService.SLOTS_PER_EPOCH;
        }
        store.updateStakeSlashing(pubkey, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
        log.info("Validator slashed (flag only): pubkey={}, withdrawable at epoch={}",
                Utils.HEX.encode(pubkey), chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
    }

    /** Marks the bonded deposit output as spent (burned/confiscated). Consensus-driven. */
    public void confiscateBond(byte[] pubkey, StakeRecord stake, BlockStoreInterface store) throws Exception {
        if (stake.getBlockHash() == null) {
            return; // legacy locally-seeded deposit without a chain block
        }
        Sha256Hash txHash = stake.getTxHash();
        if (txHash == null) {
            Block block = store.get(stake.getBlockHash());
            if (block != null && !block.getTransactions().isEmpty()) {
                txHash = block.getTransactions().get(0).getHash();
            } else {
                return;
            }
        }
        try {
            store.updateTransactionOutputSpent(stake.getBlockHash(), txHash, 0, true, Sha256Hash.ZERO_HASH);
            log.info("Confiscated bonded stake output for pubkey={}", Utils.HEX.encode(pubkey));
        } catch (Exception e) {
            log.warn("Could not confiscate bonded output for pubkey={}: {}",
                    Utils.HEX.encode(pubkey), e.getMessage());
        }
    }

    /**
     * Graded-slash refund: mints the slashed validator's remaining bond
     * (amount - amount/32) back to them as a STORE-LEVEL UTXO keyed to the
     * slashing block. A plain transaction output on the SLASHING block would
     * break value conservation (the block is not a minting block), so the mint
     * is a store-level reorg-aware write: created (idempotently) when the
     * slashing block is applied, confirmed with the block by
     * {@link #applySlashingConfirmed}, and cancelled by
     * {@link #revertSlashingBlock} on unconfirm. Net protocol effect: the
     * validator is burned exactly amount/32 instead of the full bond.
     */
    private void mintSlashingRefund(Block block, byte[] pubkey, StakeRecord stake,
            BlockStoreInterface store) throws Exception {
        BigInteger penalty = stake.getAmount()
                .divide(BigInteger.valueOf(SLASH_PENALTY_DIVISOR));
        BigInteger refund = stake.getAmount().subtract(penalty);
        if (refund.signum() <= 0) {
            return;
        }
        String[] bonded = bondedOutputScript(stake, store);
        if (bonded == null) {
            return;
        }
        UTXO utxo = buildSlashingMintUtxo(block, SLASHING_REFUND_OUTPUT_INDEX, refund,
                Utils.HEX.decode(bonded[0]), bonded[1], store);
        if (utxo != null) {
            store.addUnspentTransactionOutput(List.of(utxo));
            log.info("Slashing refund of {} minted to slashed pubkey={} (block {})",
                    refund, Utils.HEX.encode(pubkey), block.getHashAsString());
        }
    }

    /**
     * Whistleblower reward: mints 1/512 of the slashed penalty (the reporter's
     * share) to the reporter identity embedded in the slashing proof. The
     * reporter is whoever proposed the proof (finders-keepers); no protocol
     * value is created or destroyed — the reward is carved out of the
     * validator's 1/32 penalty, and nodes are indifferent to who receives it.
     */
    private void mintWhistleblowerReward(Block block, Map<String, Object> data,
            StakeRecord stake, BlockStoreInterface store) throws Exception {
        Object reporterObj = data.get("reporter");
        if (!(reporterObj instanceof String)) {
            return;
        }
        byte[] reporterPubkey;
        try {
            reporterPubkey = Utils.HEX.decode((String) reporterObj);
        } catch (Exception e) {
            return;
        }
        BigInteger penalty = stake.getAmount().divide(BigInteger.valueOf(SLASH_PENALTY_DIVISOR));
        BigInteger reward = penalty.divide(BigInteger.valueOf(WHISTLEBLOWER_REWARD_DIVISOR));
        if (reward.signum() <= 0) {
            return;
        }
        Script reporterScript;
        try {
            reporterScript = ScriptBuilder.createOutputScript(PQKey.fromPublicOnly(reporterPubkey));
        } catch (Exception e) {
            log.warn("Could not build reporter script for slashing block {}: {}",
                    block.getHashAsString(), e.getMessage());
            return;
        }
        String reporterAddress = Address
                .fromHash160(networkParameters, Utils.sha256hash160(reporterPubkey)).toBase58();
        UTXO utxo = buildSlashingMintUtxo(block, SLASHING_REPORTER_OUTPUT_INDEX, reward,
                reporterScript.getProgram(), reporterAddress, store);
        if (utxo != null) {
            store.addUnspentTransactionOutput(List.of(utxo));
            log.info("Whistleblower reward of {} minted to reporter (block {})",
                    reward, block.getHashAsString());
        }
    }

    /**
     * Builds a store-level mint UTXO keyed to the slashing block (proof-tx hash
     * + output index), so the row is idempotent (re-apply of the same slashing
     * block is a no-op) and reorg-revert is a stable key.
     */
    private UTXO buildSlashingMintUtxo(Block block, long index, BigInteger value,
            byte[] scriptProgram, String address, BlockStoreInterface store) throws Exception {
        if (scriptProgram == null) {
            return null;
        }
        UTXO utxo = new UTXO();
        utxo.setHash(slashingMintTxHash(block));
        utxo.setIndex(index);
        utxo.setValue(new Coin(value, NetworkParameters.BIGTANGLE_TOKENID));
        utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
        utxo.setScript(new Script(scriptProgram));
        utxo.setAddress(address);
        utxo.setCoinbase(true);
        utxo.setBlockHash(block.getHash());
        utxo.setConfirmed(isBlockConfirmed(block, store));
        utxo.setSpent(false);
        return utxo;
    }

    /** Deterministic synthetic tx hash for the store-level slashing mints:
     *  the slashing PROOF tx hash itself (always available, even on a
     *  not-yet-hashed block during tests/sync) so the row is idempotent across
     *  re-apply and reorg-revert. The refund/reporter rows are distinguished
     *  by their output index. */
    private Sha256Hash slashingMintTxHash(Block block) {
        return block.getTransactions().get(0).getHash();
    }

    /** Whether the block is currently confirmed, from its block evaluation. */
    private boolean isBlockConfirmed(Block block, BlockStoreInterface store) throws Exception {
        net.bigtangle.core.BlockEvaluation be = store.getBlockEvaluationsByhashs(block.getHash());
        return be != null && be.isConfirmed();
    }

    /** The script program (hex) and address of the bonded deposit output (the
     *  validator's P2PKH), or null. */
    private String[] bondedOutputScript(StakeRecord stake, BlockStoreInterface store) throws Exception {
        if (stake.getBlockHash() == null) {
            return null;
        }
        Sha256Hash txHash = stake.getTxHash();
        if (txHash == null) {
            Block block = store.get(stake.getBlockHash());
            if (block != null && !block.getTransactions().isEmpty()) {
                txHash = block.getTransactions().get(0).getHash();
            }
        }
        if (txHash == null) {
            return null;
        }
        UTXO bonded = store.getTransactionOutput(stake.getBlockHash(), txHash, 0);
        if (bonded != null && bonded.getScript() != null) {
            return new String[] { Utils.HEX.encode(bonded.getScript().getProgram()), bonded.getAddress() };
        }
        // Fallback: rebuild the P2PKH from the withdrawal credentials.
        if (stake.getWithdrawalCredentials() != null) {
            String addr = Address.fromHash160(networkParameters, stake.getWithdrawalCredentials()).toBase58();
            return new String[] { Utils.HEX.encode(ScriptBuilder
                    .createOutputScript(Address.fromHash160(networkParameters, stake.getWithdrawalCredentials()))
                    .getProgram()), addr };
        }
        return null;
    }

    /** Confirmation-time restore: the slashing block confirmed, so its refund
     *  and reporter mints ride the normal confirm lifecycle. Idempotent. Only
     *  flips a mint back to unspent when it was CANCELLED by a prior revert
     *  (spent by ZERO_HASH) — a genuinely spent refund is left alone. */
    private void confirmSlashingMints(Block block, BlockStoreInterface store) throws Exception {
        for (long idx : new long[] { SLASHING_REFUND_OUTPUT_INDEX, SLASHING_REPORTER_OUTPUT_INDEX }) {
            Sha256Hash mintHash = slashingMintTxHash(block);
            UTXO minted = store.getTransactionOutput(block.getHash(), mintHash, idx);
            if (minted != null && minted.getSpenderBlockHash() != null
                    && !minted.getSpenderBlockHash().equals(Sha256Hash.ZERO_HASH)) {
                continue; // genuinely spent — do not resurrect
            }
            store.updateTransactionOutputConfirmed(block.getHash(), mintHash, idx, true);
            store.updateTransactionOutputSpent(block.getHash(), mintHash, idx, false, null);
        }
    }

    /** Reorg revert: the slashing block unconfirmed, so its refund and reporter
     *  mints are cancelled (unconfirmed + marked spent so they can never be
     *  claimed). A later re-confirmation re-mints via applySlashingConfirmed. */
    private void cancelSlashingMints(Block block, BlockStoreInterface store) throws Exception {
        for (long idx : new long[] { SLASHING_REFUND_OUTPUT_INDEX, SLASHING_REPORTER_OUTPUT_INDEX }) {
            store.updateTransactionOutputConfirmed(block.getHash(), slashingMintTxHash(block), idx, false);
            store.updateTransactionOutputSpent(block.getHash(), slashingMintTxHash(block), idx, true,
                    Sha256Hash.ZERO_HASH);
        }
    }

    public BigInteger getTotalActiveStake(BlockStoreInterface store) throws Exception {
        // Active as of the CURRENT CHAIN epoch: validators that activated within
        // the delay window (activatedEpoch > currentChainEpoch) are excluded.
        return store.getActiveStakeDeposits(SlotService.currentChainEpoch(store)).stream()
                .map(StakeService::effectiveBalance)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * Voluntary exit as a consensus BLOCKTYPE_EXIT block: the validator signs an
     * exit request (proving key ownership) which travels in the block. Every
     * node validates and applies it, so the validator set never diverges via an
     * HTTP-triggered DB write. Exiting validators stay active (and slashable)
     * until the bond is released after the withdrawal delay.
     */
    public void submitExit(byte[] pubkey, byte[] signature, BlockStoreInterface store) throws Exception {
        if (pubkey == null || signature == null || signature.length == 0) {
            throw new IllegalArgumentException("Exit request is missing pubkey or signature");
        }
        // Authenticate the exit request: the signature covers sha256(pubkey ||
        // nonce) with the nonce bound to the CHAIN position (the max confirmed
        // reward chainlength), so it is consensus-verifiable, not wall-clock.
        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        long nonce = maxConfirmedReward != null ? maxConfirmedReward.getChainLength() : 0;
        PQKey signer = PQKey.fromPublicOnly(pubkey);
        Sha256Hash msg = Sha256Hash.of(buildExitMessage(pubkey, nonce));
        if (!PQScriptUtils.verifyPQ(signer.getPublicKeyBytes(), signature, msg)) {
            throw new IllegalArgumentException("Exit request signature is invalid");
        }
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null) {
            throw new IllegalArgumentException("No stake deposit for pubkey");
        }
        if (stake.isSlashed()) {
            throw new IllegalStateException("Slashed validators cannot request exit");
        }

        Block head = store.get(maxConfirmedReward.getBlockHash());
        Block b = Block.createBlock(networkParameters, head, head);
        b.setBlockType(BlockType.BLOCKTYPE_EXIT);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(EXIT_DATA_CLASS);
        Map<String, Object> data = new HashMap<>();
        data.put("pubkey", Utils.HEX.encode(pubkey));
        data.put("signature", Utils.HEX.encode(signature));
        data.put("nonce", nonce);
        tx.setData(Json.jsonmapper().writeValueAsBytes(data));
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);
        log.info("Exit block proposed for pubkey={}: {}", Utils.HEX.encode(pubkey), b.getHashAsString());
    }

    public static byte[] buildExitMessage(byte[] pubkey, long nonce) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(pubkey.length + 8);
        buf.put(pubkey);
        buf.putLong(nonce);
        return buf.array();
    }

    /**
     * Chain EPOCH of a block: the parent beacon's reward chainlength converted
     * to an epoch (chainlength / SLOTS_PER_EPOCH), or 0 for genesis. Used for
     * consensus state transitions (withdrawable epoch) so every node that
     * applies the block derives the SAME value — never the local wall-clock
     * time. THROWS when the position cannot be determined: a state transition
     * that cannot derive its own chain position must not write a value (and
     * would otherwise default to epoch 0, making the bond withdrawable
     * immediately on a mature chain).
     */
    private long chainEpochOf(Block block, BlockStoreInterface store) throws Exception {
        Block parent = store.get(block.getPrevBlockHash());
        if (parent == null) {
            throw new IllegalStateException("Cannot derive chain epoch: parent block is missing");
        }
        if (parent.getBlockType() == BlockType.BLOCKTYPE_BEACON) {
            net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                    .parseChecked(parent.getTransactions().get(0).getData());
            if (ri == null) {
                throw new IllegalStateException("Cannot derive chain epoch: parent reward info is unparseable");
            }
            return ri.getChainlength() / net.bigtangle.server.service.SlotService.SLOTS_PER_EPOCH;
        }
        if (parent.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
            return 0;
        }
        throw new IllegalStateException("Cannot derive chain epoch: parent is not a beacon or genesis");
    }

    /**
     * Consensus application of a BLOCKTYPE_EXIT block: marks the validator as
     * voluntarily exiting with a withdrawable epoch. It is NOT slashed — it
     * keeps its stake (and remains slashable) until the bond is released.
     */
    public void applyExitBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_EXIT || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!EXIT_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        String pubkeyHex = (String) data.get("pubkey");
        if (pubkeyHex == null) {
            return;
        }
        byte[] pubkey = Utils.HEX.decode(pubkeyHex);
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) {
            return;
        }
        store.updateStakeExit(pubkey, -1L);
        log.info("Validator exit applied via consensus block {}: pubkey={} (withdrawable set at confirmation)",
                block.getHashAsString(), pubkeyHex);
    }

    /**
     * Confirm-time variant: sets the withdrawable epoch from the CONFIRMING
     * beacon's chain epoch (passed in), which is fixed once confirmed, current,
     * and not chosen by the submitter. Always overwrites (see
     * applySlashingConfirmed).
     */
    public void applyExitConfirmed(Block block, long chainEpoch, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_EXIT || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!EXIT_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        String pubkeyHex = (String) data.get("pubkey");
        if (pubkeyHex == null) {
            return;
        }
        byte[] pubkey = Utils.HEX.decode(pubkeyHex);
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null) {
            return;
        }
        // Always (re)derived from the confirming beacon (see
        // applySlashingConfirmed): a stale epoch from an unconfirmed beacon must
        // be overwritten on the next confirmation, never frozen.
        store.updateStakeExit(pubkey, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
        log.info("Exit withdrawable set at confirmation: pubkey={}, withdrawable at epoch={}",
                pubkeyHex, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
    }

    /** Reverts an EXIT block on reorg: the validator is no longer exiting. */
    public void revertExitBlock(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_EXIT || block.getTransactions().isEmpty()) {
            return;
        }
        Transaction tx = block.getTransactions().get(0);
        if (!EXIT_DATA_CLASS.equals(tx.getDataClassName()) || tx.getData() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
        String pubkeyHex = (String) data.get("pubkey");
        if (pubkeyHex == null) {
            return;
        }
        byte[] pubkey = Utils.HEX.decode(pubkeyHex);
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake != null) {
            // Fully clear the exit flag so the record can re-validate its own
            // EXIT block after the unconfirm (updateStakeExit only ever sets it).
            store.clearStakeExit(pubkey);
        }
        log.info("Reorg: reverted exit for pubkey={} (block {})", pubkeyHex, block.getHashAsString());
    }

    /**
     * Clears the withdrawable epoch of the validator identified by a SLASHING or
     * EXIT block. Used when the block's referencing beacon is unconfirmed: the
     * epoch must become pending again so it is re-derived on re-confirmation
     * (and never stuck from a stale beacon).
     */
    public void clearWithdrawableForBlock(Block block, BlockStoreInterface store) throws Exception {
        byte[] pubkey = null;
        if (block.getBlockType() == BlockType.BLOCKTYPE_SLASHING && !block.getTransactions().isEmpty()) {
            Transaction tx = block.getTransactions().get(0);
            if (SLASHING_DATA_CLASS.equals(tx.getDataClassName()) && tx.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
                pubkey = slashingEvidencePubkey(data, store);
            }
        } else if (block.getBlockType() == BlockType.BLOCKTYPE_EXIT && !block.getTransactions().isEmpty()) {
            Transaction tx = block.getTransactions().get(0);
            if (EXIT_DATA_CLASS.equals(tx.getDataClassName()) && tx.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = Json.jsonmapper().readValue(tx.getData(), Map.class);
                String pubkeyHex = (String) data.get("pubkey");
                if (pubkeyHex != null) {
                    pubkey = Utils.HEX.decode(pubkeyHex);
                }
            }
        }
        if (pubkey != null) {
            store.clearStakeWithdrawable(pubkey);
            log.info("Withdrawable reset to pending for pubkey={} (beacon unconfirmed)",
                    Utils.HEX.encode(pubkey));
        }
    }

    public void processWithdrawals(long currentEpoch, BlockStoreInterface store) throws Exception {
        List<StakeRecord> allDeposits = store.getAllStakeDeposits();
        // Exit queue: at most churnLimit validators withdraw per epoch (bounds
        // validator-set churn). Withdrawable deposits are processed in a
        // deterministic order (withdrawable epoch, then pubkey).
        long churn = churnLimit(allDeposits.size());
        List<StakeRecord> withdrawable = allDeposits.stream()
                .filter(s -> s.getWithdrawableEpoch() >= 0 && s.getWithdrawableEpoch() <= currentEpoch)
                .sorted(java.util.Comparator
                        .comparingLong((StakeRecord s) -> s.getWithdrawableEpoch())
                        .thenComparing(s -> Utils.HEX.encode(s.getPubkey())))
                .collect(java.util.stream.Collectors.toList());
        int released = 0;
        for (StakeRecord stake : withdrawable) {
            if (released >= churn) {
                break;
            }
            // The bonded output is freed: deleting the record makes it
            // spendable again (the bond spend check no longer sees it).
            store.deleteStakeDeposit(stake.getPubkey());
            released++;
            log.info("Stake withdrawal processed: pubkey={}, amount={}",
                    Utils.HEX.encode(stake.getPubkey()), stake.getAmount());
        }
        for (StakeRecord stake : allDeposits) {
            if (stake.getWithdrawableEpoch() >= 0 && stake.getWithdrawableEpoch() <= currentEpoch) {
                continue; // already handled above (or deferred by the churn cap)
            }
            // Reconciliation for the save-time application gap: a deposit whose
            // STAKE block was saved but never gained confirmation (orphaned,
            // or its beacon never confirmed) and is stale is deactivated. Both
            // sides are CHAIN positions (the deposit's chain position at the            // time its STAKE block was created vs the current position).
            if (stake.getActivatedEpoch() >= 0 && stake.getBlockHash() != null) {
                Block stakeBlock = store.get(stake.getBlockHash());
                if (stakeBlock != null) {
                    try {
                        long depositEpoch = chainEpochOf(stakeBlock, store);
                        if (currentEpoch - depositEpoch > WITHDRAWAL_DELAY_EPOCHS) {
                            net.bigtangle.core.BlockEvaluation be = store.getBlockEvaluationsByhashs(stake.getBlockHash());
                            if (be == null || !be.isConfirmed()) {
                                store.releaseStakeDeposit(stake.getPubkey());
                                log.info("Deactivated stale unconfirmed stake deposit: pubkey={}, block={}",
                                        Utils.HEX.encode(stake.getPubkey()), stake.getBlockHash());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Could not verify confirmation for stake block {}: {}",
                                stake.getBlockHash(), e.getMessage());
                    }
                }
            }
        }
    }
}
