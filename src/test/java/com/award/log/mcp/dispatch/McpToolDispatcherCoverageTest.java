package com.award.log.mcp.dispatch;

import com.award.log.agent.OpsPerceptionCache;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.tools.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolDispatcherCoverageTest {

    @Mock OpsPerceptionCache opsPerceptionCache;
    @Mock McpToolCatalog mcpToolCatalog;
    @Mock DiskTool diskTool;
    @Mock ProcessTool processTool;
    @Mock SystemLoadTool systemLoadTool;
    @Mock LogAnalysisTool logAnalysisTool;
    @Mock CleanTempTool cleanTempTool;
    @Mock ConfigCheckTool configCheckTool;
    @Mock ConfigDriftTool configDriftTool;
    @Mock NetworkTool networkTool;
    @Mock PrivilegeTool privilegeTool;
    @Mock OsInsightTool osInsightTool;
    @Mock LogCleanupTool logCleanupTool;
    @Mock ServiceRestartTool serviceRestartTool;
    @Mock DiskAnalyzeTool diskAnalyzeTool;
    @Mock PortHealthTool portHealthTool;
    @Mock DockerTool dockerTool;
    @Mock CronJobTool cronJobTool;
    @Mock FirewallTool firewallTool;
    @Mock SslCertTool sslCertTool;
    @Mock SystemdTool systemdTool;
    @Mock AutonomousOpsTool autonomousOpsTool;
    @Mock DiskOpsTool diskOpsTool;
    @Mock LogOpsTool logOpsTool;
    @Mock ServiceOpsTool serviceOpsTool;
    @Mock ContainerOpsTool containerOpsTool;
    @Mock ProcessOpsTool processOpsTool;

    private McpToolDispatcher dispatcher;

    @BeforeEach
    void setUp() throws JsonProcessingException {
        dispatcher = new McpToolDispatcher(
                opsPerceptionCache, mcpToolCatalog,
                diskTool, processTool, systemLoadTool, logAnalysisTool, cleanTempTool,
                configCheckTool, configDriftTool, networkTool, privilegeTool, osInsightTool,
                logCleanupTool, serviceRestartTool, diskAnalyzeTool, portHealthTool,
                dockerTool, cronJobTool, firewallTool, sslCertTool, systemdTool,
                autonomousOpsTool, diskOpsTool, logOpsTool, serviceOpsTool, containerOpsTool, processOpsTool);

        when(diskTool.checkDiskUsage()).thenReturn("{\"disk\":true}");
        when(diskTool.rankDiskUsageUnderPath(any(), any(), any())).thenReturn("{\"rank\":true}");
        when(diskAnalyzeTool.analyzeDiskPressure(any(), any(), any())).thenReturn("{\"analyze\":true}");
        when(systemLoadTool.checkSystemLoad()).thenReturn("{\"load\":true}");
        when(logAnalysisTool.analyzeLogs(any(), any())).thenReturn("{\"logs\":true}");
        when(logAnalysisTool.analyzeLogs(any(), any(), any(), any())).thenReturn("{\"logs\":true}");
        when(cleanTempTool.cleanTempFiles(any(), any(), any(), any(), any())).thenReturn("{\"clean\":true}");
        when(logCleanupTool.cleanupOldLogs(any(), any(), any(), any())).thenReturn("{\"cleanup\":true}");
        when(serviceRestartTool.restartService(any(), any(), any())).thenReturn("{\"restart\":true}");
        when(configCheckTool.checkConfig(any())).thenReturn("{\"config\":true}");
        when(configDriftTool.checkConfigDrift(any())).thenReturn("{\"drift\":true}");
        when(networkTool.diagnoseNetwork(any(), any(), any())).thenReturn("{\"net\":true}");
        when(privilegeTool.checkPrivilege(any(), any())).thenReturn("{\"priv\":true}");
        when(osInsightTool.executeGateway(any(), any(), any(), any(), any())).thenReturn("{\"os\":true}");
        when(portHealthTool.checkPortConnectivity(any(), any(), any())).thenReturn("{\"port\":true}");
        when(dockerTool.executeGateway(any(), any(), any(), any(), any(), any())).thenReturn("{\"docker\":true}");
        when(cronJobTool.listCronJobs(any())).thenReturn("{\"cron\":true}");
        when(firewallTool.checkFirewallStatus()).thenReturn("{\"fw\":true}");
        when(sslCertTool.checkSslCertificate(any(), any(), any())).thenReturn("{\"ssl\":true}");
        when(systemdTool.executeGateway(any(), any(), any(), any())).thenReturn("{\"systemd\":true}");
        when(autonomousOpsTool.executeGateway(any(), any())).thenReturn("{\"auto\":true}");
        when(diskOpsTool.executeGateway(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("{\"diskOps\":true}");
        when(logOpsTool.executeGateway(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("{\"logOps\":true}");
        when(serviceOpsTool.executeGateway(any(), any(), any(), any())).thenReturn("{\"svcOps\":true}");
        when(containerOpsTool.executeGateway(any(), any(), any(), any(), any(), any()))
                .thenReturn("{\"ctrOps\":true}");
        when(processOpsTool.executeGateway(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("{\"procOps\":true}");
        when(processTool.executeGateway(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("{\"proc\":true}");
    }

    @Test
    void rejectsBlankToolName() {
        McpToolDispatchResult result = dispatcher.dispatch("  ", Map.of());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("工具名称"));
    }

    @Test
    void rejectsUnknownTool() {
        McpToolDispatchResult result = dispatcher.dispatch("UnknownTool", Map.of());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("UnknownTool"));
    }

    @Test
    void dispatchesAllKnownTools() {
        Map<String, Object> params = fullParams();
        for (String tool : new String[]{
                "DiskTool", "DiskInsightTool", "DiskAnalyzeTool", "SystemLoadTool", "LogAnalysisTool",
                "CleanTempTool", "LogCleanupTool", "ServiceRestartTool", "ConfigCheckTool", "ConfigDriftTool",
                "NetworkTool", "PrivilegeTool", "OsInsightTool", "PortHealthTool", "DockerTool", "DiskOpsTool",
                "LogOpsTool", "ServiceOpsTool", "ContainerOpsTool", "ProcessOpsTool", "ProcessTool",
                "CronJobTool", "FirewallTool", "SslCertTool", "SystemdTool", "AutonomousOpsTool"
        }) {
            McpToolDispatchResult result = dispatcher.dispatch(tool, params);
            assertTrue(result.success(), "expected success for " + tool + " but got: " + result.errorMessage());
        }
    }

    @Test
    void logAnalysisUsesExtendedBranchWhenSinceHoursSet() throws Exception {
        Map<String, Object> params = Map.of(
                "logPath", "/var/log/syslog",
                "lines", 20,
                "sinceHours", 2,
                "keywordContains", "error");
        McpToolDispatchResult result = dispatcher.dispatch("LogAnalysisTool", params);
        assertTrue(result.success());
        verify(logAnalysisTool).analyzeLogs("/var/log/syslog", 20, 2, "error");
    }

    @Test
    void validationFailuresForRequiredFields() {
        assertFalse(dispatcher.dispatch("ConfigCheckTool", Map.of()).success());
        assertFalse(dispatcher.dispatch("NetworkTool", Map.of()).success());
        assertFalse(dispatcher.dispatch("PrivilegeTool", Map.of()).success());
        assertFalse(dispatcher.dispatch("PortHealthTool", Map.of("host", "127.0.0.1")).success());
        assertFalse(dispatcher.dispatch("SslCertTool", Map.of()).success());
    }

    @Test
    void invalidatesPerceptionCacheOnWriteToolSuccess() throws JsonProcessingException {
        when(mcpToolCatalog.isReadOnlyObservation("CleanTempTool")).thenReturn(false);
        when(cleanTempTool.cleanTempFiles(any(), any(), any(), any(), any()))
                .thenReturn("{\"success\":true,\"data\":{\"mode\":\"DELETE\",\"filesDeleted\":3}}");
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/tmp");
        params.put("days", 7);
        params.put("dryRun", false);
        params.put("confirmDelete", true);

        McpToolDispatchResult result = dispatcher.dispatch("CleanTempTool", params);

        assertTrue(result.success());
        verify(opsPerceptionCache).invalidate();
    }

    @Test
    void autonomousOpsAlwaysInvalidatesCache() {
        McpToolDispatchResult result = dispatcher.dispatch("AutonomousOpsTool", Map.of("dryRun", true));
        assertTrue(result.success());
        verify(opsPerceptionCache).invalidate();
    }

    @Test
    void mapsJsonProcessingException() throws Exception {
        when(diskTool.checkDiskUsage()).thenThrow(new JsonProcessingException("bad json") {});
        McpToolDispatchResult result = dispatcher.dispatch("DiskTool", Map.of());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("JSON"));
    }

    @Test
    void mapsRuntimeException() throws Exception {
        when(diskTool.checkDiskUsage()).thenThrow(new RuntimeException("boom"));
        McpToolDispatchResult result = dispatcher.dispatch("DiskTool", Map.of());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("boom"));
    }

    private static Map<String, Object> fullParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("rootPath", "/tmp");
        params.put("maxDepth", 2);
        params.put("topN", 5);
        params.put("includeHotspots", true);
        params.put("logPath", "/var/log/syslog");
        params.put("lines", 10);
        params.put("sinceHours", 1);
        params.put("keywordContains", "error");
        params.put("path", "/tmp");
        params.put("days", 7);
        params.put("dryRun", true);
        params.put("confirmDelete", false);
        params.put("removeDirectory", false);
        params.put("serviceName", "nginx");
        params.put("confirmRestart", false);
        params.put("configPath", "/etc/nginx/nginx.conf");
        params.put("target", "127.0.0.1");
        params.put("type", "ping");
        params.put("count", 1);
        params.put("resource", "ping");
        params.put("action", "execute");
        params.put("operation", "list");
        params.put("sinceMinutes", 5);
        params.put("maxLines", 20);
        params.put("pid", 1);
        params.put("jsonOutput", false);
        params.put("host", "127.0.0.1");
        params.put("port", 80);
        params.put("timeoutMs", 1000);
        params.put("includeStopped", false);
        params.put("containerName", "demo");
        params.put("confirmStop", false);
        params.put("minCpu", 1.0);
        params.put("minMem", 1.0);
        params.put("signal", "TERM");
        params.put("confirmKill", false);
        params.put("scope", "user");
        params.put("userIntent", "patrol");
        params.put("forceRemediate", false);
        return params;
    }
}
