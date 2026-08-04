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
    public static final long WITHDRAWAL_DELAY_EPOCHS = 256;
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

    public long getEffectiveStake(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed() || stake.getActivatedEpoch() < 0) return 0L;
        return stake.getAmount().longValue();
    }

    /** Serializes {@code pubkey} + {@code withdrawalCredentials} into the STAKE tx payload. */
    public static byte[] buildStakeDepositData(byte[] pubkey, byte[] withdrawalCredentials) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(pubkey.length);
            dos.write(pubkey);
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

    /** Parses {@link #buildStakeDepositData}; returns {@code {pubkey, withdrawalCredentials}}. */
    public static byte[][] parseStakeDepositData(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int plen = dis.readInt();
        byte[] pubkey = new byte[plen];
        dis.readFully(pubkey);
        int clen = dis.readInt();
        byte[] creds = null;
        if (clen > 0) {
            creds = new byte[clen];
            dis.readFully(creds);
        }
        return new byte[][] { pubkey, creds };
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
        tx.setData(buildStakeDepositData(depositKey.getPubKey(), withdrawalCredentials));

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
        byte[] creds = parts[1];

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

        long activatedEpoch = SlotService.epochAt(block.getTimeSeconds() * 1000L);
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
        return Json.jsonmapper().writeValueAsBytes(data);
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
        AttestationData att1 = Json.jsonmapper().convertValue(data.get("attestation1"), AttestationData.class);
        AttestationData att2 = Json.jsonmapper().convertValue(data.get("attestation2"), AttestationData.class);
        if (att1 == null || att2 == null
                || !Arrays.equals(att1.getValidatorPubkey(), att2.getValidatorPubkey())
                || !att1.verifySignature() || !att2.verifySignature()) {
            return; // forged / unauthenticated slashing proof — ignore
        }
        boolean doubleVote = att1.getSlot() == att2.getSlot()
                && !att1.getBeaconBlockHash().equals(att2.getBeaconBlockHash());
        boolean surround = (att1.getSourceEpoch() < att2.getSourceEpoch()
                && att2.getTargetEpoch() < att1.getTargetEpoch())
                || (att2.getSourceEpoch() < att1.getSourceEpoch()
                        && att1.getTargetEpoch() < att2.getTargetEpoch());
        if (!doubleVote && !surround) {
            return;
        }

        byte[] pubkey = att1.getValidatorPubkey();
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) {
            return;
        }
        long chainEpoch = chainEpochOf(block, store);
        store.updateStakeSlashing(pubkey, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
        confiscateBond(pubkey, stake, store);
        log.info("Validator slashed via consensus block {}: pubkey={}, withdrawable at epoch={}",
                block.getHashAsString(), Utils.HEX.encode(pubkey), chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
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
        AttestationData att1 = Json.jsonmapper().convertValue(data.get("attestation1"), AttestationData.class);
        if (att1 == null || att1.getValidatorPubkey() == null) {
            return;
        }
        // Restore the confiscated bond so a reorged-out slash does not leave
        // the validator permanently penalised.
        StakeRecord stake = store.getStakeDeposit(att1.getValidatorPubkey());
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
        store.updateStakeSlashing(att1.getValidatorPubkey(), -1L);
        log.info("Reorg: un-slashed validator for pubkey={} (block {})",
                Utils.HEX.encode(att1.getValidatorPubkey()), block.getHashAsString());
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

    public BigInteger getTotalActiveStake(BlockStoreInterface store) throws Exception {
        return store.getActiveStakeDeposits().stream()
                .map(StakeRecord::getAmount)
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
        long chainEpoch = chainEpochOf(block, store);
        store.updateStakeExit(pubkey, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
        log.info("Validator exit applied via consensus block {}: pubkey={}, withdrawable at epoch={}",
                block.getHashAsString(), pubkeyHex, chainEpoch + WITHDRAWAL_DELAY_EPOCHS);
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

    public void processWithdrawals(long currentEpoch, BlockStoreInterface store) throws Exception {
        List<StakeRecord> allDeposits = store.getAllStakeDeposits();
        for (StakeRecord stake : allDeposits) {
            if (stake.getWithdrawableEpoch() >= 0 && stake.getWithdrawableEpoch() <= currentEpoch) {
                // The bonded output is freed: deleting the record makes it
                // spendable again (the bond spend check no longer sees it).
                store.deleteStakeDeposit(stake.getPubkey());
                log.info("Stake withdrawal processed: pubkey={}, amount={}",
                        Utils.HEX.encode(stake.getPubkey()), stake.getAmount());
                continue;
            }
            // Reconciliation for the save-time application gap: a deposit whose
            // STAKE block was saved but never gained confirmation (orphaned,
            // or its beacon never confirmed) and is stale is deactivated. Both
            // sides are CHAIN positions (the deposit's chain position at the
            // time its STAKE block was created vs the current position).
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
