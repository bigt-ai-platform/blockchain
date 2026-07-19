package net.bigtangle.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;

@Configuration

public class CachingConfig {

    private static final java.util.concurrent.atomic.AtomicInteger INSTANCE_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @Bean
    public Config hazelCastConfig() {

        Config config = new Config();
        MapConfig mapconfig = new MapConfig().setName("configuration")
              //  .setMaxSizeConfig(new MaxSizeConfig(200, MaxSizeConfig.MaxSizePolicy.FREE_HEAP_SIZE))
          //      .setEvictionPolicy(EvictionPolicy.LRU).setTimeToLiveSeconds(360).setMaxIdleSeconds(60)
                ;
        int instanceNum = INSTANCE_COUNTER.incrementAndGet();
        config.setInstanceName("hazelcast-instance-" + instanceNum)
                .addMapConfig(mapconfig);

        NetworkConfig networkConfig = config.getNetworkConfig();
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(false);

        return config;

    }
}
