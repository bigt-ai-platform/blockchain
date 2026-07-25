package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class RoutingTableTest {

    private NodeRecord createRecord(String host, NodeRecord.KeyPair kp) {
        return NodeRecord.createSelf(kp, host, 30303, 30304, 1);
    }

    @Test
    public void testUpdateAddsPeer() {
        NodeRecord.KeyPair selfKp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", selfKp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        NodeRecord.KeyPair peerKp = NodeRecord.generateKeyPair();
        NodeRecord peer = createRecord("peer", peerKp);
        assertNotEquals(self.getNodeId(), peer.getNodeId());

        assertTrue(table.update(peer));
        assertEquals(1, table.totalEntries());
    }

    @Test
    public void testUpdateSelfIsIgnored() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", kp);
        RoutingTable table = new RoutingTable(self.getNodeId());
        assertFalse(table.update(self));
        assertEquals(0, table.totalEntries());
    }

    @Test
    public void testRemovePeer() {
        NodeRecord.KeyPair selfKp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", selfKp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        NodeRecord.KeyPair peerKp = NodeRecord.generateKeyPair();
        NodeRecord peer = createRecord("peer", peerKp);
        table.update(peer);
        assertTrue(table.remove(peer.getNodeId()));
        assertEquals(0, table.totalEntries());
    }

    @Test
    public void testFindClosest() {
        NodeRecord.KeyPair selfKp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", selfKp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        for (int i = 0; i < 20; i++) {
            NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
            NodeRecord peer = createRecord("peer" + i, kp);
            if (!peer.getNodeId().equals(self.getNodeId())) {
                table.update(peer);
            }
        }

        byte[] targetBytes = new byte[32];
        targetBytes[0] = 0x01;
        NodeId target = new NodeId(targetBytes);
        List<NodeRecord> closest = table.findClosest(target, 16);
        assertTrue(closest.size() <= 16);
        assertTrue(closest.size() > 0);
    }

    @Test
    public void testFindClosestEmptyTable() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", kp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        byte[] targetBytes = new byte[32];
        targetBytes[0] = 0x01;
        List<NodeRecord> closest = table.findClosest(new NodeId(targetBytes), 16);
        assertTrue(closest.isEmpty());
    }

    @Test
    public void testEvictStaleDoesNotCrash() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", kp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        table.evictStale();
        assertEquals(0, table.totalEntries());
    }

    @Test
    public void testBucketDistribution() {
        NodeRecord.KeyPair selfKp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", selfKp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        for (int i = 0; i < 100; i++) {
            NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
            NodeRecord peer = createRecord("peer" + i, kp);
            if (!peer.getNodeId().equals(self.getNodeId())) {
                table.update(peer);
            }
        }

        int total = table.totalEntries();
        assertTrue(total > 0);
        assertTrue(total <= 100);
    }

    @Test
    public void testGetAllEntries() {
        NodeRecord.KeyPair selfKp = NodeRecord.generateKeyPair();
        NodeRecord self = createRecord("self", selfKp);
        RoutingTable table = new RoutingTable(self.getNodeId());

        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord peer = createRecord("peer", kp);
        table.update(peer);

        List<NodeRecord> all = table.getAllEntries();
        assertEquals(1, all.size());
        assertEquals(peer.getNodeId(), all.get(0).getNodeId());
    }
}
