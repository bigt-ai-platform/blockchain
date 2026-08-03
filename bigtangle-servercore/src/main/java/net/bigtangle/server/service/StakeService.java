package net.bigtangle.server.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.UTXO;
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

@Service
public class StakeService {

    private static final Logger log = LoggerFactory.getLogger(StakeService.class);

    public static final BigInteger MIN_STAKE = BigInteger.valueOf(32_000_000L);
    public static final long WITHDRAWAL_DELAY_EPOCHS = 256;
    public static final String STAKE_DATA_CLASS = "StakeDeposit";

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
     * bonded output so the funds are not freely spendable. Idempotent.
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

        StakeRecord stake = new StakeRecord(pubkey, amount.getValue(), creds);
        stake.setBlockHash(block.getHash());
        stake.setTxHash(tx.getHash());
        stake.setActivatedEpoch(SlotService.epochAt(block.getTimeSeconds() * 1000L));
        store.saveStakeDeposit(stake);

        lockBondedOutput(block, tx, store);
        log.info("Applied chain-derived stake deposit for pubkey={} amount={} block={}",
                Utils.HEX.encode(pubkey), amount, block.getHashAsString());
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
     * Slashes a validator: confiscates the bonded deposit output (marked spent)
     * and records the slashed state with a withdrawable epoch in chain-derived
     * epoch units. The confiscation gives slashing economic weight.
     */
    public void slashValidator(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) return;

        confiscateBond(pubkey, stake, store);

        long currentEpoch = SlotService.epochAt(System.currentTimeMillis());
        long withdrawableEpoch = currentEpoch + WITHDRAWAL_DELAY_EPOCHS;
        store.updateStakeSlashing(pubkey, withdrawableEpoch);

        log.info("Validator slashed: pubkey={}, withdrawable at epoch={}",
                Utils.HEX.encode(pubkey), withdrawableEpoch);
    }

    /** Marks the bonded deposit output as spent (burned/confiscated). */
    private void confiscateBond(byte[] pubkey, StakeRecord stake, BlockStoreInterface store) throws Exception {
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

    public void processWithdrawals(long currentEpoch, BlockStoreInterface store) throws Exception {
        List<StakeRecord> allDeposits = store.getAllStakeDeposits();
        for (StakeRecord stake : allDeposits) {
            if (stake.getWithdrawableEpoch() >= 0 && stake.getWithdrawableEpoch() <= currentEpoch
                    && (stake.isSlashed() || stake.getActivatedEpoch() >= 0)) {
                store.releaseStakeDeposit(stake.getPubkey());
                log.info("Stake withdrawal processed: pubkey={}, amount={}",
                        Utils.HEX.encode(stake.getPubkey()), stake.getAmount());
            }
        }
    }
}
