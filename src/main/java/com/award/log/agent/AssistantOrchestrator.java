package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowExecutionService;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.McpToolPayloadParser;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ProcessTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAutoRemediationService;
import com.award.log.util.OpsPathExtractSupport;
import com.award.log.util.RuntimePlatform;
import com.award.log.util.TimeSource;
import com.award.log.util.TraceIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作台运维闭环：感知 → 预览 →（策略内）执行 → 验证，减少「空确认」、提高真实可运维性。
 */
@Slf4j
@Service
public class AssistantOrchestrator {

    private final OpsIntentRouter opsIntentRouter;

    private final DiskTool diskTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final LogCleanupTool logCleanupTool;
    private final CleanTempTool cleanTempTool;
    private final SystemLoadTool systemLoadTool;
    private final ProcessTool processTool;
    private final ServiceRestartTool serviceRestartTool;
    private final OpsRemediationGate opsRemediationGate;
    private final OpsPathPolicy opsPathPolicy;
    private final ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    private final AssistantAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final WorkflowRetriever workflowRetriever;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowInductionService workflowInductionService;
    private final RuntimePlatform runtimePlatform;
    private final TimeSource timeSource;
    private final TraceIdGenerator traceIdGenerator;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    @Autowired(required = false)
    private RemediationEffectEvaluator remediationEffectEvaluator;

    public AssistantOrchestrator(
            DiskTool diskTool,
            DiskAnalyzeTool diskAnalyzeTool,
            LogCleanupTool logCleanupTool,
            CleanTempTool cleanTempTool,
            SystemLoadTool systemLoadTool,
            ProcessTool processTool,
            ServiceRestartTool serviceRestartTool,
            OpsRemediationGate opsRemediationGate,
            OpsPathPolicy opsPathPolicy,
            ServiceRestartCandidateResolver serviceRestartCandidateResolver,
            AssistantAuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            OpsIntentRouter opsIntentRouter,
            WorkflowRetriever workflowRetriever,
            WorkflowExecutionService workflowExecutionService,
            WorkflowInductionService workflowInductionService,
            RuntimePlatform runtimePlatform,
            TimeSource timeSource,
            TraceIdGenerator traceIdGenerator) {
        this.opsIntentRouter = opsIntentRouter;
        this.diskTool = diskTool;
        this.diskAnalyzeTool = diskAnalyzeTool;
        this.logCleanupTool = logCleanupTool;
        this.cleanTempTool = cleanTempTool;
        this.systemLoadTool = systemLoadTool;
        this.processTool = processTool;
        this.serviceRestartTool = serviceRestartTool;
        this.opsRemediationGate = opsRemediationGate;
        this.opsPathPolicy = opsPathPolicy;
        this.serviceRestartCandidateResolver = serviceRestartCandidateResolver;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.workflowRetriever = workflowRetriever;
        this.workflowExecutionService = workflowExecutionService;
        this.workflowInductionService = workflowInductionService;
        this.runtimePlatform = runtimePlatform;
        this.timeSource = timeSource;
        this.traceIdGenerator = traceIdGenerator;
    }

    @Value("${agent.assistant.orchestrator.log-clean-days:30}")
    private int logCleanDays;

    @Value("${agent.assistant.orchestrator.temp-clean-days:7}")
    private int tempCleanDays;

    @Value("${agent.assistant.orchestrator.disk-pressure-percent:80}")
    private double diskPressurePercent;

    public record RunResult(String markdown, String traceId, Map<String, Object> streamMeta) {
        public RunResult(String markdown, String traceId) {
            this(markdown, traceId, Map.of());
        }
    }

    public boolean supports(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        OpsIntentRouter.Playbook pb = opsIntentRouter.resolve(userMessage);
        return pb == OpsIntentRouter.Playbook.DISK_CLEANUP
                || pb == OpsIntentRouter.Playbook.CPU_PRESSURE
                || pb == OpsIntentRouter.Playbook.PATROL_CONTINUATION;
    }

