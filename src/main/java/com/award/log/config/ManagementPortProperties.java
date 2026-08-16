package com.award.log.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理端口配置：启用后，管理面接口与监控 WebSocket 仅允许从该端口访问。
 */
@Component
@ConfigurationProperties(prefix = "app.management")
public class ManagementPortProperties {

    private boolean enabled = false;

    private Integer port;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public boolean isActive(Integer appPort) {
        return enabled && port != null && port > 0 && !port.equals(appPort);
    }
}
