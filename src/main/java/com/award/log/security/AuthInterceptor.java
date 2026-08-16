package com.award.log.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER_ID = "LOGIN_USER_ID";
    public static final String SESSION_USER_ROLE = "LOGIN_USER_ROLE";

    private final RequestUserResolver requestUserResolver;

    public AuthInterceptor(RequestUserResolver requestUserResolver) {
        this.requestUserResolver = requestUserResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = normalizedPath(request);

        if (AdminAccessPaths.isPublic(path)) {
            return true;
        }

        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(SESSION_USER_ID) == null) {
                writeJson(response, 401, "{\"code\":401,\"message\":\"未登录或登录已过期\"}");
                return false;
            }
        }

        if (AdminAccessPaths.requiresAdmin(path) && !requestUserResolver.isAdmin(request)) {
            writeJson(response, 403, "{\"code\":403,\"message\":\"需要管理员权限\"}");
            return false;
        }

        return true;
    }

    static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }
}
