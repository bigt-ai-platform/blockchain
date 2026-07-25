package net.bigtangle.p2p;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;

import java.security.MessageDigest;
import java.util.Arrays;

public final class NodeId {

    private final byte[] id;

    public NodeId(byte[] id) {
        if (id == null || id.length != 32)
            throw new IllegalArgumentException("NodeId must be 32 bytes");
        this.id = id.clone();
    }

    public static NodeId fromPublicKey(byte[] publicKey) {
        MessageDigest digest = Sha256Hash.newDigest();
        byte[] hash = digest.digest(publicKey);
        return new NodeId(hash);
    }

    public byte[] getBytes() {
        return id.clone();
    }

    public byte[] xor(NodeId other) {
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) (this.id[i] ^ other.id[i]);
        }
        return result;
    }

    public int bucketIndex(NodeId other) {
        byte[] distance = xor(other);
        for (int i = 0; i < 32; i++) {
            if (distance[i] != 0) {
                return 255 - (i * 8 + (7 - (Integer.numberOfLeadingZeros(distance[i] & 0xFF) - 24)));
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeId nodeId = (NodeId) o;
        return Arrays.equals(id, nodeId.id);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public String toString() {
        return Utils.HEX.encode(id);
    }
}
