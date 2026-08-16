package com.award.log.config;

import com.award.log.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动后按本地覆盖文件重放一次可热更新 runtime 配置，
 * 避免 profile 默认值覆盖掉 system-config-overrides.json 中的真实写入开关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemConfigRuntimeBootstrap {

    private final SystemConfigService systemConfigService;

    @PostConstruct
    public void init() {
        try {
            systemConfigService.reapplySavedRuntimeConfig();
        } catch (Exception e) {
            log.warn("启动热应用 runtime 配置失败: {}", e.getMessage());
        }
    }
}
