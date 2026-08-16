package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend contract for the visible agent loop: objective -> plan -> act -> verify -> reflect.
 */
public final class AgentExecutionState {

    private static final String LOOP_CONTRACT = "objective-plan-act-verify-reflect";

    private AgentExecutionState() {
    }

    public static Map<String, Object> build(
            String replyMode,
            boolean writeConfirmed,
            boolean writeToolsMounted,
            List<String> plannedTools,
            String planPhase,
            String traceId,
            String securityOutcome,
            OpsWorkflow workflow
    ) {
        List<String> tools = normalizedTools(plannedTools);
        boolean awaitingConfirm = !writeConfirmed && AgentSkillPlan.hasWriteTools(tools);
        String effectivePlanPhase = planPhase == null || planPhase.isBlank()
                ? AgentSkillPlan.planPhase(writeConfirmed, tools)
                : planPhase.trim();
        String phase = resolvePhase(writeToolsMounted, awaitingConfirm, securityOutcome);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", 1);
        state.put("loopContract", LOOP_CONTRACT);
        state.put("replyMode", blankToDefault(replyMode, "TOOL_AGENT"));
        state.put("phase", phase);
        state.put("phaseLabel", phaseLabel(phase, awaitingConfirm, securityOutcome));
        state.put("planPhase", effectivePlanPhase);
        state.put("writeConfirmed", writeConfirmed);
        state.put("writeToolsMounted", writeToolsMounted);
        state.put("awaitingConfirm", awaitingConfirm);
        state.put("plannedTools", tools);
        state.put("observeTools", AgentSkillPlan.observeTools(tools));
        state.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(tools));
        state.put("planItems", AgentSkillPlan.remediationItems(tools, writeConfirmed));
        state.put("guardrail", guardrail(writeConfirmed, awaitingConfirm, tools));
        state.put("verification", verificationHint(securityOutcome, awaitingConfirm, traceId));
        state.put("reflection", reflectionHint(securityOutcome, awaitingConfirm, writeToolsMounted));
        state.put("nextAction", nextAction(securityOutcome, awaitingConfirm, writeToolsMounted));
        state.put("phaseTrail", phaseTrail(phase));
        if (traceId != null && !traceId.isBlank()) {
            state.put("traceId", traceId);
        }
        if (securityOutcome != null && !securityOutcome.isBlank()) {
            state.put("securityOutcome", securityOutcome);
        }
        if (workflow != null) {
            state.put("awmWorkflowId", workflow.workflowId());
            state.put("awmWorkflowTitle", workflow.title());
        }
        return state;
    }

    private static List<String> normalizedTools(List<String> plannedTools) {
        if (plannedTools == null || plannedTools.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tool : plannedTools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String t = tool.trim();
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    private static String resolvePhase(boolean writeToolsMounted, boolean awaitingConfirm, String securityOutcome) {
        String outcome = securityOutcome == null ? "" : securityOutcome.trim().toUpperCase(Locale.ROOT);
        if (outcome.contains("ERROR") || outcome.contains("FAILED") || outcome.contains("REJECT")) {
            return "reflect";
        }
        if (outcome.equals("EXECUTED") || outcome.equals("REMEDIATED") || outcome.equals("HEALTHY")
                || outcome.equals("DIAGNOSED") || outcome.equals("PREVIEW")
                || outcome.equals("PREVIEW_OR_WRITE_PENDING")) {
            return "verify";
        }
        if (awaitingConfirm) {
            return "plan";
        }
        return writeToolsMounted ? "act" : "observe";
    }

    private static String phaseLabel(String phase, boolean awaitingConfirm, String securityOutcome) {
        if (awaitingConfirm) {
            return "等待确认";
        }
        String outcome = securityOutcome == null ? "" : securityOutcome.trim().toUpperCase(Locale.ROOT);
        if (outcome.equals("EXECUTED") || outcome.equals("REMEDIATED")) {
            return "已执行验证";
        }
        if (outcome.equals("DIAGNOSED") || outcome.equals("PREVIEW") || outcome.equals("PREVIEW_OR_WRITE_PENDING")) {
            return "已诊断验证";
        }
        return switch (phase) {
            case "act" -> "执行中";
            case "verify" -> "验证中";
            case "reflect" -> "复盘中";
            case "plan" -> "计划待确认";
            default -> "观测中";
        };
    }

    private static String guardrail(boolean writeConfirmed, boolean awaitingConfirm, List<String> tools) {
        List<String> pending = AgentSkillPlan.pendingWriteTools(tools);
        if (awaitingConfirm || !pending.isEmpty()) {
            return "写工具待确认：" + (pending.isEmpty() ? "高风险写操作" : String.join("、", pending));
        }
        if (writeConfirmed) {
            return "用户已确认写操作，仍受白名单、风险评分和审计门控约束。";
        }
        return "只读观测优先；没有确认前不挂载写工具。";
    }

    private static String verificationHint(String securityOutcome, boolean awaitingConfirm, String traceId) {
        if (securityOutcome != null && !securityOutcome.isBlank()) {
            return "安全结论：" + securityOutcome + traceSuffix(traceId);
        }
        if (awaitingConfirm) {
            return "计划已生成，等待确认后才能执行写步骤。";
        }
        return "等待工具回执、审计 trace 与结果校验。";
    }

    private static String reflectionHint(String securityOutcome, boolean awaitingConfirm, boolean writeToolsMounted) {
        String outcome = securityOutcome == null ? "" : securityOutcome.trim().toUpperCase(Locale.ROOT);
        if (outcome.contains("ERROR") || outcome.contains("FAILED")) {
            return "本轮未闭环，应回看 trace、工具回执和策略门，避免重复失败。";
        }
        if (outcome.equals("EXECUTED") || outcome.equals("REMEDIATED")) {
            return "本轮已落地，应沉淀可复用工具序列，并复查指标是否回落。";
        }
        if (awaitingConfirm) {
            return "已暂停在确认门，避免把预览误判为执行。";
        }
        if (writeToolsMounted) {
            return "执行阶段需要保留回滚线索和最终验证证据。";
        }
        return "观测阶段先收集证据，再决定是否进入写操作。";
    }

    private static String nextAction(String securityOutcome, boolean awaitingConfirm, boolean writeToolsMounted) {
        String outcome = securityOutcome == null ? "" : securityOutcome.trim().toUpperCase(Locale.ROOT);
        if (awaitingConfirm) {
            return "请核对计划，确认后再执行写操作。";
        }
        if (outcome.equals("EXECUTED") || outcome.equals("REMEDIATED")) {
            return "进入 trace 复查和效果验证，必要时归纳为 AWM workflow。";
        }
        if (outcome.contains("ERROR") || outcome.contains("FAILED")) {
            return "从失败 trace 生成 Reflexion 教训，重新规划下一轮。";
        }
        return writeToolsMounted ? "继续执行并验证结果。" : "继续采集证据并生成计划。";
    }

    private static List<Map<String, Object>> phaseTrail(String currentPhase) {
        List<String[]> phases = List.of(
                new String[]{"objective", "目标"},
                new String[]{"plan", "计划"},
                new String[]{"observe", "观测"},
                new String[]{"act", "执行"},
                new String[]{"verify", "验证"},
                new String[]{"reflect", "反思"}
        );
        int current = 0;
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i)[0].equals(currentPhase)) {
                current = i;
                break;
            }
        }
        List<Map<String, Object>> trail = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", phases.get(i)[0]);
            row.put("label", phases.get(i)[1]);
            row.put("index", i + 1);
            row.put("state", i < current ? "done" : (i == current ? "current" : "pending"));
            trail.add(row);
        }
        return trail;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String traceSuffix(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "";
        }
        return "，Trace " + traceId.substring(0, Math.min(8, traceId.length()));
    }
}
