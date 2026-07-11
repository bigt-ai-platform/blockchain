package net.bigtangle.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.utils.Json;

public class LayerAnchor implements Serializable {

    private static final long serialVersionUID = 1L;

    private String chainId;
    private Sha256Hash l1RewardHeadHash;
    private long l1Height;
    private Sha256Hash confirmedRoot;
    private byte[] signature;

    public LayerAnchor() {
    }

    public LayerAnchor(String chainId, Sha256Hash l1RewardHeadHash, long l1Height, Sha256Hash confirmedRoot,
            byte[] signature) {
        this.chainId = chainId;
        this.l1RewardHeadHash = l1RewardHeadHash;
        this.l1Height = l1Height;
        this.confirmedRoot = confirmedRoot;
        this.signature = signature;
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

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof LayerAnchor))
            return false;
        LayerAnchor that = (LayerAnchor) o;
        return l1Height == that.l1Height && Objects.equals(chainId, that.chainId)
                && Objects.equals(l1RewardHeadHash, that.l1RewardHeadHash)
                && Objects.equals(confirmedRoot, that.confirmedRoot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chainId, l1RewardHeadHash, l1Height, confirmedRoot);
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(chainId != null ? chainId : "");
        dos.write(l1RewardHeadHash != null ? l1RewardHeadHash.getBytes() : new byte[32]);
        dos.writeLong(l1Height);
        if (confirmedRoot != null) {
            dos.write(1);
            dos.write(confirmedRoot.getBytes());
        } else {
            dos.write(0);
        }
        if (signature != null) {
            dos.writeInt(signature.length);
            dos.write(signature);
        } else {
            dos.writeInt(0);
        }
        dos.flush();
        return baos.toByteArray();
    }

    public static LayerAnchor parse(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        LayerAnchor anchor = new LayerAnchor();
        anchor.chainId = dis.readUTF();
        byte[] hashBytes = new byte[32];
        dis.readFully(hashBytes);
        anchor.l1RewardHeadHash = Sha256Hash.wrap(hashBytes);
        anchor.l1Height = dis.readLong();
        int marker = dis.read();
        if (marker == 1) {
            byte[] rootBytes = new byte[32];
            dis.readFully(rootBytes);
            anchor.confirmedRoot = Sha256Hash.wrap(rootBytes);
        }
        int sigLen = dis.readInt();
        if (sigLen > 0) {
            anchor.signature = new byte[sigLen];
            dis.readFully(anchor.signature);
        }
        return anchor;
    }

    public String toJson() {
        try {
            return Json.jsonmapper().writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static LayerAnchor fromJson(String json) {
        try {
            return Json.jsonmapper().readValue(json, LayerAnchor.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
