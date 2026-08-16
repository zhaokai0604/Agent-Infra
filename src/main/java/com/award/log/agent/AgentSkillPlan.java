package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.OpsWorkflowStep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 根据用户话术 / 对话上下文推断「计划调用的工具序列」，供 Plan-then-Act 与 AWM 偏置。
 */
public final class AgentSkillPlan {

    private static final Set<String> WRITE_TOOLS = Set.of(
            "CleanTempTool",
            "LogCleanupTool",
            "ServiceRestartTool",
            "DockerTool",
            "DiskOpsTool",
            "LogOpsTool",
            "ServiceOpsTool",
            "ContainerOpsTool",
            "ProcessOpsTool",
            "SystemdTool",
            "AutonomousOpsTool",
            "FirewallTool",
            "PrivilegeTool"
    );

    private AgentSkillPlan() {
    }

    public static String guessDomain(String userMessage) {
        String u = normalize(userMessage);
        if (isPatrolRequest(u)) {
            return "patrol";
        }
        if (u.contains("cpu") || u.contains("进程") || u.contains("负载") || u.contains("卡顿")) {
            return "cpu";
        }
        return "disk";
    }

    /** Broad health/status requests must stay read-only; do not infer cleanup from generic wording. */
    private static boolean isPatrolRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("巡检")
                || normalized.contains("全面检查")
                || normalized.contains("综合检查")
                || normalized.contains("系统体检")
                || normalized.contains("健康检查")
                || normalized.contains("系统状态")
                || normalized.contains("主机状态")
                || normalized.contains("运行状态")
                || normalized.contains("检查系统")
                || normalized.contains("排查系统")
                || normalized.contains("诊断系统")
                || normalized.contains("检查主机")
                || normalized.contains("检查本机");
    }

    public static List<String> forOrchestrate(String userMessage) {
        String domain = guessDomain(userMessage);
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        if ("cpu".equals(domain)) {
            tools.add("SystemLoadTool");
            tools.add("ProcessTool");
            if (normalize(userMessage).contains("重启")) {
                tools.add("ServiceRestartTool");
            }
        } else if ("patrol".equals(domain)) {
            tools.add("SystemLoadTool");
            tools.add("DiskTool");
            tools.add("PortHealthTool");
            tools.add("DiskAnalyzeTool");
        } else {
            tools.add("DiskTool");
            tools.add("DiskAnalyzeTool");
            String u = normalize(userMessage);
            if (u.contains("临时") || u.contains("清理") || u.contains("满") || u.contains("空间")) {
                tools.add("CleanTempTool");
            }
            if (u.contains("日志") || u.contains("journal") || u.contains("满") || u.contains("空间")) {
                tools.add("LogCleanupTool");
            }
            if (tools.size() <= 2) {
                tools.add("CleanTempTool");
                tools.add("LogCleanupTool");
            }
        }
        return List.copyOf(tools);
    }

    public static List<String> forToolAgent(String userMessage) {
        LinkedHashSet<String> tools = new LinkedHashSet<>(forOrchestrate(userMessage));
        String u = normalize(userMessage);
        if (u.contains("端口") || u.contains("监听") || u.contains("连通")) {
            tools.add("PortHealthTool");
        }
        if (u.contains("服务") || u.contains("systemd") || u.contains("nginx") || u.contains("重启")) {
            tools.add("ServiceRestartTool");
        }
        if (u.contains("进程") || u.contains("内存")) {
            tools.add("ProcessTool");
            tools.add("SystemLoadTool");
        }
        if (tools.isEmpty()) {
            tools.add("DiskTool");
            tools.add("SystemLoadTool");
        }
        return List.copyOf(tools);
    }

    /**
     * 短确认话术（如「确认执行」）用对话上下文还原意图；并优先插入 AWM 成功套路工具序。
     */
    public static List<String> resolveTools(String userMessage,
                                            String conversationContext,
                                            OpsWorkflow awm,
                                            boolean orchestrate) {
        String seed = planningSeed(userMessage, conversationContext);
        List<String> base = orchestrate ? forOrchestrate(seed) : forToolAgent(seed);
        return preferAwm(base, awm);
    }

    public static String planningSeed(String userMessage, String conversationContext) {
        if (isShortConfirm(userMessage)
                && conversationContext != null
                && !conversationContext.isBlank()) {
            return conversationContext;
        }
        return userMessage == null ? "" : userMessage;
    }

    public static boolean isShortConfirm(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String t = userMessage.trim();
        if (t.length() > 48) {
            return false;
        }
        return AssistantIntentSignals.CONFIRM_WRITE.matcher(t).find();
    }

    public static boolean hasWriteTools(List<String> plannedTools) {
        if (plannedTools == null || plannedTools.isEmpty()) {
            return false;
        }
        for (String tool : plannedTools) {
            if (tool != null && WRITE_TOOLS.contains(tool.trim())) {
                return true;
            }
        }
        return false;
    }

    /** AWM 工具序前置，再补齐规则计划中尚未出现的工具。 */
    public static List<String> preferAwm(List<String> base, OpsWorkflow awm) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        for (String t : toolsFromWorkflow(awm)) {
            if (t != null && !t.isBlank()) {
                tools.add(t.trim());
            }
        }
        if (base != null) {
            for (String t : base) {
                if (t != null && !t.isBlank()) {
                    tools.add(t.trim());
                }
            }
        }
        if (tools.isEmpty()) {
            return List.of("DiskTool", "SystemLoadTool");
        }
        return List.copyOf(tools);
    }

    public static List<String> remediationItems(List<String> plannedTools, boolean writeConfirmed) {
        List<String> items = new ArrayList<>();
        for (String tool : plannedTools) {
            if (tool == null) {
                continue;
            }
            switch (tool) {
                case "CleanTempTool" -> items.add(writeConfirmed
                        ? "清理临时目录（白名单路径，真实删除）"
                        : "预览清理临时目录（Dry-Run，不落真实删除）");
                case "LogCleanupTool" -> items.add(writeConfirmed
                        ? "清理/裁剪旧日志（白名单路径，真实写入）"
                        : "预览旧日志清理（Dry-Run，待确认）");
                case "ServiceRestartTool" -> items.add(writeConfirmed
                        ? "按治理策略重启允许名单内服务"
                        : "服务重启仅预览，须确认后执行");
                case "DiskAnalyzeTool" -> items.add("扫描磁盘热点与大文件");
                case "DiskTool" -> items.add("采集磁盘占用概况");
                case "SystemLoadTool" -> items.add("采集 CPU/负载概况");
                case "ProcessTool" -> items.add("列出高占用进程");
                case "PortHealthTool" -> items.add("检查关键端口健康");
                default -> {
                }
            }
        }
        if (items.isEmpty()) {
            items.add("只读采集本机指标并给出建议");
        }
        // 只读诊断计划不能凭空追加确认执行步骤；确认提示只属于含写工具的方案。
        if (!writeConfirmed && hasWriteTools(plannedTools)) {
            items.add("写操作默认预览；回复「确认执行」后在策略允许范围内落地");
        }
        return items;
    }

    public static String workflowStepSummary(OpsWorkflow workflow) {
        if (workflow == null || workflow.steps() == null || workflow.steps().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("推荐复用历史成功套路「")
                .append(workflow.title() == null ? workflow.workflowId() : workflow.title())
                .append("」步骤：");
        int i = 0;
        for (OpsWorkflowStep step : workflow.steps()) {
            if (step == null || step.toolName() == null || step.toolName().isBlank()) {
                continue;
            }
            if (i++ > 0) {
                sb.append(" → ");
            }
            sb.append(step.toolName().trim());
            if (i >= 6) {
                break;
            }
        }
        return sb.toString();
    }

    public static List<String> toolsFromWorkflow(OpsWorkflow workflow) {
        if (workflow == null || workflow.steps() == null) {
            return List.of();
        }
        Set<String> tools = new LinkedHashSet<>();
        for (OpsWorkflowStep step : workflow.steps()) {
            if (step != null && step.toolName() != null && !step.toolName().isBlank()) {
                tools.add(step.toolName().trim());
            }
        }
        return List.copyOf(tools);
    }

    public static List<String> observeTools(List<String> plannedTools) {
        if (plannedTools == null || plannedTools.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tool : plannedTools) {
            if (tool != null && !WRITE_TOOLS.contains(tool.trim())) {
                out.add(tool.trim());
            }
        }
        return List.copyOf(out);
    }

    public static List<String> pendingWriteTools(List<String> plannedTools) {
        if (plannedTools == null || plannedTools.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tool : plannedTools) {
            if (tool != null && WRITE_TOOLS.contains(tool.trim())) {
                out.add(tool.trim());
            }
        }
        return List.copyOf(out);
    }

    /** 诊断阶段 vs 确认执行阶段。 */
    public static String planPhase(boolean allowWrite, List<String> plannedTools) {
        if (allowWrite) {
            return "EXECUTE";
        }
        if (hasWriteTools(plannedTools)) {
            return "DIAGNOSE_THEN_CONFIRM";
        }
        return "DIAGNOSE";
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }
}
