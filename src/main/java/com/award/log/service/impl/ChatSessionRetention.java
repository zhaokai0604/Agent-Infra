package com.award.log.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 进程内会话数据的 TTL 与总量上限，避免长期运行内存无限增长。
 */
@Slf4j
@Component
public class ChatSessionRetention {

    public static final int MAX_SESSIONS = 256;
    public static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;

    private final Map<String, Long> lastAccessEpochMs = new ConcurrentHashMap<>();

    public void touch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        lastAccessEpochMs.put(sessionId, System.currentTimeMillis());
        trimOverflow(null);
    }

    public void forget(String sessionId) {
        if (sessionId != null) {
            lastAccessEpochMs.remove(sessionId);
        }
    }

    public void evictExpired(Consumer<String> onEvict) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastAccessEpochMs.entrySet()) {
            if (now - e.getValue() > SESSION_TTL_MS) {
                expired.add(e.getKey());
            }
        }
        for (String sessionId : expired) {
            lastAccessEpochMs.remove(sessionId);
            if (onEvict != null) {
                onEvict.accept(sessionId);
            }
        }
        if (!expired.isEmpty()) {
            log.info("会话 retention 清理过期 session {} 个", expired.size());
        }
        trimOverflow(onEvict);
    }

    private void trimOverflow(Consumer<String> onEvict) {
        int overflow = lastAccessEpochMs.size() - MAX_SESSIONS;
        if (overflow <= 0) {
            return;
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(lastAccessEpochMs.entrySet());
        sorted.sort(Comparator.comparingLong(Map.Entry::getValue));
        for (int i = 0; i < overflow && i < sorted.size(); i++) {
            String sessionId = sorted.get(i).getKey();
            lastAccessEpochMs.remove(sessionId);
            if (onEvict != null) {
                onEvict.accept(sessionId);
            }
        }
        log.info("会话 retention 淘汰最旧 session {} 个", overflow);
    }
}
