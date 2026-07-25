package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class PeerScoreTest {

    @Test
    public void testInitialScore() {
        NodeId id = new NodeId(new byte[32]);
        PeerScore score = new PeerScore(id);
        double computed = score.compute(100);
        assertTrue(computed >= 0);
    }

    @Test
    public void testBetterChainLengthHigherScore() {
        NodeId id1 = new NodeId(new byte[32]);
        NodeId id2 = new NodeId(new byte[32]);
        id2.getBytes()[0] = 0x01;

        PeerScore low = new PeerScore(id1);
        PeerScore high = new PeerScore(id2);

        low.updateChainLength(50);
        high.updateChainLength(100);

        double sLow = low.compute(100);
        double sHigh = high.compute(100);
        assertTrue(sHigh > sLow);
    }

    @Test
    public void testFasterResponseHigherScore() {
        NodeId id1 = new NodeId(new byte[32]);
        NodeId id2 = new NodeId(new byte[32]);
        id2.getBytes()[0] = 0x01;

        PeerScore slow = new PeerScore(id1);
        PeerScore fast = new PeerScore(id2);

        slow.recordResponse(3000);
        fast.recordResponse(100);

        double sSlow = slow.compute(100);
        double sFast = fast.compute(100);
        assertTrue(sFast > sSlow);
    }

    @Test
    public void testHigherSuccessRateHigherScore() {
        NodeId id1 = new NodeId(new byte[32]);
        NodeId id2 = new NodeId(new byte[32]);
        id2.getBytes()[0] = 0x01;

        PeerScore low = new PeerScore(id1);
        PeerScore high = new PeerScore(id2);

        low.recordRequest(false);
        low.recordRequest(false);

        high.recordRequest(true);
        high.recordRequest(true);

        double sLow = low.compute(100);
        double sHigh = high.compute(100);
        assertTrue(sHigh > sLow);
    }

    @Test
    public void testRecordRequest() {
        NodeId id = new NodeId(new byte[32]);
        PeerScore score = new PeerScore(id);
        score.recordRequest(true);
        score.recordRequest(false);
        score.recordRequest(true);
        assertEquals(3, score.getTotalRequests());
        assertEquals(2, score.getSuccessfulRequests());
        assertEquals(2.0 / 3.0, score.getSuccessRate(), 0.001);
    }

    @Test
    public void testUpdateChainLength() {
        NodeId id = new NodeId(new byte[32]);
        PeerScore score = new PeerScore(id);
        score.updateChainLength(500);
        assertEquals(500, score.getChainLength());
    }

    @Test
    public void testGetAgeHours() {
        NodeId id = new NodeId(new byte[32]);
        PeerScore score = new PeerScore(id);
        assertEquals(0, score.getAgeHours());
    }
}
