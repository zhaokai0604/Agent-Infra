package com.award.log.agent;

import com.award.log.util.TimeSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 自主运维感知快照 TTL 缓存；写操作成功后应 {@link #invalidate()}。
 */
@Component
public class OpsPerceptionCache {

    private final TimeSource timeSource;

    public OpsPerceptionCache(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @Value("${agent.perception.cache-ttl-ms:60000}")
    private long ttlMs;

    private volatile long cachedAtMs;
    private volatile Map<String, Object> cachedView;

    public Map<String, Object> getIfFresh() {
        if (cachedView == null) {
            return null;
        }
        if (timeSource.currentTimeMillis() - cachedAtMs >= ttlMs) {
            return null;
        }
        return cachedView;
    }

    public void put(Map<String, Object> view) {
        cachedView = view;
        cachedAtMs = timeSource.currentTimeMillis();
    }

    public void invalidate() {
        cachedView = null;
        cachedAtMs = 0L;
    }

    public boolean isEnabled() {
        return ttlMs > 0;
    }
}
