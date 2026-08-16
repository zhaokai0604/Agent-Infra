package com.award.log.security;

import com.award.log.mcp.McpToolCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agentic AI 工具调用风险评分（0–10）：综合工具类型、路径敏感度、运行身份与指令文本特征。
 * 与 {@link McpInvocationSecurityGate} 阈值配合：&lt;autoMax 自动执行倾向；[autoMax, confirmMax] 二次确认；&gt;confirmMax 拒绝。
 */
@Component
public class AgenticRiskScoreEngine {

    private final double autoMax;
    private final double confirmMax;
    private final McpToolCatalog mcpToolCatalog;

    public AgenticRiskScoreEngine(
            McpToolCatalog mcpToolCatalog,
            @Value("${agent.security.risk-score-auto-max:5}") double autoMax,
            @Value("${agent.security.risk-score-confirm-max:9.5}") double confirmMax) {
        this.mcpToolCatalog = mcpToolCatalog;
        this.autoMax = clamp(autoMax, 0, 10);
        this.confirmMax = clamp(confirmMax, this.autoMax, 10);
    }

    public double getAutoMax() {
        return autoMax;
    }

    public double getConfirmMax() {
        return confirmMax;
    }

    /**
     * @param instruction {@link McpInvocationSecurityGate#buildInstruction} 产物
     */
    public ScoreResult score(String toolName, Map<String, Object> parameters, String instruction) {
        Map<String, Double> dims = new LinkedHashMap<>();
        double total = 0;

        double toolBase = mcpToolCatalog.defaultRiskScore(toolName);
        dims.put("toolBase", round1(toolBase));
        total += toolBase;

        String haystack = (instruction == null ? "" : instruction).toLowerCase(Locale.ROOT);
        String structuredPaths = extractStructuredPathHaystack(parameters);
        if (parameters != null) {
            for (Object v : parameters.values()) {
                if (v != null) {
                    haystack += " " + v.toString().toLowerCase(Locale.ROOT);
                }
            }
        }

        // 路径敏感度优先看结构化路径字段，降低整段 instruction 误伤
        double pathFromStructured = pathSensitivityScore(structuredPaths);
        double pathFromText = pathSensitivityScore(haystack);
        double pathRisk = structuredPaths.isBlank()
                ? pathFromText
                : Math.max(pathFromStructured, pathFromText * 0.35);
        dims.put("pathSensitivity", round1(pathRisk));
        total += pathRisk;

        // 命令模式主要看用户指令与显式 command 参数，避免工具名拼接串误抬升
        String commandHaystack = (instruction == null ? "" : instruction).toLowerCase(Locale.ROOT)
                + " " + firstParam(parameters, "command", "cmd", "shell");
        double cmdRisk = commandPatternScore(commandHaystack);
        dims.put("commandPattern", round1(cmdRisk));
        total += cmdRisk;

        double privRisk = privilegeContextScore();
        dims.put("runtimePrivilege", round1(privRisk));
        total += privRisk;

        total = clamp(round1(total), 0, 10);
        String explanation = buildExplanation(toolName, total, dims);
        return new ScoreResult(total, Map.copyOf(dims), explanation);
    }

