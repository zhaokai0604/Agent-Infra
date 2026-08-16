package com.award.log.service;

import com.award.log.config.AgentOpsProperties;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证前端保存的白名单会写入 OpsPathPolicy 内存并参与 MCP 路径校验。
 */
class AgentPathPolicyServiceTest {

    @TempDir
    Path tempDir;

    private AgentOpsProperties agentOpsProperties;
    private OpsPathPolicy opsPathPolicy;
    private AgentPathPolicyService service;

    @BeforeEach
    void setUp() {
        agentOpsProperties = new AgentOpsProperties();
        agentOpsProperties.setPaths(AgentOpsProperties.Paths.linuxKylinDefaults());
        agentOpsProperties.setServiceRestart(AgentOpsProperties.ServiceRestart.linuxKylinDefaults());

        opsPathPolicy = new OpsPathPolicy(agentOpsProperties);
        ReflectionTestUtils.setField(opsPathPolicy, "logCollectorFilePath", "");
        opsPathPolicy.applyFrom(agentOpsProperties.getPaths());

        service = new AgentPathPolicyService(agentOpsProperties, opsPathPolicy, new ObjectMapper());
        ReflectionTestUtils.setField(service, "overrideFilePath",
                tempDir.resolve("agent-path-policy-overrides.json"));
    }

    @Test
    void saveUpdatesLogCleanupWhitelistInMemory() {
        boolean win = OsRuntime.isWindows();
        String customRoot = win ? "E:/data/app/logs" : "/data/app/logs";
        String defaultLogRoot = win ? "C:/Windows/Logs" : "/var/log";
        String defaultClean = win ? "C:/Temp" : "/tmp";
        assertFalse(opsPathPolicy.isAllowedLogCleanupPath(customRoot),
                "保存前应不在默认白名单");

        Map<String, Object> saved = service.saveEditablePolicy(Map.of(
                "readPrefixes", List.of(defaultLogRoot, customRoot),
                "cleanRoots", List.of(defaultClean),
                "logCleanupRoots", List.of(defaultLogRoot, customRoot),
                "serviceRestartAllowlist", List.of("nginx")
        ));

        assertTrue((Boolean) saved.get("saved"));
        assertTrue(opsPathPolicy.isAllowedLogCleanupPath(customRoot),
                "保存后 MCP 校验应立刻允许新日志清理根");
        assertTrue(opsPathPolicy.snapshotLogCleanupRoots().contains(customRoot));

        @SuppressWarnings("unchecked")
        List<String> reloaded = (List<String>) service.getEffectivePolicyView().get("logCleanupRoots");
        assertTrue(reloaded.contains(customRoot), "GET 应返回与内存一致的白名单");
    }
}
