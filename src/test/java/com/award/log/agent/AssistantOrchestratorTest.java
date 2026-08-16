package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.OpsWorkflowStep;
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
class AssistantOrchestratorTest {

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
    void setUp() throws Exception {
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
                new TestTimeSource(1_000L),
                new TestTraceIdGenerator("trace-assistant-1"));
        ReflectionTestUtils.setField(orchestrator, "logCleanDays", 30);
        ReflectionTestUtils.setField(orchestrator, "tempCleanDays", 7);
        ReflectionTestUtils.setField(orchestrator, "diskPressurePercent", 80.0);

        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
        lenient().when(opsPathPolicy.snapshotLogCleanupRoots()).thenReturn(List.of("/var/log/app"));
        lenient().when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp/app"));
    }

    @Test
    void supportsOnlyKnownPlaybooks() {
        when(opsIntentRouter.resolve("disk")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        when(opsIntentRouter.resolve("cpu")).thenReturn(OpsIntentRouter.Playbook.CPU_PRESSURE);
        when(opsIntentRouter.resolve("patrol")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);
        when(opsIntentRouter.resolve("other")).thenReturn(OpsIntentRouter.Playbook.NONE);

        assertTrue(orchestrator.supports("disk"));
        assertTrue(orchestrator.supports("cpu"));
        assertTrue(orchestrator.supports("patrol"));
        assertFalse(orchestrator.supports("other"));
        assertFalse(orchestrator.supports(""));
    }

    @Test
    void diskRunReturnsReadOnlyReplyWithoutCallingTools() throws Exception {
        when(opsIntentRouter.resolve("cleanup disk")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);

        AssistantOrchestrator.RunResult result = orchestrator.run("cleanup disk", McpToolSurface.READ_ONLY, RiskLevel.MEDIUM);

        assertEquals("trace-assistant-1", result.traceId());
        assertTrue(result.markdown().contains("只读模式"));
        assertFalse(result.markdown().contains("Trace"));
        verify(diskTool, never()).checkDiskUsage();
        verify(auditRecorder).record(eq("trace-assistant-1"), eq("cleanup disk"), eq("MEDIUM"),
                eq("READ_ONLY_SURFACE"), eq("AssistantOrchestrator"), eq(false), anyString(), anyList(), anyLong());
    }

    @Test
    void diskRunUsesHandledAwmWorkflowAndSkipsFallbackCleanup() throws Exception {
        when(opsIntentRouter.resolve("cleanup disk")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        when(opsIntentRouter.forceRemediate("cleanup disk")).thenReturn(false);
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"90%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"70%\"}]}");
        when(diskAnalyzeTool.analyzeDiskPressure("/var/log", true, 12))
                .thenReturn("{\"success\":true,\"data\":{\"hotspots\":[]}}");
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-disk-1", "disk", List.of("DISK_PRESSURE"), "disk", "disk",
                List.of(OpsWorkflowStep.of("a", "a", "LogCleanupTool", Map.of("path", "{log-path}"))),
                "seed", null, 0, true);
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "cleanup disk")).thenReturn(workflow);
        when(workflowExecutionService.execute(eq(workflow), any()))
                .thenReturn(new WorkflowExecutionService.WorkflowRunResult(
                        workflow, List.of(), true, false, false, false, false));

        AssistantOrchestrator.RunResult result = orchestrator.run("cleanup disk", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("执行结果"));
        assertTrue(result.markdown().contains("历史治理方案"));
        assertFalse(result.markdown().contains("Trace"));
        verify(workflowRetriever, never()).recordHit(workflow);
        verify(cleanTempTool, never()).cleanTempFiles(anyString(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(logCleanupTool, never()).cleanupOldLogs(anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(workflowInductionService).afterSuccessfulRun(eq("trace-assistant-1"), eq("cleanup disk"),
                eq("EXECUTED"), eq(true), anyList(), eq("disk"), eq(List.of("DISK_PRESSURE")));
    }

    @Test
    void diskRunFallsBackToLogCleanupWhenAwmNotHandled() throws Exception {
        when(opsIntentRouter.resolve("cleanup logs")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        when(opsIntentRouter.forceRemediate("cleanup logs")).thenReturn(true);
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"85%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"60%\"}]}");
        when(diskAnalyzeTool.analyzeDiskPressure("/var/log", true, 12))
                .thenReturn("{\"success\":true,\"data\":{\"hotspots\":[]}}");
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-disk-2", "disk", List.of("DISK_PRESSURE"), "disk", "disk",
                List.of(OpsWorkflowStep.of("a", "a", "LogCleanupTool", Map.of("path", "{log-path}"))),
                "seed", null, 0, true);
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "cleanup logs")).thenReturn(workflow);
        when(workflowExecutionService.execute(eq(workflow), any()))
                .thenReturn(new WorkflowExecutionService.WorkflowRunResult(
                        workflow, List.of(), false, false, false, false, true));
        when(cleanTempTool.scanAllTempJunk(0))
                .thenReturn("{\"success\":true,\"data\":{\"totalFilesFound\":0,\"locations\":[]}}");
        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":3,\"protectedSkipped\":0}}");
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, false, true))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":2}}");

        AssistantOrchestrator.RunResult result = orchestrator.run("cleanup logs", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("历史方案未形成可执行动作"));
        assertFalse(result.markdown().contains("Trace"));
        verify(logCleanupTool).cleanupOldLogs("/var/log", 30, true, false);
        verify(logCleanupTool).cleanupOldLogs("/var/log", 30, false, true);
    }

    @Test
    void diskRunPreviewsExplicitTempCleanupWithoutExecuting() throws Exception {
        when(opsIntentRouter.resolve("清理 /tmp/cache")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        when(opsIntentRouter.forceRemediate("清理 /tmp/cache")).thenReturn(false);
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"82%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"80%\"}]}");
        when(diskAnalyzeTool.analyzeDiskPressure("/var/log", true, 12))
                .thenReturn("{\"success\":true,\"data\":{\"hotspots\":[]}}");
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "清理 /tmp/cache")).thenReturn(null);
        when(opsPathPolicy.isAllowedCleanDirectory("/tmp/cache")).thenReturn(true);
        when(opsPathPolicy.snapshotTempCleanRoots()).thenReturn(List.of("/tmp"));
        when(opsRemediationGate.decideTempCleanup(eq("/tmp/cache"), eq(0), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview temp"));
        when(cleanTempTool.cleanTempFiles("/tmp/cache", 0, true, false, true))
                .thenReturn("{\"success\":true,\"data\":{\"preview\":{\"entries\":4,\"exists\":true},\"mode\":\"DRY-RUN\"}}");
        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview logs"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":0}}");

        AssistantOrchestrator.RunResult result = orchestrator.run("清理 /tmp/cache", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("/tmp/cache"));
        verify(cleanTempTool, never()).cleanTempFiles("/tmp/cache", 0, false, true, true);
    }

    @Test
    void diskRunExecutesMultiDriveTempCleanupWhenNoPathSpecified() throws Exception {
        when(opsIntentRouter.resolve("清理 temp")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);
        when(opsIntentRouter.forceRemediate("清理 temp")).thenReturn(true);
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"88%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"70%\"}]}");
        when(diskAnalyzeTool.analyzeDiskPressure("/var/log", true, 12))
                .thenReturn("{\"success\":true,\"data\":{\"hotspots\":[]}}");
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "清理 temp")).thenReturn(null);
        when(cleanTempTool.scanAllTempJunk(0)).thenReturn("""
                {"success":true,"data":{
                  "totalFilesFound":3,
                  "drives":["C:"],
                  "locations":[
                    {"path":"C:\\\\temp\\\\app","drive":"C:","filesFound":3,"autoCleanEligible":true,"writable":true}
                  ]
                }}
                """);
        when(opsRemediationGate.decideTempCleanup(eq("C:\\temp\\app"), eq(0), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute temp"));
        when(cleanTempTool.cleanTempFiles("C:\\temp\\app", 0, false, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":3}}");
        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview logs"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":0}}");

        AssistantOrchestrator.RunResult result = orchestrator.run("清理 temp", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("Temp") || result.markdown().contains("C:"));
        verify(cleanTempTool).cleanTempFiles("C:\\temp\\app", 0, false, true, false);
    }

    @Test
    void cpuRunFallsBackToServiceRestartWhenAwmMisses() throws Exception {
        when(opsIntentRouter.resolve("restart nginx because cpu high")).thenReturn(OpsIntentRouter.Playbook.CPU_PRESSURE);
        when(opsIntentRouter.forceRemediate("restart nginx because cpu high")).thenReturn(true);
        when(workflowRetriever.bestMatch("cpu", List.of("CPU_HIGH"), "restart nginx because cpu high")).thenReturn(null);
        when(systemLoadTool.checkSystemLoad()).thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":95.0}}");
        when(processTool.listProcesses(5.0, 5.0)).thenReturn("{\"success\":true,\"data\":[]}");
        when(serviceRestartCandidateResolver.pickFromUserMessage("restart nginx because cpu high")).thenReturn("nginx");
        when(opsRemediationGate.decideServiceRestart(eq("nginx"), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "restart"));
        when(serviceRestartTool.restartService("nginx", true, false))
                .thenReturn("{\"success\":true,\"data\":{\"service\":\"nginx\"}}");
        when(serviceRestartTool.restartService("nginx", false, true))
                .thenReturn("{\"success\":true,\"data\":{\"service\":\"nginx\"}}");

        AssistantOrchestrator.RunResult result = orchestrator.run("restart nginx because cpu high", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("nginx"));
        verify(serviceRestartTool).restartService("nginx", false, true);
    }

    @Test
    void cpuRunReturnsSuggestionWhenRestartNotRequested() throws Exception {
        when(opsIntentRouter.resolve("cpu 过高")).thenReturn(OpsIntentRouter.Playbook.CPU_PRESSURE);
        when(opsIntentRouter.forceRemediate("cpu 过高")).thenReturn(false);
        when(workflowRetriever.bestMatch("cpu", List.of("CPU_HIGH"), "cpu 过高")).thenReturn(null);
        when(systemLoadTool.checkSystemLoad()).thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":95.0}}");
        when(processTool.listProcesses(5.0, 5.0)).thenReturn("""
                {"success":true,"data":"[{\\\"user\\\":\\\"root\\\",\\\"pid\\\":\\\"1234\\\",\\\"cpu\\\":\\\"87.5\\\",\\\"mem\\\":\\\"12.3\\\",\\\"etime\\\":\\\"01:02\\\",\\\"command\\\":\\\"java -jar app.jar\\\",\\\"state\\\":\\\"running\\\"}]"}
                """);

        AssistantOrchestrator.RunResult result = orchestrator.run("cpu 过高", McpToolSurface.FULL, RiskLevel.MEDIUM);

        assertTrue(result.markdown().contains("java -jar app.jar"));
        assertTrue(result.markdown().contains("1234"));
        assertTrue(result.markdown().contains("87.5%"));
        assertTrue(result.markdown().contains("12.3%"));
        assertFalse(result.markdown().contains("详见工具原始输出"));
        assertFalse(result.markdown().contains("Trace"));
        assertTrue(result.markdown().contains("agent.service-restart.allowlist") || result.markdown().contains("重启"));
        verify(serviceRestartTool, never()).restartService(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void patrolContinuationReturnsNoPendingWhenProposalMissing() {
        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", opsAutoRemediationService);
        when(opsIntentRouter.resolve("继续巡检")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);
        when(opsAutoRemediationService.getPendingProposalView()).thenReturn(Map.of("hasPending", false));

        AssistantOrchestrator.RunResult result = orchestrator.run("继续巡检", McpToolSurface.FULL, RiskLevel.LOW);

        assertEquals("当前没有待执行方案。", result.markdown());
        assertFalse(result.markdown().contains("Trace"));
        verify(opsAutoRemediationService, never()).confirmPendingFromAssistant(anyString());
    }

    @Test
    void patrolContinuationExecutesPendingProposal() {
        ReflectionTestUtils.setField(orchestrator, "opsAutoRemediationService", opsAutoRemediationService);
        when(opsIntentRouter.resolve("继续处理巡检待办，确认执行")).thenReturn(OpsIntentRouter.Playbook.PATROL_CONTINUATION);
        when(opsIntentRouter.forceRemediate("继续处理巡检待办，确认执行")).thenReturn(true);
        when(opsAutoRemediationService.getPendingProposalView()).thenReturn(Map.of(
                "hasPending", true,
                "proposalId", "p-1",
                "summary", "restart nginx"));
        when(opsAutoRemediationService.confirmPendingFromAssistant("p-1")).thenReturn(Map.of(
                "success", true,
                "resultSummary", "已执行巡检修复动作 1 个，成功 1 个。服务重启 `nginx` 执行成功。",
                "actions", List.of(Map.of("tool", "ServiceRestartTool", "path", "nginx", "success", true))));

        AssistantOrchestrator.RunResult result = orchestrator.run("继续处理巡检待办，确认执行", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.markdown().contains("p-1"));
        assertTrue(result.markdown().contains("服务重启 `nginx` 执行成功"));
        assertFalse(result.markdown().contains("```json"));
        verify(opsAutoRemediationService).confirmPendingFromAssistant("p-1");
    }
}
