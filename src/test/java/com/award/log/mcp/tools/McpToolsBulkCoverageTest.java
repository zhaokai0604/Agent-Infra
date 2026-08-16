package com.award.log.mcp.tools;

import com.award.log.agent.OpsPatrolAutomationService;
import com.award.log.agent.OpsRunResult;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.AiDiagnosisService;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Bulk coverage for all {@code @Component} MCP tools under {@code com.award.log.mcp.tools}.
 * Follows {@link DiskToolTest}: ObjectMapper, MinPrivilegeExecutor, OpsPathPolicy, ExecutorService.
 */
@ExtendWith(MockitoExtension.class)
class McpToolsBulkCoverageTest {

    @Mock
    private AiDiagnosisService aiDiagnosisService;

    @Mock
    private OpsPatrolAutomationService patrolAutomationService;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private static void assertJsonLike(String result) {
        assertNotNull(result);
        assertTrue(result.startsWith("{") || result.startsWith("["),
                "Expected JSON-like result, got: " + result);
    }

    @Test
    void diskTool_checkDiskUsage() throws Exception {
        String result = McpToolsTestFixtures.diskTool(executorService).checkDiskUsage();
        assertJsonLike(result);
    }

    @Test
    void diskTool_rankDiskUsageUnderPath() throws Exception {
        String root = McpToolsTestFixtures.safeTempRoot();
        String result = McpToolsTestFixtures.diskTool(executorService)
                .rankDiskUsageUnderPath(root, 1, 5);
        assertJsonLike(result);
    }

    @Test
    void diskAnalyzeTool_analyzeDiskPressure() throws Exception {
        String result = McpToolsTestFixtures.diskAnalyzeTool(executorService)
                .analyzeDiskPressure(McpToolsTestFixtures.safeLogRoot(), false, 5);
        assertJsonLike(result);
    }

    @Test
    void diskOpsTool_dfGateway() throws Exception {
        String result = McpToolsTestFixtures.diskOpsTool(executorService)
                .diskOpsGateway("df", null, null, true, null, null, null, null, null);
        assertJsonLike(result);
    }

    @Test
    void diskOpsTool_cleanTempGatewayDryRun() throws Exception {
        String result = McpToolsTestFixtures.diskOpsTool(executorService)
                .diskOpsGateway("clean-temp", McpToolsTestFixtures.safeTempRoot(), 7, true, null,
                        null, null, null, null);
        assertJsonLike(result);
    }

    @Test
    void cleanTempTool_dryRunPreview() throws Exception {
        String result = McpToolsTestFixtures.cleanTempTool(executorService)
                .cleanTempFiles(McpToolsTestFixtures.safeTempRoot(), 7, true, null, null);
        assertJsonLike(result);
    }

    @Test
    void processTool_listProcesses() throws Exception {
        String result = McpToolsTestFixtures.processTool(executorService)
                .listProcesses(50.0, 50.0);
        assertJsonLike(result);
    }

    @Test
    void processTool_terminateProcessDryRun() throws Exception {
        String result = McpToolsTestFixtures.processTool(executorService)
                .terminateProcess(99999, "TERM", true, null);
        assertJsonLike(result);
    }

    @Test
    void processOpsTool_listGateway() throws Exception {
        String result = McpToolsTestFixtures.processOpsTool(executorService)
                .processOpsGateway("list", 50.0, 50.0, null, null, true, null);
        assertJsonLike(result);
    }

    @Test
    void systemLoadTool_checkSystemLoad() throws Exception {
        SystemLoadTool tool = new SystemLoadTool(
                executorService,
                new ObjectMapper(),
                minPrivilegeExecutor(),
                McpToolsTestFixtures.statisticsService());
        assertJsonLike(tool.checkSystemLoad());
    }

    @Test
    void networkTool_pingLocalhost() throws Exception {
        NetworkTool tool = new NetworkTool(executorService, new ObjectMapper(), minPrivilegeExecutor());
        assertJsonLike(tool.diagnoseNetwork("127.0.0.1", "ping", 1));
    }

    @Test
    void logCleanupTool_dryRunPreview() throws Exception {
        String result = McpToolsTestFixtures.logCleanupTool(executorService)
                .cleanupOldLogs(McpToolsTestFixtures.safeLogRoot(), 30, true, null);
        assertJsonLike(result);
    }

    @Test
    void logAnalysisTool_analyzeLogs() throws Exception {
        String logPath = OsRuntime.isWindows() ? "C:/Windows/Logs/setupapi.log" : "/var/log/syslog";
        String result = McpToolsTestFixtures.logAnalysisTool(executorService, aiDiagnosisService)
                .analyzeLogs(logPath, 10, null, null);
        assertJsonLike(result);
    }

