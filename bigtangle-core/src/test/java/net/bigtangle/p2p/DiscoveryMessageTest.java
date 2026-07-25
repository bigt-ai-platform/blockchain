package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class DiscoveryMessageTest {

    @Test
    public void testCreatePing() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);
        NodeId senderId = self.getNodeId();

        byte[] payload = "ping data".getBytes();
        DiscoveryMessage msg = DiscoveryMessage.create(
                DiscoveryMessage.Type.PING, senderId, payload, kp.privateKey);

        assertEquals(DiscoveryMessage.Type.PING, msg.getType());
        assertEquals(senderId, msg.getSenderId());
        assertArrayEquals(payload, msg.getPayload());
        assertNotNull(msg.getSignature());
    }

    @Test
    public void testVerifySignature() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);

        byte[] payload = "hello".getBytes();
        DiscoveryMessage msg = DiscoveryMessage.create(
                DiscoveryMessage.Type.PING, self.getNodeId(), payload, kp.privateKey);

        assertTrue(msg.verifySignature(kp.publicKey));
    }

    @Test
    public void testVerifySignatureWithWrongKey() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord.KeyPair wrongKp = NodeRecord.generateKeyPair();
        NodeRecord self = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);

        byte[] payload = "hello".getBytes();
        DiscoveryMessage msg = DiscoveryMessage.create(
                DiscoveryMessage.Type.PING, self.getNodeId(), payload, kp.privateKey);

        assertFalse(msg.verifySignature(wrongKp.publicKey));
    }

    @Test
    public void testSerializeDeserialize() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);

        byte[] payload = "{\"key\":\"value\"}".getBytes();
        DiscoveryMessage original = DiscoveryMessage.create(
                DiscoveryMessage.Type.FINDNODE, self.getNodeId(), payload, kp.privateKey);

        byte[] serialized = original.serialize();
        DiscoveryMessage deserialized = DiscoveryMessage.deserialize(serialized);

        assertEquals(original.getType(), deserialized.getType());
        assertEquals(original.getSenderId(), deserialized.getSenderId());
        assertArrayEquals(original.getPayload(), deserialized.getPayload());
        assertArrayEquals(original.getSignature(), deserialized.getSignature());
    }

    @Test
    public void testInvalidMagic() {
        assertThrows(IllegalArgumentException.class, () ->
                DiscoveryMessage.deserialize(new byte[100]));
    }

    @Test
    public void testAllMessageTypes() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeId id = NodeId.fromPublicKey(kp.publicKey);

        for (DiscoveryMessage.Type type : DiscoveryMessage.Type.values()) {
            byte[] payload = ("type:" + type).getBytes();
            DiscoveryMessage msg = DiscoveryMessage.create(type, id, payload, kp.privateKey);
            byte[] ser = msg.serialize();
            DiscoveryMessage deser = DiscoveryMessage.deserialize(ser);
            assertEquals(type, deser.getType());
        }
    }

    @Test
    public void testTamperedPayload() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = NodeRecord.createSelf(kp, "127.0.0.1", 30303, 30304, 1);

        byte[] payload = "original".getBytes();
        DiscoveryMessage msg = DiscoveryMessage.create(
                DiscoveryMessage.Type.PING, self.getNodeId(), payload, kp.privateKey);

        byte[] serialized = msg.serialize();
        serialized[37] ^= 0xFF;

        DiscoveryMessage tampered = DiscoveryMessage.deserialize(serialized);
        assertFalse(tampered.verifySignature(kp.publicKey));
    }
}
