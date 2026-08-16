package com.award.log.security;

import com.award.log.config.ManagementPortProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理端口隔离：启用后，管理面接口只能走管理端口，业务接口只能走业务端口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ManagementPortFilter extends OncePerRequestFilter {

    private final ManagementPortProperties managementPortProperties;
    private final Integer appPort;

    public ManagementPortFilter(
            ManagementPortProperties managementPortProperties,
            @Value("${server.port:8080}") Integer appPort) {
        this.managementPortProperties = managementPortProperties;
        this.appPort = appPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!managementPortProperties.isActive(appPort)) {
            return true;
        }
        String path = AuthInterceptor.normalizedPath(request);
        return "/error".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = AuthInterceptor.normalizedPath(request);
        boolean managementPath = ManagementAccessPaths.isManagementPath(path);
        int localPort = request.getLocalPort();
        int managementPort = managementPortProperties.getPort();

        if (managementPath && localPort != managementPort) {
            writeJson(response, 403, "MANAGEMENT_PORT_REQUIRED", "管理接口仅允许通过管理端口访问");
            return;
        }

        if (!managementPath && localPort == managementPort) {
            writeJson(response, 404, "BUSINESS_PORT_REQUIRED", "业务接口不在管理端口开放");
            return;
        }

        filterChain.doFilter(request, response);
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
