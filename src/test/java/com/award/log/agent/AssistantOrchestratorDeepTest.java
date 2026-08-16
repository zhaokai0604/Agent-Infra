package com.award.log.agent;

import com.award.log.agent.awm.WorkflowExecutionService;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ProcessTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsTrustTier;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.util.TestRuntimePlatform;
import com.award.log.util.TestTimeSource;
import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantOrchestratorDeepTest {

    @Mock private DiskTool diskTool;
    @Mock private DiskAnalyzeTool diskAnalyzeTool;
    @Mock private LogCleanupTool logCleanupTool;
    @Mock private CleanTempTool cleanTempTool;
    @Mock private SystemLoadTool systemLoadTool;
    @Mock private ProcessTool processTool;
    @Mock private ServiceRestartTool serviceRestartTool;
    @Mock private OpsRemediationGate opsRemediationGate;
    @Mock private OpsPathPolicy opsPathPolicy;
    @Mock private ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    @Mock private AssistantAuditRecorder auditRecorder;
    @Mock private OpsIntentRouter opsIntentRouter;
    @Mock private WorkflowRetriever workflowRetriever;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowInductionService workflowInductionService;
    @Mock private OpsAutoRemediationService opsAutoRemediationService;

    private AssistantOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AssistantOrchestrator(
                diskTool,
                diskAnalyzeTool,
                logCleanupTool,
                cleanTempTool,
                systemLoadTool,
                processTool,
                serviceRestartTool,
                opsRemediationGate,
                opsPathPolicy,
                serviceRestartCandidateResolver,
                auditRecorder,
                new ObjectMapper(),
                opsIntentRouter,
                workflowRetriever,
                workflowExecutionService,
                workflowInductionService,
                new TestRuntimePlatform(false),
                new TestTimeSource(1_500L),
                new TestTraceIdGenerator("trace-assistant-deep"));
        ReflectionTestUtils.setField(orchestrator, "logCleanDays", 30);
        ReflectionTestUtils.setField(orchestrator, "tempCleanDays", 7);
        ReflectionTestUtils.setField(orchestrator, "diskPressurePercent", 80.0);

        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
        lenient().when(opsPathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of("/var/log/app"));
        lenient().when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp/app"));
    }

    @Test
    void helperMethodsResolveCpuVarsTempRootAndToolSuccess() {
        when(serviceRestartCandidateResolver.pickFromUserMessage("restart redis")).thenReturn("redis");

        @SuppressWarnings("unchecked")
        Map<String, Object> cpuVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildCpuWorkflowVariables", "restart redis");
        assertEquals("redis", cpuVars.get("service-name"));
        assertEquals(Boolean.TRUE, cpuVars.get("dryRun"));

        when(serviceRestartCandidateResolver.pickFromUserMessage("observe")).thenReturn(null);
        when(serviceRestartCandidateResolver.pickDefaultFromAllowlist()).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> noServiceVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildCpuWorkflowVariables", "observe");
        assertFalse(noServiceVars.containsKey("service-name"));
        assertEquals(Boolean.TRUE, noServiceVars.get("dryRun"));

        when(opsPathPolicy.isAllowedCleanDirectory("/tmp/cache")).thenReturn(true);
        String allowedTemp = ReflectionTestUtils.invokeMethod(orchestrator, "resolveTempCleanupRoot", "/tmp/cache");
        assertEquals("/tmp/cache", allowedTemp);

        when(opsPathPolicy.isAllowedCleanDirectory("/data/cache")).thenReturn(false);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp/fallback"));
        String fallbackTemp = ReflectionTestUtils.invokeMethod(orchestrator, "resolveTempCleanupRoot", "/data/cache");
        assertEquals("/tmp/fallback", fallbackTemp);

        assertTrue(Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "mentionsTemp", "please clean temp")));
        assertFalse(Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "mentionsTemp", "inspect logs only")));
        // parseToolSuccess 走 WriteToolResultSupport：仅 success=true 不够，需真实写证据字段
        assertFalse(Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(orchestrator, "parseToolSuccess", "{\"success\":true}")));
        assertFalse(Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(orchestrator, "parseToolSuccess", "{\"success\":false,\"error\":\"denied\"}")));
    }

    @Test
    void formatBytesCoversBlankUnitsAndInvalidInput() {
        assertEquals("-", ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "formatBytes", ""));
        assertEquals("512 B", ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "formatBytes", "512"));
        Object kib = ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "formatBytes", "2048");
        Object mib = ReflectionTestUtils.invokeMethod(
                AssistantOrchestrator.class, "formatBytes", String.valueOf(3 * 1024 * 1024L));
        Object gib = ReflectionTestUtils.invokeMethod(
                AssistantOrchestrator.class, "formatBytes", String.valueOf(5L * 1024 * 1024 * 1024));
        assertTrue(String.valueOf((Object) kib).contains("KiB"));
        assertTrue(String.valueOf((Object) mib).contains("MiB"));
        assertTrue(String.valueOf((Object) gib).contains("GiB"));
        assertEquals("raw", ReflectionTestUtils.invokeMethod(AssistantOrchestrator.class, "formatBytes", "raw"));
    }

    @Test
    void appendTempCleanupSectionReportsPreviewFailure() throws Exception {
        when(opsPathPolicy.isAllowedCleanDirectory("/tmp")).thenReturn(true);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
        when(opsRemediationGate.decideTempCleanup(eq("/tmp"), eq(7), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview temp"));
        when(cleanTempTool.cleanTempFiles("/tmp", 7, true, false, false))
                .thenReturn("{\"success\":false,\"error\":\"preview failed\"}");

        StringBuilder md = new StringBuilder();
        boolean executed = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendTempCleanupSection", md, new ArrayList<Map<String, Object>>(), false, "clean /tmp");

        assertFalse(executed);
        assertTrue(md.toString().contains("preview failed"));
    }

    @Test
    void appendTempCleanupSectionStopsWhenExecutionFails() throws Exception {
        when(opsPathPolicy.isAllowedCleanDirectory("/tmp")).thenReturn(true);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
        when(opsRemediationGate.decideTempCleanup(eq("/tmp"), eq(0), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute temp"));
        when(cleanTempTool.cleanTempFiles("/tmp", 0, true, false, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        when(cleanTempTool.cleanTempFiles("/tmp", 0, false, true, false))
                .thenReturn("{\"success\":false,\"error\":\"delete failed\"}");

        StringBuilder md = new StringBuilder();
        boolean executed = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendTempCleanupSection", md, new ArrayList<Map<String, Object>>(), true, "clean /tmp");

        assertFalse(executed);
        assertTrue(md.toString().contains("delete failed"));
    }

    @Test
    void appendTempCleanupSectionStopsForMissingRemoveDirectoryTarget() throws Exception {
        when(opsPathPolicy.isAllowedCleanDirectory("/tmp/cache")).thenReturn(true);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
        when(opsRemediationGate.decideTempCleanup(eq("/tmp/cache"), eq(0), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview temp"));
        when(cleanTempTool.cleanTempFiles("/tmp/cache", 0, true, false, true))
                .thenReturn("{\"success\":true,\"data\":{\"preview\":{\"entries\":0,\"exists\":false}}}");

        StringBuilder md = new StringBuilder();
        boolean executed = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendTempCleanupSection", md, new ArrayList<Map<String, Object>>(), false, "clean /tmp/cache");

        assertFalse(executed);
        assertTrue(md.toString().contains("/tmp/cache"));
        verify(cleanTempTool, never()).cleanTempFiles("/tmp/cache", 0, false, true, true);
    }

    @Test
    void appendMultiDriveTempCleanupHandlesScanFailuresAndPolicyBlocks() throws Exception {
        when(cleanTempTool.scanAllTempJunk(7))
                .thenReturn("{\"success\":false,\"error\":\"scan failed\"}");

        StringBuilder failedMd = new StringBuilder();
        boolean scanFailed = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendMultiDriveTempCleanup", failedMd, new ArrayList<Map<String, Object>>(), false, "clean temp");

        assertFalse(scanFailed);
        assertTrue(failedMd.toString().contains("scan failed"));

        when(cleanTempTool.scanAllTempJunk(0)).thenReturn("""
                {"success":true,"data":{
                  "totalFilesFound":3,
                  "locations":[
                    {"path":"/tmp/a","drive":"/","filesFound":3,"autoCleanEligible":true,"writable":true}
                  ]
                }}
                """);
        when(opsRemediationGate.decideTempCleanup(eq("/tmp/a"), eq(0), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "manual approval"));

        StringBuilder blockedMd = new StringBuilder();
        boolean blocked = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendMultiDriveTempCleanup", blockedMd, new ArrayList<Map<String, Object>>(), true, "clean temp");

        assertFalse(blocked);
        assertTrue(blockedMd.toString().contains("manual approval"));
        verify(cleanTempTool, never()).cleanTempFiles("/tmp/a", 0, false, true, false);
    }

    @Test
    void appendServiceRestartSectionHandlesPreviewWithoutExecuting() throws Exception {
        when(opsRemediationGate.decideServiceRestart(eq("nginx"), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview restart"));
        when(serviceRestartTool.restartService("nginx", true, false))
                .thenReturn("{\"success\":true,\"data\":{\"mode\":\"DRY-RUN\"}}");

        StringBuilder md = new StringBuilder();
        boolean executed = ReflectionTestUtils.invokeMethod(
                orchestrator, "appendServiceRestartSection", md, new ArrayList<Map<String, Object>>(), "nginx", false);

        assertFalse(executed);
        assertTrue(md.toString().contains("nginx"));
        verify(serviceRestartTool, never()).restartService("nginx", false, true);
    }

    @Test
    void cpuRunReturnsErrorWhenSystemLoadProbeFails() throws Exception {
        when(opsIntentRouter.resolve("cpu overload")).thenReturn(OpsIntentRouter.Playbook.CPU_PRESSURE);
        when(systemLoadTool.checkSystemLoad()).thenThrow(new RuntimeException("load failed"));

        AssistantOrchestrator.RunResult result = orchestrator.run("cpu overload", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("load failed"));
        verify(auditRecorder).record(eq("trace-assistant-deep"), eq("cpu overload"), eq("MEDIUM"),
                eq("ERROR"), eq("CpuPressure"), eq(false), eq("load failed"), anyList(), anyLong());
    }

    @Test
    void patrolContinuationReturnsReadOnlyReplyOnReadOnlySurface() {
        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", opsAutoRemediationService);
        when(opsIntentRouter.resolve("continue patrol")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);

        AssistantOrchestrator.RunResult result = orchestrator.run("continue patrol", McpToolSurface.READ_ONLY, RiskLevel.LOW);

        assertTrue(result.markdown().contains("trace"));
        verify(opsAutoRemediationService, never()).getPendingProposalView();
    }

    @Test
    void patrolContinuationReportsUnavailableServiceAndFailedConfirmation() {
        when(opsIntentRouter.resolve("continue patrol")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);

        AssistantOrchestrator.RunResult unavailable = orchestrator.run("continue patrol", McpToolSurface.FULL, RiskLevel.LOW);
        assertTrue(unavailable.markdown().contains("没有待执行方案") || unavailable.markdown().contains("未启用"));

        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", opsAutoRemediationService);
        when(opsIntentRouter.forceRemediate("continue patrol")).thenReturn(true);
        when(opsAutoRemediationService.getPendingProposalView()).thenReturn(Map.of(
                "hasPending", true,
                "proposalId", "p-2",
                "summary", "cleanup temp"));
        when(opsAutoRemediationService.confirmPendingFromAssistant("p-2")).thenReturn(Map.of(
                "success", false,
                "error", "approval expired"));

        AssistantOrchestrator.RunResult failed = orchestrator.run("continue patrol", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(failed.markdown().contains("approval expired"));
        verify(opsAutoRemediationService).confirmPendingFromAssistant("p-2");
    }
}
