package com.award.log.security;

import com.award.log.config.AppCorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 会话 Cookie 认证下的简易 CSRF 防护：状态变更请求须带自定义头或来自 CORS 白名单 Origin/Referer。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class SessionCsrfGuardFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AppCorsProperties corsProperties;

    public SessionCsrfGuardFilter(AppCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!MUTATING.contains(request.getMethod().toUpperCase())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (RequestUserResolver.AUTH_MODE_API_KEY.equals(request.getAttribute(RequestUserResolver.REQ_AUTH_MODE))) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(AuthInterceptor.SESSION_USER_ID) == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (hasTrustedClientMarker(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeJson(response, 403, "CSRF_ORIGIN_DENIED", "请求来源未通过校验，请使用官方控制台访问");
    }

    private boolean hasTrustedClientMarker(HttpServletRequest request) {
        String requestedWith = request.getHeader("X-Requested-With");
        if (requestedWith != null && !requestedWith.isBlank()) {
            return true;
        }
        if (corsProperties.isOriginAllowed(request.getHeader("Origin"))) {
            return true;
        }
        return corsProperties.isRefererAllowed(request.getHeader("Referer"));
    }

    private static void writeJson(HttpServletResponse response, int status, String securityCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"code\":%d,\"securityCode\":\"%s\",\"message\":\"%s\"}",
                status,
                securityCode,
                message.replace("\\", "\\\\").replace("\"", "\\\"")
        ));
    }
}