    public RunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        OpsIntentRouter.Playbook playbook = opsIntentRouter.resolve(userMessage);
        if (playbook == OpsIntentRouter.Playbook.PATROL_CONTINUATION) {
            return runPatrolContinuation(userMessage, surface, intentRisk);
        }
        if (playbook == OpsIntentRouter.Playbook.CPU_PRESSURE) {
            return runCpuPressure(userMessage, surface, intentRisk);
        }
        long t0 = timeSource.currentTimeMillis();
        String traceId = traceIdGenerator.nextId();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            String md = buildReadOnlyReply(traceId);
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "READ_ONLY_SURFACE",
                    "AssistantOrchestrator", false, "只读工具面", steps, timeSource.currentTimeMillis() - t0);
            return new RunResult(md, traceId);
        }

        boolean forceExecute = resolveForceExecute(userMessage);
        Map<String, Object> metricsBefore = null;
        if (forceExecute && remediationEffectEvaluator != null) {
            metricsBefore = remediationEffectEvaluator.captureMetrics();
        }
        StringBuilder md = new StringBuilder();
        md.append("## 运维闭环报告\n\n");

        OpsWorkflow workflowHit = workflowRetriever.bestMatch(
                "disk", List.of("DISK_PRESSURE"), userMessage);

        try {
            LinkedHashSet<String> toolsInvoked = new LinkedHashSet<>();
            auditRecorder.addCot(steps, 2, "感知", "AWM 回放 + 磁盘采集");
            WorkflowExecutionService.WorkflowRunResult workflowRun = executeAwmWorkflow(
                    workflowHit, traceId, userMessage, surface, forceExecute, steps,
                    buildDiskWorkflowVariables(userMessage, forceExecute));
            collectToolsFromWorkflow(workflowRun, toolsInvoked);

            String diskOverview = workflowRun != null
                    ? workflowRun.rawResultForTool("DiskTool").orElse(null)
                    : null;
            String analyzeJson = workflowRun != null
                    ? workflowRun.rawResultForTool("DiskAnalyzeTool").orElse(null)
                    : null;

            if (diskOverview == null) {
                diskOverview = diskTool.checkDiskUsage();
                auditRecorder.addStep(steps, "perceive", truncate(diskOverview, 800));
                toolsInvoked.add("DiskTool");
            }
            if (analyzeJson == null) {
                String analyzeRoot = runtimePlatform.isWindows() ? null : defaultLogRoot();
                analyzeJson = diskAnalyzeTool.analyzeDiskPressure(analyzeRoot, true, 12);
                auditRecorder.addStep(steps, "perceive", truncate(analyzeJson, 800));
                toolsInvoked.add("DiskAnalyzeTool");
            }

            md.append("### 环境感知\n\n");
            md.append(summarizeDisk(diskOverview)).append("\n\n");
            md.append("### 热点目录\n\n");
            md.append(summarizeDiskAnalyze(analyzeJson)).append("\n\n");

            if (workflowHit != null && workflowRun != null) {
                md.append(buildWorkflowResultSummary(workflowRun)).append("\n\n");
            }

            double maxUse = extractMaxDiskUsePercent(diskOverview);
            boolean needTemp = maxUse >= diskPressurePercent || mentionsTemp(userMessage);
            boolean needLog = mentionsLog(userMessage) || maxUse >= diskPressurePercent;

            if (!needTemp && !needLog) {
                needLog = true;
            }

            boolean anyExecuted = workflowRun != null && workflowRun.anyExecuted();
            boolean remediationDone = workflowRun != null
                    && workflowRun.completedOk()
                    && workflowRun.anyExecuted();

            if (!remediationDone) {
                if (needTemp) {
                    anyExecuted |= appendTempCleanupSection(md, steps, forceExecute, userMessage);
                    toolsInvoked.add("CleanTempTool");
                }
                if (needLog) {
                    anyExecuted |= appendLogCleanupSection(md, steps, forceExecute);
                    toolsInvoked.add("LogCleanupTool");
                }
            }

            auditRecorder.addCot(steps, 4, "验证", "再次采集 df");
            String verifyDisk = diskTool.checkDiskUsage();
            auditRecorder.addStep(steps, "verify", truncate(verifyDisk, 600));
            toolsInvoked.add("DiskTool");
            md.append("### 验证\n\n");
            md.append(summarizeDisk(verifyDisk)).append("\n\n");

            if (anyExecuted) {
                appendEffectSection(md, metricsBefore, 1);
            }

            if (!anyExecuted) {
                md.append("\n").append(OpsReportFormat.previewPendingFooter("自动清理")).append("\n");
                md.append(OpsReportFormat.remediationPlanMarkdown(List.of(
                        "预览清理临时目录（Dry-Run）",
                        "预览旧日志清理（Dry-Run）",
                        "再次采集磁盘占用做验证",
                        "写操作须回复「确认执行」后落地"
                )));
            } else {
                md.append("\n").append(OpsReportFormat.executedFooter()).append("\n");
            }

            String securityOutcome = resolveSecurityOutcome(anyExecuted, workflowRun);
            auditRecorder.record(traceId, userMessage, intentRisk.name(), securityOutcome,
                    "AssistantOrchestrator", true, md.substring(0, Math.min(500, md.length())), steps,
                    timeSource.currentTimeMillis() - t0);
            workflowInductionService.afterSuccessfulRun(
                    traceId,
                    userMessage,
                    securityOutcome,
                    true,
                    steps,
                    "disk",
                    List.of("DISK_PRESSURE"));
            return new RunResult(md.toString(), traceId,
                    buildStreamMeta(traceId, securityOutcome, workflowHit, workflowRun, toolsInvoked));

        } catch (Exception e) {
            log.error("编排器执行失败 traceId={}", traceId, e);
            auditRecorder.addStep(steps, "error", e.getMessage());
            md.append("\n\n**编排失败:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "AssistantOrchestrator", false, e.getMessage(), steps, timeSource.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);
        }
    }


    private WorkflowExecutionService.WorkflowRunResult executeAwmWorkflow(
            OpsWorkflow workflowHit,
            String traceId,
            String userMessage,
            McpToolSurface surface,
            boolean forceExecute,
            List<Map<String, Object>> steps,
            Map<String, Object> variables) {
        if (workflowHit == null) {
            return null;
        }
        auditRecorder.addStep(steps, "workflow", "命中 AWM workflow=" + workflowHit.workflowId());
        return workflowExecutionService.execute(
                workflowHit,
                new WorkflowExecutionService.ExecutionRequest(
                        traceId,
                        userMessage,
                        surface,
                        forceExecute,
                        steps,
                        variables));
    }

    private static String resolveSecurityOutcome(
            boolean anyExecuted,
            WorkflowExecutionService.WorkflowRunResult workflowRun) {
        if (anyExecuted) {
            return "EXECUTED";
        }
        if (workflowRun != null && workflowRun.handled()) {
            return "DIAGNOSED";
        }
        return "PREVIEW";
    }

    private static void collectToolsFromWorkflow(
            WorkflowExecutionService.WorkflowRunResult workflowRun,
            Set<String> tools) {
        if (workflowRun == null || workflowRun.stepResults() == null || tools == null) {
            return;
        }
        for (WorkflowExecutionService.StepResult step : workflowRun.stepResults()) {
            if (step.success() && step.toolName() != null && !step.toolName().isBlank()) {
                tools.add(step.toolName());
            }
        }
    }

    private Map<String, Object> buildStreamMeta(
            String traceId,
            String securityOutcome,
            OpsWorkflow workflowHit,
            WorkflowExecutionService.WorkflowRunResult workflowRun,
            Set<String> extraTools) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "orchestrate-result");
        meta.put("traceId", traceId);
        meta.put("securityOutcome", securityOutcome);
        meta.put("replyMode", "ORCHESTRATE");
        if (workflowHit != null) {
            meta.put("awmWorkflowId", workflowHit.workflowId());
            meta.put("awmWorkflowTitle", workflowHit.title());
        }
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        collectToolsFromWorkflow(workflowRun, tools);
        if (extraTools != null) {
            tools.addAll(extraTools);
        }
        List<String> plannedTools = List.copyOf(tools);
        boolean writeConfirmed = workflowRun != null && workflowRun.anyExecuted();
        meta.put("toolsUsed", plannedTools);
        meta.put("plannedTools", plannedTools);
        meta.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
        meta.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        meta.put("planPhase", AgentSkillPlan.planPhase(writeConfirmed, plannedTools));
        meta.put("awaitingConfirm", !writeConfirmed && AgentSkillPlan.hasWriteTools(plannedTools));
        meta.put("writeConfirmed", writeConfirmed);
        meta.put("writeToolsMounted", writeConfirmed);
        meta.put("executionState", AgentExecutionState.build(
                "ORCHESTRATE",
                writeConfirmed,
                writeConfirmed,
                plannedTools,
                AgentSkillPlan.planPhase(writeConfirmed, plannedTools),
                traceId,
                securityOutcome,
                workflowHit));
        if (workflowRun != null) {
            meta.put("awmHandled", workflowRun.handled());
            meta.put("awmStepsOk", workflowRun.successfulStepCount());
        }
        return meta;
    }

    private boolean appendLogCleanupSection(StringBuilder md, List<Map<String, Object>> steps, boolean forceExecute)
            throws Exception {
        String path = defaultLogRoot();
        String instruction = "执行 LogCleanupTool 参数: path=" + path + " days=" + logCleanDays + " dryRun=true";
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideLogCleanup(path, logCleanDays, forceExecute, instruction);

        String preview = logCleanupTool.cleanupOldLogs(path, logCleanDays, true, false);
        if (!McpToolPayloadParser.isSuccessful(objectMapper, preview)) {
            md.append("### 日志清理 (`").append(path).append("`)\n\n");
            md.append("- **预览失败：** ").append(WriteToolResultSupport.errorMessage(preview)).append("\n\n");
            return false;
        }
        JsonNode data = parseToolData(preview);
        int found = deletableCount(data);
        int protectedSkip = data != null && data.has("protectedSkipped") ? data.get("protectedSkipped").asInt(0) : 0;

        auditRecorder.addCot(steps, 3, "推理", "日志清理预览 filesFound=" + found + " decision=" + decision.decision());
        md.append("### 日志清理 (`").append(path).append("`)\n\n");
        md.append("- 预览候选：**").append(found).append("** 个；保护规则跳过：**").append(protectedSkip).append("**\n");
        md.append("- ").append(decision.reason()).append("\n");

        if (found == 0) {
            md.append("- 无符合条件且允许删除的陈旧日志。\n\n");
            return false;
        }

        if (decision.forbidden()) {
            md.append("- **已拒绝执行**。\n\n");
            return false;
        }

        if (decision.mayPreview()) {
            md.append("- 已生成预览，等待您确认后再删。\n\n");
            return false;
        }

        String exec = logCleanupTool.cleanupOldLogs(path, logCleanDays, false, true);
        if (!WriteToolResultSupport.isConfirmedRealWrite(exec)) {
            md.append("- **执行失败：** ").append(WriteToolResultSupport.errorMessage(exec)).append("\n\n");
            return false;
        }
        JsonNode execData = parseToolData(exec);
        int deleted = execData != null && execData.has("filesDeleted") ? execData.get("filesDeleted").asInt(0) : 0;
        auditRecorder.addStep(steps, "execute", "LogCleanup deleted=" + deleted);
        md.append("- **已执行删除：** ").append(deleted).append(" 个文件（已排除受保护项）。\n\n");
        return deleted > 0;
    }

    private boolean appendTempCleanupSection(StringBuilder md, List<Map<String, Object>> steps, boolean forceExecute,
                                             String userMessage)
            throws Exception {
        if (OpsPathExtractSupport.bestPath(userMessage).isEmpty()) {
            return appendMultiDriveTempCleanup(md, steps, forceExecute, userMessage);
        }
        String defaultPath = defaultTempRoot();
        String path = OpsPathExtractSupport.bestPath(userMessage).orElse(defaultPath);
        boolean removeDirectory = OpsPathExtractSupport.isCleanableSubDirectory(opsPathPolicy, path);
        int days = (forceExecute || removeDirectory) ? 0 : tempCleanDays;

        String instruction = "执行 CleanTempTool path=" + path + " days=" + days
                + (removeDirectory ? " removeDirectory=true" : "");
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideTempCleanup(path, days, forceExecute, instruction);

        String preview = cleanTempTool.cleanTempFiles(path, days, true, false, removeDirectory);
        JsonNode previewRoot = objectMapper.readTree(preview);
        if (!previewRoot.path("success").asBoolean(false)) {
            md.append("### 临时目录清理 (`").append(path).append("`)\n\n");
            md.append("- **预览失败：** ").append(previewRoot.path("error").asText("未知错误")).append("\n");
            md.append("- ").append(decision.reason()).append("\n\n");
            auditRecorder.addStep(steps, "preview_error", previewRoot.path("error").asText(""));
            return false;
        }
        JsonNode data = parseToolData(preview);
        int found = data != null && data.has("filesFound") ? data.get("filesFound").asInt(0) : 0;
        if (removeDirectory && data != null && data.has("preview")) {
            JsonNode p = data.get("preview");
            if (p.has("entries")) {
                found = p.get("entries").asInt(0);
            }
        }

        md.append("### 临时目录清理 (`").append(path).append("`)\n\n");
        md.append("- 预览：**").append(found).append("** 个条目");
        if (removeDirectory) {
            md.append("（整目录递归）");
        }
        md.append("\n");
        md.append("- ").append(decision.reason()).append("\n");

        if (found == 0 && !removeDirectory) {
            if (decision.forbidden()) {
                md.append("- **已拒绝执行**。\n\n");
            } else {
                md.append("- 无可清理文件（默认仅删除 **").append(days).append("** 天前的文件；指定子目录可用 removeDirectory）。\n\n");
            }
            return false;
        }
        if (found == 0 && removeDirectory && data != null && data.path("preview").path("exists").asBoolean(false) == false) {
            md.append("- 目标路径不存在或为空。\n\n");
            return false;
        }

        if (decision.forbidden()) {
            md.append("- **已拒绝执行**。\n\n");
            return false;
        }

        if (decision.mayPreview()) {
            md.append("- 已生成预览，等待您回复 **「确认执行」** 或 **「直接删除」** 后再删。\n\n");
            return false;
        }

        String exec = cleanTempTool.cleanTempFiles(path, days, false, true, removeDirectory);
        JsonNode execRoot = objectMapper.readTree(exec);
        if (!execRoot.path("success").asBoolean(false)) {
            md.append("- **执行失败：** ").append(execRoot.path("error").asText("未知错误")).append("\n\n");
            return false;
        }
        JsonNode execData = parseToolData(exec);
        int deleted = execData != null && execData.has("filesDeleted") ? execData.get("filesDeleted").asInt(0) : 0;
        long bytesFreed = execData != null && execData.has("bytesFreed") ? execData.get("bytesFreed").asLong(0) : 0;
        auditRecorder.addStep(steps, "execute", "CleanTemp deleted=" + deleted + " path=" + path);
        if (deleted <= 0 && !(removeDirectory && execData != null && "DELETE".equals(execData.path("mode").asText())
                && WriteToolResultSupport.isConfirmedRealWrite(exec))) {
            md.append("- **未删除到文件**（路径下无匹配或无写权限），请勿视为已完成清理。\n\n");
            return false;
        }
        md.append("- **已执行删除：** ").append(deleted).append(" 项");
        if (bytesFreed > 0) {
            md.append("，约 **").append(String.format(Locale.ROOT, "%.1f", bytesFreed / (1024.0 * 1024.0))).append(" MiB**");
        }
        md.append("\n\n");
        return true;
    }

    /** 未指定路径时：扫描全部盘符 Temp 白名单目录，列出垃圾并可选批量清理。 */
    private boolean appendMultiDriveTempCleanup(StringBuilder md, List<Map<String, Object>> steps,
                                                boolean forceExecute, String userMessage) throws Exception {
        int days = forceExecute ? 0 : tempCleanDays;
        String scanJson = cleanTempTool.scanAllTempJunk(days);
        JsonNode scanRoot = objectMapper.readTree(scanJson);
        md.append("### 全盘垃圾扫描（各盘 Temp 白名单）\n\n");
        if (!scanRoot.path("success").asBoolean(false)) {
            md.append("- **扫描失败：** ").append(scanRoot.path("error").asText("未知错误")).append("\n\n");
            return false;
        }
        JsonNode scanData = parseToolData(scanJson);
        if (scanData == null || !scanData.has("locations")) {
            md.append("- 未获取到扫描结果。\n\n");
            return false;
        }
        int total = scanData.path("totalFilesFound").asInt(0);
        JsonNode drives = scanData.get("drives");
        if (drives != null && drives.isArray()) {
            md.append("- 检测到盘符：");
            for (JsonNode d : drives) {
                md.append(" `").append(d.asText()).append("`");
            }
            md.append("\n");
        }
        md.append("- 合计可清理临时文件：**").append(total).append("** 个（统计 ≥ **").append(days)
                .append("** 天前；0=含今日）\n\n");
        if (forceExecute) {
            md.append("- 模式：**直接执行**（可写且非系统 Temp 的路径将立即删除）\n\n");
        }
        md.append("| 盘符 | 路径 | 可清理 | 可自动删 | 执行结果 |\n| --- | --- | ---: | --- | --- |\n");
        boolean anyExecuted = false;
        int totalDeleted = 0;
        for (JsonNode loc : scanData.get("locations")) {
            String path = loc.path("path").asText("");
            String drive = loc.path("drive").asText("");
            boolean autoEligible = loc.path("autoCleanEligible").asBoolean(
                    !loc.path("systemElevatedTemp").asBoolean(false) && loc.path("writable").asBoolean(true));
            if (loc.has("error")) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | - | 否 | 扫描失败：")
                        .append(loc.path("error").asText()).append(" |\n");
                continue;
            }
            int found = loc.path("filesFound").asInt(0);
            String eligible = autoEligible ? "是" : "否";
            String skipReason = loc.path("skipReason").asText("");
            if (!forceExecute) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | ").append(found)
                        .append(" | ").append(eligible);
                if (!autoEligible && !skipReason.isBlank()) {
                    md.append(" | ").append(skipReason);
                } else {
                    md.append(" | 待执行");
                }
                md.append(" |\n");
                continue;
            }
            if (found <= 0) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | 0 | ")
                        .append(eligible).append(" | 无文件 |\n");
                continue;
            }
            if (!autoEligible) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | ").append(found)
                        .append(" | 否 | 跳过：").append(skipReason.isBlank() ? "不可自动删" : skipReason).append(" |\n");
                continue;
            }
            OpsRemediationGate.RemediationDecision decision =
                    opsRemediationGate.decideTempCleanup(path, days, true,
                            "执行 CleanTempTool path=" + path + " days=" + days);
            if (decision.forbidden() || decision.mayPreview()) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | ").append(found)
                        .append(" | 是 | 策略拦截：").append(decision.reason()).append(" |\n");
                continue;
            }
            String exec = cleanTempTool.cleanTempFiles(path, days, false, true, false);
            JsonNode execRoot = objectMapper.readTree(exec);
            if (!execRoot.path("success").asBoolean(false)) {
                md.append("| ").append(drive).append(" | `").append(path).append("` | ").append(found)
                        .append(" | 是 | **失败：** ").append(execRoot.path("error").asText("未知")).append(" |\n");
                continue;
            }
            JsonNode execData = parseToolData(exec);
            int deleted = execData != null && execData.has("filesDeleted") ? execData.get("filesDeleted").asInt(0) : 0;
            auditRecorder.addStep(steps, "execute", "CleanTemp multi-drive deleted=" + deleted + " path=" + path);
            anyExecuted |= deleted > 0;
            totalDeleted += deleted;
            md.append("| ").append(drive).append(" | `").append(path).append("` | ").append(found)
                    .append(" | 是 | **已删 ").append(deleted).append("** |\n");
        }
        md.append("\n");
        if (forceExecute && anyExecuted) {
            md.append("- **已真实删除合计 **").append(totalDeleted).append("** 个文件**（见上表「已删」行）。\n\n");
        } else if (!forceExecute && total > 0) {
            md.append("- 说 **「确认执行」** 或 **「直接删除」** 将立即清理上表「可自动删=是」的路径。\n\n");
        } else if (forceExecute && !anyExecuted && total > 0) {
            md.append("- 扫描到 ").append(total).append(" 个可清理文件，但未能删除（演练模式未关 / 权限 / 文件占用）。\n\n");
        } else if (total == 0) {
            md.append("- 各盘 Temp 白名单内暂无可清理文件；删整文件夹请带完整路径。\n\n");
        }
        return anyExecuted;
    }

    private RunResult runPatrolContinuation(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        long t0 = timeSource.currentTimeMillis();
        String traceId = traceIdGenerator.nextId();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            return new RunResult(buildReadOnlyReply(traceId), traceId);
        }
        if (opsAutoRemediationService == null) {
            return new RunResult("自动修复服务未启用。", traceId);
        }

        Map<String, Object> pending = opsAutoRemediationService.getPendingProposalView();
        if (!Boolean.TRUE.equals(pending.get("hasPending"))) {
            String md = "当前没有待执行方案。";
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "NO_PENDING",
                    "PatrolContinuation", false, md, steps, timeSource.currentTimeMillis() - t0);
            return new RunResult(md, traceId);
        }

        if (!resolveForceExecute(userMessage)) {
            String proposalId = String.valueOf(pending.get("proposalId"));
            StringBuilder preview = new StringBuilder();
            preview.append("## 巡检待确认方案\n\n");
            preview.append("检测到待执行方案 `").append(proposalId).append("`。\n\n");
            Object summary = pending.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) {
                preview.append("- 方案摘要：").append(summary).append("\n");
            }
            preview.append("\n请回复 **「确认执行」** 后再落地写操作（点「继续处理」也会弹出确认）。\n");
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "PREVIEW",
                    "PatrolContinuation", true, "await confirm", steps, timeSource.currentTimeMillis() - t0);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "orchestrate-result");
            meta.put("traceId", traceId);
            meta.put("securityOutcome", "PREVIEW");
            meta.put("replyMode", "ORCHESTRATE");
            meta.put("executionState", AgentExecutionState.build(
                    "ORCHESTRATE",
                    false,
                    false,
                    List.of("SystemLoadTool", "DiskTool", "DiskAnalyzeTool", "PortHealthTool"),
                    "DIAGNOSE",
                    traceId,
                    "PREVIEW",
                    null));
            return new RunResult(preview.toString(), traceId, meta);
        }

        String proposalId = String.valueOf(pending.get("proposalId"));
        auditRecorder.addCot(steps, 2, "感知", "加载巡检待确认方案 " + proposalId);
        auditRecorder.addCot(steps, 3, "推理", String.valueOf(pending.getOrDefault("summary", "")));

        Map<String, Object> metricsBefore = remediationEffectEvaluator != null
                ? remediationEffectEvaluator.captureMetrics()
                : null;
        Map<String, Object> exec = opsAutoRemediationService.confirmPendingFromAssistant(proposalId);
        boolean ok = Boolean.TRUE.equals(exec.get("success"));
        String resultSummary = String.valueOf(exec.getOrDefault("resultSummary", summarizeExecutionActions(exec.get("actions"))));
        auditRecorder.addStep(steps, "execute", resultSummary);
        auditRecorder.addCot(steps, 4, "验证", ok ? "巡检方案已执行" : "执行失败");

        StringBuilder md = new StringBuilder();
        md.append("## 巡检执行结果\n\n");
        md.append(ok ? "执行完成。" : "执行失败。").append(resultSummary).append("\n\n");
        md.append("- 方案 ID：`").append(proposalId).append("`\n");
        Object summary = pending.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            md.append("- 原方案：").append(summary).append("\n");
        }
        if (!ok) {
            md.append("\n错误：").append(exec.getOrDefault("error", "未知")).append("\n");
        } else {
            appendEffectSection(md, metricsBefore, 1);
        }

        auditRecorder.record(traceId, userMessage, intentRisk.name(), ok ? "EXECUTED" : "FAILED",
                "PatrolContinuation", ok, resultSummary, steps,
                timeSource.currentTimeMillis() - t0);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "orchestrate-result");
        meta.put("traceId", traceId);
        meta.put("securityOutcome", ok ? "EXECUTED" : "FAILED");
        meta.put("replyMode", "ORCHESTRATE");
        meta.put("resultSummary", resultSummary);
        meta.put("executionState", AgentExecutionState.build(
                "ORCHESTRATE",
                ok,
                ok,
                List.of("SystemLoadTool", "DiskTool"),
                ok ? "EXECUTE" : "DIAGNOSE",
                traceId,
                ok ? "EXECUTED" : "FAILED",
                null));
        return new RunResult(md.toString(), traceId, meta);
    }

    private static String summarizeExecutionActions(Object actions) {
        if (!(actions instanceof List<?> list) || list.isEmpty()) {
            return "未返回可展示的执行明细。";
        }
        int successCount = 0;
        List<String> parts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> action)) {
                parts.add(String.valueOf(item));
                continue;
            }
            boolean ok = Boolean.TRUE.equals(action.get("success"));
            if (ok) {
                successCount++;
            }
            Object tool = action.get("tool");
            Object path = action.get("path");
            parts.add(actionLabel(tool)
                    + (path == null ? "" : " `" + path + "`")
                    + (ok ? " 成功" : " 失败"));
        }
        return "已执行巡检修复动作 " + list.size() + " 个，成功 " + successCount + " 个。"
                + truncate(String.join("；", parts), 900);
    }

    private static String actionLabel(Object tool) {
        if (tool == null) {
            return "处置动作";
        }
        return switch (String.valueOf(tool)) {
            case "ServiceRestartTool" -> "服务重启";
            case "CleanTempTool" -> "临时文件清理";
            case "LogCleanupTool" -> "日志清理";
            case "ProcessTool", "ProcessOpsTool" -> "进程处置";
            default -> "处置动作";
        };
    }

    private RunResult runCpuPressure(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        long t0 = timeSource.currentTimeMillis();
        String traceId = traceIdGenerator.nextId();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "接收", userMessage);

        if (surface == McpToolSurface.READ_ONLY) {
            return new RunResult(buildReadOnlyReply(traceId), traceId);
        }

        StringBuilder md = new StringBuilder();
        md.append("## CPU / 负载闭环\n\n");

        OpsWorkflow workflowHit = workflowRetriever.bestMatch(
                "cpu", List.of("CPU_HIGH"), userMessage);

        try {
            LinkedHashSet<String> toolsInvoked = new LinkedHashSet<>();
            boolean forceExecute = resolveForceExecute(userMessage);
            Map<String, Object> metricsBefore = null;
            if (forceExecute && remediationEffectEvaluator != null) {
                metricsBefore = remediationEffectEvaluator.captureMetrics();
            }
            WorkflowExecutionService.WorkflowRunResult workflowRun = executeAwmWorkflow(
                    workflowHit, traceId, userMessage, surface, forceExecute, steps,
                    buildCpuWorkflowVariables(userMessage));
            collectToolsFromWorkflow(workflowRun, toolsInvoked);

            String loadJson = workflowRun != null
                    ? workflowRun.rawResultForTool("SystemLoadTool").orElse(null)
                    : null;
            if (loadJson == null) {
                loadJson = systemLoadTool.checkSystemLoad();
                auditRecorder.addStep(steps, "perceive", truncate(loadJson, 800));
                toolsInvoked.add("SystemLoadTool");
            }
            md.append("### 当前负载\n\n");
            md.append(summarizeSystemLoad(loadJson)).append("\n\n");

            String processJson = workflowRun != null
                    ? workflowRun.rawResultForTool("ProcessTool").orElse(null)
                    : null;
            if (processJson == null) {
                processJson = processTool.listProcesses(5.0, 5.0);
                auditRecorder.addStep(steps, "perceive", truncate(processJson, 800));
                toolsInvoked.add("ProcessTool");
            }
            md.append(summarizeProcessPressure(processJson)).append("\n\n");

            if (workflowHit != null && workflowRun != null) {
                md.append(buildWorkflowResultSummary(workflowRun)).append("\n\n");
            }

            boolean wantRestart = userMessage.contains("重启") || userMessage.contains("restart");
            boolean anyExecuted = workflowRun != null && workflowRun.anyExecuted();
            if (wantRestart) {
                String svc = resolveRestartService(userMessage);
                if (svc == null) {
                    md.append("### 服务重启\n\n未配置可重启服务白名单或治理禁止。\n\n");
                } else {
                    anyExecuted |= appendServiceRestartSection(md, steps, svc, forceExecute);
                    toolsInvoked.add("ServiceRestartTool");
                }
            } else if (!anyExecuted) {
                md.append("### 建议\n\n");
                md.append("- 若需重启白名单内非关键服务，请说明服务名并带「重启」（如：重启 nginx）。\n");
                md.append("- 允许重启列表见配置 `agent.service-restart.allowlist`。\n\n");
                md.append(OpsReportFormat.remediationPlanMarkdown(List.of(
                        "采集系统负载与高占用进程",
                        "必要时预览白名单服务重启",
                        "写操作须回复「确认执行」后落地"
                )));
            }

            if (anyExecuted) {
                appendEffectSection(md, metricsBefore, 1);
            }

            String securityOutcome = resolveSecurityOutcome(anyExecuted, workflowRun);
            auditRecorder.record(traceId, userMessage, intentRisk.name(), securityOutcome,
                    "CpuPressure", true, md.substring(0, Math.min(400, md.length())), steps,
                    timeSource.currentTimeMillis() - t0);
            workflowInductionService.afterSuccessfulRun(
                    traceId,
                    userMessage,
                    securityOutcome,
                    true,
                    steps,
                    "cpu",
                    List.of("CPU_HIGH"));
            return new RunResult(md.toString(), traceId,
                    buildStreamMeta(traceId, securityOutcome, workflowHit, workflowRun, toolsInvoked));
        } catch (Exception e) {
            log.error("CPU 闭环失败", e);
            md.append("**失败:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "CpuPressure", false, e.getMessage(), steps, timeSource.currentTimeMillis() - t0);
            return new RunResult(md.toString(), traceId);
        }
    }

    private boolean appendServiceRestartSection(
            StringBuilder md, List<Map<String, Object>> steps, String serviceName, boolean forceExecute)
            throws Exception {
        String instruction = "执行 ServiceRestartTool serviceName=" + serviceName;
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideServiceRestart(serviceName, forceExecute, instruction);

        serviceRestartTool.restartService(serviceName, true, false);
        md.append("### 服务重启（").append(serviceName).append("）\n\n");
        md.append("- ").append(decision.reason()).append("\n");

        if (decision.forbidden()) {
            md.append("- **已拒绝**。\n\n");
            return false;
        }
        if (decision.mayPreview()) {
            md.append("- 已预览，回复「执行清理」或明确「重启 ").append(serviceName).append("」后执行。\n\n");
            return false;
        }
        String exec = serviceRestartTool.restartService(serviceName, false, true);
        auditRecorder.addStep(steps, "execute", truncate(exec, 800));
        boolean restarted = parseToolSuccess(exec);
        if (restarted) {
            md.append("- **已执行重启**（请自行验证业务恢复）。\n\n");
        } else {
            md.append("- **重启未成功：** ").append(WriteToolResultSupport.errorMessage(exec)).append("\n\n");
        }
        return restarted;
    }

    private boolean parseToolSuccess(String toolJson) {
        return WriteToolResultSupport.isConfirmedRealWrite(toolJson);
    }

    private String buildReadOnlyReply(String traceId) {
        return "## 只读模式\n\n"
                + "当前会话为只读模式，本轮不会执行清理、重启、删除等写操作。"
                + "请改成只做分析，或在需要真实处置时切换到可执行工具面。\n\n"
                + "- 追踪 ID：`" + (traceId == null ? "-" : traceId) + "`\n";
    }

    private String defaultLogRoot() {
        return runtimePlatform.isWindows() ? "C:\\Windows\\Logs" : "/var/log";
    }

    private String defaultTempRoot() {
        if (!runtimePlatform.isWindows()) {
            return "/tmp";
        }
        String runtimeTemp = System.getProperty("java.io.tmpdir", "").trim();
        if (!runtimeTemp.isEmpty()) {
            return runtimeTemp;
        }
        String envTemp = System.getenv("TEMP");
        if (envTemp != null && !envTemp.isBlank()) {
            return envTemp.trim();
        }
        return "C:\\Temp";
    }

    private Map<String, Object> buildDiskWorkflowVariables(String userMessage, boolean forceExecute) {
        Map<String, Object> variables = new LinkedHashMap<>();
        String extractedPath = OpsPathExtractSupport.bestPath(userMessage).orElse(null);
        String logPath = resolveLogCleanupRoot(extractedPath);
        String tempPath = resolveTempCleanupRoot(extractedPath);
        boolean removeDirectory = OpsPathExtractSupport.isCleanableSubDirectory(opsPathPolicy, tempPath);

        variables.put("log-path", logPath);
        variables.put("temp-path", tempPath);
        variables.put("log-days", logCleanDays);
        variables.put("temp-days", (forceExecute || removeDirectory) ? 0 : tempCleanDays);
        variables.put("dryRun", true);
        if (removeDirectory) {
            variables.put("removeDirectory", true);
        }
        return variables;
    }

    private Map<String, Object> buildCpuWorkflowVariables(String userMessage) {
        Map<String, Object> variables = new LinkedHashMap<>();
        String serviceName = resolveRestartService(userMessage);
        if (serviceName != null && !serviceName.isBlank()) {
            variables.put("service-name", serviceName);
        }
        variables.put("dryRun", true);
        return variables;
    }

    private String resolveRestartService(String userMessage) {
        String fromUser = serviceRestartCandidateResolver.pickFromUserMessage(userMessage);
        if (fromUser != null && !fromUser.isBlank()) {
            return fromUser;
        }
        return serviceRestartCandidateResolver.pickDefaultFromAllowlist();
    }

    private String resolveLogCleanupRoot(String extractedPath) {
        if (extractedPath != null && opsPathPolicy.isAllowedLogCleanupPath(extractedPath)) {
            return extractedPath;
        }
        List<String> allowedRoots = opsPathPolicy.snapshotLogCleanupRoots();
        if (!allowedRoots.isEmpty()) {
            return allowedRoots.get(0);
        }
        return defaultLogRoot();
    }

    private String resolveTempCleanupRoot(String extractedPath) {
        if (extractedPath != null
                && (opsPathPolicy.isAllowedCleanDirectory(extractedPath)
                || OpsPathExtractSupport.isCleanableSubDirectory(opsPathPolicy, extractedPath))) {
            return extractedPath;
        }
        List<String> allowedRoots = opsPathPolicy.snapshotTempCleanRoots();
        if (!allowedRoots.isEmpty()) {
            return preferredExistingTempRoot(allowedRoots);
        }
        return defaultTempRoot();
    }

    private String preferredExistingTempRoot(List<String> allowedRoots) {
        String fallback = allowedRoots.get(0);
        String runtimeTemp = defaultTempRoot();
        for (String candidate : allowedRoots) {
            if (sameWindowsPath(candidate, runtimeTemp) && isExistingDirectory(candidate)) {
                return candidate;
            }
        }
        for (String candidate : allowedRoots) {
            if (!candidate.toLowerCase(Locale.ROOT).contains("\\windows\\temp")
                    && !candidate.toLowerCase(Locale.ROOT).contains("/windows/temp")
                    && isExistingDirectory(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private boolean isExistingDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameWindowsPath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().replace('\\', '/').equalsIgnoreCase(b.trim().replace('\\', '/'));
    }

    private static boolean mentionsTemp(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("临时") || m.contains("tmp") || m.contains("temp") || m.contains("垃圾");
    }

    private static boolean mentionsLog(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("日志") || m.contains("log");
    }

    private JsonNode parseToolData(String toolJson) {
        return McpToolPayloadParser.parsePayload(objectMapper, toolJson);
    }

    private double extractMaxDiskUsePercent(String diskToolJson) {
        try {
            JsonNode root = objectMapper.readTree(diskToolJson);
            JsonNode data = root.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }
            if (data.isArray()) {
                double max = 0;
                for (JsonNode n : data) {
                    max = Math.max(max, parsePercent(n.path("usePercent").asText("")));
                    max = Math.max(max, parsePercent(n.path("usage").asText("")));
                }
                return max;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static double parsePercent(String usage) {
        if (usage == null || !usage.endsWith("%")) {
            return 0;
        }
        try {
            return Double.parseDouble(usage.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String summarizeDiskAnalyze(String analyzeJson) {
        JsonNode data = parseToolData(analyzeJson);
        if (data == null) {
            if (!McpToolPayloadParser.isSuccessful(objectMapper, analyzeJson)) {
                return "- **磁盘热点扫描失败：** "
                        + McpToolPayloadParser.errorMessage(objectMapper, analyzeJson);
            }
            return "- 磁盘热点扫描未返回可展示的数据。";
        }
        StringBuilder sb = new StringBuilder();
        JsonNode multi = data.get("multiDrive");
        if (multi != null && multi.isArray() && !multi.isEmpty()) {
            sb.append("| 盘符 | 热点摘要 |\n| --- | --- |\n");
            for (JsonNode drive : multi) {
                String label = drive.path("drive").asText(drive.path("root").asText("?"));
                if (drive.has("hotspotsError")) {
                    sb.append("| `").append(label).append("` | 扫描失败：")
                            .append(drive.path("hotspotsError").asText()).append(" |\n");
                    continue;
                }
                sb.append("| `").append(label).append("` | ")
                        .append(summarizeHotspotsBrief(drive.get("hotspots"))).append(" |\n");
            }
            return sb.toString().trim();
        }
        JsonNode hotspots = data.get("hotspotsRank");
        if (hotspots != null) {
            return summarizeHotspotsBrief(hotspots);
        }
        return "- 已完成磁盘热点扫描，未发现明显热点目录。";
    }

    private String summarizeHotspotsBrief(JsonNode hotspots) {
        if (hotspots == null || hotspots.isNull()) {
            return "无热点";
        }
        JsonNode entries = hotspots.path("entries");
        if (!entries.isArray() || entries.isEmpty()) {
            return "未发现明显大目录";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, entries.size());
        for (int i = 0; i < limit; i++) {
            JsonNode e = entries.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(formatBytes(String.valueOf(e.path("kb").asLong(0) * 1024L))).append(" @ `")
                    .append(e.path("path").asText("-")).append("`");
        }
        if (entries.size() > limit) {
            sb.append(" 等 ").append(entries.size()).append(" 项");
        }
        return sb.toString();
    }

    private String summarizeDisk(String diskToolJson) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode root = objectMapper.readTree(diskToolJson);
            JsonNode data = root.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }
            if (data.isArray() && !data.isEmpty()) {
                sb.append("| 盘符 | 使用率 | 已用 | 可用 |\n| --- | ---: | --- | --- |\n");
                for (JsonNode n : data) {
                    String drive = n.path("mountedOn").asText(n.path("filesystem").asText("?"));
                    String pct = n.path("usePercent").asText("");
                    if (pct.isBlank()) {
                        pct = n.path("usage").asText("-");
                    }
                    String used = formatBytes(n.path("used").asText(""));
                    String avail = formatBytes(n.path("available").asText(""));
                    sb.append("| `").append(drive).append("` | ").append(pct).append(" | ")
                            .append(used).append(" | ").append(avail).append(" |\n");
                }
                return sb.toString().trim();
            }
        } catch (Exception ignored) {
        }
        return "- 已完成磁盘采集，但未提取到可展示的盘符和使用率。";
    }

    private String summarizeSystemLoad(String loadJson) {
        JsonNode data = parseToolData(loadJson);
        if (data == null) {
            if (!McpToolPayloadParser.isSuccessful(objectMapper, loadJson)) {
                return "- 负载采集失败：" + McpToolPayloadParser.errorMessage(objectMapper, loadJson);
            }
            return "- 已完成负载采集，但未返回可展示的指标。";
        }
        double cpu = firstDouble(data, -1, "cpuUsagePercent", "cpuPercent", "cpu", "systemCpuLoad");
        double mem = firstDouble(data, -1, "memUsagePercent", "memoryUsagePercent", "memoryPercent", "mem");
        double load1 = firstDouble(data, -1, "loadAverage1m", "load1", "systemLoadAverage");
        StringBuilder sb = new StringBuilder();
        sb.append("| 指标 | 当前值 |\n| --- | ---: |\n");
        boolean hasAny = false;
        if (cpu >= 0) {
            sb.append("| CPU 使用率 | ").append(formatPercentValue(cpu)).append(" |\n");
            hasAny = true;
        }
        if (mem >= 0) {
            sb.append("| 内存使用率 | ").append(formatPercentValue(mem)).append(" |\n");
            hasAny = true;
        }
        if (load1 >= 0) {
            sb.append("| 1 分钟负载 | ").append(String.format(Locale.ROOT, "%.2f", load1)).append(" |\n");
            hasAny = true;
        }
        if (!hasAny) {
            return "- 已完成负载采集，未发现可展示的 CPU/内存百分比字段。";
        }
        return sb.toString().trim();
    }

    private String summarizeProcessPressure(String processJson) {
        if (!McpToolPayloadParser.isSuccessful(objectMapper, processJson)) {
            return "### 高占用进程\n\n- 进程采样失败："
                    + McpToolPayloadParser.errorMessage(objectMapper, processJson);
        }
        JsonNode data = parseToolData(processJson);
        JsonNode rows = data != null && data.isArray() ? data : (data == null ? null : data.path("processes"));
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return "### 高占用进程\n\n- 未发现 CPU 或内存超过阈值的进程（当前阈值：CPU > 5%，内存 > 5%）。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 高占用进程\n\n");
        sb.append("当前高占用进程如下，已按 CPU / 内存压力排序：\n\n");
        sb.append("| 进程 | PID | CPU | 内存 | 运行时长 | 状态 |\n");
        sb.append("| --- | ---: | ---: | ---: | --- | --- |\n");
        int limit = Math.min(8, rows.size());
        boolean hasZombie = false;
        for (int i = 0; i < limit; i++) {
            JsonNode row = rows.get(i);
            String state = text(row, "state", "-");
            hasZombie |= state.toLowerCase(Locale.ROOT).contains("zombie");
            sb.append("| `").append(escapeTableCell(truncate(firstNonBlank(
                            text(row, "command", ""),
                            text(row, "name", ""),
                            text(row, "processName", ""),
                            "-"), 72))).append("` | ")
                    .append(escapeTableCell(text(row, "pid", "-"))).append(" | ")
                    .append(formatProcessPercent(text(row, "cpu", text(row, "cpuPct", "")))).append(" | ")
                    .append(formatProcessPercent(text(row, "mem", text(row, "memPct", "")))).append(" | ")
                    .append(escapeTableCell(text(row, "etime", "-"))).append(" | ")
                    .append(escapeTableCell(state)).append(" |\n");
        }
        if (rows.size() > limit) {
            sb.append("\n- 其余 ").append(rows.size() - limit).append(" 个低优先级命中项已收进审计详情。");
        }
        if (hasZombie) {
            sb.append("\n- 发现僵尸进程，请优先确认父进程状态；不要直接批量结束业务进程。");
        } else {
            sb.append("\n- 可先确认表中 PID 是否为业务进程；需要结束或重启时请明确 PID 或服务名。");
        }
        return sb.toString();
    }

    private String buildWorkflowResultSummary(WorkflowExecutionService.WorkflowRunResult workflowRun) {
        if (workflowRun == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("### 执行结果\n\n");
        if (workflowRun.failed()) {
            sb.append("- 历史方案执行中断，已停止后续动作；具体失败步骤可在审计页查看。");
            return sb.toString();
        }
        int ok = Math.max(workflowRun.successfulStepCount(), workflowRun.handled() ? 1 : 0);
        if (workflowRun.anyExecuted()) {
            sb.append("- 已按历史治理方案完成 ").append(ok).append(" 个步骤，其中包含真实执行动作。");
        } else if (workflowRun.anyPreviewed()) {
            sb.append("- 已生成 ").append(ok).append(" 个预览步骤，未做真实写操作。");
        } else if (workflowRun.anyReadSucceeded()) {
            sb.append("- 已完成 ").append(ok).append(" 个只读诊断步骤。");
        } else {
            sb.append("- 历史方案未形成可执行动作。");
        }
        List<String> highlights = workflowRun.stepResults() == null
                ? List.of()
                : workflowRun.stepResults().stream()
                .filter(WorkflowExecutionService.StepResult::success)
                .map(WorkflowExecutionService.StepResult::message)
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .toList();
        if (!highlights.isEmpty()) {
            sb.append("\n");
            for (String item : highlights) {
                sb.append("- ").append(truncate(item, 120)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean resolveForceExecute(String userMessage) {
        if (opsIntentRouter.forceRemediate(userMessage)) {
            return true;
        }
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        return ctx != null && ctx.isUserConfirmedWrite();
    }

    private void appendEffectSection(StringBuilder md, Map<String, Object> metricsBefore, int remediationsExecuted) {
        if (remediationEffectEvaluator == null || md == null || remediationsExecuted <= 0) {
            return;
        }
        Map<String, Object> before = metricsBefore != null
                ? metricsBefore
                : remediationEffectEvaluator.captureMetrics();
        Map<String, Object> effect = remediationEffectEvaluator.evaluate(
                before,
                remediationEffectEvaluator.captureMetrics(),
                remediationsExecuted);
        md.append(OpsReportFormat.effectSectionMarkdown(effect));
    }

    private static String formatBytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        try {
            long bytes = Long.parseLong(raw.trim());
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024L * 1024) {
                return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
            }
            if (bytes < 1024L * 1024 * 1024) {
                return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
            }
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static int deletableCount(JsonNode data) {
        if (data == null) {
            return 0;
        }
        if (data.has("deletableCount")) {
            return data.get("deletableCount").asInt(0);
        }
        return data.has("filesFound") ? data.get("filesFound").asInt(0) : 0;
    }

    private static double firstDouble(JsonNode node, double fallback, String... names) {
        if (node == null || names == null) {
            return fallback;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asDouble();
            }
            String text = value.asText("");
            if (text.isBlank()) {
                continue;
            }
            try {
                return Double.parseDouble(text.replace("%", "").trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String formatPercentValue(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String formatProcessPercent(String raw) {
        if (raw == null || raw.isBlank() || "n/a".equalsIgnoreCase(raw.trim())) {
            return "未采集";
        }
        String cleaned = raw.trim();
        if (cleaned.endsWith("%")) {
            return escapeTableCell(cleaned);
        }
        try {
            return String.format(Locale.ROOT, "%.1f%%", Double.parseDouble(cleaned));
        } catch (NumberFormatException e) {
            return escapeTableCell(cleaned);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || field == null) {
            return fallback;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String escapeTableCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "\\|");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
