/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="kafka")
public class KafkaConfiguration {
    private String topicOutName;
    private String bootstrapServers;   
    private String consumerIdSuffix;
    private long commitInterval;
    
 


    public String getTopicOutName() {
        return topicOutName;
    }




    public void setTopicOutName(String topicOutName) {
        this.topicOutName = topicOutName;
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




    public long getCommitInterval() {
        return commitInterval;
    }




    public void setCommitInterval(long commitInterval) {
        this.commitInterval = commitInterval;
    }




    @Override
    public String toString() {
        return "KafkaConfiguration [topicOutName=" + topicOutName 
                + ", bootstrapServers=" + bootstrapServers + ", consumerIdSuffix=" + consumerIdSuffix
                + ", commitInterval=" + commitInterval + "]";
    }

 

  
}