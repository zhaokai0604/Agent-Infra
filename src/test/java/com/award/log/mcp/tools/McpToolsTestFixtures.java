package com.award.log.mcp.tools;

import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.agent.OpsPatrolAutomationService;
import com.award.log.analyzer.DrainPlusParser;
import com.award.log.analyzer.LogCleaner;
import com.award.log.config.AgentOpsProperties;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.config.OpsRemediationProperties;
import com.award.log.mcp.LogSafetyClassifier;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.AiDiagnosisService;
import com.award.log.service.StatisticsService;
import com.award.log.service.impl.StatisticsServiceImpl;
import com.award.log.util.OsRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;

/**
 * Shared wiring for MCP tool bulk-coverage tests (DiskToolTest pattern).
 */
final class McpToolsTestFixtures {

    private McpToolsTestFixtures() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static OpsPathPolicy opsPathPolicy() {
        AgentOpsProperties props = new AgentOpsProperties();
        OpsPathPolicy policy = new OpsPathPolicy(props);
        ReflectionTestUtils.setField(policy, "logCollectorFilePath", "");
        policy.applyFrom(props.getPaths());
        return policy;
    }

    static MinPrivilegeExecutor minPrivilegeExecutor(ExecutorService executor) {
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        dryRun.setGlobal(false);
        return new MinPrivilegeExecutor("root", false, dryRun, executor);
    }

    static AgentOpsProperties agentOpsProperties() {
        return new AgentOpsProperties();
    }

    static StatisticsService statisticsService() {
        return new StatisticsServiceImpl();
    }

    static LogSafetyClassifier logSafetyClassifier(OpsPathPolicy policy, AgentOpsProperties props) {
        return new LogSafetyClassifier(policy, props);
    }

    static String safeTempRoot() {
        return OsRuntime.isWindows() ? "C:/Temp" : "/tmp";
    }

    static String safeLogRoot() {
        return OsRuntime.isWindows() ? "C:/Windows/Logs" : "/var/log";
    }

    static String safeConfigPath() {
        return OsRuntime.isWindows() ? "C:/ProgramData/test.conf" : "/etc/nginx/nginx.conf";
    }

    static DiskTool diskTool(ExecutorService executor) {
        return new DiskTool(executor, objectMapper(), minPrivilegeExecutor(executor), opsPathPolicy());
    }

    static CleanTempTool cleanTempTool(ExecutorService executor) {
        return new CleanTempTool(executor, objectMapper(), minPrivilegeExecutor(executor), opsPathPolicy(),
                new OpsRemediationProperties());
    }

    static DiskAnalyzeTool diskAnalyzeTool(ExecutorService executor) {
        return new DiskAnalyzeTool(diskTool(executor), objectMapper(), opsPathPolicy());
    }

    static DiskOpsTool diskOpsTool(ExecutorService executor) {
        return new DiskOpsTool(objectMapper(), diskTool(executor), diskAnalyzeTool(executor), cleanTempTool(executor));
    }

    static ServiceRestartTool serviceRestartTool(ExecutorService executor) {
        return new ServiceRestartTool(executor, objectMapper(), minPrivilegeExecutor(executor), agentOpsProperties());
    }

    static SystemdTool systemdTool(ExecutorService executor) {
        return new SystemdTool(executor, objectMapper(), minPrivilegeExecutor(executor), serviceRestartTool(executor));
    }

    static ServiceOpsTool serviceOpsTool(ExecutorService executor) {
        return new ServiceOpsTool(objectMapper(), systemdTool(executor), serviceRestartTool(executor));
    }

    static ProcessTool processTool(ExecutorService executor) {
        return new ProcessTool(executor, objectMapper(), minPrivilegeExecutor(executor));
    }

    static ProcessOpsTool processOpsTool(ExecutorService executor) {
        return new ProcessOpsTool(processTool(executor));
    }

    static LogCleanupTool logCleanupTool(ExecutorService executor) {
        OpsPathPolicy policy = opsPathPolicy();
        return new LogCleanupTool(executor, objectMapper(), minPrivilegeExecutor(executor),
                policy, logSafetyClassifier(policy, agentOpsProperties()), new OpsRemediationProperties());
    }

    static LogAnalysisTool logAnalysisTool(ExecutorService executor, AiDiagnosisService aiDiagnosisService) {
        OpsPathPolicy policy = opsPathPolicy();
        return new LogAnalysisTool(executor, objectMapper(), new DrainPlusParser(8, 50),
                aiDiagnosisService, minPrivilegeExecutor(executor), policy,
                new LogCleaner(), new DrainTemplateNoveltyTracker());
    }

    static LogOpsTool logOpsTool(ExecutorService executor, AiDiagnosisService aiDiagnosisService) {
        return new LogOpsTool(objectMapper(), logAnalysisTool(executor, aiDiagnosisService), logCleanupTool(executor));
    }

    static DockerTool dockerTool(ExecutorService executor) {
        return new DockerTool(executor, objectMapper(), minPrivilegeExecutor(executor));
    }

    static ContainerOpsTool containerOpsTool(ExecutorService executor) {
        return new ContainerOpsTool(dockerTool(executor));
    }

    static AutonomousOpsTool autonomousOpsTool(OpsPatrolAutomationService patrolAutomationService) {
        return new AutonomousOpsTool(patrolAutomationService, objectMapper());
    }
}
