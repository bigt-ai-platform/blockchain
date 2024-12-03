/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.kafka;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PreDestroy;
import net.bigtangle.server.config.ServerConfiguration;
public abstract class AbstractStreamHandler {

    @Autowired
    protected KafkaConfiguration kafkaConfiguration;

    @Autowired
    protected ServerConfiguration serverConfiguration;

    protected KafkaStreams streams;
    private static final Logger log = LoggerFactory.getLogger(KafkaMessageProducer.class);

    
    public void runStream() {
        if ("".equalsIgnoreCase(kafkaConfiguration.getBootstrapServers()))
            return;
        log.info("KafkaConfiguration {} ", kafkaConfiguration.toString());
        log.info("start stream {} handler", this.getClass().getSimpleName());
        Properties props = prepareConfiguration();
        StreamsBuilder streamBuilder = new StreamsBuilder();

        try {
            run(streamBuilder);
        } catch (Exception e) {
            log.error(" run(streamBuilder);  ", e);

        }

        streams = new KafkaStreams(streamBuilder.build(), props);
        streams.setUncaughtExceptionHandler((thread, exception) -> {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);
            log.error("uncaught exception handler {} {}", exception, exception.getMessage());
            log.error(sw.toString());

        });
        streams.start();
    }

    public abstract void run(final StreamsBuilder streamBuilder) throws Exception;

    private Properties prepareConfiguration() {
        Properties streamsConfiguration = new Properties();
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, getApplicationId());
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfiguration.getBootstrapServers());
        streamsConfiguration.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        streamsConfiguration.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG,
        // kafkaConfiguration.getCommitInterval());
        return streamsConfiguration;
    }

    @PreDestroy
    public void closeStream() {
        if(streams !=null)
          streams.close();
    }

    private String getApplicationId() {
        String consumerIdSuffix=kafkaConfiguration.getConsumerIdSuffix();
        if(consumerIdSuffix==null || "".equals(consumerIdSuffix )) {
            consumerIdSuffix= serverConfiguration.getMineraddress(); 
        }
        return BlockStreamHandler.class.getCanonicalName() + "_" + this.getClass().getSimpleName() + "_"
                + consumerIdSuffix;
    }
    public boolean isRunning() {
        if(streams==null) return false;
        return   org.apache.kafka.streams.KafkaStreams.State.RUNNING.equals(streams.state());
    }
}
