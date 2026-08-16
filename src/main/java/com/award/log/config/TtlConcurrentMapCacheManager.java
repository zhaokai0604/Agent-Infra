package com.award.log.config;

import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 带 TTL 的进程内缓存，用于 MCP 工具等短生命周期热点数据。
 */
public class TtlConcurrentMapCacheManager extends ConcurrentMapCacheManager {

    private final long defaultTtlMs;

    public TtlConcurrentMapCacheManager(long defaultTtlMs, String... cacheNames) {
        super(cacheNames);
        this.defaultTtlMs = defaultTtlMs;
    }

    @Override
    protected Cache createConcurrentMapCache(String name) {
        return new TtlConcurrentMapCache(name, defaultTtlMs);
    }

    static final class TtlConcurrentMapCache extends ConcurrentMapCache {

        private final long ttlMs;
        private final ConcurrentMap<Object, Long> expiryAtMs = new ConcurrentHashMap<>();

        TtlConcurrentMapCache(String name, long ttlMs) {
            super(name);
            this.ttlMs = ttlMs;
        }

        @Override
        public ValueWrapper get(Object key) {
            Long exp = expiryAtMs.get(key);
            if (exp != null && System.currentTimeMillis() > exp) {
                evict(key);
                return null;
            }
            return super.get(key);
        }

        @Override
        public void put(Object key, Object value) {
            expiryAtMs.put(key, System.currentTimeMillis() + ttlMs);
            super.put(key, value);
        }

        @Override
        public void evict(Object key) {
            expiryAtMs.remove(key);
            super.evict(key);
        }

        @Override
        public void clear() {
            expiryAtMs.clear();
            super.clear();
        }
    }
}
