package net.bigtangle.server.data;

import java.util.Objects;

import net.bigtangle.core.Sha256Hash;

public class AnchorRecord {

    private String chainId;
    private Sha256Hash l1RewardHeadHash;
    private long l1Height;
    private Sha256Hash confirmedRoot;
    private String signatureHex;
    private Sha256Hash blockHash;
    private boolean confirmed;

    public AnchorRecord() {
    }

    public AnchorRecord(String chainId, Sha256Hash l1RewardHeadHash, long l1Height, Sha256Hash confirmedRoot,
            String signatureHex, Sha256Hash blockHash, boolean confirmed) {
        this.chainId = chainId;
        this.l1RewardHeadHash = l1RewardHeadHash;
        this.l1Height = l1Height;
        this.confirmedRoot = confirmedRoot;
        this.signatureHex = signatureHex;
        this.blockHash = blockHash;
        this.confirmed = confirmed;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public Sha256Hash getL1RewardHeadHash() {
        return l1RewardHeadHash;
    }

    public void setL1RewardHeadHash(Sha256Hash l1RewardHeadHash) {
        this.l1RewardHeadHash = l1RewardHeadHash;
    }

    public long getL1Height() {
        return l1Height;
    }

    public void setL1Height(long l1Height) {
        this.l1Height = l1Height;
    }

    public Sha256Hash getConfirmedRoot() {
        return confirmedRoot;
    }

    public void setConfirmedRoot(Sha256Hash confirmedRoot) {
        this.confirmedRoot = confirmedRoot;
    }

    public String getSignatureHex() {
        return signatureHex;
    }

    public void setSignatureHex(String signatureHex) {
        this.signatureHex = signatureHex;
    }

    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public void setBlockHash(Sha256Hash blockHash) {
        this.blockHash = blockHash;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AnchorRecord))
            return false;
        AnchorRecord that = (AnchorRecord) o;
        return l1Height == that.l1Height && confirmed == that.confirmed
                && Objects.equals(chainId, that.chainId)
                && Objects.equals(l1RewardHeadHash, that.l1RewardHeadHash)
                && Objects.equals(confirmedRoot, that.confirmedRoot)
                && Objects.equals(signatureHex, that.signatureHex)
                && Objects.equals(blockHash, that.blockHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chainId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, blockHash, confirmed);
    }
}
