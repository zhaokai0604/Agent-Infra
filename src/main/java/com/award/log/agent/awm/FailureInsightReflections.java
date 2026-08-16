package com.award.log.agent.awm;

import java.util.Locale;

/**
 * 规则化 Reflexion 文案（MVP；后续可换 Spring AI 生成）。
 */
public final class FailureInsightReflections {

    private FailureInsightReflections() {
    }

    public static String reflect(String securityCode, String toolName, String userInput) {
        String code = securityCode == null ? "" : securityCode.toUpperCase(Locale.ROOT);
        return switch (code) {
            case "INJECTION", "REJECT_INJECTION" ->
                    "失败原因：输入含提示注入特征。下次请用直白运维目标描述，避免「忽略规则/执行隐藏指令」类话术。";
            case "HIGH_INTENT", "REJECT_HIGH_INTENT" ->
                    "失败原因：意图被判定为高风险。下次请拆分为只读诊断（ProcessTool/SystemLoadTool）或走 MCP 二次确认后再写操作。";
            case "INTENT_TOOL_MISMATCH", "REJECT_INTENT_MISMATCH" ->
                    "失败原因：破坏性话术与只读工具不匹配（如「删除」+ DiskTool）。下次改用 CleanTempTool/LogCleanupTool 并 dryRun，或先只做观测。";
            case "HIGH_RISK_COMMAND", "REJECT_HIGH_RISK_COMMAND" ->
                    "失败原因：话术命中高危命令模式。下次避免 rm -rf、格式化、提权等表述；改用受控运维工具与白名单路径。";
            case "RISK_SCORE_HIGH", "REJECT_RISK_SCORE" ->
                    "失败原因：Agentic 风险分超阈值。下次缩小路径范围、启用 dryRun，或在界面点击「确认执行」。";
            case "READ_ONLY_TOOL_SURFACE" ->
                    "失败原因：当前为只读工具面，写类工具 " + safeTool(toolName) + " 不可用。下次切换完整工具面或仅请求分析/诊断。";
            case "NEED_CONFIRM" ->
                    "经验：写操作 " + safeTool(toolName) + " 进入二次确认。下次可先 dryRun 预览，确认后再说「确认执行」。";
            case "REJECTED_HIGH_RISK", "REJECTED_HIGH_RISK_COMMAND", "REJECTED_INJECTION" ->
                    reflect(mapLegacyCode(code), toolName, userInput);
            default ->
                    "失败原因：安全门拦截（" + code + "）。下次优先只读工具验证环境，写操作需策略白名单与确认。";
        };
    }

    private static String mapLegacyCode(String code) {
        if (code.contains("INJECTION")) {
            return "INJECTION";
        }
        if (code.contains("MISMATCH")) {
            return "INTENT_TOOL_MISMATCH";
        }
        if (code.contains("HIGH_RISK_COMMAND")) {
            return "HIGH_RISK_COMMAND";
        }
        if (code.contains("HIGH")) {
            return "HIGH_INTENT";
        }
        return code;
    }

    private static String safeTool(String toolName) {
        return toolName == null || toolName.isBlank() ? "（写工具）" : toolName;
    }

    /** 用于 dedupe 的 intent 摘要（取用户输入前 48 字符规范化）。 */
    public static String intentHint(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "";
        }
        String s = userInput.trim().replaceAll("\\s+", " ");
        return s.length() <= 48 ? s : s.substring(0, 48);
    }

    public static String insightKey(String securityCode, String toolName, String intentHint) {
        String c = securityCode == null ? "UNKNOWN" : securityCode.trim().toUpperCase(Locale.ROOT);
        String t = toolName == null ? "" : toolName.trim();
        String i = intentHint == null ? "" : intentHint.trim().toLowerCase(Locale.ROOT);
        return c + "|" + t + "|" + i;
    }
}
