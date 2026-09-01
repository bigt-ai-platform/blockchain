package net.bigtangle.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.Test;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;

public class KafkaConfigurationTest {

    private static final String CHAIN_ID = MainNetParams.get().getChainId();

    @Test
    public void testTopicsDefaultToChainSuffix() {
        KafkaConfiguration cfg = new KafkaConfiguration();
        cfg.networkParameters = MainNetParams.get();
        assertEquals("bigtangle-blocks-" + CHAIN_ID, cfg.getBlockTopic());
        assertEquals("bigtangle-transactions-" + CHAIN_ID, cfg.getTransactionTopic());
        assertEquals("bigtangle-attestations-" + CHAIN_ID, cfg.getAttestationTopic());
    }

    @Test
    public void testExplicitTopicOverrideWins() {
        KafkaConfiguration cfg = new KafkaConfiguration();
        cfg.networkParameters = MainNetParams.get();
        cfg.setBlockTopic("my-blocks");
        cfg.setTransactionTopic("my-txs");
        cfg.setAttestationTopic("my-atts");
        assertEquals("my-blocks", cfg.getBlockTopic());
        assertEquals("my-txs", cfg.getTransactionTopic());
        assertEquals("my-atts", cfg.getAttestationTopic());
    }

    @Test
    public void testNoNetworkParametersFallsBackToBase() {
        KafkaConfiguration cfg = new KafkaConfiguration();
        assertEquals(KafkaConfiguration.DEFAULT_BLOCK_TOPIC, cfg.getBlockTopic());
        assertEquals(KafkaConfiguration.DEFAULT_TRANSACTION_TOPIC, cfg.getTransactionTopic());
        assertEquals(KafkaConfiguration.DEFAULT_ATTESTATION_TOPIC, cfg.getAttestationTopic());
    }

    @Test
    public void testChainStampFilter() {
        AbstractStreamHandler h = new AbstractStreamHandler() {
            @Override
            protected String topic() {
                return "t";
            }

            @Override
            protected void process(KStream<String, byte[]> stream) {
            }
        };
        NetworkParameters params = MainNetParams.get();
        h.networkParameters = params;

        // own chain stamp → local; the stripped key is the payload key
        assertFalse(h.foreignChainRecord(CHAIN_ID + ":" + "abc123"));
        assertEquals("abc123", h.recordKey(CHAIN_ID + ":" + "abc123"));

        // foreign chain stamp → filtered out entirely
        assertTrue(h.foreignChainRecord("SOCIAL:abc123"));
        assertTrue(h.foreignChainRecord("other:abc123"));

        // unprefixed legacy records are accepted as local
        assertFalse(h.foreignChainRecord("abc123"));
        assertEquals("abc123", h.recordKey("abc123"));

        // a chain id whose name contains the local id is still foreign
        assertTrue(h.foreignChainRecord(CHAIN_ID + "-x:abc123"));
    }
}
