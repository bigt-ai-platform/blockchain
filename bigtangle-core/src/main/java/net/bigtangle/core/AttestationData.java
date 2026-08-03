package net.bigtangle.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class AttestationData {

    private long slot;
    private long epoch;
    private long sourceEpoch = -1;
    private long targetEpoch = -1;
    private Sha256Hash beaconBlockHash;
    private Sha256Hash sourceCheckpoint;
    private Sha256Hash targetCheckpoint;
    private byte[] validatorPubkey;
    private byte[] signature;

    public AttestationData() {}

    /**
     * Canonical hash of every field except the signature. The attestation
     * signature covers this hash, so slot, epoch, head, checkpoints and
     * validator cannot be altered without invalidating the signature.
     */
    public Sha256Hash getMessageHash() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(slot);
            dos.writeLong(epoch);
            dos.writeLong(sourceEpoch);
            dos.writeLong(targetEpoch);
            dos.write(beaconBlockHash != null ? beaconBlockHash.getBytes() : new byte[32]);
            dos.write(sourceCheckpoint != null ? sourceCheckpoint.getBytes() : new byte[32]);
            dos.write(targetCheckpoint != null ? targetCheckpoint.getBytes() : new byte[32]);
            if (validatorPubkey != null) {
                dos.writeInt(validatorPubkey.length);
                dos.write(validatorPubkey);
            } else {
                dos.writeInt(0);
            }
            dos.flush();
            return Sha256Hash.of(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getSlot() { return slot; }
    public void setSlot(long s) { this.slot = s; }
    public long getEpoch() { return epoch; }
    public void setEpoch(long e) { this.epoch = e; }
    public long getSourceEpoch() { return sourceEpoch; }
    public void setSourceEpoch(long e) { this.sourceEpoch = e; }
    public long getTargetEpoch() { return targetEpoch; }
    public void setTargetEpoch(long e) { this.targetEpoch = e; }
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
