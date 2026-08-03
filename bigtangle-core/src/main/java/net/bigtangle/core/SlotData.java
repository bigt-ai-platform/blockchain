package net.bigtangle.core;

public class SlotData {

    private long slot;
    private long epoch;
    private long proposerIndex;
    private byte[] randaoReveal;
    private Sha256Hash parentHash;
    private Sha256Hash dagStateRoot;
    private Sha256Hash[] attestationRoots;
    private long feePool;

    public SlotData() {}

    public SlotData(long slot, long epoch, long proposerIndex, Sha256Hash parentHash) {
        this.slot = slot;
        this.epoch = epoch;
        this.proposerIndex = proposerIndex;
        this.parentHash = parentHash;
    }

    public long getSlot() { return slot; }
    public void setSlot(long s) { this.slot = s; }
    public long getEpoch() { return epoch; }
    public void setEpoch(long e) { this.epoch = e; }
    public long getProposerIndex() { return proposerIndex; }
    public void setProposerIndex(long i) { this.proposerIndex = i; }
    public byte[] getRandaoReveal() { return randaoReveal; }
    public void setRandaoReveal(byte[] r) { this.randaoReveal = r; }
    public Sha256Hash getParentHash() { return parentHash; }
    public void setParentHash(Sha256Hash h) { this.parentHash = h; }
    public Sha256Hash getDagStateRoot() { return dagStateRoot; }
    public void setDagStateRoot(Sha256Hash h) { this.dagStateRoot = h; }
    public Sha256Hash[] getAttestationRoots() { return attestationRoots; }
    public void setAttestationRoots(Sha256Hash[] a) { this.attestationRoots = a; }

    public long getFeePool() { return feePool; }
    public void setFeePool(long feePool) { this.feePool = feePool; }
}