    private static String extractStructuredPathHaystack(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("path", "rootPath", "logPath", "targetPath", "file", "directory")) {
            Object v = parameters.get(key);
            if (v != null) {
                sb.append(' ').append(v);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String firstParam(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object v = parameters.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s.toLowerCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    /**
     * 对不含工具名的纯自然语言做「路径 / 命令模式」维度的快速评分，用于会话级工具面收缩（0–10）。
     */
    public ScoreResult scoreNaturalLanguageUtterance(String text) {
        String hay = normalizeUtterance(text).toLowerCase(Locale.ROOT);
        Map<String, Double> dims = new LinkedHashMap<>();
        double path = pathSensitivityScore(hay);
        double cmd = commandPatternScore(hay);
        dims.put("pathSensitivity", round1(path));
        dims.put("commandPattern", round1(cmd));
        double total = clamp(round1(path + cmd), 0, 10);
        String explanation = String.format(Locale.ROOT,
                "utterance 特征分=%.1f/10（路径敏感 %.1f + 命令模式 %.1f）", total, path, cmd);
        return new ScoreResult(total, Map.copyOf(dims), explanation);
    }

    public static String normalizeUtterance(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC);
        return n.replaceAll("\\p{Cf}", "");
    }

    private static double pathSensitivityScore(String haystack) {
        double s = 0;
        String h = haystack.replace('\\', '/').toLowerCase(Locale.ROOT);
        // Windows：仅对真正系统关键路径打满分；Temp/Logs/事件日志是本机运维白名单场景，不能因含 c:/windows 前缀误杀
        if (isWindowsCriticalSystemPath(h)) {
            return 10.0;
        }
        if (haystack.contains("/etc/shadow") || haystack.contains("/etc/gshadow")
                || h.contains("/etc/shadow") || h.contains("/etc/gshadow")) {
            s += 6;
        }
        if (h.contains("/boot") || h.contains(":/windows/system32/config")) {
            s += 3;
        }
        if (h.contains("/etc/") || (h.contains("c:/windows/system32") && !isWindowsOpsAllowlistedSubpath(h))) {
            s += 2;
        }
        if (h.contains("/root/") || h.contains("/.ssh") || h.contains(".ssh/")) {
            s += 2.5;
        }
        if (h.contains("/sys/") || h.contains("/proc/")) {
            s += 1.5;
        }
        if (h.contains("passwd") && (h.contains("/etc") || h.contains("system32"))) {
            s += 2;
        }
        return Math.min(s, 6);
    }

    /**
     * Windows 系统关键路径（应拒绝自动写）：Program Files、注册表配置、非运维白名单的 System32 / Windows 根。
     */
    private static boolean isWindowsCriticalSystemPath(String h) {
        if (h.contains("c:/program files") || h.contains("c:/programfiles")) {
            return true;
        }
        if (h.contains("c:/windows/system32/config")) {
            return true;
        }
        if (isWindowsOpsAllowlistedSubpath(h)) {
            return false;
        }
        // 裸 C:/Windows 或非白名单子路径（如 System32 本体）仍视为高危
        return h.contains("c:/windows");
    }

    /** 与 agent.paths.windows-* 常见运维根对齐：临时目录、日志、事件日志。 */
    private static boolean isWindowsOpsAllowlistedSubpath(String h) {
        return h.contains("c:/windows/temp")
                || h.contains("c:/windows/logs")
                || h.contains("c:/windows/system32/winevt")
                || h.contains("c:/inetpub/logs")
                || h.contains("c:/programdata/logs")
                || h.contains("/appdata/local/temp");
    }

    private static double commandPatternScore(String haystack) {
        double s = 0;
        if (haystack.contains("rm ") || haystack.contains("rm\t")) {
            s += 5;
        }
        if (haystack.contains("chmod ") || haystack.contains("chown ")) {
            s += 3;
        }
        if (haystack.contains("mkfs") || haystack.contains("dd ") || haystack.contains(":(){")) {
            s += 8;
        }
        if (haystack.contains("curl ") && haystack.contains("| sh")) {
            s += 6;
        }
        return Math.min(s, 8);
    }

    private static double privilegeContextScore() {
        String user = System.getProperty("user.name", "");
        if ("root".equalsIgnoreCase(user)) {
            return 2.0;
        }
        return 0;
    }

    private static String buildExplanation(String toolName, double total, Map<String, Double> dims) {
        return String.format(Locale.ROOT,
                "工具=%s 综合分=%.1f/10（维度: %s）",
                toolName == null ? "?" : toolName,
                total,
                dims.toString());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public record ScoreResult(double total, Map<String, Double> dimensions, String explanation) {
    }
}
