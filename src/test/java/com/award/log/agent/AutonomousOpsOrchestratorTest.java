package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowExecutionService;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.config.SystemConfigRuntimeState;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.CronJobTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.DockerTool;
import com.award.log.mcp.tools.FirewallTool;
import com.award.log.mcp.tools.LogAnalysisTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.NetworkTool;
import com.award.log.mcp.tools.OsInsightTool;
import com.award.log.mcp.tools.PortHealthTool;
import com.award.log.mcp.tools.ProcessTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.mcp.tools.SystemdTool;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsTrustTier;
import com.award.log.security.RiskLevel;
import com.award.log.security.signal.SecuritySignalService;
import com.award.log.util.TestRuntimePlatform;
import com.award.log.util.TestTimeSource;
import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomousOpsOrchestratorTest {

    @Mock private SystemLoadTool systemLoadTool;
    @Mock private DiskTool diskTool;
    @Mock private DiskAnalyzeTool diskAnalyzeTool;
    @Mock private ProcessTool processTool;
    @Mock private SystemdTool systemdTool;
    @Mock private NetworkTool networkTool;
    @Mock private PortHealthTool portHealthTool;
    @Mock private DockerTool dockerTool;
    @Mock private FirewallTool firewallTool;
    @Mock private CronJobTool cronJobTool;
    @Mock private LogAnalysisTool logAnalysisTool;
    @Mock private OsInsightTool osInsightTool;
    @Mock private CleanTempTool cleanTempTool;
    @Mock private LogCleanupTool logCleanupTool;
    @Mock private ServiceRestartTool serviceRestartTool;
    @Mock private OpsRemediationGate opsRemediationGate;
    @Mock private ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    @Mock private AssistantAuditRecorder auditRecorder;
    @Mock private OpsIntentRouter opsIntentRouter;
    @Mock private OpsPerceptionCache opsPerceptionCache;
    @Mock private RemediationEffectEvaluator remediationEffectEvaluator;
    @Mock private WorkflowRetriever workflowRetriever;
    @Mock private WorkflowExecutionService workflowExecutionService;
    @Mock private WorkflowInductionService workflowInductionService;
    @Mock private SecuritySignalService securitySignalService;

    private AutonomousOpsOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        Environment env = new MockEnvironment()
                .withProperty("agent.autonomous.health-check-ports", "8080")
                .withProperty("agent.autonomous.ping-target", "8.8.8.8");
        SystemConfigRuntimeState runtimeState = new SystemConfigRuntimeState(env);
        runtimeState.init();

        orchestrator = new AutonomousOpsOrchestrator(
                systemLoadTool,
                diskTool,
                diskAnalyzeTool,
                processTool,
                systemdTool,
                networkTool,
                portHealthTool,
                dockerTool,
                firewallTool,
                cronJobTool,
                logAnalysisTool,
                osInsightTool,
                cleanTempTool,
                logCleanupTool,
                serviceRestartTool,
                opsRemediationGate,
                serviceRestartCandidateResolver,
                auditRecorder,
                new ObjectMapper(),
                opsIntentRouter,
                opsPerceptionCache,
                remediationEffectEvaluator,
                workflowRetriever,
                workflowExecutionService,
                workflowInductionService,
                securitySignalService,
                runtimeState,
                new TestRuntimePlatform(false),
                new TestTimeSource(2_000L),
                new TestTraceIdGenerator("trace-auto-1"));

        ReflectionTestUtils.setField(orchestrator, "enabled", true);
        ReflectionTestUtils.setField(orchestrator, "diskPressurePercent", 80.0);
        ReflectionTestUtils.setField(orchestrator, "cpuWarnPercent", 85.0);
        ReflectionTestUtils.setField(orchestrator, "memWarnPercent", 88.0);
        ReflectionTestUtils.setField(orchestrator, "logCleanDays", 30);
        ReflectionTestUtils.setField(orchestrator, "tempCleanDays", 7);
        ReflectionTestUtils.setField(orchestrator, "healthCheckHost", "127.0.0.1");

        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
        lenient().when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", false));
        stubHealthyPerception();
    }

    @Test
    void supportsOnlyWhenEnabledAndPatrolIntentMatches() {
        when(opsIntentRouter.resolve("auto")).thenReturn(OpsIntentRouter.Playbook.PATROL_AUTOMATION);
        when(opsIntentRouter.resolve("other")).thenReturn(OpsIntentRouter.Playbook.DISK_CLEANUP);

        assertTrue(orchestrator.supports("auto"));
        assertFalse(orchestrator.supports("other"));

        ReflectionTestUtils.setField(orchestrator, "enabled", false);
        assertFalse(orchestrator.supports("auto"));
    }

    @Test
    void applyHotConfigUpdatesPortsAndPingTarget() throws Exception {
        orchestrator.applyHotConfig("9090,10000", "1.1.1.1");

        when(portHealthTool.checkPortConnectivity("127.0.0.1", 9090, 3000))
                .thenReturn("{\"success\":true,\"data\":{\"reachable\":false}}");
        when(portHealthTool.checkPortConnectivity("127.0.0.1", 10000, 3000))
                .thenReturn("{\"success\":true,\"data\":{\"reachable\":true}}");
        when(networkTool.diagnoseNetwork("1.1.1.1", "ping", 3))
                .thenReturn("{\"success\":true,\"data\":{\"packetLossPercent\":0}}");

        Map<String, Object> view = orchestrator.buildPerceptionView(false, McpToolSurface.READ_ONLY);

        assertTrue(String.valueOf(view).contains("9090"));
        verify(networkTool).diagnoseNetwork("1.1.1.1", "ping", 3);
    }

    @Test
    void buildPerceptionViewReturnsCachedSnapshotWhenFresh() throws Exception {
        when(opsPerceptionCache.getIfFresh()).thenReturn(Map.of("cached", true));

        Map<String, Object> view = orchestrator.buildPerceptionView(true, McpToolSurface.READ_ONLY);

        assertEquals(Map.of("cached", true), view);
        verify(diskTool, never()).checkDiskUsage();
    }

    @Test
    void buildPerceptionViewBuildsSnapshotAndFindingsWhenCacheMisses() throws Exception {
        when(opsPerceptionCache.getIfFresh()).thenReturn(null);
        when(systemLoadTool.checkSystemLoad()).thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":91.0,\"memUsagePercent\":89.0}}");
        when(diskTool.checkDiskUsage()).thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"95%\"}]}");
        when(networkTool.diagnoseNetwork(anyString(), eq("ping"), eq(3)))
                .thenReturn("{\"success\":true,\"data\":{\"packetLossPercent\":60}}");
        when(portHealthTool.checkPortConnectivity(any(), anyInt(), anyInt()))
                .thenReturn("{\"success\":true,\"data\":{\"reachable\":false}}");
        when(systemdTool.listFailedSystemdUnits())
                .thenReturn("{\"success\":true,\"data\":{\"output\":\"nginx.service failed\"}}");
        when(logAnalysisTool.analyzeLogs("/var/log", 80))
                .thenReturn("{\"success\":true,\"data\":\"error burst\"}");
        when(osInsightTool.queryJournalLogs(30, 80, false))
                .thenReturn("{\"success\":true,\"data\":\"fatal exception\"}");
        when(dockerTool.listDockerContainers(false))
                .thenReturn("{\"success\":true,\"data\":\"exited container\"}");
        when(processTool.listProcesses(5.0, 5.0))
                .thenReturn("{\"success\":true,\"data\":\"zombie\"}");

        Map<String, Object> view = orchestrator.buildPerceptionView(true, McpToolSurface.READ_ONLY);

        assertEquals(Boolean.TRUE, view.get("readOnly"));
        assertTrue(String.valueOf(view.get("findings")).contains("DISK_PRESSURE"));
        verify(opsPerceptionCache).put(any());
    }

    @Test
    void runReadOnlyDiagnosesWithoutRemediation() throws Exception {
        when(opsIntentRouter.forceRemediate("full check")).thenReturn(false);
        when(systemLoadTool.checkSystemLoad()).thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":91.0,\"memUsagePercent\":40.0}}");
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"95%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"95%\"}]}");

        AutonomousOpsOrchestrator.RunResult result =
                orchestrator.run("full check", McpToolSurface.READ_ONLY, RiskLevel.LOW);

        assertEquals("trace-auto-1", result.traceId());
        assertTrue(result.markdown().contains("read-only"));
        assertFalse(result.markdown().contains("Trace"));
        verify(workflowExecutionService, never()).execute(any(), any());
        verify(logCleanupTool, never()).cleanupOldLogs(anyString(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void runScheduledHealthyReportSkipsRemediation() {
        AutonomousOpsOrchestrator.RunResult result = orchestrator.runScheduled(McpToolSurface.FULL, false);

        assertTrue(result.markdown().contains("Autonomous Ops Report")
                || result.markdown().contains("No remediation needed")
                || result.markdown().contains("Remediation"));
        verify(workflowExecutionService, never()).execute(any(), any());
    }

    @Test
    void runUsesHandledAwmWorkflowAndInvalidatesCacheOnExecution() throws Exception {
        when(opsIntentRouter.forceRemediate("autonomous ops")).thenReturn(true);
        when(systemLoadTool.checkSystemLoad())
                .thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":20.0,\"memUsagePercent\":40.0}}")
                .thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":10.0,\"memUsagePercent\":40.0}}");
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"96%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"70%\"}]}");
        when(remediationEffectEvaluator.captureMetrics())
                .thenReturn(Map.of("diskUsagePct", 96.0, "cpuUsagePct", 20.0))
                .thenReturn(Map.of("diskUsagePct", 70.0, "cpuUsagePct", 10.0));
        when(remediationEffectEvaluator.evaluate(any(), any(), eq(1)))
                .thenReturn(Map.of("healingScore", 88, "verdict", "effective", "diskUsageDeltaPct", 26.0, "cpuUsageDeltaPct", 10.0));
        OpsWorkflow workflow = new OpsWorkflow(
                "wf-disk-auto", "disk", List.of("DISK_PRESSURE"), "disk", "disk", List.of(), "seed", null, 0, true);
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "autonomous ops")).thenReturn(workflow);
        when(workflowExecutionService.execute(eq(workflow), any()))
                .thenReturn(new WorkflowExecutionService.WorkflowRunResult(workflow, List.of(), true, false, false, false, false));

        AutonomousOpsOrchestrator.RunResult result =
                orchestrator.run("autonomous ops", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.markdown().contains("历史处置经验"));
        assertFalse(result.markdown().contains("AWM"));
        assertFalse(result.markdown().contains("Trace"));
        verify(opsPerceptionCache).invalidate();
        verify(workflowRetriever).recordHit(workflow);
        verify(workflowInductionService).afterSuccessfulRun(eq("trace-auto-1"), eq("autonomous ops"),
                eq("REMEDIATED"), eq(true), anyList(), eq("disk"), eq(List.of("DISK_PRESSURE")));
    }

    @Test
    void runFallsBackToLogCleanupWhenAwmMissesDiskPressure() throws Exception {
        when(opsIntentRouter.forceRemediate("autonomous ops")).thenReturn(true);
        when(systemLoadTool.checkSystemLoad())
                .thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":20.0,\"memUsagePercent\":40.0}}")
                .thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":20.0,\"memUsagePercent\":40.0}}");
        when(diskTool.checkDiskUsage())
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"92%\"}]}")
                .thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"80%\"}]}");
        when(remediationEffectEvaluator.captureMetrics())
                .thenReturn(Map.of("diskUsagePct", 92.0, "cpuUsagePct", 20.0))
                .thenReturn(Map.of("diskUsagePct", 80.0, "cpuUsagePct", 20.0));
        when(remediationEffectEvaluator.evaluate(any(), any(), eq(1)))
                .thenReturn(Map.of("healingScore", 70, "verdict", "effective", "diskUsageDeltaPct", 12.0, "cpuUsageDeltaPct", 0.0));
        when(workflowRetriever.bestMatch("disk", List.of("DISK_PRESSURE"), "autonomous ops")).thenReturn(null);
        when(cleanTempTool.scanAllTempJunk(0))
                .thenReturn("{\"success\":true,\"data\":{\"totalFilesFound\":0}}");
        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "cleanup"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":3}}");
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, false, true))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":2}}");

        AutonomousOpsOrchestrator.RunResult result =
                orchestrator.run("autonomous ops", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.markdown().contains("Log cleanup") || result.markdown().contains("deleted"));
        verify(logCleanupTool).cleanupOldLogs("/var/log", 30, false, true);
    }

    @Test
    void runFallsBackToServiceRestartForFailedService() throws Exception {
        when(opsIntentRouter.forceRemediate("service failure")).thenReturn(true);
        when(systemdTool.listFailedSystemdUnits())
                .thenReturn("{\"success\":true,\"data\":{\"output\":\"nginx.service failed\"}}");
        when(serviceRestartCandidateResolver.pickFromFailedUnits(anyList())).thenReturn("nginx");
        when(opsRemediationGate.decideServiceRestart(eq("nginx"), anyBoolean(), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "restart"));
        when(serviceRestartTool.restartService("nginx", false, true))
                .thenReturn("{\"success\":true,\"data\":{\"service\":\"nginx\"}}");
        when(remediationEffectEvaluator.captureMetrics())
                .thenReturn(Map.of("diskUsagePct", 40.0, "cpuUsagePct", 20.0))
                .thenReturn(Map.of("diskUsagePct", 40.0, "cpuUsagePct", 20.0));
        when(remediationEffectEvaluator.evaluate(any(), any(), eq(1)))
                .thenReturn(Map.of("healingScore", 60, "verdict", "partial", "diskUsageDeltaPct", 0.0, "cpuUsageDeltaPct", 0.0));

        AutonomousOpsOrchestrator.RunResult result =
                orchestrator.run("service failure", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.markdown().contains("nginx"));
        verify(serviceRestartTool).restartService("nginx", false, true);
    }

    private void stubHealthyPerception() throws Exception {
        lenient().when(systemLoadTool.checkSystemLoad()).thenReturn("{\"success\":true,\"data\":{\"cpuUsagePercent\":20.0,\"memUsagePercent\":40.0}}");
        lenient().when(diskTool.checkDiskUsage()).thenReturn("{\"success\":true,\"data\":[{\"usePercent\":\"40%\"}]}");
        lenient().when(diskAnalyzeTool.analyzeDiskPressure("/var/log", true, 10)).thenReturn("{\"success\":true,\"data\":{}}");
        lenient().when(processTool.listProcesses(5.0, 5.0)).thenReturn("{\"success\":true,\"data\":[]}");
        lenient().when(systemdTool.listFailedSystemdUnits()).thenReturn("{\"success\":true,\"data\":{\"output\":\"0 loaded units listed.\"}}");
        lenient().when(networkTool.diagnoseNetwork(anyString(), eq("ping"), eq(3)))
                .thenReturn("{\"success\":true,\"data\":{\"packetLossPercent\":0}}");
        lenient().when(portHealthTool.checkPortConnectivity(any(), anyInt(), anyInt()))
                .thenReturn("{\"success\":true,\"data\":{\"reachable\":true}}");
        lenient().when(dockerTool.listDockerContainers(false)).thenReturn("{\"success\":true,\"data\":[]}");
        lenient().when(firewallTool.checkFirewallStatus()).thenReturn("{\"success\":true,\"data\":{}}");
        lenient().when(cronJobTool.listCronJobs("user")).thenReturn("{\"success\":true,\"data\":[]}");
        lenient().when(osInsightTool.queryJournalLogs(30, 80, false)).thenReturn("{\"success\":true,\"data\":[]}");
        lenient().when(logAnalysisTool.analyzeLogs("/var/log", 80)).thenReturn("{\"success\":true,\"data\":[]}");
    }
}
