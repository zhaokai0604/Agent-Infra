package com.award.log.config;

import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.security.WebSocketAuthHandshakeInterceptor;
import com.award.log.websocket.LogStreamWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketAuthHandshakeInterceptor wsAuthInterceptor;
    private final PerformanceWebSocketHandler performanceWebSocketHandler;
    private final LogStreamWebSocketHandler logStreamWebSocketHandler;
    private final AppCorsProperties corsProperties;

    public WebSocketConfig(WebSocketAuthHandshakeInterceptor wsAuthInterceptor,
                           PerformanceWebSocketHandler performanceWebSocketHandler,
                           LogStreamWebSocketHandler logStreamWebSocketHandler,
                           AppCorsProperties corsProperties) {
        this.wsAuthInterceptor = wsAuthInterceptor;
        this.performanceWebSocketHandler = performanceWebSocketHandler;
        this.logStreamWebSocketHandler = logStreamWebSocketHandler;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = corsProperties.getAllowedOriginPatterns().toArray(String[]::new);
        registry.addHandler(performanceWebSocketHandler, "/ws/performance")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
        registry.addHandler(logStreamWebSocketHandler, "/ws/logs")
                .addInterceptors(wsAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
