package com.award.log.agent.awm;

import java.util.Locale;
import java.util.Set;

/**
 * Centralizes the MCP tool subset that AWM is allowed to induce and replay.
 */
public final class AwmToolProfile {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "DiskTool",
            "DiskAnalyzeTool",
            "SystemLoadTool",
            "ProcessTool",
            "ProcessOpsTool",
            "SystemdTool",
            "ServiceOpsTool",
            "NetworkTool",
            "PortHealthTool",
            "ConfigCheckTool",
            "ConfigDriftTool",
            "FirewallTool",
            "DockerTool",
            "ContainerOpsTool",
            "LogAnalysisTool",
            "SslCertTool",
            "CronJobTool",
            "OsInsightTool",
            "PrivilegeTool",
            "LogOpsTool",
            "DiskOpsTool"
    );

    private static final Set<String> WRITE_TOOLS = Set.of(
            "CleanTempTool",
            "LogCleanupTool",
            "ServiceRestartTool"
    );

    /** LLM 归纳 workflow 时禁止自动复用的敏感只读工具（仍可经 MCP 手动调用） */
    private static final Set<String> INDUCTION_DENIED = Set.of(
            "PrivilegeTool"
    );

    private AwmToolProfile() {
    }

    public static boolean isSupported(String toolName) {
        String normalized = normalize(toolName);
        return READ_ONLY_TOOLS.contains(normalized) || WRITE_TOOLS.contains(normalized);
    }

    public static boolean isInductionAllowed(String toolName) {
        String normalized = normalize(toolName);
        return isSupported(normalized) && !INDUCTION_DENIED.contains(normalized);
    }

    public static boolean isReadOnly(String toolName) {
        return READ_ONLY_TOOLS.contains(normalize(toolName));
    }

    public static boolean isWrite(String toolName) {
        return WRITE_TOOLS.contains(normalize(toolName));
    }

    public static Set<String> supportedTools() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(READ_ONLY_TOOLS);
        out.addAll(WRITE_TOOLS);
        return java.util.Collections.unmodifiableSet(out);
    }

    public static String normalize(String toolName) {
        if (toolName == null) {
            return "";
        }
        String trimmed = toolName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return switch (trimmed) {
            case "CleanTemp" -> "CleanTempTool";
            case "LogCleanup" -> "LogCleanupTool";
            case "ServiceRestart" -> "ServiceRestartTool";
            case "Process", "ProcessOps" -> "ProcessTool";
            case "SystemLoad" -> "SystemLoadTool";
            case "Systemd", "ServiceOps" -> "SystemdTool";
            case "DiskAnalyze" -> "DiskAnalyzeTool";
            case "Disk", "DiskOps" -> "DiskTool";
            case "Network" -> "NetworkTool";
            case "PortHealth" -> "PortHealthTool";
            case "ConfigCheck" -> "ConfigCheckTool";
            case "ConfigDrift" -> "ConfigDriftTool";
            case "Firewall" -> "FirewallTool";
            case "Docker" -> "DockerTool";
            case "ContainerOps" -> "ContainerOpsTool";
            case "LogAnalysis" -> "LogAnalysisTool";
            case "SslCert" -> "SslCertTool";
            case "CronJob" -> "CronJobTool";
            case "OsInsight" -> "OsInsightTool";
            case "Privilege" -> "PrivilegeTool";
            case "LogOps" -> "LogOpsTool";
            default -> trimmed.endsWith("Tool") ? trimmed : capitalize(trimmed) + "Tool";
        };
    }

    private static String capitalize(String raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        return raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
    }
}
