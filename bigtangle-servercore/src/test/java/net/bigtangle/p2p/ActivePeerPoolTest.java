package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class ActivePeerPoolTest {

    private NodeRecord createRecord(String host) {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        return NodeRecord.createSelf(kp, host, 30303, 30304, 1);
    }

    @Test
    public void testEmptyPool() {
        ActivePeerPool pool = new ActivePeerPool(8, 0);
        assertTrue(pool.getActivePeers().isEmpty());
        assertNull(pool.getNextPeer());
    }

    @Test
    public void testUpdateWithCandidates() {
        ActivePeerPool pool = new ActivePeerPool(8, 0);
        NodeRecord r1 = createRecord("host1");
        NodeRecord r2 = createRecord("host2");
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());

        List<NodeRecord> candidates = List.of(r1, r2);
        pool.update(candidates, rt);

        assertEquals(2, pool.size());
        assertEquals(2, pool.getActivePeers().size());
    }

    @Test
    public void testPoolMaxSize() {
        ActivePeerPool pool = new ActivePeerPool(3, 0);
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());

        List<NodeRecord> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(createRecord("host" + i));
        }
        pool.update(candidates, rt);
        assertEquals(3, pool.size());
    }

    @Test
    public void testRoundRobin() {
        ActivePeerPool pool = new ActivePeerPool(8, 0);
        NodeRecord r1 = createRecord("host1");
        NodeRecord r2 = createRecord("host2");
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());

        pool.update(List.of(r1, r2), rt);

        NodeRecord first = pool.getNextPeer();
        NodeRecord second = pool.getNextPeer();
        NodeRecord third = pool.getNextPeer();

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);

        assertEquals(first.getNodeId(), third.getNodeId());
    }

    @Test
    public void testOnDisconnect() {
        ActivePeerPool pool = new ActivePeerPool(8, 0);
        NodeRecord r1 = createRecord("host1");
        NodeRecord r2 = createRecord("host2");
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());

        pool.update(List.of(r1, r2), rt);
        pool.onDisconnect(r1.getNodeId());

        assertEquals(1, pool.size());
        assertEquals(r2.getNodeId(), pool.getActivePeers().get(0).getNodeId());
    }

    @Test
    public void testIsFull() {
        ActivePeerPool pool = new ActivePeerPool(2, 0);
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());
        assertFalse(pool.isFull());

        pool.update(List.of(createRecord("h1"), createRecord("h2")), rt);
        assertTrue(pool.isFull());
    }

    @Test
    public void testUpdateRemovesAbsentPeers() {
        ActivePeerPool pool = new ActivePeerPool(8, 0);
        NodeRecord r1 = createRecord("host1");
        NodeRecord r2 = createRecord("host2");
        NodeRecord r3 = createRecord("host3");
        RoutingTable rt = new RoutingTable(createRecord("self").getNodeId());

        pool.update(List.of(r1, r2, r3), rt);
        assertEquals(3, pool.size());

        pool.update(List.of(r1, r2), rt);
        assertEquals(2, pool.size());
    }
}
