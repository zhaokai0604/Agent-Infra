package com.award.log.agent;

import com.award.log.service.OpsAutoRemediationService;
import com.award.log.service.OpsPatrolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检自动修复单轨：与 {@link AssistantOrchestrator} / MCP 分发解耦，避免启动期循环依赖。
 */
@Service
@RequiredArgsConstructor
public class OpsPatrolAutomationService {

    private final OpsPatrolService opsPatrolService;
    private final OpsAutoRemediationService opsAutoRemediationService;

    public OpsRunResult run() {
        opsPatrolService.runPatrolCycle();
        Map<String, Object> rawRemediation = opsAutoRemediationService != null
                ? opsAutoRemediationService.getLastSummary()
                : Map.of();
        Map<String, Object> remediation = rawRemediation != null
                ? new LinkedHashMap<>(rawRemediation)
                : new LinkedHashMap<>();
        Map<String, Object> rawPending = opsAutoRemediationService != null
                ? opsAutoRemediationService.getPendingProposalView("system")
                : Map.of("hasPending", false);
        Map<String, Object> pending = rawPending != null
                ? new LinkedHashMap<>(rawPending)
                : new LinkedHashMap<>(Map.of("hasPending", false));
        List<Map<String, Object>> rawCoverage = opsAutoRemediationService != null
                ? opsAutoRemediationService.getRemediationCoverage()
                : List.of();
        List<Map<String, Object>> coverage = rawCoverage != null ? rawCoverage : List.of();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("findings", opsPatrolService.getLastFindingsSnapshot());
        report.put("correlation", opsPatrolService.getLastCorrelationSnapshot());
        report.put("remediation", remediation);
        report.put("pending", pending);
        report.put("coverage", coverage);

        String traceId = remediation.get("traceId") != null ? String.valueOf(remediation.get("traceId")) : null;
        return new OpsRunResult(buildPatrolMarkdown(report, remediation, pending, traceId), traceId, report);
    }

    private String buildPatrolMarkdown(Map<String, Object> report,
                                        Map<String, Object> remediation,
                                        Map<String, Object> pending,
                                        String traceId) {
        StringBuilder md = new StringBuilder("## 全面巡检\n\n");

        List<?> findings = report.get("findings") instanceof List<?> list ? list : List.of();
        md.append("- 发现项：**").append(findings.size()).append("**\n");

        String status = String.valueOf(remediation.getOrDefault("status", ""));
        Object actions = remediation.get("actions");
        int actionCount = listSize(actions);
        int successCount = asInt(remediation.get("successCount"), countSuccess(actions));
        int pendingCount = listSize(pending.get("steps"));
        if (pendingCount == 0) {
            pendingCount = asInt(remediation.get("pendingCount"), 0);
        }
        md.append("- 修复状态：**")
                .append(humanStatus(status, Boolean.TRUE.equals(pending.get("hasPending")), actionCount))
                .append("**\n");
        md.append("- 自动执行：**").append(actionCount).append("** 步");
        if (actionCount > 0) {
            md.append("，成功 **").append(successCount).append("** 步");
        }
        md.append("\n");
        if (pendingCount > 0) {
            md.append("- 待确认步骤：**").append(pendingCount).append("**\n");
        }

        List<Map<String, Object>> coverage = coverageRows(report.get("coverage"));
        String coverageSummary = summarizeCoverage(coverage);
        if (!coverageSummary.isBlank()) {
            md.append("- 策略车道：").append(coverageSummary).append("\n");
        }

        if (Boolean.TRUE.equals(pending.get("hasPending"))) {
            md.append("- 存在待确认的后续处置，请确认后继续执行。\n");
            Object summary = pending.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) {
                md.append("- 待确认摘要：").append(summary).append("\n");
            }
        } else if (actionCount > 0) {
            md.append("- 本轮巡检自动处置已完成。\n");
        } else if (!remediation.isEmpty()) {
            Object reason = remediation.get("reason");
            if (reason != null && !String.valueOf(reason).isBlank()) {
                md.append("- 说明：").append(reason).append("\n");
            } else {
                md.append("- 本轮巡检没有执行写类修复动作。\n");
            }
        } else {
            md.append("- 本轮巡检未发现需要自动处置的问题，仅记录观察结果。\n");
        }
        return md.toString();
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static int countSuccess(Object actions) {
        if (!(actions instanceof List<?> list)) {
            return 0;
        }
        int success = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> action && Boolean.TRUE.equals(action.get("success"))) {
                success++;
            }
        }
        return success;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<Map<String, Object>> coverageRows(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String humanStatus(String status, boolean hasPending, int actionCount) {
        if ("EXECUTED_WITH_PENDING".equals(status)) {
            return "已自动执行，仍有待确认";
        }
        if ("EXECUTED".equals(status)) {
            return "已自动执行";
        }
        if ("EXECUTION_PARTIAL".equals(status)) {
            return "部分执行";
        }
        if ("PENDING_CONFIRMATION".equals(status) || hasPending) {
            return "等待确认";
        }
        if ("SKIPPED_COOLDOWN".equals(status)) {
            return "冷却期跳过";
        }
        if ("NO_ACTION".equals(status) || "NO_IMMEDIATE_ACTION".equals(status)) {
            return "本轮无可执行修复";
        }
        if ("MANUAL_REVIEW".equals(status)) {
            return "需人工处理";
        }
        if ("DISABLED".equals(status)) {
            return "自动修复已关闭";
        }
        if (actionCount > 0) {
            return "已自动执行";
        }
        return "已巡检";
    }

    private static String summarizeCoverage(List<Map<String, Object>> coverage) {
        if (coverage == null || coverage.isEmpty()) {
            return "";
        }
        Map<String, Integer> lanes = new LinkedHashMap<>();
        for (Map<String, Object> row : coverage) {
            Object remediation = row.get("remediation");
            if (remediation instanceof Map<?, ?> laneMap) {
                Object rawLane = laneMap.get("lane");
                String lane = rawLane == null ? "NONE" : String.valueOf(rawLane);
                lanes.put(lane, lanes.getOrDefault(lane, 0) + 1);
            }
        }
        if (lanes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : lanes.entrySet()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(laneLabel(entry.getKey())).append(" ").append(entry.getValue());
        }
        return sb.toString();
    }

    private static String laneLabel(String lane) {
        return switch (lane) {
            case "AUTO" -> "自动";
            case "CONFIRM" -> "待确认";
            case "MIXED" -> "混合";
            case "MANUAL" -> "人工";
            default -> "无动作";
        };
    }
}
