package net.bigtangle.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;

/**
 * A null Kafka record key must be dropped, never dereferenced: the old
 * {@code foreignChainRecord}/{@code recordKey} called {@code indexOf} on it
 * and NPE'd the consumer thread. One null-keyed record (anyone with produce
 * access can post one) then permanently wedged that node's consumer — the
 * failed offset never commits and the thread is never replaced (observed live
 * on the interop mesh: single NPE, no recovery).
 */
public class StreamKeyRoutingTest {

    static class Probe extends AbstractStreamHandler {
        Probe() {
            this.networkParameters = MainNetParams.get();
        }

        @Override
        protected String topic() {
            return "probe";
        }

        @Override
        protected void process(KStream<String, byte[]> stream) {
        }
    }

    private final Probe probe = new Probe();
    private final String chain = MainNetParams.get().getChainId();

    @Test
    public void testNullKeyIsDropped() {
        assertTrue(probe.foreignChainRecord(null));
        assertNull(probe.recordKey(null));
    }

    @Test
    public void testStampedAndLegacyKeysUnchanged() {
        assertFalse(probe.foreignChainRecord(chain + ":abc"));
        assertEquals("abc", probe.recordKey(chain + ":abc"));
        assertFalse(probe.foreignChainRecord("legacy-unprefixed"));
        assertEquals("legacy-unprefixed", probe.recordKey("legacy-unprefixed"));
    }

    @Test
    public void testForeignChainStillDropped() {
        assertTrue(probe.foreignChainRecord("FOREIGNCHAIN:abc"));
    }
}
