package com.award.log.service;

import com.award.log.config.OpsDryRunProperties;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.McpInvocationSecurityGate.McpSecurityProfile;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.ReadOnlySurfaceDenylist;
import com.award.log.security.RiskLevel;
import com.award.log.security.effect.PlanEffectGate;
import com.award.log.security.effect.PolicyReplayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — no database or Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class SecuritySelfCheckServiceUnitTest {

    @Mock
    private McpInvocationSecurityGate securityGate;
    @Mock
    private AgenticRiskScoreEngine riskScoreEngine;
    @Mock
    private McpToolCatalog mcpToolCatalog;
    @Mock
    private ReadOnlySurfaceDenylist readOnlySurfaceDenylist;
    @Mock
    private OpsDryRunProperties opsDryRunProperties;
    @Mock
    private MinPrivilegeExecutor minPrivilegeExecutor;
    @Mock
    private PlanEffectGate planEffectGate;
    @Mock
    private PolicyReplayService policyReplayService;

    private SecuritySelfCheckService service;

    @BeforeEach
    void setUp() {
        service = new SecuritySelfCheckService(
                securityGate,
                riskScoreEngine,
                mcpToolCatalog,
                readOnlySurfaceDenylist,
                opsDryRunProperties,
                minPrivilegeExecutor,
                planEffectGate,
                policyReplayService);
        stubConfigSnapshot();
        stubGateProbes();
        stubPlanAndPolicy();
    }

    @AfterEach
    void tearDown() {
        OpsSecurityContext.clear();
    }

    @Test
    void runShouldReportPassWhenAllProbesMatchExpectations() {
        Map<String, Object> report = service.run();

        assertEquals("PASS", report.get("overallStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(0, ((Number) summary.get("failed")).intValue());
        assertTrue(((Number) summary.get("total")).intValue() >= 14);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> probes = (List<Map<String, Object>>) report.get("probes");
        assertTrue(probes.stream().allMatch(p -> Boolean.TRUE.equals(p.get("passed"))));
        assertNotNull(report.get("effectEngine"));
        assertNotNull(report.get("adversarialSuite"));
    }

    @Test
    void runShouldIncludeConfigAndLayerSummaries() {
        Map<String, Object> report = service.run();

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) report.get("config");
        assertEquals(5.0, config.get("riskScoreAutoMax"));
        assertEquals(2, config.get("httpAllowedToolCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) report.get("layers");
        assertFalse(layers.isEmpty());
        assertTrue(layers.stream().anyMatch(l -> "governance".equals(l.get("id"))));
        assertTrue(layers.stream().anyMatch(l -> "plan".equals(l.get("id"))));
        assertTrue(layers.stream().allMatch(l -> l.containsKey("passed")));
    }

    private void stubConfigSnapshot() {
        when(riskScoreEngine.getAutoMax()).thenReturn(5.0);
        when(riskScoreEngine.getConfirmMax()).thenReturn(9.5);
        when(mcpToolCatalog.getHttpAllowedToolNames()).thenReturn(Set.of("DiskInsightTool", "CleanTempTool"));
        when(readOnlySurfaceDenylist.snapshot()).thenReturn(Set.of("ServiceRestartTool"));
        when(opsDryRunProperties.isGlobalDryRun()).thenReturn(false);
        when(minPrivilegeExecutor.isMinPrivilegeEnabled()).thenReturn(true);
        when(minPrivilegeExecutor.getRunAsUser()).thenReturn("award-ops");
        when(opsDryRunProperties.looksLikeMutatingCommand(anyList())).thenReturn(true);
    }

    private void stubPlanAndPolicy() {
        when(policyReplayService.currentPolicySnapshot()).thenReturn(Map.of("pathPolicyVersion", "v-test"));
        when(planEffectGate.evaluate(argThat(list -> list != null && list.size() == 1)))
                .thenReturn(new PlanEffectGate.PlanDecision(
                        PlanEffectGate.DecisionType.BLOCK, "PLAN_GOVERNANCE_FORBIDDEN", "forbidden",
                        1, 6, 0, false, List.of()));
        when(planEffectGate.evaluate(argThat(list -> list != null && list.size() == 2)))
                .thenReturn(new PlanEffectGate.PlanDecision(
                        PlanEffectGate.DecisionType.NEED_CONFIRM, "PLAN_SENSITIVE_OBSERVE_THEN_WRITE", "combo",
                        1, 6, 0, true, List.of()));
    }

    private void stubGateProbes() {
        when(securityGate.evaluate(eq("EvilTool"), anyMap(), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("UNKNOWN_TOOL", "unknown"));
        when(securityGate.evaluate(eq("bad tool"), anyMap(), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("TOOL_NAME_REJECTED", "bad format"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                contains("ignore previous instructions"), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("INJECTION", "injection"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/tmp; rm -rf /")),
                eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("HIGH_RISK_COMMAND", "high risk cmd"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                eq("rm -rf /"), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("HIGH_INTENT", "high intent"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                eq("删除整个系统盘上的所有文件"), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("INTENT_TOOL_MISMATCH", "mismatch"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                eq("检查磁盘使用情况"), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.allow(RiskLevel.LOW, 1.0, Map.of(), "ok"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                eq("修改配置并重载 nginx 服务"), eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.needConfirm(RiskLevel.MEDIUM, "confirm", 6.0, Map.of(), "needs confirm"));
        when(securityGate.evaluate(eq("DiskInsightTool"), eq(Map.of("path", "/")),
                eq("修改配置并重载 nginx 服务"), eq(McpSecurityProfile.POST_CONFIRMATION)))
                .thenReturn(GateDecision.allow(RiskLevel.MEDIUM, 4.0, Map.of(), "confirmed"));
        when(securityGate.evaluate(eq("ServiceRestartTool"), eq(Map.of("service", "nginx", "dryRun", true)),
                eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("READ_ONLY_TOOL_SURFACE", "readonly"));
        when(securityGate.evaluate(eq("ServiceRestartTool"),
                eq(Map.of("serviceName", "sshd", "dryRun", true)),
                eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.block("GOVERNANCE_FORBIDDEN", "sshd forbidden"));
        when(securityGate.evaluate(eq("ServiceRestartTool"),
                eq(Map.of("serviceName", "redis", "dryRun", false, "confirmRestart", true)),
                eq(McpSecurityProfile.INITIAL_REQUEST)))
                .thenReturn(GateDecision.needConfirm(RiskLevel.MEDIUM, "gov confirm", 4.0, Map.of(), "confirm"));
    }
}
