package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class PeerManagerTest {

    private PeerConfiguration createConfig() {
        PeerConfiguration config = new PeerConfiguration();
        config.setUdpPort(0);
        config.setTcpPort(0);
        config.setBucketSize(16);
        config.setMaxPeers(100);
        config.setActivePeers(8);
        config.setMinValidators(0);
        config.setScoreFloor(0.0);
        return config;
    }

    @Test
    public void testPeerManagerCreation() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);
        assertNotNull(manager.getSelfRecord());
        assertNotNull(manager.getRoutingTable());
        assertNotNull(manager.getActivePool());
    }

    @Test
    public void testUpdatePeer() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);

        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord peer = NodeRecord.createSelf(kp, "192.168.1.1", 30303, 30304, 1);
        assertTrue(manager.updatePeer(peer));

        List<NodeRecord> closest = manager.findClosest(peer.getNodeId(), 10);
        assertFalse(closest.isEmpty());
    }

    @Test
    public void testRemovePeer() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);

        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord peer = NodeRecord.createSelf(kp, "192.168.1.1", 30303, 30304, 1);
        manager.updatePeer(peer);
        assertTrue(manager.removePeer(peer.getNodeId()));

        List<NodeRecord> closest = manager.findClosest(peer.getNodeId(), 10);
        assertTrue(closest.isEmpty());
    }

    @Test
    public void testGetActivePeersEmpty() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);
        assertTrue(manager.getActivePeers().isEmpty());
    }

    @Test
    public void testScoreIsCreatedOnUpdate() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);

        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord peer = NodeRecord.createSelf(kp, "192.168.1.1", 30303, 30304, 1);
        manager.updatePeer(peer);

        assertNotNull(manager.getScore(peer.getNodeId()));
    }

    @Test
    public void testDoesNotAddSelf() {
        PeerConfiguration config = createConfig();
        PeerManager manager = new PeerManager(config);
        assertFalse(manager.updatePeer(manager.getSelfRecord()));
    }
}
