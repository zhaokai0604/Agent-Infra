package com.award.log.service.mcp;

import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceMcpBulkCoverageTest {

    @Mock OpsAuditTraceService opsAuditTraceService;
    @Mock HttpAuditSubject httpAuditSubject;
    @Mock OpsPathPolicy opsPathPolicy;

    private McpAuditService auditService;
    private McpPendingConfirmationService pendingService;

    @BeforeEach
    void setUp() {
        auditService = new McpAuditService(opsAuditTraceService, httpAuditSubject, opsPathPolicy);
        pendingService = new McpPendingConfirmationService();
        org.springframework.test.util.ReflectionTestUtils.setField(pendingService, "confirmationTtlMs", 600_000L);
    }

    @Test
    void auditServicePersistsGateOutcomes() throws Exception {
        when(httpAuditSubject.currentOperatorId()).thenReturn("42");
        when(opsPathPolicy.getPolicyVersion()).thenReturn("path-policy-v1");

        GateDecision block = GateDecision.block("INJECTION", "blocked");
        auditService.persistGateReject("t1", "user msg", "DiskTool", block, System.currentTimeMillis());
        verify(opsAuditTraceService).save(eq("t1"), eq("MCP"), anyString(), eq(RiskLevel.HIGH.name()),
                eq("REJECT_INJECTION"), eq("DiskTool"), eq(false), anyString(), anyList(), anyLong(),
                eq("42"), eq("path-policy-v1"), anyMap(), anyMap());

        GateDecision confirm = GateDecision.needConfirm(RiskLevel.MEDIUM, "confirm", 4.0, Map.of(), "preview");
        auditService.persistNeedConfirm("t2", "user msg", "CleanTempTool", confirm, System.currentTimeMillis());

        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("success", true);
        exec.put("data", "{\"ok\":true}");
        GateDecision allow = GateDecision.allow(RiskLevel.LOW, 1.0, Map.of(), "ok");
        auditService.persistSuccess("t3", "user msg", allow, "DiskTool", exec, System.currentTimeMillis());
    }

    @Test
    void pendingConfirmationTakeLifecycle() {
        McpPendingConfirmationService.PendingConfirmation pending = pendingService.register(
                "cid-1", "DiskTool", Map.of("dryRun", true), "hello", "audit", "user-a");
        assertNotNull(pending);

        McpPendingConfirmationService.TakeResult ok =
                pendingService.take("cid-1", "user-a");
        assertEquals(McpPendingConfirmationService.TakeStatus.OK, ok.status());
        assertNotNull(ok.pending());

        McpPendingConfirmationService.TakeResult missing =
                pendingService.take("cid-1", "user-a");
        assertEquals(McpPendingConfirmationService.TakeStatus.NOT_FOUND, missing.status());

        pendingService.register("cid-2", "DiskTool", Map.of(), null, null, "user-b");
        McpPendingConfirmationService.TakeResult mismatch =
                pendingService.take("cid-2", "other-user");
        assertEquals(McpPendingConfirmationService.TakeStatus.REQUESTER_MISMATCH, mismatch.status());
    }
}
