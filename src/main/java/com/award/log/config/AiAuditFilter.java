package com.award.log.config;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.AiAuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class AiAuditFilter extends OncePerRequestFilter {

    private static final List<String> AUDIT_PREFIXES = List.of(
            "/log/diagnose/",
            "/api/assistant/",
            "/api/mcp/",
            "/api/profile/",
            "/api/ops/effect/",
            "/api/ops/patrol/",
            "/api/security/",
            "/api/platform/",
            "/api/system-config/"
    );

    private final ObjectProvider<AiAuditLogService> auditLogServiceProvider;
    private final RequestUserResolver requestUserResolver;

    public AiAuditFilter(ObjectProvider<AiAuditLogService> auditLogServiceProvider,
                         RequestUserResolver requestUserResolver) {
        this.auditLogServiceProvider = auditLogServiceProvider;
        this.requestUserResolver = requestUserResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        for (String prefix : AUDIT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        Object userId = requestUserResolver.currentUserId(request);
        Object role = requestUserResolver.currentUserRole(request);
        String path = normalizedPath(request);
        String method = request.getMethod();
        String remoteIp = request.getRemoteAddr();
        int contentLength = request.getContentLength();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            try {
                AiAuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
                if (auditLogService != null && userId != null) {
                    auditLogService.save(
                            userId,
                            role,
                            remoteIp,
                            method,
                            path,
                            response.getStatus(),
                            elapsed,
                            Math.max(contentLength, 0));
                }
            } catch (Exception ignored) {
                // Keep business flow unaffected when audit persistence fails.
            }
            log.info("AI_AUDIT userId={} role={} authMode={} ip={} method={} path={} status={} durationMs={} reqBytes={}",
                    userId,
                    role,
                    requestUserResolver.authMode(request),
                    remoteIp,
                    method,
                    path,
                    response.getStatus(),
                    elapsed,
                    Math.max(contentLength, 0));
        }
    }

    private static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
