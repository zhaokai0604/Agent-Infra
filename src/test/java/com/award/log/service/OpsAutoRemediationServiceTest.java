package com.award.log.service;

import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.OpsPathPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsAutoRemediationServiceTest {

    @Mock
    private CleanTempTool cleanTempTool;
    @Mock
    private LogCleanupTool logCleanupTool;
    @Mock
    private ServiceRestartTool serviceRestartTool;
    @Mock
    private OpsPathPolicy opsPathPolicy;
    @Mock
    private AgenticRiskScoreEngine agenticRiskScoreEngine;
    @Mock
    private McpInvocationSecurityGate mcpInvocationSecurityGate;
    @Mock
    private OpsGovernanceService opsGovernanceService;
    @Mock
    private OpsAuditTraceService opsAuditTraceService;
    @Mock
    private HttpAuditSubject httpAuditSubject;

    private OpsAutoRemediationService service;

    @BeforeEach
    void setUp() {
        service = new OpsAutoRemediationService(
                cleanTempTool,
                logCleanupTool,
                serviceRestartTool,
                opsPathPolicy,
                new ObjectMapper(),
                agenticRiskScoreEngine,
                mcpInvocationSecurityGate,
                opsGovernanceService,
                opsAuditTraceService,
                httpAuditSubject);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "riskPatrolAutoMax", 6.0d);
        ReflectionTestUtils.setField(service, "pendingProposalId", "p-1");
        ReflectionTestUtils.setField(service, "pendingExpiresAtMs", System.currentTimeMillis() + 60_000L);
        ReflectionTestUtils.setField(service, "pendingSteps", List.of(Map.of(
                "kind", "CLEAN_TEMP",
                "path", "/tmp/demo",
                "days", 1)));
        ReflectionTestUtils.setField(service, "pendingSummary", "summary");
        ReflectionTestUtils.setField(service, "pendingCorrelationDigest", Map.of("diskUsagePct", 91.0));
        ReflectionTestUtils.setField(service, "pendingFindingsCount", 1);
    }

    @Test
    void pendingProposalIsClaimedPerRequester() {
        Map<String, Object> ownerView = service.getPendingProposalView("u1");
        Map<String, Object> otherView = service.getPendingProposalView("u2");
        Map<String, Object> denied = service.confirmPending("p-1", OpsAutoRemediationService.CONFIRM_CODE, "u2");
        Map<String, Object> ownerViewAgain = service.getPendingProposalView("u1");

        assertTrue(Boolean.TRUE.equals(ownerView.get("hasPending")));
        assertEquals("u1", ownerView.get("claimOwner"));

        assertFalse(Boolean.TRUE.equals(otherView.get("hasPending")));
        assertTrue(Boolean.TRUE.equals(otherView.get("claimedByOther")));

        assertFalse(Boolean.TRUE.equals(denied.get("success")));
        assertTrue(String.valueOf(denied.get("error")).contains("其他操作者"));

        assertTrue(Boolean.TRUE.equals(ownerViewAgain.get("hasPending")));
    }

    @Test
    void afterPatrolNoActionClearsStaleExecutionSummaryAndPendingProposal() {
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of());
        when(opsPathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of());
        when(opsGovernanceService.filterPlanSteps(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(service, "lastSummary", Map.of(
                "traceId", "old-trace",
                "actions", List.of(Map.of("success", true))));

        service.afterPatrol(Map.of(
                "diskUsagePct", 42.0,
                "cpuUsagePct", 10.0,
                "memoryUsagePct", 35.0), List.of());

        Map<String, Object> summary = service.getLastSummary();
        assertEquals("NO_ACTION", summary.get("status"));
        assertEquals(0, summary.get("plannedCount"));
        assertFalse(summary.containsKey("traceId"));
        assertTrue(summary.get("actions") instanceof List<?> actions && actions.isEmpty());
        assertFalse(Boolean.TRUE.equals(service.getPendingProposalView("u1").get("hasPending")));
    }
}
