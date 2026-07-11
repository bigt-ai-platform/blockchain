package net.bigtangle.bridge;

import java.io.Serializable;
import java.util.Objects;

import net.bigtangle.core.Sha256Hash;

public class LayerAnchor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceChainId;
    private String targetChainId;
    private Sha256Hash sourceBlockHash;
    private Sha256Hash targetBlockHash;
    private long sourceHeight;
    private long targetHeight;

    public LayerAnchor() {
    }

    public LayerAnchor(String sourceChainId, String targetChainId, Sha256Hash sourceBlockHash,
            Sha256Hash targetBlockHash, long sourceHeight, long targetHeight) {
        this.sourceChainId = sourceChainId;
        this.targetChainId = targetChainId;
        this.sourceBlockHash = sourceBlockHash;
        this.targetBlockHash = targetBlockHash;
        this.sourceHeight = sourceHeight;
        this.targetHeight = targetHeight;
    }

    public String getSourceChainId() {
        return sourceChainId;
    }

    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    public String getTargetChainId() {
        return targetChainId;
    }

    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
    }

    public Sha256Hash getSourceBlockHash() {
        return sourceBlockHash;
    }

    public void setSourceBlockHash(Sha256Hash sourceBlockHash) {
        this.sourceBlockHash = sourceBlockHash;
    }

    public Sha256Hash getTargetBlockHash() {
        return targetBlockHash;
    }

    public void setTargetBlockHash(Sha256Hash targetBlockHash) {
        this.targetBlockHash = targetBlockHash;
    }

    public long getSourceHeight() {
        return sourceHeight;
    }

    public void setSourceHeight(long sourceHeight) {
        this.sourceHeight = sourceHeight;
    }

    public long getTargetHeight() {
        return targetHeight;
    }

    public void setTargetHeight(long targetHeight) {
        this.targetHeight = targetHeight;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof LayerAnchor)) {
            return false;
        }
        LayerAnchor that = (LayerAnchor) object;
        return sourceHeight == that.sourceHeight
                && targetHeight == that.targetHeight
                && Objects.equals(sourceChainId, that.sourceChainId)
                && Objects.equals(targetChainId, that.targetChainId)
                && Objects.equals(sourceBlockHash, that.sourceBlockHash)
                && Objects.equals(targetBlockHash, that.targetBlockHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceChainId, targetChainId, sourceBlockHash, targetBlockHash, sourceHeight, targetHeight);
    }
}