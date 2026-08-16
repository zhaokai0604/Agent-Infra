package com.award.log.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * 为同一 Spring Boot 进程追加管理端口，避免拆成第二个服务。
 */
@Component
public class ManagementPortTomcatCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final ManagementPortProperties managementPortProperties;
    private final Integer serverPort;

    public ManagementPortTomcatCustomizer(
            ManagementPortProperties managementPortProperties,
            @Value("${server.port:8080}") Integer serverPort) {
        this.managementPortProperties = managementPortProperties;
        this.serverPort = serverPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (!managementPortProperties.isActive(serverPort)) {
            return;
        }
        factory.addAdditionalTomcatConnectors(createConnector(managementPortProperties.getPort()));
    }

    private static Connector createConnector(int port) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(port);
        connector.setScheme("http");
        connector.setSecure(false);
        return connector;
    }
}
