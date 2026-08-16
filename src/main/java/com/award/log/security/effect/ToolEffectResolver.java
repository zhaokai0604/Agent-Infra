package com.award.log.security.effect;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 将工具名 + 参数归一为 {@link ToolEffect}。
 */
@Component
public class ToolEffectResolver {

    public ToolEffect resolve(String toolName, Map<String, Object> parameters) {
        String tool = toolName == null ? "" : toolName.trim();
        String path = firstString(parameters, "path", "rootPath", "logPath", "targetPath");
        String service = firstString(parameters, "serviceName", "service", "unit");
        String pid = firstString(parameters, "pid", "processId");

        return switch (tool) {
            case "CleanTempTool", "DiskOpsTool" -> writePath(EffectAction.DELETE, path, "DELETE_PATH");
            case "LogCleanupTool", "LogOpsTool" -> writePath(
                    looksLikeTruncate(parameters) ? EffectAction.TRUNCATE : EffectAction.DELETE,
                    path,
                    "DELETE_PATH");
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" -> new ToolEffect(
                    EffectAction.RESTART, "SERVICE", service, 7, true, "RESTART_SERVICE");
            case "ProcessTool" -> new ToolEffect(
                    EffectAction.KILL, "PROCESS", pid.isBlank() ? firstString(parameters, "name") : pid,
                    6, true, "KILL_PROCESS");
            case "DockerTool" -> new ToolEffect(
                    EffectAction.KILL, "CONTAINER", firstString(parameters, "containerName", "name"),
                    6, true, "KILL_PROCESS");
            case "FirewallTool", "ConfigCheckTool", "ConfigDriftTool" -> new ToolEffect(
                    EffectAction.MUTATE_CONFIG, "CONFIG", path.isBlank() ? service : path,
                    5, true, "MUTATE_CONFIG");
            case "DiskTool", "DiskAnalyzeTool", "SystemLoadTool", "PortHealthTool",
                 "LogAnalysisTool", "SslCertTool", "PrivilegeTool" ->
                    ToolEffect.observe(path.isBlank() ? "HOST" : "PATH", path.isBlank() ? "local" : path);
            default -> inferDefault(tool, path, service, pid);
        };
    }

    private static ToolEffect writePath(EffectAction action, String path, String contractId) {
        return new ToolEffect(action, "PATH", path, action == EffectAction.TRUNCATE ? 5 : 6, true, contractId);
    }

    private static ToolEffect inferDefault(String tool, String path, String service, String pid) {
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.contains("clean") || lower.contains("delete") || lower.contains("remove")) {
            return writePath(EffectAction.DELETE, path, "DELETE_PATH");
        }
        if (lower.contains("restart")) {
            return new ToolEffect(EffectAction.RESTART, "SERVICE", service, 7, true, "RESTART_SERVICE");
        }
        if (lower.contains("kill") || lower.contains("stop")) {
            return new ToolEffect(EffectAction.KILL, "PROCESS", pid, 6, true, "KILL_PROCESS");
        }
        if (!path.isBlank()) {
            return ToolEffect.observe("PATH", path);
        }
        return new ToolEffect(EffectAction.UNKNOWN_WRITE, "UNKNOWN", tool, 4, true, "");
    }

    private static boolean looksLikeTruncate(Map<String, Object> parameters) {
        String op = firstString(parameters, "operation", "op", "action").toLowerCase(Locale.ROOT);
        return op.contains("truncat") || op.contains("裁剪") || op.contains("清空");
    }

    private static String firstString(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object v = parameters.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return "";
    }
}
