package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

public class PeerControllerTest {

    @Test
    public void testGetPeersWithNoManager() {
        PeerController controller = new PeerController();
        Map<String, Object> result = controller.getPeers();
        assertEquals("disabled", result.get("status"));
    }

    @Test
    public void testGetPeersWithManager() {
        PeerConfiguration config = new PeerConfiguration();
        config.setScoreFloor(0.0);
        PeerManager manager = new PeerManager(config);

        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord peer = NodeRecord.createSelf(kp, "192.168.1.1", 30303, 30304, 1);
        manager.updatePeer(peer);

        PeerController controller = new PeerController();
        java.lang.reflect.Field field;
        try {
            field = PeerController.class.getDeclaredField("peerManager");
            field.setAccessible(true);
            field.set(controller, manager);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> result = controller.getPeers();
        assertEquals(manager.getSelfRecord().getNodeId().toString(), result.get("self"));
        assertTrue(((Number) result.get("count")).intValue() >= 1);
    }

    @Test
    public void testDiscoverWithNoBootnodes() {
        PeerConfiguration config = new PeerConfiguration();
        PeerManager manager = new PeerManager(config);
        PeerController controller = new PeerController();
        try {
            java.lang.reflect.Field field = PeerController.class.getDeclaredField("peerManager");
            field.setAccessible(true);
            field.set(controller, manager);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> result = controller.discover(100);
        assertEquals("no_bootnodes", result.get("status"));
    }
}
