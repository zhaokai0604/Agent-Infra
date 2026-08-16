package com.award.log.agent.awm;

import com.award.log.agent.AssistantAuditRecorder;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.McpToolPayloadParser;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.WriteExecutionCoordinator;
import com.award.log.security.effect.PlanEffectGate;
import com.award.log.util.OpsPathExtractSupport;
import com.award.log.util.RuntimePlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays AWM workflows through the real MCP tools and safety gates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionService {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{([a-zA-Z0-9._\\-]+)}$");

    private final McpToolDispatcher mcpToolDispatcher;
    private final OpsRemediationGate opsRemediationGate;
    private final AssistantAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final OpsPathPolicy opsPathPolicy;
    private final RuntimePlatform runtimePlatform;
    private final WorkflowMemoryService workflowMemoryService;
    private final PlanEffectGate planEffectGate;

    @Value("${agent.assistant.orchestrator.log-clean-days:30}")
    private int logCleanDays;

    @Value("${agent.assistant.orchestrator.temp-clean-days:7}")
    private int tempCleanDays;

    public record ExecutionRequest(
            String traceId,
            String userMessage,
            McpToolSurface surface,
            boolean forceExecute,
            List<Map<String, Object>> auditSteps,
            Map<String, Object> variables
    ) {
    }

    public record StepResult(
            String toolName,
            String mode,
            boolean success,
            boolean executed,
            boolean preview,
            boolean skipped,
            String message,
            Map<String, Object> parameters,
            String rawResult
    ) {
        public String toMarkdownLine() {
            String label = switch (mode) {
                case "EXECUTE" -> "执行";
                case "PREVIEW" -> "预览";
                case "SKIP" -> "跳过";
                default -> "读取";
            };
            return "- `" + toolName + "` [" + label + "] " + message;
        }
    }

    public record WorkflowRunResult(
            OpsWorkflow workflow,
            List<StepResult> stepResults,
            boolean anyExecuted,
            boolean anyPreviewed,
            boolean anyReadSucceeded,
            boolean failed,
            boolean blocked
    ) {
        public boolean handled() {
            return anyExecuted || anyPreviewed || anyReadSucceeded;
        }

        /** 回放成功完成（含只读诊断），可用于入库统计 */
        public boolean completedOk() {
            return !failed && handled();
        }

        public java.util.Optional<String> rawResultForTool(String toolName) {
            if (toolName == null || stepResults == null) {
                return java.util.Optional.empty();
            }
            return stepResults.stream()
                    .filter(s -> toolName.equals(s.toolName()) && s.success() && s.rawResult() != null)
                    .map(StepResult::rawResult)
                    .findFirst();
        }

        public int successfulStepCount() {
            if (stepResults == null) {
                return 0;
            }
            return (int) stepResults.stream().filter(StepResult::success).count();
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            if (workflow != null) {
                sb.append("**").append(workflow.title()).append("** (`").append(workflow.workflowId()).append("`)\n");
            }
            if (stepResults != null) {
                for (StepResult stepResult : stepResults) {
                    sb.append(stepResult.toMarkdownLine()).append("\n");
                }
            }
            if (failed) {
                sb.append("- AWM workflow 执行中断，已停止后续步骤。\n");
            } else if (completedOk()) {
                sb.append("- 回放完成：").append(successfulStepCount()).append(" 步成功");
                if (anyReadSucceeded && !anyExecuted && !anyPreviewed) {
                    sb.append("（只读诊断，已入库计数）");
                }
                sb.append("。\n");
            } else if (blocked && !handled()) {
                sb.append("- AWM workflow 当前只能提供参考，未形成可执行动作。\n");
            }
            return sb.toString().trim();
        }
    }

    public WorkflowRunResult execute(OpsWorkflow workflow, ExecutionRequest request) {
        if (workflow == null || request == null) {
            return new WorkflowRunResult(workflow, List.of(), false, false, false, true, true);
        }
        Map<String, Object> variables = prepareVariables(request.variables(), request.userMessage());
        List<StepResult> results = new ArrayList<>();
        boolean anyExecuted = false;
        boolean anyPreviewed = false;
        boolean anyReadSucceeded = false;
        boolean failed = false;
        boolean blocked = false;

        PlanEffectGate.PlanDecision planDecision = evaluateWorkflowPlan(workflow, variables);
        boolean planForcePreview = planDecision.needsConfirm() && !request.forceExecute();
        if (planDecision.blocked()) {
            StepResult blockedStep = new StepResult(
                    "PlanEffectGate",
                    "SKIP",
                    false,
                    false,
                    false,
                    true,
                    planDecision.message() + " [" + planDecision.code() + "]",
                    Map.of("planDecision", planDecision.toMap()),
                    null
            );
            results.add(blockedStep);
            recordStructuredAuditStep(request.auditSteps(), workflow, -1, blockedStep);
            return new WorkflowRunResult(workflow, results, false, false, false, true, true);
        }

        for (int i = 0; i < (workflow.steps() == null ? 0 : workflow.steps().size()); i++) {
            OpsWorkflowStep workflowStep = workflow.steps().get(i);
            PreparedInvocation invocation = prepareInvocation(workflow, workflowStep, variables);
            if (invocation.abortReason() != null) {
                StepResult stepResult = new StepResult(
                        invocation.toolName(),
                        "SKIP",
                        false,
                        false,
                        false,
                        true,
                        invocation.abortReason(),
                        invocation.parameters(),
                        null
                );
                results.add(stepResult);
                recordStructuredAuditStep(request.auditSteps(), workflow, i, stepResult);
                failed = true;
                blocked = true;
                break;
            }

            WriteResolution resolution = resolveWriteMode(workflow, i, invocation, request);
            if (planForcePreview && "EXECUTE".equals(resolution.mode()) && AwmToolProfile.isWrite(invocation.toolName())) {
                resolution = new WriteResolution("PREVIEW",
                        "计划级门控要求确认：" + planDecision.code() + "；已降级为预览");
            }
            if ("SKIP".equals(resolution.mode())) {
                StepResult stepResult = new StepResult(
                        invocation.toolName(),
                        "SKIP",
                        true,
                        false,
                        false,
                        true,
                        resolution.reason(),
                        invocation.parameters(),
                        null
                );
                results.add(stepResult);
                recordStructuredAuditStep(request.auditSteps(), workflow, i, stepResult);
                blocked = true;
                continue;
            }

            Map<String, Object> parameters = new LinkedHashMap<>(invocation.parameters());
            if ("EXECUTE".equals(resolution.mode())) {
                WriteExecutionCoordinator.applyForcedWriteParams(invocation.toolName(), parameters);
            } else if (AwmToolProfile.isWrite(invocation.toolName())) {
                parameters.put("dryRun", true);
            }

            McpToolDispatchResult dispatched = mcpToolDispatcher.dispatch(invocation.toolName(), parameters);
            StepResult stepResult = summarizeStep(invocation.toolName(), resolution.mode(), parameters, dispatched);
            results.add(stepResult);
            recordStructuredAuditStep(request.auditSteps(), workflow, i, stepResult);

            anyExecuted |= stepResult.executed() && stepResult.success();
            anyPreviewed |= stepResult.preview() && stepResult.success();
            anyReadSucceeded |= "READ".equals(stepResult.mode()) && stepResult.success();
            if (!stepResult.success()) {
                failed = true;
                break;
            }
        }

        WorkflowRunResult result = new WorkflowRunResult(
                workflow, List.copyOf(results), anyExecuted, anyPreviewed, anyReadSucceeded, failed, blocked);
        persistRun(request, result);
        return result;
    }

    private PlanEffectGate.PlanDecision evaluateWorkflowPlan(OpsWorkflow workflow, Map<String, Object> variables) {
        if (planEffectGate == null || workflow == null || workflow.steps() == null || workflow.steps().isEmpty()) {
            return new PlanEffectGate.PlanDecision(
                    PlanEffectGate.DecisionType.ALLOW, "PLAN_SKIP", "无计划门控",
                    0, 0, 0, false, List.of());
        }
        List<PlanEffectGate.PlannedCall> calls = new ArrayList<>();
        for (OpsWorkflowStep workflowStep : workflow.steps()) {
            PreparedInvocation invocation = prepareInvocation(workflow, workflowStep, variables);
            if (invocation.abortReason() != null) {
                continue;
            }
            calls.add(new PlanEffectGate.PlannedCall(invocation.toolName(), invocation.parameters()));
        }
        return planEffectGate.evaluate(calls);
    }

    private void persistRun(ExecutionRequest request, WorkflowRunResult result) {
        if (workflowMemoryService == null || !workflowMemoryService.isEnabled()) {
            return;
        }
        if (result.workflow() == null || result.workflow().workflowId() == null) {
            return;
        }
        String summary = result.stepResults().stream()
                .filter(StepResult::success)
                .map(s -> s.toolName() + ":" + s.mode())
                .reduce((a, b) -> a + ", " + b)
                .orElse("no-steps");
        workflowMemoryService.recordRun(
                result.workflow().workflowId(),
                request.traceId(),
                result.completedOk(),
                result.successfulStepCount(),
                result.stepResults().size(),
                summary);
        if (result.completedOk()) {
            workflowMemoryService.recordSuccess(result.workflow().workflowId());
            workflowMemoryService.recordUtility(result.workflow().workflowId());
        }
    }

    private Map<String, Object> prepareVariables(Map<String, Object> supplied, String userMessage) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("log-path", defaultLogRoot());
        variables.put("temp-path", defaultTempRoot());
        variables.put("log-days", logCleanDays);
        variables.put("temp-days", tempCleanDays);
        variables.put("dryRun", true);
        Optional<String> extractedPath = OpsPathExtractSupport.bestPath(userMessage);
        extractedPath.ifPresent(path -> variables.putIfAbsent("user-path", path));
        if (supplied != null) {
            variables.putAll(supplied);
        }
        return variables;
    }

    private PreparedInvocation prepareInvocation(
            OpsWorkflow workflow,
            OpsWorkflowStep workflowStep,
            Map<String, Object> variables
    ) {
        String toolName = AwmToolProfile.normalize(workflowStep.toolName());
        if (!AwmToolProfile.isSupported(toolName)) {
            return new PreparedInvocation(toolName, Map.of(),
                    "AWM 仅允许复用受治理的工具，当前步骤不在允许列表: " + toolName);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (workflowStep.argsTemplate() != null) {
            for (Map.Entry<String, String> entry : workflowStep.argsTemplate().entrySet()) {
                String normalizedKey = normalizeArgKey(toolName, entry.getKey());
                if (normalizedKey == null || normalizedKey.isBlank()) {
                    continue;
                }
                Object resolved = resolveArgValue(entry.getValue(), variables);
                if (resolved != null) {
                    params.put(normalizedKey, coerceValue(resolved));
                }
            }
        }
        applyToolDefaults(toolName, params, variables);
        String missing = validateRequiredParam(toolName, params);
        return new PreparedInvocation(toolName, params, missing);
    }

    private WriteResolution resolveWriteMode(
            OpsWorkflow workflow,
            int stepIndex,
            PreparedInvocation invocation,
            ExecutionRequest request
    ) {
        if (!AwmToolProfile.isWrite(invocation.toolName())) {
            return WriteResolution.readOnly();
        }
        if (request.surface() == McpToolSurface.READ_ONLY) {
            return WriteResolution.skip("当前为只读工具面，不执行写操作");
        }

        String instruction = "AWM workflow=" + workflow.workflowId()
                + " step=" + (stepIndex + 1)
                + " tool=" + invocation.toolName()
                + " params=" + invocation.parameters();
        OpsRemediationGate.RemediationDecision decision = switch (invocation.toolName()) {
            case "CleanTempTool" -> opsRemediationGate.decideTempCleanup(
                    stringParam(invocation.parameters(), "path"),
                    intParam(invocation.parameters(), "days", tempCleanDays),
                    request.forceExecute(),
                    instruction);
            case "LogCleanupTool" -> opsRemediationGate.decideLogCleanup(
                    stringParam(invocation.parameters(), "path"),
                    intParam(invocation.parameters(), "days", logCleanDays),
                    request.forceExecute(),
                    instruction);
            case "ServiceRestartTool" -> opsRemediationGate.decideServiceRestart(
                    stringParam(invocation.parameters(), "serviceName"),
                    request.forceExecute(),
                    instruction);
            default -> null;
        };
        if (decision == null) {
            return WriteResolution.skip("当前写工具未接入治理门: " + invocation.toolName());
        }
        if (decision.forbidden()) {
            return WriteResolution.skip(decision.reason());
        }
        if (decision.mayPreview()) {
            return WriteResolution.preview(decision.reason());
        }
        return WriteResolution.execute(decision.reason());
    }

    private StepResult summarizeStep(
            String toolName,
            String mode,
            Map<String, Object> parameters,
            McpToolDispatchResult dispatched
    ) {
        if (!dispatched.success()) {
            return new StepResult(
                    toolName,
                    mode,
                    false,
                    "EXECUTE".equals(mode),
                    "PREVIEW".equals(mode),
                    false,
                    dispatched.errorMessage(),
                    parameters,
                    null
            );
        }
        String raw = String.valueOf(dispatched.data());
        boolean success = McpToolPayloadParser.isSuccessful(objectMapper, raw);
        if ("EXECUTE".equals(mode) && AwmToolProfile.isWrite(toolName)) {
            success = success && WriteToolResultSupport.isConfirmedRealWrite(raw);
        } else if ("PREVIEW".equals(mode) && AwmToolProfile.isWrite(toolName)) {
            success = success && McpToolPayloadParser.isSuccessful(objectMapper, raw);
        }
        String message = success
                ? summarizeToolPayload(toolName, mode, raw)
                : WriteToolResultSupport.errorMessage(raw);
        return new StepResult(
                toolName,
                mode,
                success,
                "EXECUTE".equals(mode) && success,
                "PREVIEW".equals(mode) && success,
                false,
                message,
                parameters,
                raw
        );
    }

    private String summarizeToolPayload(String toolName, String mode, String raw) {
        JsonNode nested = McpToolPayloadParser.parsePayload(objectMapper, raw);
        if (nested == null) {
            if (!McpToolPayloadParser.isSuccessful(objectMapper, raw)) {
                return McpToolPayloadParser.errorMessage(objectMapper, raw);
            }
            return "工具返回成功，但结果无法结构化解析";
        }
        return switch (toolName) {
            case "DiskTool" -> summarizeDiskRows(nested, "已采集磁盘占用快照");
            case "DiskAnalyzeTool" -> summarizeDiskAnalyze(nested);
            case "DiskInsightTool" -> summarizeHotspotRoot(nested, "已完成目录热点扫描");
            case "SystemLoadTool" -> summarizeSystemLoad(nested);
            case "ProcessTool", "ProcessOpsTool" -> summarizeProcessList(nested);
            case "SystemdTool", "ServiceOpsTool" -> summarizeSystemd(nested);
            case "NetworkTool" -> summarizeNetwork(nested);
            case "PortHealthTool" -> summarizePortHealth(nested);
            case "CleanTempTool" -> summarizeCleanTemp(mode, nested);
            case "LogCleanupTool" -> summarizeLogCleanup(mode, nested);
            case "ServiceRestartTool" -> summarizeServiceRestart(mode, nested);
            case "LogAnalysisTool" -> summarizeLogAnalysis(nested);
            case "DockerTool", "ContainerOpsTool" -> summarizeDocker(nested);
            case "FirewallTool" -> "已采集防火墙状态";
            case "SslCertTool" -> summarizeSsl(nested);
            case "CronJobTool" -> summarizeCron(nested);
            case "ConfigCheckTool" -> summarizeConfigCheck(nested);
            case "ConfigDriftTool" -> summarizeConfigDrift(nested);
            case "OsInsightTool" -> summarizeOsInsight(nested);
            case "PrivilegeTool" -> "已完成权限探测";
            case "DiskOpsTool", "LogOpsTool", "AutonomousOpsTool" -> summarizeGatewayTool(toolName, nested);
            default -> "工具执行成功（" + toolName + "）";
        };
    }

    private String summarizeDiskRows(JsonNode data, String fallback) {
        JsonNode rows = data.isArray() ? data : data.path("overview");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return fallback;
        }
        StringBuilder sb = new StringBuilder("磁盘：");
        int limit = Math.min(4, rows.size());
        for (int i = 0; i < limit; i++) {
            JsonNode row = rows.get(i);
            if (i > 0) {
                sb.append("；");
            }
            String mount = row.path("mountedOn").asText(row.path("filesystem").asText("?"));
            String pct = row.path("usePercent").asText(row.path("usage").asText("-"));
            sb.append(mount).append(" ").append(pct);
        }
        return sb.toString();
    }

    private String summarizeDiskAnalyze(JsonNode data) {
        StringBuilder sb = new StringBuilder();
        JsonNode overview = data.path("overview");
        if (overview.isArray() && !overview.isEmpty()) {
            sb.append("已分析 ").append(overview.size()).append(" 个分区");
            double max = 0;
            String maxDrive = "";
            for (JsonNode row : overview) {
                double pct = parsePercent(row.path("usePercent").asText(""));
                if (pct > max) {
                    max = pct;
                    maxDrive = row.path("mountedOn").asText(row.path("filesystem").asText(""));
                }
            }
            if (max > 0 && !maxDrive.isBlank()) {
                sb.append("，最高 ").append(maxDrive).append(" ").append((int) max).append("%");
            }
        } else {
            sb.append("已完成磁盘压力分析");
        }
        if (data.has("multiDrive")) {
            sb.append("，已扫描多盘热点");
        } else if (data.has("hotspotsRank")) {
            sb.append("，已定位热点目录");
        }
        return sb.toString();
    }

    private String summarizeHotspotRoot(JsonNode data, String fallback) {
        JsonNode entries = data.path("entries");
        if (!entries.isArray() || entries.isEmpty()) {
            return fallback;
        }
        return "热点 Top " + entries.size() + "：" + entries.get(0).path("path").asText("-");
    }

    private String summarizeSystemLoad(JsonNode data) {
        double cpu = data.path("cpuUsagePercent").asDouble(-1);
        double mem = data.path("memUsagePercent").asDouble(-1);
        if (cpu < 0 && mem < 0) {
            return "已采集 CPU / 内存 / 负载指标";
        }
        return String.format(Locale.ROOT, "CPU %.0f%%，内存 %.0f%%", Math.max(0, cpu), Math.max(0, mem));
    }

    private String summarizeProcessList(JsonNode data) {
        JsonNode arr = data.isArray() ? data : data.path("processes");
        if (arr != null && arr.isArray() && !arr.isEmpty()) {
            return "已列出 Top " + arr.size() + " 进程（按 CPU/内存）";
        }
        return "已列出高占用进程样本";
    }

    private String summarizeSystemd(JsonNode data) {
        if (data.has("failedUnits") && data.get("failedUnits").isArray()) {
            int n = data.get("failedUnits").size();
            return n == 0 ? "未发现 failed 服务" : "发现 " + n + " 个异常服务";
        }
        return "已列出异常服务状态";
    }

    private String summarizeNetwork(JsonNode data) {
        String type = data.path("type").asText("");
        if ("ping".equals(type)) {
            return String.format(Locale.ROOT, "Ping %s：丢包 %s%%",
                    data.path("target").asText("-"), data.path("packetLossPercent").asText("-"));
        }
        return "已完成网络探测";
    }

    private String summarizePortHealth(JsonNode data) {
        JsonNode ports = data.path("unreachablePorts");
        if (ports.isArray()) {
            return ports.isEmpty() ? "监控端口均可达" : "不可达端口 " + ports.size() + " 个";
        }
        return "已完成端口探测";
    }

    private String summarizeLogAnalysis(JsonNode data) {
        int anomalies = data.path("anomalyCount").asInt(data.path("totalAnomalies").asInt(-1));
        if (anomalies >= 0) {
            return "日志分析完成，异常 " + anomalies + " 条";
        }
        return "已完成日志分析";
    }

    private String summarizeDocker(JsonNode data) {
        JsonNode containers = data.path("containers");
        if (containers.isArray()) {
            return "容器 " + containers.size() + " 个";
        }
        return "已完成容器巡检";
    }

    private String summarizeSsl(JsonNode data) {
        int days = data.path("daysUntilExpiry").asInt(-1);
        if (days >= 0) {
            return "证书剩余 " + days + " 天";
        }
        return "已完成证书检查";
    }

    private String summarizeCron(JsonNode data) {
        JsonNode jobs = data.path("jobs");
        if (jobs.isArray()) {
            return "定时任务 " + jobs.size() + " 条";
        }
        return "已列出定时任务";
    }

    private String summarizeConfigCheck(JsonNode data) {
        boolean passed = data.path("passed").asBoolean(false);
        return passed ? "配置检查通过" : "配置检查未通过";
    }

    private String summarizeConfigDrift(JsonNode data) {
        boolean drift = data.path("driftDetected").asBoolean(data.path("changed").asBoolean(false));
        return drift ? "检测到配置漂移" : "配置未漂移";
    }

    private String summarizeOsInsight(JsonNode data) {
        String op = data.path("operation").asText(data.path("mode").asText(""));
        return op.isBlank() ? "已完成 OS 洞察" : "OS 洞察：" + op;
    }

    private String summarizeGatewayTool(String toolName, JsonNode data) {
        String op = data.path("operation").asText(data.path("mode").asText(""));
        if (!op.isBlank()) {
            return toolName + " / " + op + " 完成";
        }
        return toolName + " 执行成功";
    }

    private static double parsePercent(String usage) {
        if (usage == null || usage.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(usage.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private JsonNode parseNestedData(String raw) {
        return McpToolPayloadParser.parsePayload(objectMapper, raw);
    }

    private String summarizeCleanTemp(String mode, JsonNode nested) {
        if ("PREVIEW".equals(mode)) {
            int found = nested.path("filesFound").asInt(
                    nested.path("preview").path("entries").asInt(0));
            return "预览到可清理临时文件 " + found + " 个";
        }
        int deleted = nested.path("filesDeleted").asInt(0);
        return "已清理临时文件 " + deleted + " 个";
    }

    private String summarizeLogCleanup(String mode, JsonNode nested) {
        if ("PREVIEW".equals(mode)) {
            int found = nested.path("deletableCount").asInt(nested.path("filesFound").asInt(0));
            return "预览到可清理日志文件 " + found + " 个";
        }
        int deleted = nested.path("filesDeleted").asInt(0);
        return "已清理日志文件 " + deleted + " 个";
    }

    private String summarizeServiceRestart(String mode, JsonNode nested) {
        String service = nested.path("service").asText("service");
        if ("PREVIEW".equals(mode)) {
            return "已生成服务重启预览: " + service;
        }
        return "已执行服务重启: " + service;
    }

    private void recordStructuredAuditStep(
            List<Map<String, Object>> auditSteps,
            OpsWorkflow workflow,
            int stepIndex,
            StepResult stepResult
    ) {
        if (auditSteps == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workflowId", workflow.workflowId());
        detail.put("workflowTitle", workflow.title());
        detail.put("workflowStep", stepIndex + 1);
        detail.put("toolName", stepResult.toolName());
        detail.put("mode", stepResult.mode());
        detail.put("success", stepResult.success());
        detail.put("message", stepResult.message());
        detail.put("parameters", stepResult.parameters());
        if (stepResult.rawResult() != null) {
            detail.put("detail", truncate(stepResult.rawResult(), 1200));
        }
        auditRecorder.addStructuredStep(
                auditSteps,
                "EXECUTE".equals(stepResult.mode()) ? "execute"
                        : ("PREVIEW".equals(stepResult.mode()) ? "preview" : "workflow"),
                detail
        );
    }

    private void applyToolDefaults(String toolName, Map<String, Object> params, Map<String, Object> variables) {
        switch (toolName) {
            case "DiskAnalyzeTool" -> {
                params.putIfAbsent("rootPath", variables.get("log-path"));
                params.putIfAbsent("includeHotspots", true);
                params.putIfAbsent("topN", 12);
            }
            case "ProcessTool" -> {
                params.putIfAbsent("operation", "list");
                params.putIfAbsent("minCpu", 5.0);
                params.putIfAbsent("minMem", 5.0);
            }
            case "SystemdTool" -> params.putIfAbsent("operation", "failed");
            case "CleanTempTool" -> {
                params.putIfAbsent("path", variables.get("temp-path"));
                params.putIfAbsent("days", variables.get("temp-days"));
                String path = stringParam(params, "path");
                if (OpsPathExtractSupport.isCleanableSubDirectory(opsPathPolicy, path)) {
                    params.put("removeDirectory", true);
                    params.put("days", 0);
                }
            }
            case "LogCleanupTool" -> {
                params.putIfAbsent("path", variables.get("log-path"));
                params.putIfAbsent("days", variables.get("log-days"));
            }
            case "ServiceRestartTool" -> params.putIfAbsent("serviceName", variables.get("service-name"));
            case "NetworkTool" -> params.putIfAbsent("operation", "ping");
            case "PortHealthTool" -> params.putIfAbsent("operation", "scan");
            case "DockerTool", "ContainerOpsTool" -> params.putIfAbsent("operation", "list");
            case "LogAnalysisTool" -> params.putIfAbsent("operation", "summary");
            case "FirewallTool" -> params.putIfAbsent("operation", "status");
            case "SslCertTool" -> params.putIfAbsent("operation", "check");
            case "CronJobTool" -> params.putIfAbsent("operation", "list");
            case "ConfigCheckTool", "ConfigDriftTool" -> params.putIfAbsent("operation", "check");
            case "OsInsightTool" -> params.putIfAbsent("operation", "summary");
            case "PrivilegeTool" -> params.putIfAbsent("operation", "check");
            case "ProcessOpsTool" -> {
                params.putIfAbsent("operation", "list");
                params.putIfAbsent("minCpu", 5.0);
                params.putIfAbsent("minMem", 5.0);
            }
            case "ServiceOpsTool" -> params.putIfAbsent("operation", "failed");
            case "LogOpsTool", "DiskOpsTool" -> params.putIfAbsent("operation", "status");
            default -> {
                // no-op
            }
        }
    }

    private String validateRequiredParam(String toolName, Map<String, Object> params) {
        return switch (toolName) {
            case "CleanTempTool", "LogCleanupTool" -> isBlank(stringParam(params, "path"))
                    ? "缺少可执行路径参数" : null;
            case "ServiceRestartTool" -> isBlank(stringParam(params, "serviceName"))
                    ? "缺少服务名，无法回放服务重启 workflow" : null;
            default -> null;
        };
    }

    private Object resolveArgValue(String raw, Map<String, Object> variables) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(raw.trim());
        if (!matcher.matches()) {
            return raw;
        }
        return variables.get(matcher.group(1));
    }

    private String normalizeArgKey(String toolName, String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        return switch (toolName) {
            case "DiskAnalyzeTool" -> switch (rawKey) {
                case "path", "rootPath" -> "rootPath";
                case "includeSubdirs", "includeHotspots" -> "includeHotspots";
                default -> rawKey;
            };
            default -> rawKey;
        };
    }

    private Object coerceValue(Object raw) {
        if (!(raw instanceof String s)) {
            return raw;
        }
        String trimmed = s.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String defaultLogRoot() {
        return runtimePlatform.defaultLogRoot();
    }

    private String defaultTempRoot() {
        return runtimePlatform.defaultTempRoot();
    }

    private record PreparedInvocation(String toolName, Map<String, Object> parameters, String abortReason) {
    }

    private record WriteResolution(String mode, String reason) {
        static WriteResolution readOnly() {
            return new WriteResolution("READ", "读取步骤");
        }

        static WriteResolution preview(String reason) {
            return new WriteResolution("PREVIEW", reason);
        }

        static WriteResolution execute(String reason) {
            return new WriteResolution("EXECUTE", reason);
        }

        static WriteResolution skip(String reason) {
            return new WriteResolution("SKIP", reason);
        }
    }
}