    @Test
    void logOpsTool_analyzeGateway() throws Exception {
        String result = McpToolsTestFixtures.logOpsTool(executorService, aiDiagnosisService)
                .logOpsGateway("analyze", null, 5, null, null, null, null, true, null);
        assertJsonLike(result);
    }

    @Test
    void serviceRestartTool_dryRunPreview() throws Exception {
        String result = McpToolsTestFixtures.serviceRestartTool(executorService)
                .restartService("nginx", true, null);
        assertJsonLike(result);
    }

    @Test
    void systemdTool_listFailedUnits() throws Exception {
        String result = McpToolsTestFixtures.systemdTool(executorService).listFailedSystemdUnits();
        assertJsonLike(result);
    }

    @Test
    void systemdTool_getServiceStatus() throws Exception {
        String result = McpToolsTestFixtures.systemdTool(executorService).getServiceStatus("nginx");
        assertJsonLike(result);
    }

    @Test
    void serviceOpsTool_failedGateway() throws Exception {
        String result = McpToolsTestFixtures.serviceOpsTool(executorService)
                .serviceOpsGateway("failed", null, true, null);
        assertJsonLike(result);
    }

    @Test
    void dockerTool_listContainers() throws Exception {
        String result = McpToolsTestFixtures.dockerTool(executorService).listDockerContainers(false);
        assertJsonLike(result);
    }

    @Test
    void containerOpsTool_listGateway() throws Exception {
        String result = McpToolsTestFixtures.containerOpsTool(executorService)
                .containerOpsGateway("list", false, null, true, null, null);
        assertJsonLike(result);
    }

    @Test
    void firewallTool_checkStatus() throws Exception {
        FirewallTool tool = new FirewallTool(executorService, new ObjectMapper(), minPrivilegeExecutor());
        assertJsonLike(tool.checkFirewallStatus());
    }

    @Test
    void cronJobTool_listUserJobs() throws Exception {
        CronJobTool tool = new CronJobTool(executorService, new ObjectMapper(), minPrivilegeExecutor());
        assertJsonLike(tool.listCronJobs("user"));
    }

    @Test
    void configCheckTool_missingFileStillReturnsJson() throws Exception {
        ConfigCheckTool tool = new ConfigCheckTool(
                executorService, new ObjectMapper(), minPrivilegeExecutor(), opsPathPolicy());
        assertJsonLike(tool.checkConfig(McpToolsTestFixtures.safeConfigPath()));
    }

    @Test
    void configDriftTool_whitelistedPath() throws Exception {
        ConfigDriftTool tool = new ConfigDriftTool(opsPathPolicy(), new ObjectMapper());
        assertJsonLike(tool.checkConfigDrift(McpToolsTestFixtures.safeConfigPath()));
    }

    @Test
    void privilegeTool_checkExecutePing() throws Exception {
        PrivilegeTool tool = new PrivilegeTool(
                executorService, new ObjectMapper(), minPrivilegeExecutor(), opsPathPolicy());
        String resource = OsRuntime.isWindows() ? "ping" : "df";
        assertJsonLike(tool.checkPrivilege(resource, "execute"));
    }

    @Test
    void osInsightTool_queryJournalLogs() throws Exception {
        OsInsightTool tool = new OsInsightTool(executorService, new ObjectMapper(), minPrivilegeExecutor());
        assertJsonLike(tool.queryJournalLogs(5, 10, false));
    }

    @Test
    void osInsightTool_listListeningSockets() throws Exception {
        OsInsightTool tool = new OsInsightTool(executorService, new ObjectMapper(), minPrivilegeExecutor());
        assertJsonLike(tool.listListeningSockets());
    }

    @Test
    void portHealthTool_localhost() throws Exception {
        PortHealthTool tool = new PortHealthTool(new ObjectMapper());
        assertJsonLike(tool.checkPortConnectivity("127.0.0.1", 80, 1000));
    }

    @Test
    void sslCertTool_localhost() throws Exception {
        SslCertTool tool = new SslCertTool(new ObjectMapper());
        assertJsonLike(tool.checkSslCertificate("127.0.0.1", 443, 2000));
    }

    @Test
    void autonomousOpsTool_runPatrol() throws Exception {
        when(patrolAutomationService.run())
                .thenReturn(new OpsRunResult("## Patrol", "trace-1", Map.of("ok", true)));
        String result = McpToolsTestFixtures.autonomousOpsTool(patrolAutomationService)
                .runAutonomousOps("bulk test", false);
        assertJsonLike(result);
    }

    private MinPrivilegeExecutor minPrivilegeExecutor() {
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        dryRun.setGlobal(false);
        return new MinPrivilegeExecutor("root", false, dryRun, executorService);
    }

    private OpsPathPolicy opsPathPolicy() {
        return McpToolsTestFixtures.opsPathPolicy();
    }
}
