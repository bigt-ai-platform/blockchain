package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

public class NodeIdTest {

    @Test
    public void testFromPublicKey() {
        byte[] pubKey = new byte[32];
        Arrays.fill(pubKey, (byte) 0xAB);
        NodeId id = NodeId.fromPublicKey(pubKey);
        assertNotNull(id);
        assertEquals(32, id.getBytes().length);
    }

    @Test
    public void testXorDistance() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        b[31] = 1;
        NodeId idA = new NodeId(a);
        NodeId idB = new NodeId(b);
        byte[] xor = idA.xor(idB);
        assertEquals(0, xor[0]);
        assertEquals(1, xor[31]);
    }

    @Test
    public void testBucketIndex() {
        byte[] self = new byte[32];
        byte[] other = new byte[32];
        self[0] = (byte) 0x80;
        other[0] = 0x00;
        NodeId selfId = new NodeId(self);
        NodeId otherId = new NodeId(other);
        int index = selfId.bucketIndex(otherId);
        assertTrue(index >= 0 && index <= 255);
    }

    @Test
    public void testBucketIndexSameNode() {
        byte[] a = new byte[32];
        a[0] = 0x01;
        NodeId id = new NodeId(a);
        assertEquals(0, id.bucketIndex(id));
    }

    @Test
    public void testEquality() {
        byte[] data = new byte[32];
        data[0] = 0x42;
        NodeId id1 = new NodeId(data);
        NodeId id2 = new NodeId(data.clone());
        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    public void testInequality() {
        byte[] a = new byte[32];
        a[0] = 0x01;
        byte[] b = new byte[32];
        b[0] = 0x02;
        NodeId id1 = new NodeId(a);
        NodeId id2 = new NodeId(b);
        assertNotEquals(id1, id2);
    }

    @Test
    public void testRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new NodeId(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new NodeId(null));
    }

    @Test
    public void testGetBytesReturnsCopy() {
        byte[] original = new byte[32];
        original[0] = 0x01;
        NodeId id = new NodeId(original);
        byte[] retrieved = id.getBytes();
        retrieved[0] = (byte) 0xFF;
        byte[] expected = new byte[32];
        expected[0] = 0x01;
        assertArrayEquals(expected, id.getBytes());
    }
}
