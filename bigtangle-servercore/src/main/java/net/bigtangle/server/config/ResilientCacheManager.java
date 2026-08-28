package net.bigtangle.server.config;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.lang.Nullable;

/**
 * CacheManager wrapper that keeps the node ALIVE when the backing cache
 * infrastructure dies. Under heap exhaustion the Hazelcast instance shuts
 * itself down; every @Cacheable call then threw
 * HazelcastInstanceNotActiveException, and the beacon reference sweep —
 * which treats any sweep failure as "no references" — confirmed nothing:
 * the whole chain stalled until the process restarted (observed on a
 * 5-node mesh at sustained load, 246 dead-instance events on one node).
 *
 * <p>Contract: every delegate operation is guarded. While the delegate is
 * alive it is the sole data source. The first failure flips this cache into
 * degraded mode (sticky until JVM restart): reads and writes are served by a
 * bounded in-memory fallback map, so the node degrades to uncached-store
 * performance instead of losing confirmation. Evictions always hit both
 * layers, so invalidation semantics are preserved in either mode. The
 * fallback is deliberately crude (clear-on-overflow): a cache miss only
 * costs a store re-read, never correctness.
 */
public class ResilientCacheManager implements CacheManager {

    /** Per-cache entry cap for degraded mode; overflow clears the map. */
    static final int FALLBACK_MAX_ENTRIES = 100_000;

    private final CacheManager delegate;
    private final ConcurrentHashMap<String, ResilientCache> caches = new ConcurrentHashMap<>();

    public ResilientCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        Cache underlying = delegate.getCache(name);
        if (underlying == null) {
            return null;
        }
        return caches.computeIfAbsent(name, n -> new ResilientCache(n, underlying));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    static final class ResilientCache implements Cache {

        private final String name;
        private final Cache delegate;
        private final ConcurrentHashMap<Object, ValueWrapper> fallback =
                new ConcurrentHashMap<>();
        private volatile boolean delegateDead;
        private volatile boolean overflowLogged;

        ResilientCache(String name, Cache delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return delegate.getNativeCache();
        }

        @Override
        @Nullable
        public ValueWrapper get(Object key) {
            if (!delegateDead) {
                try {
                    return delegate.get(key);
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            return fallback.get(key);
        }

        @Override
        @Nullable
        public <T> T get(Object key, @Nullable Class<T> type) {
            ValueWrapper vw = get(key);
            return vw == null ? null : type.cast(vw.get());
        }

        @Override
        @Nullable
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            if (!delegateDead) {
                try {
                    return delegate.get(key, valueLoader);
                } catch (org.springframework.cache.Cache.ValueRetrievalException vre) {
                    throw vre; // loader failure is not cache failure
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            try {
                T value = valueLoader.call();
                put(key, value);
                return value;
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            if (!delegateDead) {
                try {
                    delegate.put(key, value);
                    return;
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            fallbackPut(key, value);
        }

        @Override
        @Nullable
        public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
            if (!delegateDead) {
                try {
                    return delegate.putIfAbsent(key, value);
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            ValueWrapper existing = fallback.get(key);
            if (existing != null) {
                return existing;
            }
            fallbackPut(key, value);
            return null;
        }

        @Override
        public void evict(Object key) {
            if (!delegateDead) {
                try {
                    delegate.evict(key);
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            fallback.remove(key);
        }

        @Override
        public void clear() {
            if (!delegateDead) {
                try {
                    delegate.clear();
                } catch (Throwable t) {
                    markDead(t);
                }
            }
            fallback.clear();
        }

        private void fallbackPut(Object key, @Nullable Object value) {
            if (fallback.size() >= FALLBACK_MAX_ENTRIES && !fallback.containsKey(key)) {
                if (!overflowLogged) {
                    overflowLogged = true;
                    log("degraded fallback cache overflow — cleared (misses fall through to the store)");
                }
                fallback.clear();
            }
            fallback.put(key, value == null ? null : new SimpleValueWrapper(value));
        }

        private void markDead(Throwable t) {
            delegateDead = true;
            log("backing cache failed — degraded to in-memory fallback (" + t + ")");
        }

        private void log(String msg) {
            org.slf4j.LoggerFactory.getLogger(ResilientCacheManager.class)
                    .warn("cache '{}': {}", name, msg);
        }
    }

    /** Minimal immutable ValueWrapper for the fallback map. */
    static final class SimpleValueWrapper implements ValueWrapper {

        private final Object value;

        SimpleValueWrapper(Object value) {
            this.value = value;
        }

        @Override
        @Nullable
        public Object get() {
            return value;
        }
    }
}
