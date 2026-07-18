package net.bigtangle.server.service;

import java.math.BigInteger;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.BlockStoreInterface;

@Service
public class StakeService {

    private static final Logger log = LoggerFactory.getLogger(StakeService.class);

    public static final BigInteger MIN_STAKE = BigInteger.valueOf(32_000_000L);
    public static final long WITHDRAWAL_DELAY_EPOCHS = 256;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    @Autowired
    private BlockSaveService blockSaveService;

    public long getEffectiveStake(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed() || stake.getActivatedEpoch() < 0) return 0L;
        return stake.getAmount().longValue();
    }

    public void processDeposit(UTXO utxo, byte[] withdrawalCredentials,
            ECKey depositKey, BlockStoreInterface store) throws Exception {
        if (utxo.getValue().getValue().compareTo(MIN_STAKE) < 0) {
            throw new IllegalArgumentException("Stake must be at least " + MIN_STAKE);
        }

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_STAKE);

        Transaction tx = new Transaction(networkParameters);
        tx.addOutput(utxo.getValue(), depositKey.toAddress(networkParameters));
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);

        StakeRecord stake = new StakeRecord();
        stake.setPubkey(depositKey.getPubKey());
        stake.setAmount(utxo.getValue().getValue());
        stake.setWithdrawalCredentials(withdrawalCredentials);
        stake.setBlockHash(b.getHash());
        store.saveStakeDeposit(stake);

        log.info("Stake deposit: {} BIG from pubkey={}", utxo.getValue(),
                Utils.HEX.encode(depositKey.getPubKey()));
    }

    public void activateValidator(byte[] pubkey, long epoch, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null) {
            throw new IllegalArgumentException("No stake deposit for pubkey");
        }
        if (stake.getActivatedEpoch() >= 0) {
            throw new IllegalStateException("Validator already activated at epoch " + stake.getActivatedEpoch());
        }
        store.updateStakeActivation(pubkey, epoch);
        log.info("Validator activated at epoch {}: pubkey={}", epoch, Utils.HEX.encode(pubkey));
    }

    public void slashValidator(byte[] pubkey, BlockStoreInterface store) throws Exception {
        StakeRecord stake = store.getStakeDeposit(pubkey);
        if (stake == null || stake.isSlashed()) return;

        long currentEpoch = System.currentTimeMillis() / 384_000;
        long withdrawableEpoch = currentEpoch + WITHDRAWAL_DELAY_EPOCHS;
        store.updateStakeSlashing(pubkey, withdrawableEpoch);

        log.info("Validator slashed: pubkey={}, withdrawable at epoch={}",
                Utils.HEX.encode(pubkey), withdrawableEpoch);
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
