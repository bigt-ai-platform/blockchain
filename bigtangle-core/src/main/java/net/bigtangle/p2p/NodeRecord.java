package net.bigtangle.p2p;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.SecureRandom;

public class NodeRecord {

    private final NodeId nodeId;
    private final byte[] publicKey;
    private final byte[] privateKey;
    private final String host;
    private final int udpPort;
    private final int tcpPort;
    private final long seq;
    private final byte[] signature;

    private NodeRecord(Builder builder) {
        this.publicKey = builder.publicKey;
        this.privateKey = builder.privateKey;
        this.nodeId = NodeId.fromPublicKey(publicKey);
        this.host = builder.host;
        this.udpPort = builder.udpPort;
        this.tcpPort = builder.tcpPort;
        this.seq = builder.seq;
        this.signature = builder.signature;
    }

    public static KeyPair generateKeyPair() {
        Ed25519KeyPairGenerator generator = new Ed25519KeyPairGenerator();
        generator.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        Ed25519PrivateKeyParameters priv = (Ed25519PrivateKeyParameters) pair.getPrivate();
        Ed25519PublicKeyParameters pub = (Ed25519PublicKeyParameters) pair.getPublic();
        return new KeyPair(pub.getEncoded(), priv.getEncoded());
    }

    public static NodeRecord createSelf(KeyPair keyPair, String host, int udpPort, int tcpPort, long seq) {
        byte[] unsigned = serializeUnsigned(keyPair.publicKey, host, udpPort, tcpPort, seq);
        byte[] signature = sign(keyPair.privateKey, unsigned);
        return new Builder()
                .publicKey(keyPair.publicKey)
                .privateKey(keyPair.privateKey)
                .host(host)
                .udpPort(udpPort)
                .tcpPort(tcpPort)
                .seq(seq)
                .signature(signature)
                .build();
    }

    public static NodeRecord fromSigned(byte[] publicKey, String host, int udpPort, int tcpPort, long seq, byte[] signature) {
        byte[] unsigned = serializeUnsigned(publicKey, host, udpPort, tcpPort, seq);
        if (!verify(publicKey, unsigned, signature)) {
            throw new IllegalArgumentException("Invalid signature on NodeRecord");
        }
        return new Builder()
                .publicKey(publicKey)
                .host(host)
                .udpPort(udpPort)
                .tcpPort(tcpPort)
                .seq(seq)
                .signature(signature)
                .build();
    }

    private static byte[] serializeUnsigned(byte[] publicKey, String host, int udpPort, int tcpPort, long seq) {
        byte[] hostBytes = host.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(baos)) {
            dos.write(publicKey);
            dos.writeShort(udpPort);
            dos.writeShort(tcpPort);
            dos.writeLong(seq);
            dos.writeInt(hostBytes.length);
            dos.write(hostBytes);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static byte[] sign(byte[] privateKey, byte[] data) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(privateKey, 0));
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    private static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        signer.update(data, 0, data.length);
        return signer.verifySignature(signature);
    }

    public NodeId getNodeId() { return nodeId; }
    public byte[] getPublicKey() { return publicKey.clone(); }
    public String getHost() { return host; }
    public int getUdpPort() { return udpPort; }
    public int getTcpPort() { return tcpPort; }
    public long getSeq() { return seq; }
    public byte[] getSignature() { return signature.clone(); }

    public byte[] serialize() {
        byte[] unsignedPart = serializeUnsigned(publicKey, host, udpPort, tcpPort, seq);
        byte[] result = new byte[unsignedPart.length + 64];
        System.arraycopy(unsignedPart, 0, result, 0, unsignedPart.length);
        System.arraycopy(signature, 0, result, unsignedPart.length, 64);
        return result;
    }

    public static NodeRecord deserialize(byte[] data) {
        int sigOffset = data.length - 64;
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        try (java.io.DataInputStream dis = new java.io.DataInputStream(bais)) {
            byte[] publicKey = new byte[32];
            dis.readFully(publicKey);
            int udpPort = dis.readUnsignedShort();
            int tcpPort = dis.readUnsignedShort();
            long seq = dis.readLong();
            int hostLen = dis.readInt();
            byte[] hostBytes = new byte[hostLen];
            dis.readFully(hostBytes);
            String host = new java.lang.String(hostBytes, java.nio.charset.StandardCharsets.UTF_8);

            byte[] signature = new byte[64];
            System.arraycopy(data, sigOffset, signature, 0, 64);
            return fromSigned(publicKey, host, udpPort, tcpPort, seq, signature);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "NodeRecord{id=" + nodeId + " host=" + host + ":" + tcpPort + " seq=" + seq + "}";
    }

    public String toEnr() {
        return "enr:" + java.util.HexFormat.of().formatHex(serialize());
    }

    public static NodeRecord fromEnr(String enr) {
        if (enr == null || !enr.startsWith("enr:"))
            throw new IllegalArgumentException("Invalid ENR format");
        String hex = enr.substring(4);
        return deserialize(java.util.HexFormat.of().parseHex(hex));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeRecord that = (NodeRecord) o;
        return nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }

    public static class KeyPair {
        public final byte[] publicKey;
        public final byte[] privateKey;
        public KeyPair(byte[] publicKey, byte[] privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }
    }

    public static class Builder {
        private byte[] publicKey;
        private byte[] privateKey;
        private String host;
        private int udpPort;
        private int tcpPort;
        private long seq;
        private byte[] signature;

        public Builder publicKey(byte[] publicKey) { this.publicKey = publicKey; return this; }
        public Builder privateKey(byte[] privateKey) { this.privateKey = privateKey; return this; }
        public Builder host(String host) { this.host = host; return this; }
        public Builder udpPort(int udpPort) { this.udpPort = udpPort; return this; }
        public Builder tcpPort(int tcpPort) { this.tcpPort = tcpPort; return this; }
        public Builder seq(long seq) { this.seq = seq; return this; }
        public Builder signature(byte[] signature) { this.signature = signature; return this; }
        public NodeRecord build() { return new NodeRecord(this); }
    }
}
