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
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.signal.SecuritySignalService;
import com.award.log.security.OpsTrustTier;
import com.award.log.security.RiskLevel;
import com.award.log.util.TestRuntimePlatform;
import com.award.log.util.TestTimeSource;
import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.Callable;

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
class AutonomousOpsOrchestratorDeepTest {

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
                new TestTimeSource(2_500L),
                new TestTraceIdGenerator("trace-auto-deep"));
        ReflectionTestUtils.setField(orchestrator, "enabled", true);
        ReflectionTestUtils.setField(orchestrator, "diskPressurePercent", 80.0);
        ReflectionTestUtils.setField(orchestrator, "cpuWarnPercent", 85.0);
        ReflectionTestUtils.setField(orchestrator, "memWarnPercent", 88.0);
        ReflectionTestUtils.setField(orchestrator, "logCleanDays", 30);
        ReflectionTestUtils.setField(orchestrator, "tempCleanDays", 7);
        ReflectionTestUtils.setField(orchestrator, "healthCheckHost", "127.0.0.1");

        lenient().when(auditRecorder.newSteps()).thenReturn(new ArrayList<>());
        lenient().when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", false));
        lenient().when(securitySignalService.summary(3_600_000L)).thenReturn(Map.of("hasThreat", false));
        lenient().when(securitySignalService.hasThreatSignals(3_600_000L)).thenReturn(false);
        stubHealthyPerception();
    }

    @AfterEach
    void clearSecurityContext() {
        OpsSecurityContext.clear();
    }

    @Test
    void parseHealthPortsChatPreviewAndPlanHelpersCoverFallbacks() {
        ReflectionTestUtils.setField(orchestrator, "healthCheckPortsRaw", "abc,0,65536");
        @SuppressWarnings("unchecked")
        List<Integer> fallbackPorts = (List<Integer>) ReflectionTestUtils.invokeMethod(orchestrator, "parseHealthPorts");
        assertEquals(List.of(8080), fallbackPorts);

        ReflectionTestUtils.setField(orchestrator, "healthCheckPortsRaw", "8081, 9090");
        @SuppressWarnings("unchecked")
        List<Integer> parsedPorts = (List<Integer>) ReflectionTestUtils.invokeMethod(orchestrator, "parseHealthPorts");
        assertEquals(List.of(8081, 9090), parsedPorts);

        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                AutonomousOpsOrchestrator.class, "chatPreviewOnly")));
        OpsSecurityContext.openChatAgent("trace", "msg", McpToolSurface.FULL, false);
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                AutonomousOpsOrchestrator.class, "chatPreviewOnly")));
        OpsSecurityContext.clear();
        OpsSecurityContext.openChatAgent("trace", "msg", McpToolSurface.FULL, true);
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(
                AutonomousOpsOrchestrator.class, "chatPreviewOnly")));
        OpsSecurityContext.clear();

        Object finding = newFinding("HIGH", "Disk pressure", "disk detail", "DISK_PRESSURE");
        String action = ReflectionTestUtils.invokeMethod(AutonomousOpsOrchestrator.class, "planActionFor", finding);
        assertTrue(action != null && !action.isBlank());
        assertEquals((Object) null,
                ReflectionTestUtils.invokeMethod(AutonomousOpsOrchestrator.class, "planActionFor", new Object[]{null}));

        String plan = ReflectionTestUtils.invokeMethod(orchestrator, "buildRemediationPlan", List.of(finding));
        assertTrue(plan.contains("Disk pressure"));
    }

    @Test
    void buildPerceptionViewAddsSecurityThreatFindingWhenSignalsActive() {
        when(securitySignalService.summary()).thenReturn(Map.of("hasThreat", true, "criticalCount", 1));
        when(securitySignalService.hasThreatSignals(3_600_000L)).thenReturn(true);
        when(securitySignalService.summary(3_600_000L))
                .thenReturn(Map.of("hasThreat", true, "criticalCount", 1, "highOrAboveCount", 2, "blockedCount", 1));
        when(securitySignalService.buildThreatSummaryText(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("security threat summary");

        Map<String, Object> view = orchestrator.buildPerceptionView(false, McpToolSurface.READ_ONLY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) view.get("findings");
        assertTrue(findings.stream().anyMatch(f -> "SECURITY_THREAT".equals(f.get("kind"))));
    }

    @Test
    void buildAwmVariablesCoversDiskFailedUnitUserMessageAllowlistAndNone() {
        Object diskPerception = newPerceptionSnapshot(List.of("nginx"), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> diskVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildAwmVariables", "disk", "cleanup", true, diskPerception);
        assertEquals("/var/log", diskVars.get("log-path"));
        assertEquals("/tmp", diskVars.get("temp-path"));
        assertEquals(0, diskVars.get("temp-days"));

        when(serviceRestartCandidateResolver.pickFromFailedUnits(anyList())).thenReturn("nginx");
        @SuppressWarnings("unchecked")
        Map<String, Object> failedUnitVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildAwmVariables", "service", "restart service", false, diskPerception);
        assertEquals("nginx", failedUnitVars.get("service-name"));

        when(serviceRestartCandidateResolver.pickFromFailedUnits(anyList())).thenReturn(null);
        when(serviceRestartCandidateResolver.pickFromUserMessage("restart redis")).thenReturn("redis");
        Object noFailedPerception = newPerceptionSnapshot(List.of(), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> userVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildAwmVariables", "service", "restart redis", false, noFailedPerception);
        assertEquals("redis", userVars.get("service-name"));

        when(serviceRestartCandidateResolver.pickFromUserMessage("restart cache")).thenReturn(null);
        when(serviceRestartCandidateResolver.pickDefaultFromAllowlist()).thenReturn("mysql");
        @SuppressWarnings("unchecked")
        Map<String, Object> allowlistVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildAwmVariables", "service", "restart cache", false, noFailedPerception);
        assertEquals("mysql", allowlistVars.get("service-name"));

        when(serviceRestartCandidateResolver.pickDefaultFromAllowlist()).thenReturn(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> noneVars = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                orchestrator, "buildAwmVariables", "service", "restart cache", false, noFailedPerception);
        assertFalse(noneVars.containsKey("service-name"));
        assertEquals(Boolean.TRUE, noneVars.get("dryRun"));
    }

    @Test
    void appendAwmGuidanceSkipsForNoFindingsOrNoWorkflowAndAppendsWhenPresent() {
        StringBuilder emptyMd = new StringBuilder();
        ReflectionTestUtils.invokeMethod(orchestrator, "appendAwmGuidance",
                emptyMd, new ArrayList<Map<String, Object>>(), List.of(), "ops");
        assertEquals("", emptyMd.toString());

        Object infoFinding = newFinding("INFO", "port", "detail", "PORT_DOWN");
        StringBuilder infoMd = new StringBuilder();
        ReflectionTestUtils.invokeMethod(orchestrator, "appendAwmGuidance",
                infoMd, new ArrayList<Map<String, Object>>(), List.of(infoFinding), "ops");
        assertEquals("", infoMd.toString());

        Object diskFinding = newFinding("HIGH", "Disk pressure", "detail", "DISK_PRESSURE");
        when(workflowRetriever.retrieve("disk", List.of("DISK_PRESSURE"), "ops", 2)).thenReturn(List.of());
        StringBuilder noneMd = new StringBuilder();
        ReflectionTestUtils.invokeMethod(orchestrator, "appendAwmGuidance",
                noneMd, new ArrayList<Map<String, Object>>(), List.of(diskFinding), "ops");
        assertEquals("", noneMd.toString());

        OpsWorkflow workflow = new OpsWorkflow("wf-1", "Disk rescue", List.of("DISK_PRESSURE"),
                "disk", "disk", List.of(), "seed", null, 0, true);
        when(workflowRetriever.retrieve("disk", List.of("DISK_PRESSURE"), "ops", 2)).thenReturn(List.of(workflow));
        StringBuilder awmMd = new StringBuilder();
        ReflectionTestUtils.invokeMethod(orchestrator, "appendAwmGuidance",
                awmMd, new ArrayList<Map<String, Object>>(), List.of(diskFinding), "ops");
        assertTrue(awmMd.toString().contains("wf-1"));
    }

    @Test
    void remediateTempCleanCoversScanFailEmptyPreviewAndBlockedExecution() throws Exception {
        when(cleanTempTool.scanAllTempJunk(7)).thenReturn("{\"success\":false,\"error\":\"scan failed\"}");
        Object scanFail = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateTempClean", false, new ArrayList<Map<String, Object>>());
        assertTrue(remediationMessage(scanFail).contains("scan failed"));

        when(cleanTempTool.scanAllTempJunk(7)).thenReturn("{\"success\":true,\"data\":{\"totalFilesFound\":0}}");
        Object none = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateTempClean", false, new ArrayList<Map<String, Object>>());
        assertTrue(remediationMessage(none).contains("Temp"));

        when(cleanTempTool.scanAllTempJunk(7)).thenReturn("""
                {"success":true,"data":{"totalFilesFound":4,"locations":[{"path":"/tmp/a","filesFound":4}]}}
                """);
        Object preview = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateTempClean", false, new ArrayList<Map<String, Object>>());
        assertTrue(remediationPreviewed(preview));

        when(cleanTempTool.scanAllTempJunk(0)).thenReturn("""
                {"success":true,"data":{"totalFilesFound":4,"locations":[{"path":"/tmp/a","filesFound":4}]}}
                """);
        when(opsRemediationGate.decideTempCleanup(eq("/tmp/a"), eq(0), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "manual"));
        Object blocked = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateTempClean", true, new ArrayList<Map<String, Object>>());
        assertTrue(remediationSkipped(blocked));
        verify(cleanTempTool, never()).cleanTempFiles("/tmp/a", 0, false, true);
    }

    @Test
    void remediateLogCleanAndServiceRestartCoverAllThreeDecisionStates() throws Exception {
        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, true, false))
                .thenReturn("{\"success\":true,\"data\":{\"filesFound\":2}}");
        Object logPreview = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateLogClean", false, new ArrayList<Map<String, Object>>());
        assertTrue(remediationPreviewed(logPreview));

        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.FORBIDDEN,
                        GovernanceAdmissionVerdict.FORBIDDEN,
                        OpsTrustTier.BLOCK,
                        "blocked"));
        Object logBlocked = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateLogClean", true, new ArrayList<Map<String, Object>>());
        assertTrue(remediationSkipped(logBlocked));

        when(opsRemediationGate.decideLogCleanup(anyString(), anyInt(), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute"));
        when(logCleanupTool.cleanupOldLogs("/var/log", 30, false, true))
                .thenReturn("{\"success\":true,\"data\":{\"filesDeleted\":3}}");
        Object logExecuted = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateLogClean", true, new ArrayList<Map<String, Object>>());
        assertTrue(remediationExecuted(logExecuted));

        when(opsRemediationGate.decideServiceRestart(eq("nginx"), eq(false), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.PREVIEW,
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        OpsTrustTier.APPROVE,
                        "preview"));
        when(serviceRestartTool.restartService("nginx", true, false))
                .thenReturn("{\"success\":true,\"data\":{\"service\":\"nginx\"}}");
        Object restartPreview = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateServiceRestart", "nginx", false, new ArrayList<Map<String, Object>>());
        assertTrue(remediationPreviewed(restartPreview));

        when(opsRemediationGate.decideServiceRestart(eq("nginx"), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.FORBIDDEN,
                        GovernanceAdmissionVerdict.FORBIDDEN,
                        OpsTrustTier.BLOCK,
                        "blocked"));
        Object restartBlocked = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateServiceRestart", "nginx", true, new ArrayList<Map<String, Object>>());
        assertTrue(remediationSkipped(restartBlocked));

        when(opsRemediationGate.decideServiceRestart(eq("nginx"), eq(true), anyString()))
                .thenReturn(new OpsRemediationGate.RemediationDecision(
                        OpsRemediationGate.WriteDecision.EXECUTE,
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        OpsTrustTier.AUTO,
                        "execute"));
        when(serviceRestartTool.restartService("nginx", false, true))
                .thenReturn("{\"success\":true,\"data\":{\"service\":\"nginx\"}}");
        Object restartExecuted = ReflectionTestUtils.invokeMethod(
                orchestrator, "remediateServiceRestart", "nginx", true, new ArrayList<Map<String, Object>>());
        assertTrue(remediationExecuted(restartExecuted));
    }

    @Test
    void safeCallAndRunHonorExceptionAndConfirmedWriteOverride() throws Exception {
        String safe = ReflectionTestUtils.invokeMethod(orchestrator, "safeCall",
                (Callable<String>) () -> { throw new IllegalStateException("boom"); },
                "DiskTool", new ArrayList<Map<String, Object>>());
        assertTrue(safe.contains("\"success\":false"));

        OpsSecurityContext.openChatAgent("trace", "autonomous ops", McpToolSurface.FULL, true);
        when(opsIntentRouter.forceRemediate("autonomous ops")).thenReturn(false);

        AutonomousOpsOrchestrator.RunResult result =
                orchestrator.run("autonomous ops", McpToolSurface.FULL, RiskLevel.LOW);

        assertTrue(result.traceId() != null && result.traceId().contains("trace"));
        assertTrue(result.markdown().contains("### Remediation"));
    }

    private Object newFinding(String severity, String title, String detail, String kind) {
        try {
            Class<?> type = Class.forName("com.award.log.agent.AutonomousOpsOrchestrator$Finding");
            var ctor = type.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(severity, title, detail, kind);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object newPerceptionSnapshot(List<String> failedUnits, Map<String, String> raw) {
        try {
            Class<?> type = Class.forName("com.award.log.agent.AutonomousOpsOrchestrator$PerceptionSnapshot");
            var ctor = type.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(
                    90.0,
                    88.0,
                    40.0,
                    !failedUnits.isEmpty(),
                    failedUnits,
                    List.of(8080),
                    20.0,
                    false,
                    false,
                    false,
                    raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean remediationExecuted(Object outcome) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outcome, "executed"));
    }

    private static boolean remediationPreviewed(Object outcome) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outcome, "previewed"));
    }

    private static boolean remediationSkipped(Object outcome) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outcome, "skipped"));
    }

    private static String remediationMessage(Object outcome) {
        Object message = ReflectionTestUtils.invokeMethod(outcome, "message");
        return String.valueOf((Object) message);
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
        lenient().when(remediationEffectEvaluator.captureMetrics()).thenReturn(Map.of("diskUsagePct", 40.0, "cpuUsagePct", 20.0));
    }
}
