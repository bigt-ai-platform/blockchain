package net.bigtangle.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import net.bigtangle.params.NetworkParameters;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaConfiguration {

    public static final String DEFAULT_BLOCK_TOPIC = "bigtangle-blocks";
    public static final String DEFAULT_TRANSACTION_TOPIC = "bigtangle-transactions";
    public static final String DEFAULT_ATTESTATION_TOPIC = "bigtangle-attestations";

    private String bootstrapServers;
    private String consumerIdSuffix;
    // Explicit overrides (kafka.blockTopic=…). When unset the topic defaults to
    // "<base>-<chainId>" so co-hosted layer chains sharing one broker never
    // consume each other's blocks/txs/attestations.
    private String blockTopic;
    private String transactionTopic;
    private String attestationTopic;

    // Optional: absent only in bare unit tests; the resolved topics then fall
    // back to the unsuffixed base name. Package-private for same-package tests.
    @Autowired(required = false)
    NetworkParameters networkParameters;

    private String chainTopic(String configured, String base) {
        if (configured != null && !configured.isBlank()) return configured;
        String chainId = networkParameters != null ? networkParameters.getChainId() : null;
        return (chainId == null || chainId.isBlank()) ? base : base + "-" + chainId;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getConsumerIdSuffix() {
        return consumerIdSuffix;
    }

    public void setConsumerIdSuffix(String consumerIdSuffix) {
        this.consumerIdSuffix = consumerIdSuffix;
    }

    public String getBlockTopic() {
        return chainTopic(blockTopic, DEFAULT_BLOCK_TOPIC);
    }

    public void setBlockTopic(String blockTopic) {
        this.blockTopic = blockTopic;
    }

    public String getTransactionTopic() {
        return chainTopic(transactionTopic, DEFAULT_TRANSACTION_TOPIC);
    }

    public void setTransactionTopic(String transactionTopic) {
        this.transactionTopic = transactionTopic;
    }

    public String getAttestationTopic() {
        return chainTopic(attestationTopic, DEFAULT_ATTESTATION_TOPIC);
    }

    public void setAttestationTopic(String attestationTopic) {
        this.attestationTopic = attestationTopic;
    }

    @Override
    public String toString() {
        return "KafkaConfiguration [bootstrapServers=" + bootstrapServers
                + ", consumerIdSuffix=" + consumerIdSuffix
                + ", blockTopic=" + getBlockTopic()
                + ", transactionTopic=" + getTransactionTopic()
                + ", attestationTopic=" + getAttestationTopic() + "]";
    }
}
