package com.award.log.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AiRateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_MINUTE = 60;
    private static final long SWEEP_INTERVAL_MS = 120_000;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private volatile long lastSweepAtMs;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/award-log/log/diagnose/")
                || path.startsWith("/award-log/api/assistant/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        long nowMinute = System.currentTimeMillis() / 60000;
        maybeSweepStaleCounters(nowMinute);
        Counter counter = counters.computeIfAbsent(key, k -> new Counter(nowMinute));
        synchronized (counter) {
            if (counter.minute != nowMinute) {
                counter.minute = nowMinute;
                counter.count.set(0);
            }
            if (counter.count.incrementAndGet() > LIMIT_PER_MINUTE) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void maybeSweepStaleCounters(long nowMinute) {
        long now = System.currentTimeMillis();
        if (now - lastSweepAtMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepAtMs = now;
        counters.entrySet().removeIf(e -> nowMinute - e.getValue().minute > 2);
    }

    private static class Counter {
        private long minute;
        private final AtomicInteger count = new AtomicInteger(0);
        private Counter(long minute) { this.minute = minute; }
    }
}
