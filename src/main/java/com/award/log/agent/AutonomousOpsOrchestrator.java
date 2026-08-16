package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.WorkflowExecutionService;
import com.award.log.agent.awm.WorkflowInductionService;
import com.award.log.agent.awm.WorkflowRetriever;
import com.award.log.config.SystemConfigRuntimeState;
import com.award.log.governance.OpsRemediationGate;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.CronJobTool;
import com.award.log.mcp.tools.DiskAnalyzeTool;
import com.award.log.mcp.tools.DiskTool;
import com.award.log.mcp.tools.DockerTool;
import com.award.log.mcp.tools.FirewallTool;
import com.award.log.mcp.tools.LogAnalysisTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.NetworkTool;
import com.award.log.mcp.tools.OsInsightTool;
import com.award.log.mcp.tools.PortHealthTool;
import com.award.log.mcp.tools.ProcessTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.mcp.tools.SystemLoadTool;
import com.award.log.mcp.tools.SystemdTool;
import com.award.log.security.McpToolSurface;
import com.award.log.security.OpsSecurityContext;
import com.award.log.security.RiskLevel;
import com.award.log.security.signal.SecuritySignalService;
import com.award.log.util.RuntimePlatform;
import com.award.log.util.TimeSource;
import com.award.log.util.TraceIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Compatibility autonomous orchestrator kept for perception and patrol automation.
 */
@Slf4j
@Service
public class AutonomousOpsOrchestrator {

    private final SystemLoadTool systemLoadTool;
    private final DiskTool diskTool;
    private final DiskAnalyzeTool diskAnalyzeTool;
    private final ProcessTool processTool;
    private final SystemdTool systemdTool;
    private final NetworkTool networkTool;
    private final PortHealthTool portHealthTool;
    private final DockerTool dockerTool;
    private final FirewallTool firewallTool;
    private final CronJobTool cronJobTool;
    private final LogAnalysisTool logAnalysisTool;
    private final OsInsightTool osInsightTool;
    private final CleanTempTool cleanTempTool;
    private final LogCleanupTool logCleanupTool;
    private final ServiceRestartTool serviceRestartTool;
    private final OpsRemediationGate opsRemediationGate;
    private final ServiceRestartCandidateResolver serviceRestartCandidateResolver;
    private final AssistantAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final OpsIntentRouter opsIntentRouter;
    private final OpsPerceptionCache opsPerceptionCache;
    private final RemediationEffectEvaluator remediationEffectEvaluator;
    private final WorkflowRetriever workflowRetriever;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowInductionService workflowInductionService;
    private final SecuritySignalService securitySignalService;
    private final RuntimePlatform runtimePlatform;
    private final TimeSource timeSource;
    private final TraceIdGenerator traceIdGenerator;
    private final ExecutorService perceiveExecutor;

    @Value("${agent.autonomous.enabled:true}")
    private boolean enabled;

    @Value("${agent.autonomous.disk-pressure-percent:80}")
    private double diskPressurePercent;

    @Value("${agent.autonomous.cpu-warn-percent:85}")
    private double cpuWarnPercent;

    @Value("${agent.autonomous.mem-warn-percent:88}")
    private double memWarnPercent;

    @Value("${agent.autonomous.log-clean-days:30}")
    private int logCleanDays;

    @Value("${agent.autonomous.temp-clean-days:7}")
    private int tempCleanDays;

    @Value("${agent.autonomous.health-check-host:127.0.0.1}")
    private String healthCheckHost;

    private String healthCheckPortsRaw;
    private String pingTarget = SystemConfigRuntimeState.DEFAULT_PING_TARGET;

