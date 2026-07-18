package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class MemoInfoTest {

    @Test
    public void testRoundTrip() throws Exception {
        MemoInfo original = new MemoInfo("hello world");
        byte[] bytes = original.toByteArray();
        MemoInfo reparsed = MemoInfo.parse(bytes);
        assertNotNull(reparsed);
        assertNotNull(reparsed.getKv());
        assertEquals(1, reparsed.getKv().size());
        assertEquals("memo", reparsed.getKv().get(0).getKey());
        assertEquals("hello world", reparsed.getKv().get(0).getValue());
    }

    @Test
    public void testRoundTripEncrypt() throws Exception {
        MemoInfo original = new MemoInfo("visible");
        original.addEncryptMemo("secret data");
        byte[] bytes = original.toByteArray();
        MemoInfo reparsed = MemoInfo.parse(bytes);
        assertNotNull(reparsed);
        assertEquals(2, reparsed.getKv().size());
        assertEquals("memo", reparsed.getKv().get(0).getKey());
        assertEquals("visible", reparsed.getKv().get(0).getValue());
        assertEquals(MemoInfo.ENCRYPT, reparsed.getKv().get(1).getKey());
        assertEquals("secret data", reparsed.getKv().get(1).getValue());
    }

    @Test
    public void testRoundTripEmpty() throws Exception {
        MemoInfo original = new MemoInfo();
        byte[] bytes = original.toByteArray();
        MemoInfo reparsed = MemoInfo.parse(bytes);
        assertNotNull(reparsed);
        assertTrue(reparsed.getKv() == null || reparsed.getKv().isEmpty());
    }

    @Test
    public void testNull() throws Exception {
        assertNull(MemoInfo.parse((byte[]) null));
        assertNull(MemoInfo.parse(new byte[0]));
        assertNull(MemoInfo.parse((String) null));
    }

    @Test
    public void testDeterministic() throws Exception {
        MemoInfo a = new MemoInfo("test");
        MemoInfo b = new MemoInfo("test");
        byte[] ba = a.toByteArray();
        byte[] bb = b.toByteArray();
        assertEquals(ba.length, bb.length);
        for (int i = 0; i < ba.length; i++) {
            assertEquals(ba[i], bb[i]);
        }
    }

    @Test
    public void testHexEncodeRoundTrip() throws Exception {
        MemoInfo original = new MemoInfo("via transaction");
        String hex = Utils.HEX.encode(original.toByteArray());
        byte[] decoded = Utils.HEX.decode(hex);
        MemoInfo reparsed = MemoInfo.parse(decoded);
        assertNotNull(reparsed);
        assertEquals(1, reparsed.getKv().size());
        assertEquals("via transaction", reparsed.getKv().get(0).getValue());
    }

    @Test
    public void testParseToStringDisplay() throws Exception {
        MemoInfo memo = new MemoInfo("display test");
        String hex = Utils.HEX.encode(memo.toByteArray());
        String display = MemoInfo.parseToString(hex);
        assertNotNull(display);
        assertTrue(display.contains("display test"));
    }

    @Test
    public void testParseToStringNull() throws Exception {
        assertNull(MemoInfo.parseToString(null));
    }

    @Test
    public void testToJsonBackwardCompat() throws Exception {
        // Legacy JSON format should still be parseable
        MemoInfo memo = new MemoInfo("legacy");
        String json = memo.toJson();
        assertNotNull(json);
        assertTrue(json.contains("legacy"));
    }

    @Test
    public void testFromJsonBackwardCompat() throws Exception {
        // Simulate a legacy JSON memo string
        String json = "{\"kv\":[{\"key\":\"memo\",\"value\":\"legacy data\"}]}";
        MemoInfo memo = MemoInfo.fromJson(json);
        assertNotNull(memo);
        assertEquals("legacy data", memo.getKv().get(0).getValue());
    }

    @Test
    public void testParseToStringJsonFallback() throws Exception {
        // Legacy JSON should still display via parseToString
        String json = "{\"kv\":[{\"key\":\"memo\",\"value\":\"legacy display\"}]}";
        String display = MemoInfo.parseToString(json);
        assertNotNull(display);
        assertTrue(display.contains("legacy display"));
    }
}
