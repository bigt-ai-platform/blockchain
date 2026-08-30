package net.bigtangle.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.crypto.pq.PQScriptUtils;

/**
 * Unit test for the M-of-N issuance signature container (threshold issuance,
 * {@link L1CrosstangleHandler#encodeSignatureList}/{@code decodeSignatureList}).
 * No database required.
 */
public class IssuanceSignatureListTest {

    @Test
    public void testSignatureListRoundTrip() throws Exception {
        PQKey k1 = PQKey.createNew();
        PQKey k2 = PQKey.createNew();
        Sha256Hash msg = Sha256Hash.of("issuance".getBytes());

        List<byte[]> sigs = List.of(k1.sign(msg).serialize(), k2.sign(msg).serialize());
        byte[] container = L1CrosstangleHandler.encodeSignatureList(sigs);
        List<byte[]> decoded = L1CrosstangleHandler.decodeSignatureList(container);

        assertEquals(2, decoded.size(), "container must round-trip the exact signature count");
        assertTrue(PQScriptUtils.verifyPQ(k1.getPublicKeyBytes(), decoded.get(0), msg));
        assertTrue(PQScriptUtils.verifyPQ(k2.getPublicKeyBytes(), decoded.get(1), msg));
        // A non-signer key must not verify against either signature.
        PQKey other = PQKey.createNew();
        assertFalse(PQScriptUtils.verifyPQ(other.getPublicKeyBytes(), decoded.get(0), msg));
        assertFalse(PQScriptUtils.verifyPQ(other.getPublicKeyBytes(), decoded.get(1), msg));
    }

    @Test
    public void testDecodeRejectsMalformedContainer() {
        assertThrows(Exception.class, () -> L1CrosstangleHandler.decodeSignatureList(new byte[] { 0, 0, 0, 2, 0 }));
    }
}
