package net.bigtangle.p2p;

import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.util.Arrays;

public class DiscoveryMessage {

    public enum Type {
        PING(0),
        PONG(1),
        FINDNODE(2),
        NODES(3),
        ENR(4);

        private final int code;
        Type(int code) { this.code = code; }
        public int code() { return code; }

        public static Type fromCode(int code) {
            for (Type t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("Unknown message type: " + code);
        }
    }

    private static final byte[] MAGIC = new byte[]{'b', 'g', 'l', '0'};

    private final Type type;
    private final NodeId senderId;
    private final byte[] payload;
    private final byte[] signature;

    public DiscoveryMessage(Type type, NodeId senderId, byte[] payload, byte[] signature) {
        this.type = type;
        this.senderId = senderId;
        this.payload = payload;
        this.signature = signature;
    }

    public Type getType() { return type; }
    public NodeId getSenderId() { return senderId; }
    public byte[] getPayload() { return payload; }
    public byte[] getSignature() { return signature; }

    public static DiscoveryMessage create(Type type, NodeId senderId, byte[] payload, byte[] privateKey) {
        byte[] unsigned = serializeForSigning(type, senderId, payload);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey, 0));
        signer.update(unsigned, 0, unsigned.length);
        byte[] signature = signer.generateSignature();
        return new DiscoveryMessage(type, senderId, payload, signature);
    }

    public boolean verifySignature(byte[] publicKey) {
        byte[] unsigned = serializeForSigning(type, senderId, payload);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        signer.update(unsigned, 0, unsigned.length);
        return signer.verifySignature(signature);
    }

    public byte[] serialize() {
        byte[] senderBytes = senderId.getBytes();
        byte[] typeAndPayload = new byte[1 + payload.length];
        typeAndPayload[0] = (byte) type.code();
        System.arraycopy(payload, 0, typeAndPayload, 1, payload.length);

        int totalLen = MAGIC.length + senderBytes.length + typeAndPayload.length + signature.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        System.arraycopy(MAGIC, 0, result, pos, MAGIC.length);
        pos += MAGIC.length;
        System.arraycopy(senderBytes, 0, result, pos, senderBytes.length);
        pos += senderBytes.length;
        System.arraycopy(typeAndPayload, 0, result, pos, typeAndPayload.length);
        pos += typeAndPayload.length;
        System.arraycopy(signature, 0, result, pos, signature.length);
        return result;
    }

    public static DiscoveryMessage deserialize(byte[] data) {
        int pos = 0;

        byte[] magic = new byte[MAGIC.length];
        System.arraycopy(data, pos, magic, 0, MAGIC.length);
        pos += MAGIC.length;
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("Invalid magic bytes");
        }

        byte[] senderBytes = new byte[32];
        System.arraycopy(data, pos, senderBytes, 0, 32);
        pos += 32;

        int typeCode = data[pos] & 0xFF;
        Type type = Type.fromCode(typeCode);
        pos++;

        int payloadLen = data.length - MAGIC.length - 32 - 1 - 64;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, pos, payload, 0, payloadLen);
        pos += payloadLen;

        byte[] signature = new byte[64];
        System.arraycopy(data, pos, signature, 0, 64);

        return new DiscoveryMessage(type, new NodeId(senderBytes), payload, signature);
    }

    private static byte[] serializeForSigning(Type type, NodeId senderId, byte[] payload) {
        byte[] senderBytes = senderId.getBytes();
        byte[] result = new byte[MAGIC.length + senderBytes.length + 1 + payload.length];
        int pos = 0;
        System.arraycopy(MAGIC, 0, result, pos, MAGIC.length);
        pos += MAGIC.length;
        System.arraycopy(senderBytes, 0, result, pos, senderBytes.length);
        pos += senderBytes.length;
        result[pos++] = (byte) type.code();
        System.arraycopy(payload, 0, result, pos, payload.length);
        return result;
    }
}
