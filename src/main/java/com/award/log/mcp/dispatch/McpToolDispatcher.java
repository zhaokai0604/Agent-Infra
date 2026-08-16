package com.award.log.mcp.dispatch;

import com.award.log.agent.OpsPerceptionCache;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.tools.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.award.log.mcp.dispatch.McpToolParamReader.*;

/**
 * MCP HTTP 与延时任务统一工具分发（替代 Controller 巨型 switch）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolDispatcher {

    private static final Set<String> ALWAYS_INVALIDATE = Set.of("AutonomousOpsTool");

    private final OpsPerceptionCache opsPerceptionCache;
    private final McpToolCatalog mcpToolCatalog;
    private final DiskTool diskTool;
    private final ProcessTool processTool;
    private final SystemLoadTool systemLoadTool;
    private final LogAnalysisTool logAnalysisTool;
    private final CleanTempTool cleanTempTool;
    private final ConfigCheckTool configCheckTool;
    private final ConfigDriftTool configDriftTool;
    private final NetworkTool networkTool;
    private final PrivilegeTool privilegeTool;
    private final OsInsightTool osInsightTool;
    private final LogCleanupTool logCleanupTool;
    private final ServiceRestartTool serviceRestartTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final PortHealthTool portHealthTool;
    private final DockerTool dockerTool;
    private final CronJobTool cronJobTool;
    private final FirewallTool firewallTool;
    private final SslCertTool sslCertTool;
    private final SystemdTool systemdTool;
    private final AutonomousOpsTool autonomousOpsTool;
    private final DiskOpsTool diskOpsTool;
    private final LogOpsTool logOpsTool;
    private final ServiceOpsTool serviceOpsTool;
    private final ContainerOpsTool containerOpsTool;
    private final ProcessOpsTool processOpsTool;

    public McpToolDispatchResult dispatch(String toolName, Map<String, Object> parameters) {
        if (toolName == null || toolName.isBlank()) {
            return McpToolDispatchResult.fail("工具名称不能为空");
        }
        try {
            McpToolDispatchResult result = switch (toolName) {
                case "DiskTool" -> McpToolDispatchResult.ok(diskTool.checkDiskUsage());
                case "DiskInsightTool" -> McpToolDispatchResult.ok(diskTool.rankDiskUsageUnderPath(
                        getString(parameters, "rootPath"),
                        getInteger(parameters, "maxDepth"),
                        getInteger(parameters, "topN")));
                case "DiskAnalyzeTool" -> McpToolDispatchResult.ok(diskAnalyzeTool.analyzeDiskPressure(
                        getString(parameters, "rootPath"),
                        getBoolean(parameters, "includeHotspots"),
                        getInteger(parameters, "topN")));
                case "SystemLoadTool" -> McpToolDispatchResult.ok(systemLoadTool.checkSystemLoad());
                case "LogAnalysisTool" -> McpToolDispatchResult.ok(dispatchLogAnalysis(parameters));
                case "CleanTempTool" -> McpToolDispatchResult.ok(cleanTempTool.cleanTempFiles(
                        getString(parameters, "path"),
                        getInteger(parameters, "days"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmDelete"),
                        getBoolean(parameters, "removeDirectory")));
                case "LogCleanupTool" -> McpToolDispatchResult.ok(logCleanupTool.cleanupOldLogs(
                        getString(parameters, "path"),
                        getInteger(parameters, "days"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmDelete")));
                case "ServiceRestartTool" -> McpToolDispatchResult.ok(serviceRestartTool.restartService(
                        getString(parameters, "serviceName"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmRestart")));
                case "ConfigCheckTool" -> dispatchConfigCheck(parameters);
                case "ConfigDriftTool" -> McpToolDispatchResult.ok(configDriftTool.checkConfigDrift(
                        getString(parameters, "configPath")));
                case "NetworkTool" -> dispatchNetwork(parameters);
                case "PrivilegeTool" -> dispatchPrivilege(parameters);
                case "OsInsightTool" -> McpToolDispatchResult.ok(osInsightTool.executeGateway(
                        getString(parameters, "operation"),
                        getInteger(parameters, "sinceMinutes"),
                        getInteger(parameters, "maxLines"),
                        getInteger(parameters, "pid"),
                        getBoolean(parameters, "jsonOutput")));
                case "PortHealthTool" -> dispatchPortHealth(parameters);
                case "DockerTool" -> McpToolDispatchResult.ok(dockerTool.executeGateway(
                        getString(parameters, "operation"),
                        getBoolean(parameters, "includeStopped"),
                        getString(parameters, "containerName"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmRestart"),
                        getBoolean(parameters, "confirmStop")));
                case "DiskOpsTool" -> McpToolDispatchResult.ok(diskOpsTool.executeGateway(
                        getString(parameters, "operation"),
                        getString(parameters, "path"),
                        getInteger(parameters, "days"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmDelete"),
                        getString(parameters, "rootPath"),
                        getInteger(parameters, "maxDepth"),
                        getInteger(parameters, "topN"),
                        getBoolean(parameters, "includeHotspots")));
                case "LogOpsTool" -> McpToolDispatchResult.ok(logOpsTool.executeGateway(
                        getString(parameters, "operation"),
                        getString(parameters, "logPath"),
                        getInteger(parameters, "lines"),
                        getInteger(parameters, "sinceHours"),
                        getString(parameters, "keywordContains"),
                        getString(parameters, "path"),
                        getInteger(parameters, "days"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmDelete")));
                case "ServiceOpsTool" -> McpToolDispatchResult.ok(serviceOpsTool.executeGateway(
                        getString(parameters, "operation"),
                        getString(parameters, "serviceName"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmRestart")));
                case "ContainerOpsTool" -> McpToolDispatchResult.ok(containerOpsTool.executeGateway(
                        getString(parameters, "operation"),
                        getBoolean(parameters, "includeStopped"),
                        getString(parameters, "containerName"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmRestart"),
                        getBoolean(parameters, "confirmStop")));
                case "ProcessOpsTool" -> McpToolDispatchResult.ok(processOpsTool.executeGateway(
                        getString(parameters, "operation"),
                        getDouble(parameters, "minCpu"),
                        getDouble(parameters, "minMem"),
                        getInteger(parameters, "pid"),
                        getString(parameters, "signal"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmKill")));
                case "ProcessTool" -> McpToolDispatchResult.ok(processTool.executeGateway(
                        getString(parameters, "operation"),
                        getDouble(parameters, "minCpu"),
                        getDouble(parameters, "minMem"),
                        getInteger(parameters, "pid"),
                        getString(parameters, "signal"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmKill")));
                case "CronJobTool" -> McpToolDispatchResult.ok(cronJobTool.listCronJobs(
                        getString(parameters, "scope")));
                case "FirewallTool" -> McpToolDispatchResult.ok(firewallTool.checkFirewallStatus());
                case "SslCertTool" -> dispatchSslCert(parameters);
                case "SystemdTool" -> McpToolDispatchResult.ok(systemdTool.executeGateway(
                        getString(parameters, "operation"),
                        getString(parameters, "serviceName"),
                        getBoolean(parameters, "dryRun"),
                        getBoolean(parameters, "confirmRestart")));
                case "AutonomousOpsTool" -> McpToolDispatchResult.ok(autonomousOpsTool.executeGateway(
                        getString(parameters, "userIntent"),
                        getBoolean(parameters, "forceRemediate")));
                default -> {
                    log.warn("未知的 MCP 工具: {}", toolName);
                    yield McpToolDispatchResult.fail("未知的工具: " + toolName);
                }
            };
            if (result.success() && shouldInvalidatePerceptionCache(toolName, parameters, result.data())) {
                opsPerceptionCache.invalidate();
                log.debug("写操作工具 {} 执行成功，已失效感知缓存", toolName);
            }
            return result;
        } catch (JsonProcessingException e) {
            log.error("MCP 工具 JSON 异常: {}", toolName, e);
            return McpToolDispatchResult.fail("JSON处理异常: " + e.getMessage());
        } catch (Exception e) {
            log.error("MCP 工具执行异常: {}", toolName, e);
            return McpToolDispatchResult.fail("执行异常: " + e.getMessage());
        }
    }

    private boolean shouldInvalidatePerceptionCache(String toolName, Map<String, Object> parameters, String toolResultJson) {
        if (ALWAYS_INVALIDATE.contains(toolName)) {
            return true;
        }
        if (mcpToolCatalog.isReadOnlyObservation(toolName)) {
            return false;
        }
        if (!WriteToolResultSupport.requestedRealWrite(parameters)) {
            return false;
        }
        return WriteToolResultSupport.isConfirmedRealWrite(toolResultJson);
    }

    private String dispatchLogAnalysis(Map<String, Object> parameters) throws JsonProcessingException {
        String logPath = getString(parameters, "logPath");
        Integer lines = getInteger(parameters, "lines");
        Integer sinceHours = getInteger(parameters, "sinceHours");
        String keywordContains = getString(parameters, "keywordContains");
        if ((sinceHours != null && sinceHours > 0)
                || (keywordContains != null && !keywordContains.isBlank())) {
            return logAnalysisTool.analyzeLogs(logPath, lines, sinceHours, keywordContains);
        }
        return logAnalysisTool.analyzeLogs(logPath, lines);
    }

    private McpToolDispatchResult dispatchConfigCheck(Map<String, Object> parameters) throws JsonProcessingException {
        String configPath = getString(parameters, "configPath");
        if (configPath == null || configPath.isBlank()) {
            return McpToolDispatchResult.fail("配置文件路径不能为空");
        }
        return McpToolDispatchResult.ok(configCheckTool.checkConfig(configPath));
    }

    private McpToolDispatchResult dispatchNetwork(Map<String, Object> parameters) throws JsonProcessingException {
        String target = getString(parameters, "target");
        if (target == null || target.isBlank()) {
            return McpToolDispatchResult.fail("目标主机不能为空");
        }
        return McpToolDispatchResult.ok(networkTool.diagnoseNetwork(
                target, getString(parameters, "type"), getInteger(parameters, "count")));
    }

    private McpToolDispatchResult dispatchPrivilege(Map<String, Object> parameters) throws JsonProcessingException {
        String resource = getString(parameters, "resource");
        if (resource == null || resource.isBlank()) {
            return McpToolDispatchResult.fail("资源路径不能为空");
        }
        return McpToolDispatchResult.ok(privilegeTool.checkPrivilege(resource, getString(parameters, "action")));
    }

    private McpToolDispatchResult dispatchPortHealth(Map<String, Object> parameters) throws JsonProcessingException {
        String host = getString(parameters, "host");
        Integer port = getInteger(parameters, "port");
        if (host == null || host.isBlank() || port == null) {
            return McpToolDispatchResult.fail("host 与 port 为必填");
        }
        return McpToolDispatchResult.ok(portHealthTool.checkPortConnectivity(
                host, port, getInteger(parameters, "timeoutMs")));
    }

    private McpToolDispatchResult dispatchSslCert(Map<String, Object> parameters) throws JsonProcessingException {
        String host = getString(parameters, "host");
        if (host == null || host.isBlank()) {
            return McpToolDispatchResult.fail("host 为必填");
        }
        return McpToolDispatchResult.ok(sslCertTool.checkSslCertificate(
                host, getInteger(parameters, "port"), getInteger(parameters, "timeoutMs")));
    }
}
