package com.award.log.agent.awm;

import com.award.log.util.RuntimePlatform;
import com.award.log.util.TraceIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWM induction service. Current MVP is rule-based with optional LLM fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInductionService {

    private static final Pattern EXEC_TOOL = Pattern.compile("(CleanTemp|LogCleanup|ServiceRestart|Process|DiskAnalyze|DiskTool)");

    private final WorkflowMemoryService workflowMemoryService;
    private final TrajectoryEvaluator trajectoryEvaluator;
    private final OpsExperienceLoader experienceLoader;
    private final LlmWorkflowInductor llmWorkflowInductor;
    private final RuntimePlatform runtimePlatform;
    private final TraceIdGenerator traceIdGenerator;

    @org.springframework.beans.factory.annotation.Value("${agent.awm.llm-induction-enabled:false}")
    private boolean llmInductionEnabled;

    public void afterSuccessfulRun(
            String traceId,
            String userMessage,
            String securityOutcome,
            boolean executionOk,
            List<Map<String, Object>> steps,
            String domainTag,
            List<String> findingKinds
    ) {
        if (!workflowMemoryService.isEnabled()) {
            return;
        }
        OpsExperience exp = new OpsExperience(
                traceId,
                userMessage,
                "ASSISTANT",
                "AssistantOrchestrator",
                securityOutcome,
                executionOk,
                null,
                steps,
                0L,
                null
        );
        if (!trajectoryEvaluator.shouldInduce(exp)) {
            return;
        }

        List<String> toolSeq = extractToolSequenceFromSteps(steps);
        if (toolSeq.size() < 2) {
            return;
        }

        OpsWorkflow matched = findByToolSequence(domainTag, toolSeq);
        if (matched != null) {
            workflowMemoryService.recordUtility(matched.workflowId());
            log.debug("AWM online: matched existing workflow {} utility+1", matched.workflowId());
            return;
        }

        if (workflowMemoryService.isDomainFull(domainTag)) {
            log.debug("AWM online: domain {} reached cap, skip induction", domainTag);
            return;
        }

        OpsWorkflow induced = induceWorkflow(exp, domainTag, findingKinds, toolSeq);
        if (workflowMemoryService.upsert(induced, false)) {
            log.info("AWM online: induced workflow {} source={} toolSeq={}",
                    induced.workflowId(), induced.sourceType(), toolSeq);
        } else {
            log.warn("AWM online: induce upsert failed id={} toolSeq={}", induced.workflowId(), toolSeq);
        }
    }

    public record InductionResult(
            int scanned,
            int created,
            int skippedNotEligible,
            int skippedDuplicate,
            int skippedShortTools,
            int skippedDomainFull,
            int upsertFailed,
            boolean awmEnabled
    ) {
        public InductionResult(
                int scanned, int created, int skippedNotEligible, int skippedDuplicate, int skippedShortTools) {
            this(scanned, created, skippedNotEligible, skippedDuplicate, skippedShortTools, 0, 0, true);
        }

        public String hintZh() {
            if (!awmEnabled) {
                return "AWM 已关闭（agent.awm.enabled=false），未执行归纳";
            }
            if (created > 0) {
                return "已从审计轨迹归纳 " + created + " 条新 workflow"
                        + (upsertFailed > 0 ? "（另有 " + upsertFailed + " 条写入失败）" : "");
            }
            if (scanned == 0) {
                return "审计库为空：请先跑一键巡检、磁盘编排或 Agent 多工具对话";
            }
            if (skippedShortTools > 0 && skippedNotEligible == 0 && skippedDuplicate == 0) {
                return "轨迹里工具步骤不足 2 个，无法归纳（需多工具成功链路）";
            }
            if (skippedDuplicate > 0) {
                return "近期轨迹与已有套路重复，无新增（可先跑新的编排再诱导）";
            }
            if (skippedDomainFull > 0) {
                return "对应域已达记忆上限，无新增（可清理旧 workflow 或提高 max-per-domain）";
            }
            if (upsertFailed > 0) {
                return "归纳写入失败 " + upsertFailed + " 条，请检查数据库 ops_workflow_memory";
            }
            if (skippedNotEligible > 0) {
                return "近期轨迹未达归纳条件（需 executionOk 且 outcome 为 EXECUTED/DIAGNOSED 等）";
            }
            return "暂无可归纳的新轨迹";
        }
    }

    public InductionResult induceFromRecentTraces(int limit) {
        if (!workflowMemoryService.isEnabled()) {
            return new InductionResult(0, 0, 0, 0, 0, 0, 0, false);
        }
        int scanned = 0;
        int created = 0;
        int skippedNotEligible = 0;
        int skippedDuplicate = 0;
        int skippedShortTools = 0;
        int skippedDomainFull = 0;
        int upsertFailed = 0;
        for (OpsExperience exp : experienceLoader.loadRecentSuccessful(limit)) {
            scanned++;
            if (!trajectoryEvaluator.shouldInduce(exp)) {
                skippedNotEligible++;
                continue;
            }
            String domain = inferDomain(exp);
            List<String> toolSeq = extractToolSequenceFromSteps(exp.steps());
            if (toolSeq.size() < 2) {
                skippedShortTools++;
                continue;
            }
            if (workflowMemoryService.existsWithToolSequence(domain, toolSeq)) {
                skippedDuplicate++;
                continue;
            }
            if (workflowMemoryService.isDomainFull(domain)) {
                skippedDomainFull++;
                continue;
            }
            OpsWorkflow wf = induceWorkflow(exp, domain, List.of(), toolSeq);
            if (workflowMemoryService.upsert(wf, false)) {
                created++;
            } else {
                upsertFailed++;
            }
        }
        return new InductionResult(
                scanned, created, skippedNotEligible, skippedDuplicate, skippedShortTools,
                skippedDomainFull, upsertFailed, true);
    }

    /** @deprecated use {@link #induceFromRecentTraces(int)} and {@link InductionResult} */
    @Deprecated
    public int induceFromRecentTracesLegacy(int limit) {
        return induceFromRecentTraces(limit).created();
    }

    private OpsWorkflow findByToolSequence(String domainTag, List<String> toolSeq) {
        for (OpsWorkflow wf : workflowMemoryService.listByDomain(domainTag)) {
            if (wf.toolSequence().equals(toolSeq)) {
                return wf;
            }
        }
        return null;
    }

    List<String> extractToolSequenceFromSteps(List<Map<String, Object>> steps) {
        List<String> seq = new ArrayList<>();
        if (steps == null) {
            return seq;
        }
        for (Map<String, Object> step : steps) {
            String explicitTool = structuredToolName(step);
            if (!explicitTool.isBlank()) {
                appendUnique(seq, explicitTool);
            }
        }
        if (!seq.isEmpty()) {
            return seq;
        }

        boolean diskPlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("LogCleanup") || d.contains("CleanTemp") || d.contains("DiskAnalyze");
        });
        boolean cpuPlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("SystemLoad") || d.contains("Process") || d.contains("CpuPressure");
        });
        boolean servicePlaybook = steps.stream().anyMatch(s -> {
            String d = detail(s);
            return d.contains("Systemd") || d.contains("systemd") || d.contains("ServiceRestart");
        });

        if (servicePlaybook && !diskPlaybook && !cpuPlaybook) {
            appendUnique(seq, "SystemdTool");
        } else if (cpuPlaybook && !diskPlaybook) {
            appendUnique(seq, "SystemLoadTool");
            if (steps.stream().anyMatch(s -> detail(s).contains("Process"))) {
                appendUnique(seq, "ProcessTool");
            }
        } else if (diskPlaybook) {
            boolean sawPerceive = steps.stream().anyMatch(s -> "perceive".equals(phase(s)));
            if (sawPerceive) {
                appendUnique(seq, "DiskTool");
                appendUnique(seq, "DiskAnalyzeTool");
            }
        }

        for (Map<String, Object> step : steps) {
            String d = detail(step);
            if (d.contains("LogCleanup")) {
                appendUnique(seq, "LogCleanupTool");
            }
            if (d.contains("CleanTemp")) {
                appendUnique(seq, "CleanTempTool");
            }
            if (d.contains("ServiceRestart")) {
                appendUnique(seq, "ServiceRestartTool");
            }
            Matcher m = EXEC_TOOL.matcher(d);
            while (m.find()) {
                appendUnique(seq, normalizeToolName(m.group(1)));
            }
        }
        if (diskPlaybook && steps.stream().anyMatch(s -> "verify".equals(phase(s))) && !seq.isEmpty()) {
            appendUnique(seq, "DiskTool");
        }
        return seq;
    }

    private OpsWorkflow induceWorkflow(
            OpsExperience exp,
            String domainTag,
            List<String> findingKinds,
            List<String> toolSeqFallback
    ) {
        if (llmInductionEnabled && llmWorkflowInductor.isAvailable()) {
            var llm = llmWorkflowInductor.induce(exp, domainTag, findingKinds);
            if (llm.isPresent()) {
                log.info("AWM LLM induction success trace={} workflow={}", exp.traceId(), llm.get().workflowId());
                return llm.get();
            }
            log.debug("AWM LLM induction fallback to rules trace={}", exp.traceId());
        }
        List<OpsWorkflowStep> structuredSteps = extractStructuredWorkflowSteps(exp.steps());
        if (structuredSteps.size() >= 2) {
            return buildStructuredWorkflow(
                    exp.traceId(),
                    domainTag,
                    findingKinds,
                    structuredSteps,
                    exp.userInput());
        }
        return buildFromToolSequence(
                exp.traceId(),
                domainTag,
                findingKinds,
                toolSeqFallback,
                exp.userInput());
    }

    private OpsWorkflow buildFromToolSequence(
            String traceId,
            String domainTag,
            List<String> findingKinds,
            List<String> toolSeq,
            String userMessage
    ) {
        List<OpsWorkflowStep> steps = new ArrayList<>();
        for (String tool : toolSeq) {
            steps.add(OpsWorkflowStep.of(
                    "replay successful perception and remediation steps",
                    defaultReason(tool),
                    tool,
                    defaultArgs(tool)));
        }
        String id = "online-" + domainTag + "-" + shortId();
        String title = "在线归纳 " + domainTag + " 处理链路";
        String desc = userMessage != null && userMessage.length() > 80
                ? userMessage.substring(0, 80) + "..."
                : (userMessage != null ? userMessage : "由成功轨迹自动归纳");
        return new OpsWorkflow(
                id,
                domainTag,
                findingKinds == null || findingKinds.isEmpty()
                        ? defaultFindingKinds(domainTag)
                        : findingKinds,
                title,
                desc,
                steps,
                "online",
                traceId,
                0,
                true
        );
    }

    private static List<String> defaultFindingKinds(String domainTag) {
        if ("cpu".equals(domainTag)) {
            return List.of("CPU_HIGH");
        }
        if ("service".equals(domainTag)) {
            return List.of("FAILED_SERVICE");
        }
        return List.of("DISK_PRESSURE");
    }

    private String inferDomain(OpsExperience exp) {
        String input = exp.userInput() != null ? exp.userInput().toLowerCase(Locale.ROOT) : "";
        List<String> seq = extractToolSequenceFromSteps(exp.steps());
        boolean hasServiceTool = seq.stream().anyMatch(t ->
                "SystemdTool".equals(t) || "ServiceRestartTool".equals(t) || "ServiceOpsTool".equals(t));
        boolean hasDiskTool = seq.stream().anyMatch(t ->
                "CleanTempTool".equals(t) || "LogCleanupTool".equals(t)
                        || "DiskTool".equals(t) || "DiskAnalyzeTool".equals(t) || "DiskOpsTool".equals(t));
        boolean hasCpuTool = seq.stream().anyMatch(t ->
                "SystemLoadTool".equals(t) || "ProcessTool".equals(t) || "ProcessOpsTool".equals(t));

        // 文本意图优先，但「重启服务」类英文要归 service
        if (input.contains("restart") || input.contains("服务") || input.contains("systemd")
                || input.contains("nginx") || input.contains("service")) {
            if (hasServiceTool || !hasDiskTool) {
                return "service";
            }
        }
        if (input.contains("cpu") || input.contains("负载") || input.contains("进程")) {
            return "cpu";
        }
        if (input.contains("磁盘") || input.contains("磁盘满") || input.contains("清理") || input.contains("temp")) {
            return "disk";
        }
        // 工具序列：写类服务工具优先于只读负载工具，避免 SystemLoad+Restart 误判成 cpu
        if (hasServiceTool) {
            return "service";
        }
        if (hasDiskTool) {
            return "disk";
        }
        if (hasCpuTool) {
            return "cpu";
        }
        return "disk";
    }

    private static void appendUnique(List<String> seq, String tool) {
        if (tool == null || tool.isBlank()) {
            return;
        }
        if (seq.isEmpty() || !seq.get(seq.size() - 1).equals(tool)) {
            seq.add(tool);
        }
    }

    private static String normalizeToolName(String fragment) {
        return AwmToolProfile.normalize(fragment);
    }

    private String defaultReason(String toolName) {
        return switch (toolName) {
            case "DiskTool" -> "collect or verify disk usage";
            case "SystemLoadTool" -> "collect CPU, memory, and load metrics";
            case "ProcessTool" -> "list high resource processes";
            case "SystemdTool" -> runtimePlatform.isWindows()
                    ? "list Windows services that should be running"
                    : "list failed systemd units";
            case "DiskAnalyzeTool" -> "inspect hotspot directories";
            case "CleanTempTool" -> "clean temp files with dry-run first";
            case "LogCleanupTool" -> "clean stale logs with dry-run first";
            case "ServiceRestartTool" -> "restart failed allowlisted service";
            default -> "execute tool " + toolName;
        };
    }

    private static Map<String, String> defaultArgs(String toolName) {
        return switch (toolName) {
            case "CleanTempTool" -> Map.of("path", "{temp-path}", "days", "{temp-days}", "dryRun", "{dryRun}");
            case "LogCleanupTool" -> Map.of("path", "{log-path}", "days", "{log-days}", "dryRun", "{dryRun}");
            case "DiskAnalyzeTool" -> Map.of("path", "{log-path}", "topN", "12");
            case "ProcessTool" -> Map.of("minCpu", "5.0", "minMem", "5.0");
            case "ServiceRestartTool" -> Map.of("serviceName", "{service-name}", "dryRun", "{dryRun}");
            default -> Map.of();
        };
    }

    private static String phase(Map<String, Object> step) {
        return String.valueOf(step.get("phase")).toLowerCase(Locale.ROOT);
    }

    private static String detail(Map<String, Object> step) {
        Object d = step.get("detail");
        return d == null ? "" : String.valueOf(d);
    }

    private static String structuredToolName(Map<String, Object> step) {
        if (step == null) {
            return "";
        }
        Object direct = step.get("toolName");
        if (direct != null) {
            return normalizeToolName(String.valueOf(direct));
        }
        Object nestedDetail = step.get("detail");
        if (nestedDetail instanceof Map<?, ?> map) {
            Object nestedTool = map.get("toolName");
            if (nestedTool != null) {
                return normalizeToolName(String.valueOf(nestedTool));
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<OpsWorkflowStep> extractStructuredWorkflowSteps(List<Map<String, Object>> steps) {
        List<OpsWorkflowStep> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (Map<String, Object> step : steps) {
            String toolName = structuredToolName(step);
            if (toolName.isBlank() || !AwmToolProfile.isSupported(toolName)) {
                continue;
            }
            Map<String, Object> rawParams = null;
            if (step.get("parameters") instanceof Map<?, ?> params) {
                rawParams = (Map<String, Object>) params;
            } else if (step.get("detail") instanceof Map<?, ?> detailMap
                    && detailMap.get("parameters") instanceof Map<?, ?> nestedParams) {
                rawParams = (Map<String, Object>) nestedParams;
            }
            out.add(OpsWorkflowStep.of(
                    defaultEnvDesc(step, toolName),
                    defaultReason(toolName),
                    toolName,
                    abstractArgs(toolName, rawParams)
            ));
        }
        return dedupeSequential(out);
    }

    private OpsWorkflow buildStructuredWorkflow(
            String traceId,
            String domainTag,
            List<String> findingKinds,
            List<OpsWorkflowStep> steps,
            String userMessage
    ) {
        String id = "online-" + domainTag + "-" + shortId();
        String title = "在线归纳 " + domainTag + " 处理链路";
        String desc = userMessage != null && userMessage.length() > 80
                ? userMessage.substring(0, 80) + "..."
                : (userMessage != null ? userMessage : "由成功轨迹自动归纳");
        return new OpsWorkflow(
                id,
                domainTag,
                findingKinds == null || findingKinds.isEmpty()
                        ? defaultFindingKinds(domainTag)
                        : findingKinds,
                title,
                desc,
                steps,
                "online",
                traceId,
                0,
                true
        );
    }

    private static List<OpsWorkflowStep> dedupeSequential(List<OpsWorkflowStep> in) {
        List<OpsWorkflowStep> out = new ArrayList<>();
        for (OpsWorkflowStep step : in) {
            if (out.isEmpty() || !out.get(out.size() - 1).toolName().equals(step.toolName())) {
                out.add(step);
            }
        }
        return out;
    }

    private static String defaultEnvDesc(Map<String, Object> step, String toolName) {
        String detail = detail(step);
        if (detail != null && !detail.isBlank()) {
            return detail.length() > 64 ? detail.substring(0, 64) + "..." : detail;
        }
        return "reuse successful step from trajectory: " + toolName;
    }

    private static Map<String, String> abstractArgs(String toolName, Map<String, Object> rawParams) {
        if (rawParams == null || rawParams.isEmpty()) {
            return defaultArgs(toolName);
        }
        Map<String, String> out = new LinkedHashMap<>();
        switch (toolName) {
            case "CleanTempTool" -> {
                out.put("path", "{temp-path}");
                out.put("days", "{temp-days}");
                out.put("dryRun", "{dryRun}");
                if (Boolean.TRUE.equals(rawParams.get("removeDirectory"))) {
                    out.put("removeDirectory", "true");
                }
            }
            case "LogCleanupTool" -> {
                out.put("path", "{log-path}");
                out.put("days", "{log-days}");
                out.put("dryRun", "{dryRun}");
            }
            case "ServiceRestartTool" -> {
                out.put("serviceName", "{service-name}");
                out.put("dryRun", "{dryRun}");
            }
            case "DiskAnalyzeTool" -> {
                out.put("path", "{log-path}");
                out.put("topN", String.valueOf(rawParams.getOrDefault("topN", 12)));
            }
            case "ProcessTool" -> {
                out.put("minCpu", String.valueOf(rawParams.getOrDefault("minCpu", 5.0)));
                out.put("minMem", String.valueOf(rawParams.getOrDefault("minMem", 5.0)));
            }
            default -> {
                return defaultArgs(toolName);
            }
        }
        return out;
    }

    private String shortId() {
        String id = traceIdGenerator.nextId();
        if (id == null || id.isBlank()) {
            return "generated";
        }
        String normalized = id.replace("-", "");
        return normalized.length() <= 8 ? normalized : normalized.substring(0, 8);
    }
}
