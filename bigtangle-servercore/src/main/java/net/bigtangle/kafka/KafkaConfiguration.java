package net.bigtangle.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaConfiguration {

    private String bootstrapServers;
    private String consumerIdSuffix;
    private String blockTopic = "bigtangle-blocks";
    private String transactionTopic = "bigtangle-transactions";
    private String attestationTopic = "bigtangle-attestations";

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
        return blockTopic;
    }

    public void setBlockTopic(String blockTopic) {
        this.blockTopic = blockTopic;
    }

    public String getTransactionTopic() {
        return transactionTopic;
    }

    public void setTransactionTopic(String transactionTopic) {
        this.transactionTopic = transactionTopic;
    }

    public String getAttestationTopic() {
        return attestationTopic;
    }

    public void setAttestationTopic(String attestationTopic) {
        this.attestationTopic = attestationTopic;
    }

    @Override
    public String toString() {
        return "KafkaConfiguration [bootstrapServers=" + bootstrapServers
                + ", consumerIdSuffix=" + consumerIdSuffix
                + ", blockTopic=" + blockTopic
                + ", transactionTopic=" + transactionTopic + "]";
    }
}
