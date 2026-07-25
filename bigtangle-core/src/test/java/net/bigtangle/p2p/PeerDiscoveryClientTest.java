package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class PeerDiscoveryClientTest {

    @Test
    public void testEmptyBootnodes() {
        PeerDiscoveryClient client = new PeerDiscoveryClient(List.of());
        List<NodeRecord> result = client.discover(10);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testHexRoundtrip() {
        byte[] data = {0x00, 0x01, (byte) 0xFF, 0x42};
        String hex = PeerDiscoveryClient.hex(data);
        assertArrayEquals(data, PeerDiscoveryClient.parseHex(hex));
    }

    @Test
    public void testParseNodesResponseWithEmptyPayload() throws Exception {
        PeerDiscoveryClient client = new PeerDiscoveryClient(List.of());
        byte[] payload = "{\"nodes\":[]}".getBytes();
        java.lang.reflect.Method method = PeerDiscoveryClient.class.getDeclaredMethod(
                "parseNodesResponse", byte[].class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<NodeRecord> result = (List<NodeRecord>) method.invoke(client, payload);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testDistanceToTarget() throws Exception {
        PeerDiscoveryClient client = new PeerDiscoveryClient(List.of());

        byte[] a = new byte[32];
        byte[] b = new byte[32];
        b[31] = 0x01;

        java.lang.reflect.Method method = PeerDiscoveryClient.class.getDeclaredMethod(
                "distanceToTarget", NodeId.class, NodeId.class);
        method.setAccessible(true);
        int dist = (int) method.invoke(client, new NodeId(a), new NodeId(b));
        assertTrue(dist >= 0 && dist <= 255);
    }

    @Test
    public void testEncodeNodeKey() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord record = NodeRecord.createSelf(kp, "host", 30303, 30304, 1);
        String key = PeerDiscoveryClient.encodeNodeKey(record);
        assertEquals(record.getNodeId().toString(), key);
    }
}
