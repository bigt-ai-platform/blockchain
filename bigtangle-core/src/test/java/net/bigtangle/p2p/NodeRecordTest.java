package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class NodeRecordTest {

    @Test
    public void testGenerateKeyPair() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        assertEquals(32, kp.publicKey.length);
        assertEquals(32, kp.privateKey.length);
    }

    @Test
    public void testCreateSelf() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);
        assertEquals("127.0.0.1", record.getHost());
        assertEquals(30303, record.getUdpPort());
        assertEquals(30304, record.getTcpPort());
        assertEquals(1, record.getSeq());
        assertNotNull(record.getNodeId());
        assertNotNull(record.getSignature());
    }

    @Test
    public void testFromSignedValidSignature() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord original = NodeRecord.createSelf(kp, "192.168.1.1", 30303, 30304, 2);
        NodeRecord deserialized = NodeRecord.fromSigned(
                original.getPublicKey(),
                original.getHost(),
                original.getUdpPort(),
                original.getTcpPort(),
                original.getSeq(),
                original.getSignature()
        );
        assertEquals(original.getNodeId(), deserialized.getNodeId());
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getSeq(), deserialized.getSeq());
    }

    @Test
    public void testFromSignedInvalidSignature() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair kp2 = NodeRecord.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () ->
                NodeRecord.fromSigned(kp.publicKey, "host", 30303, 30304, 1, kp2.publicKey)
        );
    }

    @Test
    public void testSerializeDeserialize() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord original = NodeRecord.createSelf(kp, "node.example.com", 30303, 30304, 5);
        byte[] serialized = original.serialize();
        NodeRecord deserialized = NodeRecord.deserialize(serialized);
        assertEquals(original.getNodeId(), deserialized.getNodeId());
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getUdpPort(), deserialized.getUdpPort());
        assertEquals(original.getTcpPort(), deserialized.getTcpPort());
        assertEquals(original.getSeq(), deserialized.getSeq());
        assertArrayEquals(original.getPublicKey(), deserialized.getPublicKey());
        assertArrayEquals(original.getSignature(), deserialized.getSignature());
    }

    @Test
    public void testTamperedSerialization() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord original = NodeRecord.createSelf(kp, "example.com", 30303, 30304, 1);
        byte[] serialized = original.serialize();
        serialized[48] = 'x';
        assertThrows(IllegalArgumentException.class, () -> NodeRecord.deserialize(serialized));
    }

    @Test
    public void testEnrRoundtrip() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord original = NodeRecord.createSelf(kp, "enr-test.example.com", 30303, 30304, 7);
        String enr = original.toEnr();
        assertTrue(enr.startsWith("enr:"));

        NodeRecord parsed = NodeRecord.fromEnr(enr);
        assertEquals(original.getNodeId(), parsed.getNodeId());
        assertEquals(original.getHost(), parsed.getHost());
        assertEquals(original.getUdpPort(), parsed.getUdpPort());
        assertEquals(original.getTcpPort(), parsed.getTcpPort());
        assertEquals(original.getSeq(), parsed.getSeq());
    }

    @Test
    public void testFromEnrInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> NodeRecord.fromEnr("invalid"));
        assertThrows(IllegalArgumentException.class, () -> NodeRecord.fromEnr("enx:abcd"));
    }

    @Test
    public void testEquality() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord r1 = NodeRecord.createSelf(kp, "host1", 30303, 30304, 1);
        NodeRecord r2 = NodeRecord.createSelf(kp, "host2", 30303, 30304, 1);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }
}
