package com.award.log.security;

import com.award.log.config.AppCorsProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.ServletServerHttpResponse;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthHandshakeInterceptorTest {

    @Test
    void rejectsUntrustedOrigin() {
        AppCorsProperties cors = new AppCorsProperties();
        WebSocketAuthHandshakeInterceptor interceptor = new WebSocketAuthHandshakeInterceptor(cors);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn(1);
        when(request.getHeader("Origin")).thenReturn("https://evil.example.com");

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertFalse(allowed);
    }

    @Test
    void allowsTrustedOriginWithSession() {
        AppCorsProperties cors = new AppCorsProperties();
        WebSocketAuthHandshakeInterceptor interceptor = new WebSocketAuthHandshakeInterceptor(cors);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(AuthInterceptor.SESSION_USER_ID)).thenReturn(1);
        when(request.getHeader("Origin")).thenReturn("http://localhost:3000");

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertTrue(allowed);
    }
}
