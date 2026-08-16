package com.award.log.agent;

import com.award.log.security.ChatToolExecutionTracker;
import com.award.log.util.OsRuntime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared markdown formatting helpers for ops reports.
 */
public final class OpsReportFormat {

    /** 产品确认口令（与前端 / OpsIntentRouter 一致；英文常量保留兼容别名） */
    public static final String CONFIRM_EXECUTE_HINT_ZH = "确认执行";

    /** @deprecated 使用 {@link #CONFIRM_EXECUTE_HINT_ZH}；值已统一为中文口令 */
    public static final String CONFIRM_EXECUTE_HINT = CONFIRM_EXECUTE_HINT_ZH;

    private OpsReportFormat() {
    }

    public static String markdownOutputSpecForPrompt() {
        return markdownOutputSpecForPromptZh();
    }

    /** 面向中文对话 Agent 的 Markdown 输出规范（工作台 / 流式助手） */
    public static String markdownOutputSpecForPromptZh() {
        return """

                ## 输出格式（务必遵守）
                - 全文使用**简体中文**，语气专业、简洁，像资深 SRE 写报告，不要堆砌 emoji。
                - **打招呼、闲聊、纯寒暄**时：只用 1～3 句自然语言回复，**禁止**输出指标表格或系统快照。
                - 章节标题只用 `##`、`###`，标题前后各空一行；不要把标题和正文挤在同一行。
                - 结构化数据**必须**用标准 Markdown 表格；**禁止** `||指标||数值||` 或单行管道串写法。
                - 表格示例（每行单独一行）：
                  | 指标 | 数值 |
                  |------|------|
                  | CPU 使用率 | 45.7% |
                  | 内存使用率 | 80.4% |
                - 无序列表用 `- `，步骤用 `1.` `2.`；每条单独一行，不要把多条挤成一段。
                - 命令、路径、端口、服务名、指标用反引号；多行命令用 fenced code block。
                - 严重程度统一写 `[HIGH]`、`[WARN]`、`[INFO]`，例如 `- **[HIGH]** 内存压力：…`
                - **事实**、**诊断**、**建议行动**分三个小节，逻辑清晰；禁止输出原始 JSON。
                - 禁止装饰性分隔线 `---`；未实际执行的写操作不得描述为已执行。
                """;
    }

