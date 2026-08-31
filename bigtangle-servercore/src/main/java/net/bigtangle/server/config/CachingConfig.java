package net.bigtangle.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
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
        // blocksCache holds every cached block's serialized bytes (~12MB per
        // 2000-tx batch block). Unbounded, it grows with total chain traffic
        // and exhausts the heap under sustained load; eviction only costs a
        // DB re-read (read-through cache), so bound it by ENTRY COUNT —
        // FREE_HEAP_SIZE thresholds fire only once the heap is nearly gone,
        // which is too late under GC pressure. One drain window emits <=25
        // blocks and a slot confirms them; 40 entries (~480MB) covers that.
        MapConfig blocksCacheConfig = new MapConfig().setName("blocksCache")
                .setEvictionConfig(new EvictionConfig()
                        .setSize(40)
                        .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                        .setEvictionPolicy(EvictionPolicy.LRU));
        int instanceNum = INSTANCE_COUNTER.incrementAndGet();
        config.setInstanceName("hazelcast-instance-" + instanceNum)
                .addMapConfig(mapconfig)
                .addMapConfig(blocksCacheConfig);

        NetworkConfig networkConfig = config.getNetworkConfig();
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(false);
        // AUTO-DETECTION is the discovery path that silently clusters every
        // node's Hazelcast on the same host even when multicast/tcp-ip are off
        // (the mesh nodes share --network host). A clustered shared cache is
        // both useless (each node reads its own full DB) and a consensus
        // killer: under load one member's heartbeat lapses -> partition
        // migration storm -> ClearOperations block for minutes -> the beacon
        // reference sweep stalls -> heads go stale -> proposers fork.
        // Each node runs its Hazelcast STANDALONE; caches stay local
        // read-through.
        joinConfig.getAutoDetectionConfig().setEnabled(false);
        // Belt-and-braces: no cloud/kubernetes discovery either.
        joinConfig.getAwsConfig().setEnabled(false);
        joinConfig.getAzureConfig().setEnabled(false);
        joinConfig.getGcpConfig().setEnabled(false);
        joinConfig.getKubernetesConfig().setEnabled(false);
        joinConfig.getEurekaConfig().setEnabled(false);

        return config;

    }

    /**
     * The Hazelcast instance is owned HERE (not by Boot auto-configuration)
     * so the cache manager below can wrap it deterministically.
     */
    @Bean(destroyMethod = "shutdown")
    public com.hazelcast.core.HazelcastInstance hazelcastInstance(Config hazelCastConfig) {
        return com.hazelcast.core.Hazelcast.newHazelcastInstance(hazelCastConfig);
    }

    /**
     * Cache operations must never take the node down: the wrapper degrades a
     * dead backing cache to a bounded in-memory fallback (see
     * {@link ResilientCacheManager}) instead of throwing
     * HazelcastInstanceNotActiveException into the beacon reference sweep,
     * which reads a sweep failure as "no references" and stalls confirmation.
     */
    @Bean
    public org.springframework.cache.CacheManager cacheManager(
            com.hazelcast.core.HazelcastInstance hazelcastInstance) {
        return new ResilientCacheManager(
                new com.hazelcast.spring.cache.HazelcastCacheManager(hazelcastInstance));
    }
}
