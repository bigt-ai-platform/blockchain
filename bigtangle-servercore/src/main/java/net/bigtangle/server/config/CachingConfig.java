package net.bigtangle.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;

@Configuration

public class CachingConfig {
 

    @Bean
    public Config hazelCastConfig() {

        Config config = new Config();
        MapConfig mapconfig = new MapConfig().setName("configuration")
              //  .setMaxSizeConfig(new MaxSizeConfig(200, MaxSizeConfig.MaxSizePolicy.FREE_HEAP_SIZE))
          //      .setEvictionPolicy(EvictionPolicy.LRU).setTimeToLiveSeconds(360).setMaxIdleSeconds(60)
                ;
        config.setInstanceName("hazelcast-instance")
                .addMapConfig(mapconfig)   ;

        return config;

    }
}
