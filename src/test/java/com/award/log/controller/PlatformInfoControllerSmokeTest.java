package com.award.log.controller;

import com.award.log.config.OpsDryRunProperties;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.MinPrivilegeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlatformInfoControllerSmokeTest {

    @Mock
    private Environment environment;
    @Mock
    private RandomForestDecisionEngine randomForestDecisionEngine;
    @Mock
    private MinPrivilegeExecutor minPrivilegeExecutor;
    @Mock
    private OpsGovernanceService opsGovernanceService;
    @Mock
    private OpsDryRunProperties opsDryRunProperties;
    @Mock
    private DataSource dataSource;
    @Mock
    private McpToolCatalog mcpToolCatalog;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        lenient().when(environment.getProperty("app.security.ai-audit-relaxed-read")).thenReturn("false");
        lenient().when(environment.getProperty("ops.auto-remediation.enabled")).thenReturn("true");
        lenient().when(environment.getProperty("agent.autonomous.enabled")).thenReturn("true");
        lenient().when(randomForestDecisionEngine.healthSnapshot()).thenReturn(Map.of("loaded", true));
        lenient().when(minPrivilegeExecutor.isMinPrivilegeEnabled()).thenReturn(false);
        lenient().when(minPrivilegeExecutor.getRunAsUser()).thenReturn("root");
        lenient().when(opsGovernanceService.summaryForPlatform()).thenReturn(Map.of("version", "1"));
        lenient().when(opsDryRunProperties.isGlobalDryRun()).thenReturn(false);
        lenient().when(mcpToolCatalog.getDescriptors()).thenReturn(Map.of());
        lenient().when(mcpToolCatalog.getHttpAllowedToolNames()).thenReturn(Set.of("DiskTool"));
        Connection connection = mock(Connection.class);
        lenient().when(connection.isValid(anyInt())).thenReturn(true);
        lenient().when(dataSource.getConnection()).thenReturn(connection);

        PlatformInfoController controller = new PlatformInfoController(
                environment,
                randomForestDecisionEngine,
                minPrivilegeExecutor,
                opsGovernanceService,
                opsDryRunProperties,
                dataSource,
                mcpToolCatalog);
        ReflectionTestUtils.setField(controller, "expectedArch", "loongarch64");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void acceptanceReturnsPlatformSnapshot() throws Exception {
        mockMvc.perform(get("/api/platform/acceptance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.platform").exists())
                .andExpect(jsonPath("$.data.acceptance").exists());
    }

    @Test
    void infoReturnsFullSnapshot() throws Exception {
        mockMvc.perform(get("/api/platform/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.security").exists())
                .andExpect(jsonPath("$.data.runtime.dbReachable").value(true));
    }
}
