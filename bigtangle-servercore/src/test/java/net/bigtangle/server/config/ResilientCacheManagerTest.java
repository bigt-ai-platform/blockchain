package net.bigtangle.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.CacheManager;

/**
 * The beacon reference sweep reads a sweep failure as "no references", so a
 * dead backing cache must degrade to uncached reads — never throw into the
 * consensus path (observed: one OOME killed Hazelcast, every later beacon
 * proposed an empty reference set, and the mesh stopped confirming).
 */
public class ResilientCacheManagerTest {

    /** A cache whose every operation throws (dead Hazelcast instance). */
    static final class DeadCache implements Cache {
        private final String name;

        DeadCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return this;
        }

        private RuntimeException dead() {
            return new com.hazelcast.core.HazelcastInstanceNotActiveException();
        }

        @Override
        public ValueWrapper get(Object key) {
            throw dead();
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            throw dead();
        }

        @Override
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            throw dead();
        }

        @Override
        public void put(Object key, Object value) {
            throw dead();
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            throw dead();
        }

        @Override
        public void evict(Object key) {
            throw dead();
        }

        @Override
        public void clear() {
            throw dead();
        }
    }

    static final class DeadCacheManager implements CacheManager {
        @Override
        public Cache getCache(String name) {
            return new DeadCache(name);
        }

        @Override
        public Collection<String> getCacheNames() {
            return Collections.singleton("dead");
        }
    }

    /** A simple live cache (counts puts, delegates to a map). */
    static final class LiveCache implements Cache {
        private final String name;
        private final ConcurrentHashMap<Object, Object> map = new ConcurrentHashMap<>();
        final AtomicInteger puts = new AtomicInteger();

        LiveCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return map;
        }

        @Override
        public ValueWrapper get(Object key) {
            Object v = map.get(key);
            return v == null ? null : new SimpleWrapper(v);
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            return type.cast(map.get(key));
        }

        @Override
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            @SuppressWarnings("unchecked")
            T v = (T) map.get(key);
            if (v == null) {
                try {
                    v = valueLoader.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                map.put(key, v);
            }
            return v;
        }

        @Override
        public void put(Object key, Object value) {
            puts.incrementAndGet();
            map.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            Object prev = map.putIfAbsent(key, value);
            return prev == null ? null : new SimpleWrapper(prev);
        }

        @Override
        public void evict(Object key) {
            map.remove(key);
        }

        @Override
        public void clear() {
            map.clear();
        }
    }

    static final class SimpleWrapper implements ValueWrapper {
        private final Object value;

        SimpleWrapper(Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }
    }

    static final class SingleCacheManager implements CacheManager {
        private final Cache cache;

        SingleCacheManager(Cache cache) {
            this.cache = cache;
        }

        @Override
        public Cache getCache(String name) {
            return cache;
        }

        @Override
        public Collection<String> getCacheNames() {
            return Collections.singleton(cache.getName());
        }
    }

    @Test
    public void deadBackingCacheDegradesToWorkingFallback() {
        ResilientCacheManager rm = new ResilientCacheManager(new DeadCacheManager());
        Cache cache = rm.getCache("blocksCache");
        assertNotNull(cache);

        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1").get());

        cache.evict("k1");
        assertNull(cache.get("k1"), "eviction must reach the fallback layer");

        cache.put("k2", "v2");
        assertEquals("v2", cache.get("k2", String.class));
        assertNull(cache.get("missing", String.class));
    }

    @Test
    public void healthyDelegateServesReads() {
        LiveCache live = new LiveCache("blocksCache");
        ResilientCacheManager rm = new ResilientCacheManager(new SingleCacheManager(live));
        Cache cache = rm.getCache("blocksCache");

        cache.put("a", 1);
        assertEquals(1, live.puts.get(), "put must go to the live delegate");
        assertEquals(1, cache.get("a").get());
        cache.evict("a");
        assertNull(cache.get("a"));
    }

    @Test
    public void fallbackOverflowClearsInsteadOfGrowingUnbounded() {
        ResilientCacheManager rm = new ResilientCacheManager(new DeadCacheManager());
        Cache cache = rm.getCache("blocksCache");
        for (int i = 0; i < ResilientCacheManager.FALLBACK_MAX_ENTRIES + 10; i++) {
            cache.put("k" + i, i);
        }
        cache.put("last", "ok");
        assertEquals("ok", cache.get("last").get());
    }
}
