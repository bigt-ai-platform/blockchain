package net.bigtangle.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.Sha256Hash;

public class LayerAnchorTest {

    @Test
    public void testRoundTrip() throws Exception {
        LayerAnchor original = new LayerAnchor(
                "L1",
                Sha256Hash.of("rewardHash".getBytes()),
                42,
                Sha256Hash.of("confirmedRoot".getBytes()),
                "signature".getBytes(),
                new MerkleProof()
        );
        byte[] bytes = original.toByteArray();
        LayerAnchor reparsed = LayerAnchor.parse(bytes);
        assertEquals(original.getChainId(), reparsed.getChainId());
        assertEquals(original.getL1RewardHeadHash(), reparsed.getL1RewardHeadHash());
        assertEquals(original.getL1Height(), reparsed.getL1Height());
        assertEquals(original.getConfirmedRoot(), reparsed.getConfirmedRoot());
        assertArrayEquals(original.getSignature(), reparsed.getSignature());
    }

    @Test
    public void testRoundTripNullSignature() throws Exception {
        LayerAnchor original = new LayerAnchor(
                "L1",
                Sha256Hash.of("reward".getBytes()),
                100,
                Sha256Hash.of("root".getBytes()),
                null,
                null
        );
        byte[] bytes = original.toByteArray();
        LayerAnchor reparsed = LayerAnchor.parse(bytes);
        assertEquals(original.getChainId(), reparsed.getChainId());
        assertEquals(original.getL1Height(), reparsed.getL1Height());
        assertEquals(0, reparsed.getSignature() == null ? 0 : reparsed.getSignature().length);
    }

    @Test
    public void testRoundTripEmptyChainId() throws Exception {
        LayerAnchor original = new LayerAnchor(
                "",
                Sha256Hash.ZERO_HASH,
                0,
                null,
                new byte[0],
                null
        );
        byte[] bytes = original.toByteArray();
        LayerAnchor reparsed = LayerAnchor.parse(bytes);
        assertEquals("", reparsed.getChainId());
        assertEquals(Sha256Hash.ZERO_HASH, reparsed.getL1RewardHeadHash());
        assertEquals(0, reparsed.getL1Height());
        assertEquals(null, reparsed.getConfirmedRoot());
        assertTrue(reparsed.getSignature() == null || reparsed.getSignature().length == 0);
    }

    @Test
    public void testDeterministic() throws Exception {
        LayerAnchor a = new LayerAnchor("L0", Sha256Hash.of("h1".getBytes()), 1, Sha256Hash.of("r1".getBytes()), "sig".getBytes(), null);
        LayerAnchor b = new LayerAnchor("L0", Sha256Hash.of("h1".getBytes()), 1, Sha256Hash.of("r1".getBytes()), "sig".getBytes(), null);
        byte[] ba = a.toByteArray();
        byte[] bb = b.toByteArray();
        assertEquals(ba.length, bb.length);
        for (int i = 0; i < ba.length; i++) {
            assertEquals(ba[i], bb[i]);
        }
    }

    @Test
    public void testEquals() throws Exception {
        LayerAnchor a = new LayerAnchor("L0", Sha256Hash.of("h".getBytes()), 1, Sha256Hash.of("r".getBytes()), null, null);
        LayerAnchor b = new LayerAnchor("L0", Sha256Hash.of("h".getBytes()), 1, Sha256Hash.of("r".getBytes()), null, null);
        assertTrue(a.equals(b));
    }

    @Test
    public void testJsonBackwardCompat() throws Exception {
        // Legacy JSON serialization
        LayerAnchor original = new LayerAnchor("L1", Sha256Hash.of("h".getBytes()), 5, Sha256Hash.of("r".getBytes()), "sig".getBytes(), null);
        String json = original.toJson();
        assertNotNull(json);
        assertTrue(json.contains("L1"));

        // Should still parse from JSON
        LayerAnchor fromJson = LayerAnchor.fromJson(json);
        assertEquals(original.getChainId(), fromJson.getChainId());
        assertEquals(original.getL1Height(), fromJson.getL1Height());
    }
}
