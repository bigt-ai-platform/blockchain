package net.bigtangle.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public class SlotData {

    private long slot;
    private long epoch;
    private long proposerIndex;
    private byte[] randaoReveal;
    private Sha256Hash parentHash;
    private Sha256Hash dagStateRoot;
    private Sha256Hash[] attestationRoots;
    private long feePool;
    private byte[] proposerSignature;
    /** Deterministic root over the attestations included with this beacon (inclusion commitment). */
    private Sha256Hash attestationRoot;
    /**
     * The full (signed) attestations included with this beacon. Serialized as
     * part of the SlotData JSON but NOT hashed into {@link #getMessageHash()} —
     * {@link #attestationRoot} (which IS in the message hash) commits to them.
     */
    private List<AttestationData> attestations;

    public SlotData() {}

    /**
     * Canonical hash over every committed field except the proposer signature.
     * The proposer signs this hash, so slot, epoch, proposer index, fee pool,
     * RANDAO reveal and attestation root cannot be altered without invalidating
     * the signature.
     */
    public Sha256Hash getMessageHash() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(slot);
            dos.writeLong(epoch);
            dos.writeLong(proposerIndex);
            dos.writeLong(feePool);
            if (randaoReveal != null) {
                dos.writeInt(randaoReveal.length);
                dos.write(randaoReveal);
            } else {
                dos.writeInt(0);
            }
            dos.write(parentHash != null ? parentHash.getBytes() : new byte[32]);
            dos.write(dagStateRoot != null ? dagStateRoot.getBytes() : new byte[32]);
            dos.write(attestationRoot != null ? attestationRoot.getBytes() : new byte[32]);
            dos.flush();
            return Sha256Hash.of(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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

    public byte[] getProposerSignature() { return proposerSignature; }
    public void setProposerSignature(byte[] proposerSignature) { this.proposerSignature = proposerSignature; }

    public Sha256Hash getAttestationRoot() { return attestationRoot; }
    public void setAttestationRoot(Sha256Hash attestationRoot) { this.attestationRoot = attestationRoot; }

    public List<AttestationData> getAttestations() { return attestations; }
    public void setAttestations(List<AttestationData> attestations) { this.attestations = attestations; }
}
