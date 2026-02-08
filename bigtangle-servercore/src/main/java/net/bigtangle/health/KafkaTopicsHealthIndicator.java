package net.bigtangle.health;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

import jakarta.annotation.PostConstruct;
import net.bigtangle.kafka.KafkaConfiguration;

@Component

public class KafkaTopicsHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(KafkaTopicsHealthIndicator.class);
    
    private AdminClient adminClient;

    private final KafkaConfiguration kafkaProperties;

    @Autowired
    public KafkaTopicsHealthIndicator(KafkaConfiguration kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }


    @PostConstruct
    public void initAdminClient() {
        if (kafkaStart())
            this.adminClient = AdminClient.create(prepareConfiguration());
    }

  

    @Override
    public Health health() {
        Set<String> topicsRequired = new HashSet<String>();
        topicsRequired.add ( kafkaProperties.getTopicOutName());
        Set<String> topicsFound = check();
        Set<String> topicsMissing = Sets.difference(topicsRequired, topicsFound);

        Health.Builder health = topicsMissing.isEmpty() ? Health.up() : Health.down();
        return health
            .withDetail("topics_found", topicsFound)
            .withDetail("topics_missing", topicsMissing)
            .build();
    }

    private Set<String> check() {
        Set<String> topicsRequired = new HashSet<String>();
        topicsRequired.add ( kafkaProperties.getTopicOutName());

        Set<String> topicsFound = new HashSet<>();
        adminClient.describeTopics(topicsRequired).values().forEach((name, future) -> {
            try {
                future.get();
                topicsFound.add(name);
            } catch (InterruptedException e) {
                log.error("Interrupted  describe topics", e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException ignored) {
                // Topic not found, do nothing
            }
        });

        return topicsFound;
    }

    private Properties prepareConfiguration() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        return config;
    }
    
    private boolean kafkaStart() {
        if (kafkaProperties.getBootstrapServers() != null && !"".equals(kafkaProperties.getBootstrapServers())) {
            return true;
        }
        return false;
    }

}
