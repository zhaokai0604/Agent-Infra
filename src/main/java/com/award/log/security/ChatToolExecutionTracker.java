package com.award.log.security;

import com.award.log.mcp.WriteToolResultSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记录对话 ChatClient 本轮实际调用的 MCP 工具结果，用于拦截「未执行却声称已落地」的幻觉回复。
 */
public final class ChatToolExecutionTracker {

    public record ToolInvocation(String toolName, boolean success, String mode, boolean realWriteLanded) {
        public ToolInvocation(String toolName, boolean success, String mode) {
            this(toolName, success, mode, false);
        }
    }

    private static final ThreadLocal<List<ToolInvocation>> INVOCATIONS = ThreadLocal.withInitial(ArrayList::new);

    private ChatToolExecutionTracker() {
    }

    public static void record(String toolName, String toolResultJson) {
        if (toolName == null || toolResultJson == null) {
            return;
        }
        boolean success = WriteToolResultSupport.isToolSuccess(toolResultJson);
        boolean realWriteLanded = WriteToolResultSupport.isConfirmedRealWrite(toolResultJson);
        String mode = WriteToolResultSupport.extractMode(toolResultJson);
        if ((mode == null || mode.isBlank()) && realWriteLanded) {
            mode = "EXECUTED";
        }
        // 空删除常带 mode=DELETE + success，不当成真写
        if ("DELETE".equalsIgnoreCase(mode != null ? mode.trim() : "") && !realWriteLanded) {
            mode = "NOOP";
        }
        INVOCATIONS.get().add(new ToolInvocation(toolName, success, mode, realWriteLanded));
    }

    public static List<ToolInvocation> snapshot() {
        return List.copyOf(INVOCATIONS.get());
    }

    public static void clear() {
        INVOCATIONS.remove();
    }

    /** @deprecated 使用 {@link #hasSuccessfulRealWrite()} */
    @Deprecated
    public static boolean hasSuccessfulDelete() {
        return hasSuccessfulRealWrite();
    }

    public static boolean hasSuccessfulRealWrite() {
        return INVOCATIONS.get().stream().anyMatch(ToolInvocation::realWriteLanded);
    }

    public static boolean hasAnyToolInvocation() {
        return !INVOCATIONS.get().isEmpty();
    }

    public static boolean hasOnlyPreviewOrRead() {
        List<ToolInvocation> list = INVOCATIONS.get();
        if (list.isEmpty()) {
            return true;
        }
        return list.stream().noneMatch(ToolInvocation::realWriteLanded);
    }

    public static String sanitizeUnverifiedExecutionClaims(String content, boolean allowWrite) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (allowWrite && hasSuccessfulRealWrite()) {
            return content;
        }
        if (!claimsRealExecution(content)) {
            return content;
        }
        return content
                + "\n\n> **系统校正**：本轮未检测到经 MCP 返回 `mode=DELETE/EXECUTED` 的成功写操作，"
                + "上述「已删除/已杀掉/已重启/执行成功」等结论无效。"
                + "请使用 **工具控制台** 填写 `dryRun=false` 与对应确认位后执行，"
                + "或在对话中说「**确认执行**」。\n";
    }

    private static boolean claimsRealExecution(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("预览") || lower.contains("dry-run") || lower.contains("dryrun")
                || lower.contains("尚未") || lower.contains("未真正") || lower.contains("未实际")) {
            // 若同时声称已落地，仍拦截；仅含预览说明的不算宣称
            boolean stillClaims = lower.contains("已删除") || lower.contains("已杀掉")
                    || lower.contains("已终止") || lower.contains("已重启") || lower.contains("已停止")
                    || lower.contains("执行成功") || lower.contains("清理成功") || lower.contains("重启成功");
            if (!stillClaims) {
                return false;
            }
        }
        return lower.contains("已执行删除")
                || lower.contains("真实删除")
                || lower.contains("dryrun=false")
                || lower.contains("execution_accepted")
                || lower.contains("已杀掉")
                || lower.contains("已终止")
                || lower.contains("已结束进程")
                || lower.contains("进程已结束")
                || lower.contains("终止成功")
                || lower.contains("已重启")
                || lower.contains("重启成功")
                || lower.contains("已停止容器")
                || lower.contains("停止成功")
                || lower.contains("清理成功")
                || lower.contains("删除成功")
                || lower.contains("已清理")
                || lower.contains("执行成功")
                || lower.contains("处置成功")
                || lower.contains("已真正执行")
                || (lower.contains("已删除") && !lower.contains("预览") && !lower.contains("dry-run"))
                || (lower.contains("已执行") && !lower.contains("预览") && !lower.contains("拟"));
    }
}
