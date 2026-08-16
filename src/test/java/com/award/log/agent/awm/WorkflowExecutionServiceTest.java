package com.award.log.agent.awm;

import com.award.log.agent.AssistantAuditRecorder;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsTrustTier;
import com.award.log.util.TestRuntimePlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock
    private McpToolDispatcher dispatcher;

    @Mock
    private OpsRemediationGate remediationGate;

    @Mock
    private AssistantAuditRecorder auditRecorder;

    @Mock
    private OpsPathPolicy opsPathPolicy;

    @Mock
    private WorkflowMemoryService workflowMemoryService;

    @Test
    void previewsWriteStepWhenGateRequiresConfirmation() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-log-preview",
                "disk",
                List.of("DISK_PRESSURE"),
                "log cleanup",
                "preview",
                List.of(OpsWorkflowStep.of("log pressure", "preview cleanup", "LogCleanupTool",
                        Map.of("path", "{log-path}", "days", "{log-days}"))),
                "online",
                "trace-1",
                0,
                true
        );

        when(remediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "need confirmation"));
        when(dispatcher.dispatch(eq("LogCleanupTool"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"filesFound\":3}}"));

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-1",
                        "cleanup logs",
                        McpToolSurface.FULL,
                        false,
                        new ArrayList<>(),
                        Map.of("log-path", "/var/log/app", "log-days", 14)
                ));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq("LogCleanupTool"), paramsCaptor.capture());
        verify(auditRecorder, atLeastOnce()).addStructuredStep(
                org.mockito.ArgumentMatchers.anyList(),
                eq("preview"),
                org.mockito.ArgumentMatchers.anyMap());

        assertTrue(result.handled());
        assertTrue(result.anyPreviewed());
        assertFalse(result.anyExecuted());
        assertTrue(Boolean.TRUE.equals(paramsCaptor.getValue().get("dryRun")));
    }

    @Test
    void executesWriteStepWhenGateAllowsExecution() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-log-exec",
                "disk",
                List.of("DISK_PRESSURE"),
                "log cleanup",
                "execute",
                List.of(OpsWorkflowStep.of("log pressure", "execute cleanup", "LogCleanupTool",
                        Map.of("path", "{log-path}", "days", "{log-days}"))),
                "online",
                "trace-2",
                0,
                true
        );

        when(remediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "auto execute"));
        when(dispatcher.dispatch(eq("LogCleanupTool"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"filesDeleted\":2}}"));

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-2",
                        "delete logs now",
                        McpToolSurface.FULL,
                        true,
                        new ArrayList<>(),
                        Map.of("log-path", "/var/log/app", "log-days", 30)
                ));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq("LogCleanupTool"), paramsCaptor.capture());

        assertTrue(result.handled());
        assertTrue(result.anyExecuted());
        assertFalse(result.anyPreviewed());
        assertFalse(Boolean.TRUE.equals(paramsCaptor.getValue().get("dryRun")));
        assertTrue(Boolean.TRUE.equals(paramsCaptor.getValue().get("confirmDelete")));
    }

    @Test
    void abortsWhenWorkflowUsesUnsupportedTool() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-unsupported",
                "disk",
                List.of("DISK_PRESSURE"),
                "bad tool",
                "unsupported",
                List.of(OpsWorkflowStep.of("x", "x", "UnknownTool", Map.of())),
                "seed",
                "trace-x",
                0,
                true
        );

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-x",
                        "bad",
                        McpToolSurface.FULL,
                        false,
                        new ArrayList<>(),
                        Map.of()
                ));

        assertTrue(result.failed());
        assertTrue(result.blocked());
        assertFalse(result.handled());
        assertEquals("SKIP", result.stepResults().get(0).mode());
        verify(dispatcher, never()).dispatch(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void skipsWriteStepOnReadOnlySurface() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-ro",
                "disk",
                List.of("DISK_PRESSURE"),
                "ro cleanup",
                "readonly",
                List.of(OpsWorkflowStep.of("log pressure", "cleanup", "LogCleanupTool",
                        Map.of("path", "{log-path}", "days", "{log-days}"))),
                "seed",
                "trace-ro",
                0,
                true
        );

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-ro",
                        "cleanup",
                        McpToolSurface.READ_ONLY,
                        false,
                        new ArrayList<>(),
                        Map.of("log-path", "/var/log/app")
                ));

        assertFalse(result.failed());
        assertTrue(result.blocked());
        assertFalse(result.handled());
        assertEquals("SKIP", result.stepResults().get(0).mode());
        verify(dispatcher, never()).dispatch(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void abortsWhenRequiredServiceNameMissing() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-service-missing",
                "service",
                List.of("FAILED_SERVICE"),
                "restart",
                "restart missing service",
                List.of(OpsWorkflowStep.of("svc failed", "restart", "ServiceRestartTool", Map.of())),
                "seed",
                "trace-svc",
                0,
                true
        );

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-svc",
                        "restart service",
                        McpToolSurface.FULL,
                        true,
                        new ArrayList<>(),
                        Map.of()
                ));

        assertTrue(result.failed());
        assertTrue(result.blocked());
        assertFalse(result.handled());
    }

    @Test
    void executesCleanTempWithAutoDerivedDirectoryDefaults() {
        WorkflowExecutionService service = newService(true);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-temp-exec",
                "disk",
                List.of("DISK_PRESSURE"),
                "temp exec",
                "execute temp cleanup",
                List.of(OpsWorkflowStep.of("temp", "cleanup", "CleanTempTool", Map.of())),
                "seed",
                "trace-temp",
                0,
                true
        );

        when(opsPathPolicy.isAllowedCleanDirectory("C:\\safe\\temp\\subdir")).thenReturn(true);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("C:\\safe\\temp"));
        when(remediationGate.decideTempCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute"));
        when(dispatcher.dispatch(eq("CleanTempTool"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"filesDeleted\":5}}"));

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-temp",
                        "delete C:\\safe\\temp\\subdir",
                        McpToolSurface.FULL,
                        true,
                        new ArrayList<>(),
                        Map.of("temp-path", "C:\\safe\\temp\\subdir")
                ));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq("CleanTempTool"), paramsCaptor.capture());
        assertTrue(result.anyExecuted());
        assertEquals(0, paramsCaptor.getValue().get("days"));
        assertEquals(Boolean.TRUE, paramsCaptor.getValue().get("removeDirectory"));
    }

    @Test
    void summarizesReadOnlyToolAndNormalizesDiskAnalyzeArgs() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-disk-analyze",
                "disk",
                List.of("DISK_PRESSURE"),
                "analyze",
                "disk analyze",
                List.of(OpsWorkflowStep.of("disk", "analyze", "DiskAnalyzeTool",
                        Map.of("path", "{log-path}", "includeSubdirs", "true", "topN", "9"))),
                "seed",
                "trace-da",
                0,
                true
        );

        when(dispatcher.dispatch(eq("DiskAnalyzeTool"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(McpToolDispatchResult.ok("{\"success\":true,\"data\":{\"hotspots\":[]}}"));

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-da",
                        "analyze disk",
                        McpToolSurface.FULL,
                        false,
                        new ArrayList<>(),
                        Map.of("log-path", "/srv/logs")
                ));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq("DiskAnalyzeTool"), paramsCaptor.capture());
        assertFalse(result.failed());
        assertTrue(result.handled());
        assertEquals("/srv/logs", paramsCaptor.getValue().get("rootPath"));
        assertEquals(Boolean.TRUE, paramsCaptor.getValue().get("includeHotspots"));
        assertEquals(9, paramsCaptor.getValue().get("topN"));
        assertTrue(result.toMarkdown().contains("DiskAnalyzeTool"));
    }

    @Test
    void marksFailureWhenToolDispatchFails() {
        WorkflowExecutionService service = newService(false);
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-fail",
                "disk",
                List.of("DISK_PRESSURE"),
                "fail",
                "fail dispatch",
                List.of(OpsWorkflowStep.of("disk", "read", "DiskTool", Map.of())),
                "seed",
                "trace-fail",
                0,
                true
        );

        when(dispatcher.dispatch(eq("DiskTool"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(McpToolDispatchResult.fail("dispatcher failed"));

        WorkflowExecutionService.WorkflowRunResult result = service.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        "trace-fail",
                        "disk",
                        McpToolSurface.FULL,
                        false,
                        new ArrayList<>(),
                        Map.of()
                ));

        assertTrue(result.failed());
        assertFalse(result.handled());
        assertTrue(result.toMarkdown().contains("dispatcher failed"));
    }

    private WorkflowExecutionService newService(boolean windows) {
        WorkflowExecutionService service = new WorkflowExecutionService(
                dispatcher,
                remediationGate,
                auditRecorder,
                new ObjectMapper(),
                opsPathPolicy,
                new TestRuntimePlatform(windows),
                workflowMemoryService,
                null);
        ReflectionTestUtils.setField(service, "logCleanDays", 30);
        ReflectionTestUtils.setField(service, "tempCleanDays", 7);
        return service;
    }
}
