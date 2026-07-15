package net.bigtangle.core;

import java.math.BigInteger;
import java.util.Arrays;

public class StakeRecord {

    private byte[] pubkey;
    private BigInteger amount;
    private byte[] withdrawalCredentials;
    private long activatedEpoch = -1;
    private boolean slashed = false;
    private long withdrawableEpoch = -1;
    private Sha256Hash blockHash;

    public StakeRecord() {}

    public StakeRecord(byte[] pubkey, BigInteger amount, byte[] withdrawalCredentials) {
        this.pubkey = pubkey;
        this.amount = amount;
        this.withdrawalCredentials = withdrawalCredentials;
    }

    public byte[] getPubkey() { return pubkey; }
    public void setPubkey(byte[] pubkey) { this.pubkey = pubkey; }
    public BigInteger getAmount() { return amount; }
    public void setAmount(BigInteger amount) { this.amount = amount; }
    public byte[] getWithdrawalCredentials() { return withdrawalCredentials; }
    public void setWithdrawalCredentials(byte[] c) { this.withdrawalCredentials = c; }
    public long getActivatedEpoch() { return activatedEpoch; }
    public void setActivatedEpoch(long e) { this.activatedEpoch = e; }
    public boolean isSlashed() { return slashed; }
    public void setSlashed(boolean s) { this.slashed = s; }
    public long getWithdrawableEpoch() { return withdrawableEpoch; }
    public void setWithdrawableEpoch(long e) { this.withdrawableEpoch = e; }
    public Sha256Hash getBlockHash() { return blockHash; }
    public void setBlockHash(Sha256Hash h) { this.blockHash = h; }

    @Override
    public String toString() {
        return "StakeRecord{pubkey=" + Utils.HEX.encode(pubkey)
            + ", amount=" + amount + ", slashed=" + slashed + "}";
    }
}
