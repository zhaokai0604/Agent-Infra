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

@Component
public class AiIdempotencyFilter extends OncePerRequestFilter {

    private static final long TTL_MS = 30_000L;
    private final Map<String, Long> keys = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        return !(path.startsWith("/award-log/log/diagnose/")
                || path.startsWith("/award-log/api/assistant/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String idemKey = request.getHeader("X-Idempotency-Key");
        if (idemKey == null || idemKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        keys.entrySet().removeIf(e -> now - e.getValue() > TTL_MS);
        String key = request.getRemoteAddr() + ":" + request.getRequestURI() + ":" + idemKey.trim();
        Long existing = keys.putIfAbsent(key, now);
        if (existing != null) {
            response.setStatus(409);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":409,\"message\":\"重复请求，请稍后重试\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
