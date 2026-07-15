package net.bigtangle.core;

public class AttestationData {

    private long slot;
    private long epoch;
    private Sha256Hash beaconBlockHash;
    private Sha256Hash sourceCheckpoint;
    private Sha256Hash targetCheckpoint;
    private byte[] validatorPubkey;
    private byte[] signature;

    public AttestationData() {}

    public long getSlot() { return slot; }
    public void setSlot(long s) { this.slot = s; }
    public long getEpoch() { return epoch; }
    public void setEpoch(long e) { this.epoch = e; }
    public Sha256Hash getBeaconBlockHash() { return beaconBlockHash; }
    public void setBeaconBlockHash(Sha256Hash h) { this.beaconBlockHash = h; }
    public Sha256Hash getSourceCheckpoint() { return sourceCheckpoint; }
    public void setSourceCheckpoint(Sha256Hash h) { this.sourceCheckpoint = h; }
    public Sha256Hash getTargetCheckpoint() { return targetCheckpoint; }
    public void setTargetCheckpoint(Sha256Hash h) { this.targetCheckpoint = h; }
    public byte[] getValidatorPubkey() { return validatorPubkey; }
    public void setValidatorPubkey(byte[] p) { this.validatorPubkey = p; }
    public byte[] getSignature() { return signature; }
    public void setSignature(byte[] s) { this.signature = s; }
}