    public static String tableSeparatorLine(int columnCount) {
        int cols = Math.max(1, columnCount);
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sb.append("------|");
        }
        return sb.append("\n").toString();
    }

    public static String tableHeaderLine(String... columns) {
        if (columns == null || columns.length == 0) {
            return "";
        }
        return "| " + String.join(" | ", columns) + " |\n" + tableSeparatorLine(columns.length);
    }

    public static String severityBadge(String severity) {
        String value = severity == null || severity.isBlank()
                ? "INFO"
                : severity.trim().toUpperCase(Locale.ROOT);
        return "**[" + value + "]**";
    }

    public static String formatFindingLine(String severity, String title, String detail) {
        return "- " + severityBadge(severity) + " " + title + ": " + detail + "\n";
    }

    public static String formatNameSample(List<String> names, int maxShow) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        String joined = names.stream()
                .limit(maxShow)
                .map(name -> "`" + name + "`")
                .collect(Collectors.joining(", "));
        if (names.size() <= maxShow) {
            return joined;
        }
        return joined + " and " + names.size() + " total";
    }

    public static String formatPortList(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return "all monitored ports reachable";
        }
        String listed = ports.stream().map(port -> "`" + port + "`").collect(Collectors.joining(", "));
        if (OsRuntime.isWindows()) {
            return "not listening on " + listed;
        }
        return "unreachable: " + listed;
    }

    public static String traceIdBlock(String traceId) {
        return "### Trace\n\n- Trace ID: `" + traceId + "`\n\n";
    }

    public static String windowsPlatformNote() {
        if (!OsRuntime.isWindows()) {
            return "";
        }
        return "\n\n> Note: current host is Windows. Service and path wording should follow Windows semantics.";
    }

    public static String serviceIssueLabel() {
        return OsRuntime.isWindows() ? "Windows service issue" : "systemd unit issue";
    }

    public static String serviceIssueDetailPrefix() {
        return OsRuntime.isWindows()
                ? "Found Windows services that should be running but are stopped"
                : "Detected failed units from `systemctl --failed`";
    }

    public static String servicePerceptionSummary(boolean hasIssues, List<String> names) {
        if (!hasIssues) {
            return OsRuntime.isWindows()
                    ? "no required Windows service issue detected"
                    : "no failed units detected";
        }
        if (names == null || names.isEmpty()) {
            return OsRuntime.isWindows()
                    ? "some required Windows services are not running"
                    : "failed units exist";
        }
        return (OsRuntime.isWindows() ? "affected services: " : "failed units: ") + formatNameSample(names, 5);
    }

    public static String portProbeSummary(List<Integer> unreachablePorts) {
        return formatPortList(unreachablePorts);
    }

    public static String portFindingTitle() {
        return OsRuntime.isWindows() ? "port not listening" : "port unreachable";
    }

    public static String portFindingDetail(String host, int port) {
        String endpoint = "`" + host + ":" + port + "`";
        return OsRuntime.isWindows()
                ? endpoint + " is not listening on the current Windows host"
                : endpoint + " TCP connection failed";
    }

    public static String portFindingSeverity() {
        return OsRuntime.isWindows() ? "INFO" : "MEDIUM";
    }

    public static boolean isActionableSeverity(String severity) {
        return severity != null && !"INFO".equalsIgnoreCase(severity);
    }

    public static String conclusionSection(
            double cpuPct,
            double memPct,
            double diskPct,
            int actionableCount,
            int infoCount,
            List<String> actionableTitles) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Conclusion\n\n");
        if (actionableCount == 0) {
            sb.append(tableHeaderLine("Item", "Status"));
            sb.append("| Overall | Healthy |\n");
            sb.append(String.format(Locale.ROOT,
                    "| CPU / Memory / Disk | `%.0f%%` / `%.0f%%` / `%.0f%%` |\n",
                    cpuPct, memPct, diskPct));
            if (infoCount > 0) {
                sb.append("\n> There are also ").append(infoCount)
                        .append(" info-level findings for awareness.\n");
            }
        } else {
            sb.append("Found **").append(actionableCount).append("** items that need attention.\n\n");
            sb.append(tableHeaderLine("No.", "Issue"));
            List<String> titles = actionableTitles != null ? actionableTitles : List.of();
            for (int i = 0; i < Math.min(3, titles.size()); i++) {
                sb.append("| ").append(i + 1).append(" | ").append(titles.get(i)).append(" |\n");
            }
        }
        sb.append(windowsPlatformNote());
        return sb.toString();
    }

    public static String awmSectionHeader() {
        return "### Workflow Reference\n\n";
    }

    public static String awmSectionFooter() {
        return "> Historical workflow references may not fully match the current environment.\n\n";
    }

    public static String previewPendingFooter() {
        return previewPendingFooter("自动修复");
    }

    public static String previewPendingFooter(String actionLabel) {
        return "> 部分步骤仍为预览。请回复 **「" + CONFIRM_EXECUTE_HINT_ZH
                + "」** 以继续执行 " + actionLabel + "（仅在策略允许范围内）。\n";
    }

    /**
     * 修后验效摘要（中文），供编排收尾展示。
     */
    public static String effectSectionMarkdown(Map<String, Object> effect) {
        if (effect == null || effect.isEmpty()) {
            return "";
        }
        Object score = effect.get("healingScore");
        Object verdict = effect.get("verdict");
        String verdictZh = switch (String.valueOf(verdict == null ? "" : verdict)) {
            case "effective" -> "有效";
            case "partial" -> "部分有效";
            case "uncertain" -> "尚不确定";
            default -> String.valueOf(verdict);
        };
        double diskDelta = effect.get("diskUsageDeltaPct") instanceof Number n ? n.doubleValue() : 0.0;
        double cpuDelta = effect.get("cpuUsageDeltaPct") instanceof Number n ? n.doubleValue() : 0.0;
        return "### 修后验效\n\n"
                + "- 愈合评分：**" + score + "/100**（" + verdictZh + "）\n"
                + String.format(Locale.ROOT, "- 磁盘占用变化：%.2f 个百分点\n", diskDelta)
                + String.format(Locale.ROOT, "- CPU 占用变化：%.2f 个百分点\n\n", cpuDelta);
    }

    /**
     * 供前端 RemediationPlanCard 解析的处置计划章节（Plan-then-Act）。
     */
    public static String remediationPlanMarkdown(List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 处置计划\n\n");
        if (items == null || items.isEmpty()) {
            sb.append("- 只读采集后给出建议；写操作默认预览\n");
        } else {
            for (String item : items) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                sb.append("- ").append(item.trim()).append("\n");
            }
        }
        sb.append("\n> 以上为**预览/待确认**计划，默认不会真实写入。回复 **「")
                .append(CONFIRM_EXECUTE_HINT_ZH)
                .append("」** 后在白名单与治理策略允许范围内落地。\n");
        return sb.toString();
    }

    public static String executedFooter() {
        return "> Write actions were executed within policy boundaries. See the trace for the full audit trail.\n";
    }

    public static String readOnlySkippedFooter() {
        return "- Skipped because the current session is read-only.\n\n";
    }

    public static String dataBasisFooter(boolean writeConfirmed) {
        return dataBasisFooter(writeConfirmed, ChatToolExecutionTracker.hasAnyToolInvocation(),
                ChatToolExecutionTracker.hasSuccessfulRealWrite());
    }

    /**
     * @param writeConfirmed 用户本轮是否确认写
     * @param anyTool        是否实际调用过工具
     * @param realWriteOk    是否有 mode=DELETE/EXECUTED 的成功证据
     */
    public static String dataBasisFooter(boolean writeConfirmed, boolean anyTool, boolean realWriteOk) {
        if (writeConfirmed && realWriteOk) {
            return "\n\n> **数据依据**：本回复包含经您确认后执行的写操作结果（已校验 MCP `mode=DELETE/EXECUTED`）。";
        }
        if (writeConfirmed && !realWriteOk) {
            return "\n\n> **数据依据**：您已确认写操作，但本轮工具未返回成功落地证据（可能仍为预览或失败），请勿视为已处置完成。";
        }
        if (!anyTool) {
            return "\n\n> **数据依据**：本轮未调用运维工具，以上为模型基于上下文的说明，非正式执行结果。";
        }
        if (ChatToolExecutionTracker.hasOnlyPreviewOrRead()) {
            return "\n\n> **数据依据**：本轮工具结果以只读采集或 Dry-Run 预览为准，尚未真正写入/删除/重启。";
        }
        return "\n\n> **数据依据**：结论来自本轮真实工具或编排输出。";
    }

    public static String planActionForKind(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "DISK_PRESSURE" -> "scan hotspots, preview temp or old-log cleanup, then verify disk usage";
            case "CPU_HIGH" -> "locate hot processes, then assess whether an allowlisted service restart is needed";
            case "LOG_ANOMALY" -> "inspect abnormal logs, then preview old-log cleanup";
            case "MEM_HIGH" -> "list top memory consumers, then consider throttling or scaling";
            case "FAILED_SERVICE" -> "inspect failed service units, then preview an allowlisted restart";
            case "PORT_DOWN" -> "confirm whether the service should be listening on that port";
            default -> "validate with the matching MCP tool and handle within policy";
        };
    }
}
