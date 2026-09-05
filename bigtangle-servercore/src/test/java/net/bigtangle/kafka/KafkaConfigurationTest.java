package net.bigtangle.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * Root-cause regression: two nodes that resolve the SAME kafka application
     * id join ONE consumer group; with single-partition chain topics the group
     * serves each partition to a single member, so peers starve for votes and
     * blocks — permanent head divergence and zero finality (deployed L0 pair).
     * Every node must therefore resolve a DISTINCT id.
     */
    @Test
    public void testApplicationIdIsUniqueAcrossNodes() {
        Class<?> blockHandler = net.bigtangle.kafka.BlockStreamHandler.class;

        // The wedged production layout: both L0 nodes bound API port 8082 and
        // neither set CONSUMERIDSUFFIX, so both defaulted to the SAME id.
        assertEquals(
                AbstractStreamHandler.applicationId(blockHandler, "bigtangletest", "8082"),
                AbstractStreamHandler.applicationId(blockHandler, "bigtangletest", "8082"),
                "same suffix + same port MUST collide (documented failure mode)");

        // Distinct per-node suffix (eu1/eu2) on the SAME port is unique.
        String eu1 = AbstractStreamHandler.applicationId(blockHandler, "eu1", "8082");
        String eu2 = AbstractStreamHandler.applicationId(blockHandler, "eu2", "8082");
        assertNotEquals(eu1, eu2, "distinct CONSUMERIDSUFFIX must yield distinct consumer groups");

        // Distinct ports with the same suffix are unique too (single-host mesh).
        assertNotEquals(
                AbstractStreamHandler.applicationId(blockHandler, "bigtangletest", "8081"),
                AbstractStreamHandler.applicationId(blockHandler, "bigtangletest", "8082"));
    }
}
