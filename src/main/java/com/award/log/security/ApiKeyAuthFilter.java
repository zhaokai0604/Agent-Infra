package com.award.log.security;

import com.award.log.model.SysUser;
import com.award.log.service.SysUserService;
import com.award.log.service.UserApiKeyService;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Set<String> READ_ONLY_API_KEY_PATHS = new LinkedHashSet<>();

    static {
        READ_ONLY_API_KEY_PATHS.add("/api/profile/user-info");
        READ_ONLY_API_KEY_PATHS.add("/api/profile/notification-settings");
        READ_ONLY_API_KEY_PATHS.add("/api/profile/user-stats");
        READ_ONLY_API_KEY_PATHS.add("/api/profile/access-trail");
        READ_ONLY_API_KEY_PATHS.add("/api/profile/login-history");
        READ_ONLY_API_KEY_PATHS.add("/api/ops/effect/dashboard");
        READ_ONLY_API_KEY_PATHS.add("/api/ops/patrol/history");
        READ_ONLY_API_KEY_PATHS.add("/api/ops/patrol/history/trend");
        READ_ONLY_API_KEY_PATHS.add("/api/ops/patrol/correlation/latest");
        READ_ONLY_API_KEY_PATHS.add("/api/security/self-check");
        READ_ONLY_API_KEY_PATHS.add("/api/security/policy-snapshot");
        READ_ONLY_API_KEY_PATHS.add("/api/platform/acceptance");
        READ_ONLY_API_KEY_PATHS.add("/api/platform/backend-probe");
    }

    private final UserApiKeyService userApiKeyService;
    private final SysUserService sysUserService;
    private final RequestUserResolver requestUserResolver;

    public ApiKeyAuthFilter(UserApiKeyService userApiKeyService,
                            SysUserService sysUserService,
                            RequestUserResolver requestUserResolver) {
        this.userApiKeyService = userApiKeyService;
        this.sysUserService = sysUserService;
        this.requestUserResolver = requestUserResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return path.startsWith("/admin/user/login")
                || path.startsWith("/admin/user/register")
                || path.startsWith("/admin/user/check-user");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(AuthInterceptor.SESSION_USER_ID) != null) {
            request.setAttribute(RequestUserResolver.REQ_AUTH_MODE, RequestUserResolver.AUTH_MODE_SESSION);
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        Map<String, Object> auth = userApiKeyService.authenticate(apiKey);
        if (!Boolean.TRUE.equals(auth.get("success"))) {
            writeJson(response, 401, String.valueOf(auth.getOrDefault("securityCode", "API_KEY_INVALID")),
                    String.valueOf(auth.getOrDefault("message", "API key invalid")));
            return;
        }

        String path = normalizedPath(request);
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !READ_ONLY_API_KEY_PATHS.contains(path)) {
            writeJson(response, 403, "API_KEY_SCOPE_DENIED", "API key scope denied for this endpoint");
            return;
        }

        Integer userId = asInteger(auth.get("userId"));
        if (userId == null) {
            writeJson(response, 401, "API_KEY_INVALID", "API key invalid");
            return;
        }
        SysUser user = sysUserService.getUserById(userId);
        if (user == null) {
            writeJson(response, 401, "API_KEY_INVALID", "API key user not found");
            return;
        }
        requestUserResolver.bindApiKeyUser(request, user.getUserId(), user.getRole());
        filterChain.doFilter(request, response);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private static void writeJson(HttpServletResponse response, int status, String securityCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(String.format(
                "{\"code\":%d,\"securityCode\":\"%s\",\"message\":\"%s\"}",
                status,
                escapeJson(securityCode),
                escapeJson(message)
        ));
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
