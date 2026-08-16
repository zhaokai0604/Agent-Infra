package com.award.log.controller;

import com.award.log.agent.awm.FailureInsightService;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.RiskLevel;
import com.award.log.security.effect.SessionRiskBudgetService;
import com.award.log.security.effect.ToolEffectResolver;
import com.award.log.service.mcp.McpAuditService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpPendingConfirmationService;
import com.award.log.service.mcp.McpSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpExecuteControllerTest {

    @Mock
    private McpToolCatalog mcpToolCatalog;
    @Mock
    private McpSecurityService mcpSecurityService;
    @Mock
    private McpExecutionService mcpExecutionService;
    @Mock
    private McpAuditService mcpAuditService;
    @Mock
    private FailureInsightService failureInsightService;
    @Mock
    private HttpAuditSubject httpAuditSubject;

    private McpPendingConfirmationService pendingConfirmationService;
    private McpExecuteController controller;

    @BeforeEach
    void setUp() {
        pendingConfirmationService = new McpPendingConfirmationService();
        ReflectionTestUtils.setField(pendingConfirmationService, "confirmationTtlMs", 600_000L);
        controller = new McpExecuteController(
                mcpToolCatalog,
                mcpSecurityService,
                mcpExecutionService,
                mcpAuditService,
                failureInsightService,
                pendingConfirmationService,
                httpAuditSubject,
                new ToolEffectResolver(),
                new SessionRiskBudgetService(3_600_000L, 20, 60));
        when(mcpToolCatalog.isRegistered(anyString())).thenReturn(true);
        when(mcpToolCatalog.describePlatformSupport(anyString()))
                .thenReturn(new HashMap<>(Map.of("available", true, "reason", "")));
    }

    @Test
    void confirmExecuteUsesServerSideSnapshotInsteadOfClientPayload() {
        when(httpAuditSubject.currentOperatorId()).thenReturn("u1");
        when(mcpSecurityService.evaluateInitial(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.needConfirm(RiskLevel.MEDIUM, "need confirm", 5.0, Map.of(), "preview"));
        when(mcpSecurityService.buildAuditInstruction(anyString(), anyString(), anyMap()))
                .thenReturn("audit-instruction");
        when(mcpSecurityService.buildNeedConfirmResponse(anyString(), anyLong(), any(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("needConfirm", true);
                    return response;
                });
        when(mcpSecurityService.evaluatePostConfirm(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.allow(RiskLevel.MEDIUM, 4.0, Map.of(), "ok"));

        Map<String, Object> execResponse = new HashMap<>();
        execResponse.put("success", true);
        execResponse.put("data", "ok");
        when(mcpExecutionService.execute(anyString(), anyMap(), anyLong(), anyString(), anyString(), eq(true)))
                .thenReturn(execResponse);

        Map<String, Object> previewRequest = new HashMap<>();
        previewRequest.put("toolName", "CleanTempTool");
        previewRequest.put("userMessage", "clean tmp");
        previewRequest.put("parameters", Map.of("path", "/safe/path", "dryRun", false));

        Map<String, Object> preview = controller.executeTool(previewRequest);
        String confirmationId = String.valueOf(preview.get("confirmationId"));

        Map<String, Object> confirmRequest = new HashMap<>();
        confirmRequest.put("confirmationId", confirmationId);
        confirmRequest.put("confirmCode", "确认执行");
        confirmRequest.put("toolName", "ServiceRestartTool");
        confirmRequest.put("userMessage", "tampered");
        confirmRequest.put("parameters", Map.of("path", "/evil/path", "serviceName", "sshd"));

        Map<String, Object> confirmed = controller.confirmExecuteTool(confirmRequest);

        assertTrue(Boolean.TRUE.equals(confirmed.get("success")));
        assertEquals(confirmationId, confirmed.get("traceId"));

        ArgumentCaptor<String> toolCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mcpExecutionService).execute(
                toolCaptor.capture(),
                paramsCaptor.capture(),
                anyLong(),
                eq(confirmationId),
                eq("audit-instruction"),
                eq(true));

        assertEquals("CleanTempTool", toolCaptor.getValue());
        assertEquals("/safe/path", paramsCaptor.getValue().get("path"));
        assertNotNull(preview.get("expiresAtMs"));
        assertNotNull(preview.get("capabilityToken"));
        assertNotNull(preview.get("effectFingerprint"));
        assertNotNull(preview.get("toolEffect"));
    }

    @Test
    void confirmExecuteRejectsWrongCapabilityToken() {
        when(httpAuditSubject.currentOperatorId()).thenReturn("u1");
        when(mcpSecurityService.evaluateInitial(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.needConfirm(RiskLevel.MEDIUM, "need confirm", 5.0, Map.of(), "preview"));
        when(mcpSecurityService.buildAuditInstruction(anyString(), anyString(), anyMap()))
                .thenReturn("audit-instruction");
        when(mcpSecurityService.buildNeedConfirmResponse(anyString(), anyLong(), any(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("needConfirm", true);
                    return response;
                });

        Map<String, Object> previewRequest = new HashMap<>();
        previewRequest.put("toolName", "CleanTempTool");
        previewRequest.put("userMessage", "clean tmp");
        previewRequest.put("parameters", Map.of("path", "/safe/path"));

        Map<String, Object> preview = controller.executeTool(previewRequest);
        String confirmationId = String.valueOf(preview.get("confirmationId"));

        Map<String, Object> confirmRequest = new HashMap<>();
        confirmRequest.put("confirmationId", confirmationId);
        confirmRequest.put("confirmCode", "确认执行");
        confirmRequest.put("capabilityToken", "forged-token");

        Map<String, Object> confirmed = controller.confirmExecuteTool(confirmRequest);
        assertEquals(false, confirmed.get("success"));
        assertTrue(String.valueOf(confirmed.get("error")).contains("能力凭证"));
    }
}
