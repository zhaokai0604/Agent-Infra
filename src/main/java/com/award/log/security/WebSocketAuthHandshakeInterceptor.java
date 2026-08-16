package com.award.log.security;

import com.award.log.config.AppCorsProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WebSocketAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final AppCorsProperties corsProperties;

    public WebSocketAuthHandshakeInterceptor(AppCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute(AuthInterceptor.SESSION_USER_ID) == null) {
            setStatus(response, HttpStatus.UNAUTHORIZED);
            return false;
        }
        String origin = httpRequest.getHeader("Origin");
        if (!corsProperties.isOriginAllowed(origin)) {
            setStatus(response, HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private static void setStatus(ServerHttpResponse response, HttpStatus status) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().setStatus(status.value());
        } else {
            response.setStatusCode(status);
        }
    }
}
