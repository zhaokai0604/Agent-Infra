package com.award.log.mcp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 与 {@link com.award.log.mcp.dispatch.McpToolDispatcher} 可调度工具名保持一致，用于 Catalog 兜底注册与安全门整体授权。
 */
public final class McpKnownTools {

    public static final Set<String> DISPATCHABLE = Collections.unmodifiableSet(new LinkedHashSet<>(Set.of(
            "DiskTool",
            "DiskInsightTool",
            "DiskAnalyzeTool",
            "SystemLoadTool",
            "LogAnalysisTool",
            "CleanTempTool",
            "LogCleanupTool",
            "ServiceRestartTool",
            "ConfigCheckTool",
            "ConfigDriftTool",
            "NetworkTool",
            "PrivilegeTool",
            "OsInsightTool",
            "PortHealthTool",
            "DockerTool",
            "DiskOpsTool",
            "LogOpsTool",
            "ServiceOpsTool",
            "ContainerOpsTool",
            "ProcessOpsTool",
            "ProcessTool",
            "CronJobTool",
            "FirewallTool",
            "SslCertTool",
            "SystemdTool",
            "AutonomousOpsTool"
    )));

    private McpKnownTools() {
    }

    public static boolean isDispatchable(String toolName) {
        return toolName != null && DISPATCHABLE.contains(toolName.trim());
    }
}
