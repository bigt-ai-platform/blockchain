package net.bigtangle.p2p;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class DnsDiscoveryResolverTest {

    @Test
    public void testParseRootRecordValid() {
        String txt = "enrtree-root:v1 e=ABCDEF seq=1 sig=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
        DnsDiscoveryResolver.RootRecord root = DnsDiscoveryResolver.parseRootRecord(txt);
        assertNotNull(root);
        assertEquals("ABCDEF", root.entryHash);
        assertEquals(1, root.seq);
        assertNotNull(root.signature);
    }

    @Test
    public void testParseRootRecordInvalid() {
        assertNull(DnsDiscoveryResolver.parseRootRecord("not-a-root-record"));
        assertNull(DnsDiscoveryResolver.parseRootRecord(""));
        assertNull(DnsDiscoveryResolver.parseRootRecord(null));
    }

    @Test
    public void testParseRootRecordMissingFields() {
        String txt = "enrtree-root:v1 e=ABC seq=1";
        DnsDiscoveryResolver.RootRecord root = DnsDiscoveryResolver.parseRootRecord(txt);
        assertNull(root);
    }

    @Test
    public void testParseBranchRecordValid() {
        String txt = "enrtree-branch:abc123 def456 ghi789";
        List<String> hashes = DnsDiscoveryResolver.parseBranchRecord(txt);
        assertEquals(3, hashes.size());
        assertEquals("abc123", hashes.get(0));
        assertEquals("def456", hashes.get(1));
        assertEquals("ghi789", hashes.get(2));
    }

    @Test
    public void testParseBranchRecordSingle() {
        String txt = "enrtree-branch:abc123";
        List<String> hashes = DnsDiscoveryResolver.parseBranchRecord(txt);
        assertEquals(1, hashes.size());
        assertEquals("abc123", hashes.get(0));
    }

    @Test
    public void testParseBranchRecordInvalid() {
        assertTrue(DnsDiscoveryResolver.parseBranchRecord("garbage").isEmpty());
        assertTrue(DnsDiscoveryResolver.parseBranchRecord("").isEmpty());
        assertTrue(DnsDiscoveryResolver.parseBranchRecord(null).isEmpty());
    }

    @Test
    public void testParseLeafRecordValid() {
        NodeRecord.KeyPair kp = NodeRecord.generateKeyPair();
        NodeRecord original = NodeRecord.createSelf(kp, "dns-test.example.com", 30303, 30304, 1);
        String enrHex = original.toEnr().substring(4);
        String txt = "enr:" + enrHex;

        NodeRecord parsed = DnsDiscoveryResolver.parseLeafRecord(txt);
        assertNotNull(parsed);
        assertEquals(original.getNodeId(), parsed.getNodeId());
        assertEquals(original.getHost(), parsed.getHost());
    }

    @Test
    public void testParseLeafRecordInvalid() {
        assertNull(DnsDiscoveryResolver.parseLeafRecord("enr:garbagehex"));
        assertNull(DnsDiscoveryResolver.parseLeafRecord("not-a-leaf"));
        assertNull(DnsDiscoveryResolver.parseLeafRecord(null));
    }

    @Test
    public void testResolveUnrecognizedFormat() {
        List<NodeRecord> result = DnsDiscoveryResolver.resolve("garbage");
        assertTrue(result.isEmpty());
    }

    @Test
    public void testResolveNull() {
        List<NodeRecord> result = DnsDiscoveryResolver.resolve(null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testRootSignSerialization() throws Exception {
        DnsDiscoveryResolver.RootRecord root = new DnsDiscoveryResolver.RootRecord("ABC", 42, new byte[]{1, 2, 3});
        java.lang.reflect.Method method = DnsDiscoveryResolver.class.getDeclaredMethod(
                "serializeRootForSigning", DnsDiscoveryResolver.RootRecord.class);
        method.setAccessible(true);
        byte[] serialized = (byte[]) method.invoke(null, root);
        String str = new String(serialized, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("enrtree-root:v1 e=ABC seq=42", str);
    }

    @Test
    public void testResolveInvalidEnrTree() {
        assertThrows(IllegalArgumentException.class,
                () -> DnsDiscoveryResolver.resolveEnrTree("invalid-url"));
        assertThrows(IllegalArgumentException.class,
                () -> DnsDiscoveryResolver.resolveEnrTree("enrtree://missingatsymbol"));
    }

    @Test
    public void testResolveSeedDomainInvalidPort() {
        List<NodeRecord> result = DnsDiscoveryResolver.resolve("hostname:notaport");
        assertTrue(result.isEmpty());
    }

}
