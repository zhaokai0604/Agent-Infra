package com.award.log.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Resolves the current authenticated user from either session or API key request attributes.
 */
@Component
public class RequestUserResolver {

    public static final String REQ_USER_ID = "REQ_AUTH_USER_ID";
    public static final String REQ_USER_ROLE = "REQ_AUTH_USER_ROLE";
    public static final String REQ_AUTH_MODE = "REQ_AUTH_MODE";
    public static final String AUTH_MODE_SESSION = "SESSION";
    public static final String AUTH_MODE_API_KEY = "API_KEY";

    public Integer currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(REQ_USER_ID);
        Integer fromAttr = asInteger(attr);
        if (fromAttr != null) {
            return fromAttr;
        }
        HttpSession session = request.getSession(false);
        return session == null ? null : asInteger(session.getAttribute(AuthInterceptor.SESSION_USER_ID));
    }

    public Integer currentUserRole(HttpServletRequest request) {
        Object attr = request.getAttribute(REQ_USER_ROLE);
        Integer fromAttr = asInteger(attr);
        if (fromAttr != null) {
            return fromAttr;
        }
        HttpSession session = request.getSession(false);
        return session == null ? null : asInteger(session.getAttribute(AuthInterceptor.SESSION_USER_ROLE));
    }

    public boolean isAdmin(HttpServletRequest request) {
        Integer role = currentUserRole(request);
        return role != null && role == 1;
    }

    public String authMode(HttpServletRequest request) {
        Object attr = request.getAttribute(REQ_AUTH_MODE);
        if (attr instanceof String mode && !mode.isBlank()) {
            return mode;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(AuthInterceptor.SESSION_USER_ID) != null) {
            return AUTH_MODE_SESSION;
        }
        return "ANONYMOUS";
    }

    public void bindApiKeyUser(HttpServletRequest request, Integer userId, Integer role) {
        request.setAttribute(REQ_USER_ID, userId);
        request.setAttribute(REQ_USER_ROLE, role);
        request.setAttribute(REQ_AUTH_MODE, AUTH_MODE_API_KEY);
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
}