    public AutonomousOpsOrchestrator(
            SystemLoadTool systemLoadTool,
            DiskTool diskTool,
            DiskAnalyzeTool diskAnalyzeTool,
            ProcessTool processTool,
            SystemdTool systemdTool,
            NetworkTool networkTool,
            PortHealthTool portHealthTool,
            DockerTool dockerTool,
            FirewallTool firewallTool,
            CronJobTool cronJobTool,
            LogAnalysisTool logAnalysisTool,
            OsInsightTool osInsightTool,
            CleanTempTool cleanTempTool,
            LogCleanupTool logCleanupTool,
            ServiceRestartTool serviceRestartTool,
            OpsRemediationGate opsRemediationGate,
            ServiceRestartCandidateResolver serviceRestartCandidateResolver,
            AssistantAuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            OpsIntentRouter opsIntentRouter,
            OpsPerceptionCache opsPerceptionCache,
            RemediationEffectEvaluator remediationEffectEvaluator,
            WorkflowRetriever workflowRetriever,
            WorkflowExecutionService workflowExecutionService,
            WorkflowInductionService workflowInductionService,
            SecuritySignalService securitySignalService,
            SystemConfigRuntimeState systemConfigRuntimeState,
            RuntimePlatform runtimePlatform,
            TimeSource timeSource,
            TraceIdGenerator traceIdGenerator) {
        this.systemLoadTool = systemLoadTool;
        this.diskTool = diskTool;
        this.diskAnalyzeTool = diskAnalyzeTool;
        this.processTool = processTool;
        this.systemdTool = systemdTool;
        this.networkTool = networkTool;
        this.portHealthTool = portHealthTool;
        this.dockerTool = dockerTool;
        this.firewallTool = firewallTool;
        this.cronJobTool = cronJobTool;
        this.logAnalysisTool = logAnalysisTool;
        this.osInsightTool = osInsightTool;
        this.cleanTempTool = cleanTempTool;
        this.logCleanupTool = logCleanupTool;
        this.serviceRestartTool = serviceRestartTool;
        this.opsRemediationGate = opsRemediationGate;
        this.serviceRestartCandidateResolver = serviceRestartCandidateResolver;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.opsIntentRouter = opsIntentRouter;
        this.opsPerceptionCache = opsPerceptionCache;
        this.remediationEffectEvaluator = remediationEffectEvaluator;
        this.workflowRetriever = workflowRetriever;
        this.workflowExecutionService = workflowExecutionService;
        this.workflowInductionService = workflowInductionService;
        this.securitySignalService = securitySignalService;
        this.runtimePlatform = runtimePlatform;
        this.timeSource = timeSource;
        this.traceIdGenerator = traceIdGenerator;
        this.healthCheckPortsRaw = systemConfigRuntimeState.healthCheckPortsCsv();
        this.pingTarget = systemConfigRuntimeState.getPingTarget();
        this.perceiveExecutor = Executors.newFixedThreadPool(
                Math.min(12, Runtime.getRuntime().availableProcessors() + 2),
                runnable -> {
                    Thread thread = new Thread(runnable, "autonomous-ops-perceive");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public record RunResult(String markdown, String traceId, Map<String, Object> report) {
    }

    public boolean supports(String userMessage) {
        if (!enabled || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return opsIntentRouter.resolve(userMessage) == OpsIntentRouter.Playbook.PATROL_AUTOMATION;
    }

    public void applyHotConfig(String healthCheckPortsRaw, String pingTarget) {
        if (healthCheckPortsRaw != null && !healthCheckPortsRaw.isBlank()) {
            this.healthCheckPortsRaw = healthCheckPortsRaw;
        }
        if (pingTarget != null && !pingTarget.isBlank()) {
            this.pingTarget = pingTarget;
        }
    }

    public Map<String, Object> buildPerceptionView(boolean useCache, McpToolSurface surface) {
        long now = timeSource.currentTimeMillis();
        if (useCache) {
            Map<String, Object> cached = opsPerceptionCache.getIfFresh();
            if (cached != null) {
                return cached;
            }
        }
        try {
            List<Map<String, Object>> steps = auditRecorder.newSteps();
            PerceptionSnapshot perception = perceiveAll(steps);
            List<Finding> findings = diagnose(perception);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("readOnly", surface == McpToolSurface.READ_ONLY);
            view.put("snapshot", perception.toMap());
            view.put("securitySignals", securitySignalService.summary());
            view.put("findings", findings.stream().map(Finding::toMap).toList());
            view.put("cachedAtMs", now);
            opsPerceptionCache.put(Map.copyOf(view));
            return view;
        } catch (Exception e) {
            log.warn("perception snapshot collection failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "cachedAtMs", now);
        }
    }

    public RunResult run(String userMessage, McpToolSurface surface, RiskLevel intentRisk) {
        boolean force = opsIntentRouter.forceRemediate(userMessage);
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            force = true;
        }
        return runInternal(userMessage, surface, intentRisk, force);
    }

    public RunResult runScheduled(McpToolSurface surface, boolean forceRemediate) {
        return runInternal("[scheduled-autonomous-ops]", surface, RiskLevel.LOW, forceRemediate);
    }

    private RunResult runInternal(String userMessage,
                                  McpToolSurface surface,
                                  RiskLevel intentRisk,
                                  boolean forceRemediate) {
        long startedAt = timeSource.currentTimeMillis();
        String traceId = traceIdGenerator.nextId();
        List<Map<String, Object>> steps = auditRecorder.newSteps();
        auditRecorder.addCot(steps, 1, "receive", userMessage);
        List<String> plannedTools = AgentSkillPlan.forOrchestrate(userMessage);
        String planPhase = AgentSkillPlan.planPhase(forceRemediate, plannedTools);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("traceId", traceId);
        report.put("readOnlySurface", surface == McpToolSurface.READ_ONLY);
        report.put("plannedTools", plannedTools);
        report.put("observeTools", AgentSkillPlan.observeTools(plannedTools));
        report.put("pendingWriteTools", AgentSkillPlan.pendingWriteTools(plannedTools));
        report.put("planPhase", planPhase);
        report.put("executionState", AgentExecutionState.build(
                "ORCHESTRATE",
                forceRemediate,
                forceRemediate && surface != McpToolSurface.READ_ONLY,
                plannedTools,
                planPhase,
                traceId,
                null,
                null));

        StringBuilder md = new StringBuilder("## Autonomous Ops Report\n\n");

        if (surface == McpToolSurface.READ_ONLY) {
            forceRemediate = false;
            md.append("> Current session is read-only. Only collection and diagnosis will run.\n\n");
        }

        try {
            auditRecorder.addCot(steps, 2, "perceive", "collect host and MCP signals");
            PerceptionSnapshot perception = perceiveAll(steps);
            report.put("perception", perception.toMap());

            List<Finding> findings = diagnose(perception);
            report.put("findings", findings.stream().map(Finding::toMap).toList());

            md.append(buildExecutiveSummary(perception, findings)).append("\n");
            md.append("### Perception\n\n");
            md.append(perception.toMarkdownSummary()).append("\n");
            md.append("### Diagnosis\n\n");
            if (findings.isEmpty()) {
                md.append("- No actionable issues found.\n\n");
            } else {
                for (Finding finding : findings) {
                    md.append(OpsReportFormat.formatFindingLine(finding.severity(), finding.title(), finding.detail()));
                }
                md.append("\n");
            }

            appendAwmGuidance(md, steps, findings, userMessage);

            boolean anyRemediated = false;
            Map<String, Object> metricsBefore = null;
            RemediationSummary remediationSummary = null;
            if (!findings.isEmpty() && surface != McpToolSurface.READ_ONLY) {
                metricsBefore = remediationEffectEvaluator.captureMetrics();
                remediationSummary = remediate(traceId, userMessage, surface, findings, forceRemediate, steps, perception);
                report.put("remediation", remediationSummary.toMap());
                anyRemediated = remediationSummary.executedCount() > 0;
                if (anyRemediated) {
                    opsPerceptionCache.invalidate();
                }
                md.append("### Remediation\n\n").append(remediationSummary.toMarkdown()).append("\n");
            } else if (!findings.isEmpty()) {
                md.append("### Remediation\n\n").append(OpsReportFormat.readOnlySkippedFooter());
            } else {
                md.append("### Remediation\n\n- No remediation needed.\n\n");
            }

            auditRecorder.addCot(steps, 3, "verify", "re-sample load and disk");
            String verifyLoad = safeCall(() -> systemLoadTool.checkSystemLoad(), "SystemLoadTool", steps);
            String verifyDisk = safeCall(() -> diskTool.checkDiskUsage(), "DiskTool", steps);
            report.put("verify", Map.of("load", truncate(verifyLoad, 500), "disk", truncate(verifyDisk, 500)));
            md.append("### Verification\n\n");
            md.append(summarizeDisk(verifyDisk)).append("\n");
            md.append(summarizeLoad(verifyLoad)).append("\n\n");

            if (anyRemediated && metricsBefore != null && remediationSummary != null) {
                Map<String, Object> effect = remediationEffectEvaluator.evaluate(
                        metricsBefore,
                        remediationEffectEvaluator.captureMetrics(),
                        remediationSummary.executedCount());
                report.put("remediationEffect", effect);
                md.append(OpsReportFormat.effectSectionMarkdown(effect));
            }

            boolean hasActionable = findings.stream().anyMatch(f -> OpsReportFormat.isActionableSeverity(f.severity()));
            if (!anyRemediated && hasActionable && surface != McpToolSurface.READ_ONLY) {
                md.append(OpsReportFormat.previewPendingFooter()).append("\n");
            } else if (anyRemediated) {
                md.append(OpsReportFormat.executedFooter()).append("\n");
            }

            String status = anyRemediated ? "REMEDIATED" : (findings.isEmpty() ? "HEALTHY" : "DIAGNOSED");
            report.put("securityOutcome", status);
            report.put("executionState", AgentExecutionState.build(
                    "ORCHESTRATE",
                    forceRemediate,
                    anyRemediated,
                    plannedTools,
                    planPhase,
                    traceId,
                    status,
                    null));
            auditRecorder.record(traceId, userMessage, intentRisk.name(), status,
                    "AutonomousOpsOrchestrator", true,
                    truncate(md.toString(), 500), steps, timeSource.currentTimeMillis() - startedAt);
            AwmContext context = resolveAwmContext(findings);
            workflowInductionService.afterSuccessfulRun(
                    traceId, userMessage, status, true, steps, context.domain(), context.kinds());
            return new RunResult(md.toString(), traceId, report);
        } catch (Exception e) {
            log.error("autonomous ops failed traceId={}", traceId, e);
            auditRecorder.addStep(steps, "error", e.getMessage());
            md.append("\n**Failure:** ").append(e.getMessage());
            auditRecorder.record(traceId, userMessage, intentRisk.name(), "ERROR",
                    "AutonomousOpsOrchestrator", false, e.getMessage(),
                    steps, timeSource.currentTimeMillis() - startedAt);
            report.put("error", e.getMessage());
            report.put("securityOutcome", "ERROR");
            report.put("executionState", AgentExecutionState.build(
                    "ORCHESTRATE",
                    forceRemediate,
                    false,
                    plannedTools,
                    planPhase,
                    traceId,
                    "ERROR",
                    null));
            return new RunResult(md.toString(), traceId, report);
        }
    }

    private PerceptionSnapshot perceiveAll(List<Map<String, Object>> steps) throws InterruptedException {
        Map<String, String> raw = new LinkedHashMap<>();
        List<Callable<Void>> tasks = List.of(
                () -> putRaw(raw, "load", () -> systemLoadTool.checkSystemLoad(), "SystemLoadTool", steps),
                () -> putRaw(raw, "disk", () -> diskTool.checkDiskUsage(), "DiskTool", steps),
                () -> putRaw(raw, "diskAnalyze", () -> diskAnalyzeTool.analyzeDiskPressure(defaultLogRoot(), true, 10), "DiskAnalyzeTool", steps),
                () -> putRaw(raw, "process", () -> processTool.listProcesses(5.0, 5.0), "ProcessTool", steps),
                () -> putRaw(raw, "systemdFailed", () -> systemdTool.listFailedSystemdUnits(), "SystemdTool", steps),
                () -> putRaw(raw, "network", () -> networkTool.diagnoseNetwork(pingTarget, "ping", 3), "NetworkTool", steps),
                () -> putRaw(raw, "firewall", () -> firewallTool.checkFirewallStatus(), "FirewallTool", steps),
                () -> putRaw(raw, "cron", () -> cronJobTool.listCronJobs("user"), "CronJobTool", steps),
                () -> putRaw(raw, "docker", () -> dockerTool.listDockerContainers(false), "DockerTool", steps),
                () -> putRaw(raw, "journal", () -> osInsightTool.queryJournalLogs(30, 80, false), "OsInsightTool", steps),
                () -> putRaw(raw, "logAnalysis", () -> logAnalysisTool.analyzeLogs(defaultLogRoot(), 80), "LogAnalysisTool", steps)
        );

        List<Callable<Void>> all = new ArrayList<>(tasks);
        for (Integer port : parseHealthPorts()) {
            all.add(() -> putRaw(raw, "port_" + port,
                    () -> portHealthTool.checkPortConnectivity(healthCheckHost, port, 3000),
                    "PortHealthTool:" + port, steps));
        }

        List<Future<Void>> futures = perceiveExecutor.invokeAll(all, 90, TimeUnit.SECONDS);
        for (Future<Void> future : futures) {
            try {
                future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("perception task finished with warning: {}", e.getMessage());
            }
        }
        return PerceptionSnapshot.fromRaw(raw, objectMapper, runtimePlatform.isWindows());
    }

    private Void putRaw(Map<String, String> raw,
                        String key,
                        Callable<String> call,
                        String label,
                        List<Map<String, Object>> steps) {
        raw.put(key, safeCall(call, label, steps));
        return null;
    }

    private List<Finding> diagnose(PerceptionSnapshot perception) {
        List<Finding> out = new ArrayList<>();
        if (perception.maxDiskUsePercent() >= diskPressurePercent) {
            out.add(new Finding("HIGH", "disk pressure",
                    String.format(Locale.ROOT, "highest filesystem usage is about %.0f%%", perception.maxDiskUsePercent()),
                    "DISK_PRESSURE"));
        }
        if (perception.cpuUsagePercent() >= cpuWarnPercent) {
            out.add(new Finding("HIGH", "CPU high",
                    String.format(Locale.ROOT, "CPU is about %.1f%%", perception.cpuUsagePercent()),
                    "CPU_HIGH"));
        }
        if (perception.memUsagePercent() >= memWarnPercent) {
            out.add(new Finding("MEDIUM", "memory high",
                    String.format(Locale.ROOT, "memory is about %.1f%%", perception.memUsagePercent()),
                    "MEM_HIGH"));
        }
        if (perception.hasFailedUnits()) {
            String detail = OpsReportFormat.serviceIssueDetailPrefix();
            if (!perception.failedUnitNames().isEmpty()) {
                detail += ": " + OpsReportFormat.formatNameSample(perception.failedUnitNames(), 5);
            }
            out.add(new Finding("HIGH", OpsReportFormat.serviceIssueLabel(), detail, "FAILED_SERVICE"));
        }
        for (Integer port : perception.unreachablePorts()) {
            out.add(new Finding(
                    OpsReportFormat.portFindingSeverity(),
                    OpsReportFormat.portFindingTitle(),
                    OpsReportFormat.portFindingDetail(healthCheckHost, port),
                    "PORT_DOWN"));
        }
        if (perception.networkPacketLossPercent() >= 50) {
            out.add(new Finding("MEDIUM", "network loss",
                    String.format(Locale.ROOT, "ping to %s lost about %.0f%% packets", pingTarget, perception.networkPacketLossPercent()),
                    "NETWORK_LOSS"));
        }
        if (perception.logAnomalyHint()) {
            out.add(new Finding("MEDIUM", "log anomaly", "journal or log analysis contains error-like signals", "LOG_ANOMALY"));
        }
        if (perception.dockerIssueHint()) {
            out.add(new Finding("LOW", "container state", "docker list contains non-running containers", "DOCKER_STATE"));
        }
        if (perception.zombieProcessHint()) {
            out.add(new Finding("MEDIUM", "zombie process", "process list contains zombie entries", "ZOMBIE_PROCESS"));
        }
        if (securitySignalService.hasThreatSignals(3_600_000L)) {
            out.add(new Finding("HIGH", "security threat",
                    securitySignalService.buildThreatSummaryText(securitySignalService.summary(3_600_000L)),
                    "SECURITY_THREAT"));
        }
        return out;
    }

    private RemediationSummary remediate(String traceId,
                                         String userMessage,
                                         McpToolSurface surface,
                                         List<Finding> findings,
                                         boolean forceRemediate,
                                         List<Map<String, Object>> steps,
                                         PerceptionSnapshot perception) throws Exception {
        List<String> actions = new ArrayList<>();
        int executed = 0;
        int previewed = 0;
        int skipped = 0;

        AwmExecutionSummary awmSummary = executeBestAwmWorkflow(
                traceId, userMessage, surface, findings, forceRemediate, steps, perception);
        actions.addAll(awmSummary.actions());
        executed += awmSummary.executedCount();
        previewed += awmSummary.previewCount();
        skipped += awmSummary.skippedCount();

        Set<String> coveredKinds = new LinkedHashSet<>(awmSummary.coveredKinds());
        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());
        boolean needDiskRelief = (kinds.contains("DISK_PRESSURE") || kinds.contains("LOG_ANOMALY"))
                && !coveredKinds.contains("DISK_PRESSURE");

        if (needDiskRelief) {
            RemediationOutcome temp = remediateTempClean(forceRemediate, steps);
            actions.add(temp.message());
            executed += temp.executed() ? 1 : 0;
            previewed += temp.previewed() ? 1 : 0;
            skipped += temp.skipped() ? 1 : 0;

            RemediationOutcome log = remediateLogClean(forceRemediate, steps);
            actions.add(log.message());
            executed += log.executed() ? 1 : 0;
            previewed += log.previewed() ? 1 : 0;
            skipped += log.skipped() ? 1 : 0;
        }

        if (kinds.contains("CPU_HIGH") && !coveredKinds.contains("CPU_HIGH")) {
            String service = serviceRestartCandidateResolver.pickDefaultFromAllowlist();
            if (service != null) {
                RemediationOutcome restart = remediateServiceRestart(service, forceRemediate, steps);
                actions.add(restart.message());
                executed += restart.executed() ? 1 : 0;
                previewed += restart.previewed() ? 1 : 0;
                skipped += restart.skipped() ? 1 : 0;
            } else {
                actions.add("- CPU issue detected but no allowlisted restart candidate exists.");
                skipped++;
            }
        }

        if (kinds.contains("FAILED_SERVICE") && !coveredKinds.contains("FAILED_SERVICE")) {
            String service = serviceRestartCandidateResolver.pickFromFailedUnits(perception.failedUnitNames());
            if (service != null) {
                RemediationOutcome restart = remediateServiceRestart(service, forceRemediate, steps);
                actions.add(restart.message());
                executed += restart.executed() ? 1 : 0;
                previewed += restart.previewed() ? 1 : 0;
                skipped += restart.skipped() ? 1 : 0;
            } else {
                actions.add("- Failed service detected but no allowlisted service matched.");
                skipped++;
            }
        }

        if (actions.isEmpty()) {
            actions.add("- No matching remediation action.");
        }
        return new RemediationSummary(actions, executed, previewed, skipped);
    }

    private AwmExecutionSummary executeBestAwmWorkflow(String traceId,
                                                       String userMessage,
                                                       McpToolSurface surface,
                                                       List<Finding> findings,
                                                       boolean forceRemediate,
                                                       List<Map<String, Object>> steps,
                                                       PerceptionSnapshot perception) {
        if (findings == null || findings.isEmpty()) {
            return AwmExecutionSummary.empty();
        }
        AwmContext context = resolveAwmContext(findings);
        OpsWorkflow workflow = workflowRetriever.bestMatch(context.domain(), context.kinds(), userMessage);
        if (workflow == null) {
            return AwmExecutionSummary.empty();
        }
        auditRecorder.addStep(steps, "workflow", "matched workflow=" + workflow.workflowId());
        WorkflowExecutionService.WorkflowRunResult run = workflowExecutionService.execute(
                workflow,
                new WorkflowExecutionService.ExecutionRequest(
                        traceId,
                        userMessage,
                        surface,
                        forceRemediate,
                        steps,
                        buildAwmVariables(context.domain(), userMessage, forceRemediate, perception)));

        if (run.handled()) {
            workflowRetriever.recordHit(workflow);
            Set<String> coveredKinds = (run.anyExecuted() || run.anyPreviewed())
                    ? new LinkedHashSet<>(context.kinds())
                    : Set.of();
            return new AwmExecutionSummary(
                    List.of(buildAwmExecutionMessage(run)),
                    coveredKinds,
                    run.anyExecuted() ? 1 : 0,
                    run.anyPreviewed() ? 1 : 0,
                    0);
        }
        return new AwmExecutionSummary(
                List.of("- 历史处置方案未形成可执行动作，已转入当前环境的内置处置逻辑。"),
                Set.of(),
                0,
                0,
                1);
    }

    private static String buildAwmExecutionMessage(WorkflowExecutionService.WorkflowRunResult run) {
        if (run == null) {
            return "- 未命中历史处置方案。";
        }
        int ok = Math.max(run.successfulStepCount(), run.handled() ? 1 : 0);
        StringBuilder sb = new StringBuilder("- 已复用历史处置经验，完成 ");
        sb.append(ok).append(" 个步骤");
        if (run.anyExecuted()) {
            sb.append("，包含真实执行动作");
        } else if (run.anyPreviewed()) {
            sb.append("，本轮只生成预览，未做真实写操作");
        } else if (run.anyReadSucceeded()) {
            sb.append("，本轮只做只读诊断");
        }
        sb.append("。");
        if (run.stepResults() != null) {
            List<String> highlights = run.stepResults().stream()
                    .filter(WorkflowExecutionService.StepResult::success)
                    .map(WorkflowExecutionService.StepResult::message)
                    .filter(msg -> msg != null && !msg.isBlank())
                    .limit(2)
                    .toList();
            if (!highlights.isEmpty()) {
                sb.append("关键结果：").append(String.join("；", highlights)).append("。");
            }
        }
        return sb.toString();
    }

    private Map<String, Object> buildAwmVariables(String domain,
                                                  String userMessage,
                                                  boolean forceRemediate,
                                                  PerceptionSnapshot perception) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("dryRun", true);
        if ("disk".equals(domain)) {
            variables.put("log-path", defaultLogRoot());
            variables.put("temp-path", defaultTempRoot());
            variables.put("log-days", logCleanDays);
            variables.put("temp-days", forceRemediate ? 0 : tempCleanDays);
            return variables;
        }
        String serviceName = serviceRestartCandidateResolver.pickFromFailedUnits(perception.failedUnitNames());
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = serviceRestartCandidateResolver.pickFromUserMessage(userMessage);
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = serviceRestartCandidateResolver.pickDefaultFromAllowlist();
        }
        if (serviceName != null && !serviceName.isBlank()) {
            variables.put("service-name", serviceName);
        }
        return variables;
    }

    private RemediationOutcome remediateTempClean(boolean force, List<Map<String, Object>> steps) throws Exception {
        int days = force ? 0 : tempCleanDays;
        String scanJson = cleanTempTool.scanAllTempJunk(days);
        JsonNode scanRoot = objectMapper.readTree(scanJson);
        if (!scanRoot.path("success").asBoolean(false)) {
            return RemediationOutcome.skipped("- Temp scan failed: " + scanRoot.path("error").asText("unknown error"));
        }
        JsonNode scanData = unwrapToolData(scanRoot);
        int total = scanData != null ? scanData.path("totalFilesFound").asInt(0) : 0;
        if (total == 0) {
            return RemediationOutcome.skipped("- Temp scan found no removable files.");
        }
        if (!force) {
            return RemediationOutcome.previewed("- Temp cleanup preview found **" + total + "** files pending confirmation.");
        }
        int deletedTotal = 0;
        if (scanData != null && scanData.has("locations")) {
            for (JsonNode location : scanData.get("locations")) {
                int found = location.path("filesFound").asInt(0);
                if (found <= 0) {
                    continue;
                }
                String path = location.path("path").asText("");
                OpsRemediationGate.RemediationDecision decision =
                        opsRemediationGate.decideTempCleanup(path, days, true, "AutonomousOps CleanTempTool path=" + path);
                if (decision.forbidden() || decision.mayPreview()) {
                    continue;
                }
                String exec = cleanTempTool.cleanTempFiles(path, days, false, true);
                deletedTotal += countFromTool(exec, "filesDeleted");
            }
        }
        auditRecorder.addStep(steps, "execute", "CleanTemp deleted=" + deletedTotal);
        if (deletedTotal <= 0) {
            return RemediationOutcome.skipped("- Temp cleanup found candidates but nothing was deleted.");
        }
        return RemediationOutcome.executed("- Temp cleanup deleted **" + deletedTotal + "** files.");
    }

    private JsonNode unwrapToolData(JsonNode toolRoot) throws Exception {
        JsonNode dataNode = toolRoot.get("data");
        if (dataNode == null) {
            return null;
        }
        if (dataNode.isTextual()) {
            return objectMapper.readTree(dataNode.asText());
        }
        return dataNode;
    }

    private RemediationOutcome remediateLogClean(boolean force, List<Map<String, Object>> steps) throws Exception {
        String path = defaultLogRoot();
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideLogCleanup(path, logCleanDays, force, "AutonomousOps LogCleanupTool path=" + path);
        String preview = logCleanupTool.cleanupOldLogs(path, logCleanDays, true, false);
        int found = countFromTool(preview, "deletableCount", "filesFound");
        if (found == 0) {
            return RemediationOutcome.skipped("- Log cleanup found no old files.");
        }
        if (decision.forbidden()) {
            return RemediationOutcome.skipped("- Log cleanup blocked: " + decision.reason());
        }
        if (decision.mayPreview()) {
            return RemediationOutcome.previewed("- Log cleanup preview found **" + found + "** files.");
        }
        String exec = logCleanupTool.cleanupOldLogs(path, logCleanDays, false, true);
        int deleted = countFromTool(exec, "filesDeleted");
        auditRecorder.addStep(steps, "execute", "LogCleanup deleted=" + deleted);
        if (!WriteToolResultSupport.isConfirmedRealWrite(exec)) {
            return RemediationOutcome.skipped("- Log cleanup execution failed: " + WriteToolResultSupport.errorMessage(exec));
        }
        return RemediationOutcome.executed("- Log cleanup deleted **" + deleted + "** files.");
    }

    private RemediationOutcome remediateServiceRestart(String serviceName,
                                                       boolean force,
                                                       List<Map<String, Object>> steps) throws Exception {
        OpsRemediationGate.RemediationDecision decision =
                opsRemediationGate.decideServiceRestart(serviceName, force, "AutonomousOps ServiceRestartTool " + serviceName);
        if (decision.forbidden()) {
            return RemediationOutcome.skipped("- Service **" + serviceName + "** blocked: " + decision.reason());
        }
        if (decision.mayPreview()) {
            serviceRestartTool.restartService(serviceName, true, false);
            return RemediationOutcome.previewed("- Service **" + serviceName + "** preview completed.");
        }
        String exec = serviceRestartTool.restartService(serviceName, false, true);
        auditRecorder.addStep(steps, "execute", "ServiceRestart " + serviceName);
        if (!WriteToolResultSupport.isConfirmedRealWrite(exec)) {
            return RemediationOutcome.skipped("- Service **" + serviceName + "** restart failed: "
                    + WriteToolResultSupport.errorMessage(exec));
        }
        return RemediationOutcome.executed("- Service **" + serviceName + "** restarted.");
    }

    private List<Integer> parseHealthPorts() {
        if (healthCheckPortsRaw == null || healthCheckPortsRaw.isBlank()) {
            return List.of(8080);
        }
        List<Integer> ports = new ArrayList<>();
        for (String part : healthCheckPortsRaw.split(",")) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value > 0 && value <= 65535) {
                    ports.add(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ports.isEmpty() ? List.of(8080) : ports;
    }

    private static boolean chatPreviewOnly() {
        OpsSecurityContext.Ctx ctx = OpsSecurityContext.get();
        if (ctx != null && ctx.isUserConfirmedWrite()) {
            return false;
        }
        return ctx != null && ctx.isChatAgentPath();
    }

    private String buildRemediationPlan(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Finding finding : findings) {
            sb.append(index++).append(". **").append(finding.title()).append("** - ").append(finding.detail()).append("\n");
            String action = planActionFor(finding);
            if (action != null) {
                sb.append("   - Suggested action: ").append(action).append("\n");
            }
        }
        sb.append("\n> 请回复 **「").append(OpsReportFormat.CONFIRM_EXECUTE_HINT_ZH)
                .append("」** 以在策略允许范围内继续写操作。\n");
        return sb.toString();
    }

    private static String planActionFor(Finding finding) {
        if (finding == null || finding.kind() == null) {
            return null;
        }
        return OpsReportFormat.planActionForKind(finding.kind());
    }

    private String safeCall(Callable<String> call, String label, List<Map<String, Object>> steps) {
        try {
            String result = call.call();
            auditRecorder.addStep(steps, "perceive", label + ": " + truncate(result, 400));
            return result;
        } catch (Exception e) {
            log.warn("{} collection failed: {}", label, e.getMessage());
            auditRecorder.addStep(steps, "perceive-error", label + ": " + e.getMessage());
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String defaultLogRoot() {
        return runtimePlatform.defaultLogRoot();
    }

    private String defaultTempRoot() {
        return runtimePlatform.defaultTempRoot();
    }

    private int countFromTool(String toolJson, String... keys) {
        JsonNode data = parseToolData(toolJson);
        if (data == null) {
            return 0;
        }
        for (String key : keys) {
            if (data.has(key)) {
                return data.get(key).asInt(0);
            }
        }
        return 0;
    }

    private JsonNode parseToolData(String toolJson) {
        try {
            JsonNode root = objectMapper.readTree(toolJson);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }
            JsonNode dataNode = root.get("data");
            if (dataNode == null) {
                return null;
            }
            if (dataNode.isTextual()) {
                return objectMapper.readTree(dataNode.asText());
            }
            return dataNode;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeDisk(String diskToolJson) {
        double max = extractMaxDiskUsePercent(diskToolJson);
        if (max > 0) {
            return "- Disk verification: highest usage about `" + String.format(Locale.ROOT, "%.0f", max) + "%`";
        }
        return "- Disk verification completed.";
    }

    private String summarizeLoad(String loadJson) {
        JsonNode data = parseToolData(loadJson);
        if (data != null) {
            double cpu = data.path("cpuUsagePercent").asDouble(-1);
            double mem = data.path("memUsagePercent").asDouble(-1);
            if (cpu >= 0) {
                return "- Load verification: CPU `" + String.format(Locale.ROOT, "%.1f", cpu)
                        + "%`, memory `" + String.format(Locale.ROOT, "%.1f", mem) + "%`";
            }
        }
        return "- Load verification completed.";
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
                for (JsonNode node : data) {
                    String use = diskPercentText(node);
                    if (use.endsWith("%")) {
                        max = Math.max(max, Double.parseDouble(use.replace("%", "").trim()));
                    }
                }
                return max;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String diskPercentText(JsonNode node) {
        if (node == null) {
            return "";
        }
        String usePercent = node.path("usePercent").asText("");
        if (!usePercent.isBlank()) {
            return usePercent;
        }
        return node.path("usage").asText("");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double asDouble(JsonNode value) {
        return value != null && value.isNumber() ? value.doubleValue() : 0.0;
    }

    private record AwmContext(String domain, List<String> kinds) {
    }

    private record AwmExecutionSummary(List<String> actions,
                                       Set<String> coveredKinds,
                                       int executedCount,
                                       int previewCount,
                                       int skippedCount) {
        static AwmExecutionSummary empty() {
            return new AwmExecutionSummary(List.of(), Set.of(), 0, 0, 0);
        }
    }

    private AwmContext resolveAwmContext(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return new AwmContext("disk", List.of("DISK_PRESSURE"));
        }
        Set<String> kinds = findings.stream().map(Finding::kind).collect(Collectors.toSet());
        if (kinds.contains("FAILED_SERVICE")) {
            return new AwmContext("service", List.of("FAILED_SERVICE"));
        }
        if (kinds.contains("DISK_PRESSURE") || kinds.contains("LOG_ANOMALY")) {
            return new AwmContext("disk", List.of("DISK_PRESSURE"));
        }
        if (kinds.contains("CPU_HIGH")) {
            return new AwmContext("cpu", List.of("CPU_HIGH"));
        }
        return new AwmContext("disk", List.of("DISK_PRESSURE"));
    }

    private static String buildExecutiveSummary(PerceptionSnapshot perception, List<Finding> findings) {
        List<Finding> actionable = findings.stream()
                .filter(finding -> OpsReportFormat.isActionableSeverity(finding.severity()))
                .toList();
        List<String> titles = actionable.stream().map(Finding::title).toList();
        long infoCount = findings.stream().filter(finding -> "INFO".equalsIgnoreCase(finding.severity())).count();
        return OpsReportFormat.conclusionSection(
                perception.cpuUsagePercent(),
                perception.memUsagePercent(),
                perception.maxDiskUsePercent(),
                actionable.size(),
                (int) infoCount,
                titles);
    }

    private void appendAwmGuidance(StringBuilder md,
                                   List<Map<String, Object>> steps,
                                   List<Finding> findings,
                                   String userMessage) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        boolean hasActionable = findings.stream().anyMatch(f -> OpsReportFormat.isActionableSeverity(f.severity()));
        if (!hasActionable) {
            return;
        }
        AwmContext context = resolveAwmContext(findings);
        List<OpsWorkflow> workflows = workflowRetriever.retrieve(context.domain(), context.kinds(), userMessage, 2);
        if (workflows == null || workflows.isEmpty()) {
            return;
        }
        for (OpsWorkflow workflow : workflows) {
            auditRecorder.addStep(steps, "workflow", "matched workflow=" + workflow.workflowId());
        }
        md.append(OpsReportFormat.awmSectionHeader());
        for (OpsWorkflow workflow : workflows) {
            md.append("- `").append(workflow.workflowId()).append("`");
            if (workflow.title() != null && !workflow.title().isBlank()) {
                md.append(' ').append(workflow.title());
            }
            md.append('\n');
        }
        md.append(OpsReportFormat.awmSectionFooter());
    }

    private record Finding(String severity, String title, String detail, String kind) {
        Map<String, Object> toMap() {
            return Map.of("severity", severity, "title", title, "detail", detail, "kind", kind);
        }
    }

    private record RemediationOutcome(boolean executed, boolean previewed, boolean skipped, String message) {
        static RemediationOutcome executed(String message) {
            return new RemediationOutcome(true, false, false, message);
        }

        static RemediationOutcome previewed(String message) {
            return new RemediationOutcome(false, true, false, message);
        }

        static RemediationOutcome skipped(String message) {
            return new RemediationOutcome(false, false, true, message);
        }
    }

    private record RemediationSummary(List<String> actions, int executedCount, int previewCount, int skippedCount) {
        Map<String, Object> toMap() {
            return Map.of(
                    "actions", actions,
                    "executedCount", executedCount,
                    "previewCount", previewCount,
                    "skippedCount", skippedCount);
        }

        String toMarkdown() {
            return String.join("\n", actions) + "\n";
        }
    }

    private static final class PerceptionSnapshot {
        private final double maxDiskUsePercent;
        private final double cpuUsagePercent;
        private final double memUsagePercent;
        private final boolean hasFailedUnits;
        private final List<String> failedUnitNames;
        private final List<Integer> unreachablePorts;
        private final double networkPacketLossPercent;
        private final boolean logAnomalyHint;
        private final boolean dockerIssueHint;
        private final boolean zombieProcessHint;
        private final Map<String, String> raw;

        private PerceptionSnapshot(double maxDiskUsePercent,
                                   double cpuUsagePercent,
                                   double memUsagePercent,
                                   boolean hasFailedUnits,
                                   List<String> failedUnitNames,
                                   List<Integer> unreachablePorts,
                                   double networkPacketLossPercent,
                                   boolean logAnomalyHint,
                                   boolean dockerIssueHint,
                                   boolean zombieProcessHint,
                                   Map<String, String> raw) {
            this.maxDiskUsePercent = maxDiskUsePercent;
            this.cpuUsagePercent = cpuUsagePercent;
            this.memUsagePercent = memUsagePercent;
            this.hasFailedUnits = hasFailedUnits;
            this.failedUnitNames = failedUnitNames != null ? List.copyOf(failedUnitNames) : List.of();
            this.unreachablePorts = unreachablePorts != null ? List.copyOf(unreachablePorts) : List.of();
            this.networkPacketLossPercent = networkPacketLossPercent;
            this.logAnomalyHint = logAnomalyHint;
            this.dockerIssueHint = dockerIssueHint;
            this.zombieProcessHint = zombieProcessHint;
            this.raw = raw != null ? Map.copyOf(raw) : Map.of();
        }

        static PerceptionSnapshot fromRaw(Map<String, String> raw, ObjectMapper mapper, boolean isWindows) {
            double maxDisk = 0;
            double cpu = 0;
            double mem = 0;
            boolean failedUnits = false;
            List<String> failedUnitNames = List.of();
            List<Integer> badPorts = new ArrayList<>();
            double packetLoss = 0;
            boolean logHint = false;
            boolean dockerHint = false;
            boolean zombieHint = false;

            try {
                if (raw.containsKey("disk")) {
                    JsonNode root = mapper.readTree(raw.get("disk"));
                    JsonNode data = unwrapData(root, mapper);
                    if (data.isArray()) {
                        for (JsonNode node : data) {
                            String use = diskPercentText(node);
                            if (use.endsWith("%")) {
                                maxDisk = Math.max(maxDisk, Double.parseDouble(use.replace("%", "").trim()));
                            }
                        }
                    }
                }
                if (raw.containsKey("load")) {
                    JsonNode data = unwrapData(mapper.readTree(raw.get("load")), mapper);
                    cpu = data.path("cpuUsagePercent").asDouble(0);
                    mem = data.path("memUsagePercent").asDouble(0);
                }
                if (raw.containsKey("systemdFailed")) {
                    String systemdJson = raw.get("systemdFailed");
                    failedUnitNames = ServiceRestartCandidateResolver.parseFailedUnitsFromToolJson(systemdJson, mapper);
                    failedUnits = !failedUnitNames.isEmpty();
                    if (!failedUnits && !isWindows) {
                        String text = systemdJson.toLowerCase(Locale.ROOT);
                        failedUnits = text.contains("failed") && !text.contains("0 loaded");
                    }
                }
                if (raw.containsKey("network")) {
                    JsonNode data = unwrapData(mapper.readTree(raw.get("network")), mapper);
                    packetLoss = data.path("packetLossPercent").asDouble(0);
                }
                String journal = raw.getOrDefault("journal", "");
                String logAnalysis = raw.getOrDefault("logAnalysis", "");
                logHint = containsErrorHint(journal) || containsErrorHint(logAnalysis);
                String docker = raw.getOrDefault("docker", "").toLowerCase(Locale.ROOT);
                dockerHint = docker.contains("exited") || docker.contains("dead") || docker.contains("restarting");
                String processBlob = raw.getOrDefault("process", "").toLowerCase(Locale.ROOT);
                zombieHint = processBlob.contains(" z ") || processBlob.contains("\"z\"") || processBlob.contains("zombie");
                for (Map.Entry<String, String> entry : raw.entrySet()) {
                    if (!entry.getKey().startsWith("port_")) {
                        continue;
                    }
                    JsonNode data = unwrapData(mapper.readTree(entry.getValue()), mapper);
                    boolean reachable = data.path("reachable").asBoolean(true);
                    if (!reachable) {
                        badPorts.add(Integer.parseInt(entry.getKey().substring(5)));
                    }
                }
            } catch (Exception e) {
                log.debug("parse perception snapshot failed: {}", e.getMessage());
            }

            return new PerceptionSnapshot(
                    maxDisk, cpu, mem, failedUnits, failedUnitNames, badPorts,
                    packetLoss, logHint, dockerHint, zombieHint, raw);
        }

        private static JsonNode unwrapData(JsonNode root, ObjectMapper mapper) throws Exception {
            JsonNode data = root.path("data");
            if (data.isTextual()) {
                return mapper.readTree(data.asText());
            }
            return data.isMissingNode() ? root : data;
        }

        private static boolean containsErrorHint(String blob) {
            if (blob == null) {
                return false;
            }
            String text = blob.toLowerCase(Locale.ROOT);
            return text.contains("error") || text.contains("fatal") || text.contains("exception") || text.contains("failed");
        }

        double maxDiskUsePercent() {
            return maxDiskUsePercent;
        }

        double cpuUsagePercent() {
            return cpuUsagePercent;
        }

        double memUsagePercent() {
            return memUsagePercent;
        }

        boolean hasFailedUnits() {
            return hasFailedUnits;
        }

        List<String> failedUnitNames() {
            return failedUnitNames;
        }

        List<Integer> unreachablePorts() {
            return unreachablePorts;
        }

        double networkPacketLossPercent() {
            return networkPacketLossPercent;
        }

        boolean logAnomalyHint() {
            return logAnomalyHint;
        }

        boolean dockerIssueHint() {
            return dockerIssueHint;
        }

        boolean zombieProcessHint() {
            return zombieProcessHint;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maxDiskUsePercent", maxDiskUsePercent);
            out.put("cpuUsagePercent", cpuUsagePercent);
            out.put("memUsagePercent", memUsagePercent);
            out.put("hasFailedUnits", hasFailedUnits);
            out.put("failedUnitNames", failedUnitNames);
            out.put("unreachablePorts", unreachablePorts);
            out.put("networkPacketLossPercent", networkPacketLossPercent);
            out.put("logAnomalyHint", logAnomalyHint);
            out.put("dockerIssueHint", dockerIssueHint);
            out.put("zombieProcessHint", zombieProcessHint);
            out.put("toolsInvoked", raw.keySet());
            return out;
        }

        String toMarkdownSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(OpsReportFormat.tableHeaderLine("Dimension", "Summary"));
            sb.append("| Load | CPU `").append(String.format(Locale.ROOT, "%.1f", cpuUsagePercent))
                    .append("%` / Memory `").append(String.format(Locale.ROOT, "%.1f", memUsagePercent)).append("%` |\n");
            sb.append("| Disk | Highest usage `").append(String.format(Locale.ROOT, "%.0f", maxDiskUsePercent)).append("%` |\n");
            sb.append("| Service | ").append(OpsReportFormat.servicePerceptionSummary(hasFailedUnits, failedUnitNames)).append(" |\n");
            sb.append("| Network | Packet loss ").append(String.format(Locale.ROOT, "%.0f", networkPacketLossPercent)).append("% |\n");
            sb.append("| Ports | ").append(OpsReportFormat.portProbeSummary(unreachablePorts)).append(" |\n");
            sb.append("| Logs | ").append(logAnomalyHint ? "error-like signals found" : "no strong error signal").append(" |\n");
            sb.append("| Docker | ").append(dockerIssueHint ? "non-running containers exist" : "healthy or not installed").append(" |\n");
            sb.append("| Zombie | ").append(zombieProcessHint ? "detected" : "not detected").append(" |\n");
            sb.append("| Tools | `").append(raw.size()).append("` MCP calls |\n");
            return sb.toString();
        }
    }
}
