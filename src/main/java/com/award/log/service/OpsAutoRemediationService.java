package com.award.log.service;

import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsGovernanceService;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.mcp.tools.CleanTempTool;
import com.award.log.mcp.tools.LogCleanupTool;
import com.award.log.mcp.tools.ServiceRestartTool;
import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.HttpAuditSubject;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.trace.TraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 巡检后自动修复 / 待确认修复：
 * <ul>
 *   <li>{@code run-mode=HYBRID}（默认）：与 {@link AgenticRiskScoreEngine} 同口径评分；总分 **&lt; risk-patrol-auto-max**
 *       的步骤巡检后**自动执行**；**≥** 该阈值的步骤进入待确认（由您决策）。超过 {@code agent.security.risk-score-confirm-max}
 *       的步骤默认从方案剔除，可配置允许进入待确认。</li>
 *   <li>{@code run-mode=CONFIRM_FIRST}：全部步骤仅生成待确认方案。</li>
 *   <li>{@code run-mode=IMMEDIATE}：在满足「立即」磁盘/条件过滤后全部自动执行。</li>
 * </ul>
 * <p>
 * 每条巡检 finding 的处置车道见 {@link #getRemediationCoverage()}：{@code AUTO}、{@code CONFIRM}、{@code MIXED}、
 * {@code MANUAL}、{@code NONE}。
 */
@Slf4j
@Service
public class OpsAutoRemediationService {

    public static final String CONFIRM_CODE = "确认执行";
    private static final String MODE_IMMEDIATE = "IMMEDIATE";
    private static final String MODE_CONFIRM_FIRST = "CONFIRM_FIRST";
    private static final String MODE_HYBRID = "HYBRID";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CleanTempTool cleanTempTool;
    private final LogCleanupTool logCleanupTool;
    private final ServiceRestartTool serviceRestartTool;
    private final OpsPathPolicy opsPathPolicy;
    private final ObjectMapper objectMapper;
    private final AgenticRiskScoreEngine agenticRiskScoreEngine;
    private final McpInvocationSecurityGate mcpInvocationSecurityGate;
    private final OpsGovernanceService opsGovernanceService;
    private final OpsAuditTraceService opsAuditTraceService;
    private final HttpAuditSubject httpAuditSubject;

    @Autowired(required = false)
    private PerformanceWebSocketHandler performanceWebSocketHandler;

    @Value("${ops.auto-remediation.enabled:true}")
    private boolean enabled;

    /** CONFIRM_FIRST | IMMEDIATE | HYBRID */
    @Value("${ops.auto-remediation.run-mode:HYBRID}")
    private String runMode;

    /**
     * 巡检自动修复：总分 **严格小于** 本值则自动执行（与 {@link AgenticRiskScoreEngine} 同口径，默认 6，高于
     * {@code agent.security.risk-score-auto-max}，否则临时清理等难以自动执行）。
     */
    @Value("${ops.auto-remediation.risk-patrol-auto-max:6.0}")
    private double riskPatrolAutoMax;

    /** true：允许风险分 &gt; confirmMax 的步骤仍进入「待您确认」清单；false 则直接跳过该步骤 */
    @Value("${ops.auto-remediation.allow-above-confirm-max-in-pending:false}")
    private boolean allowAboveConfirmMaxInPending;

    @Value("${ops.auto-remediation.proposal-ttl-ms:1800000}")
    private long proposalTtlMs;

    /** 纳入「待确认」临时清理的磁盘下限（与巡检 WARN 阈值可一致） */
    @Value("${ops.auto-remediation.propose-temp-clean-disk-min:80}")
    private double proposeTempCleanDiskMin;

    /** 纳入「待确认」日志裁剪的磁盘下限 */
    @Value("${ops.auto-remediation.propose-log-clean-disk-min:85}")
    private double proposeLogCleanDiskMin;

    /** IMMEDIATE 下实际执行临时清理的磁盘下限（较高，避免轻微波动即删） */
    @Value("${ops.auto-remediation.disk-temp-clean-min-percent:88}")
    private double immediateTempCleanDiskMin;

    /** IMMEDIATE 下实际执行日志裁剪的磁盘下限 */
    @Value("${ops.auto-remediation.disk-log-clean-min-percent:93}")
    private double immediateLogCleanDiskMin;

    @Value("${ops.auto-remediation.temp-clean-days:7}")
    private int tempCleanDays;

    @Value("${ops.auto-remediation.log-clean-days:30}")
    private int logCleanDays;

    @Value("${ops.auto-remediation.cooldown-ms:3600000}")
    private long cooldownMs;

    @Value("${ops.auto-remediation.on-log-anomaly-propose-log-clean:true}")
    private boolean onLogAnomalyProposeLogClean;

    @Value("${ops.auto-remediation.on-alarm-severity-propose-log-clean:false}")
    private boolean onAlarmSeverityProposeLogClean;

    /** 逗号分隔；仅当存在 cpu 类巡检 finding 且 CPU≥阈值时纳入方案；须在 agent.service-restart.allowlist 内 */
    @Value("${ops.auto-remediation.cpu-pain-restart-services:}")
    private String cpuPainRestartServicesRaw;

    @Value("${ops.auto-remediation.cpu-restart-cpu-min-percent:85}")
    private double cpuRestartCpuMinPercent;

    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    private final Object pendingLock = new Object();
    private volatile String pendingProposalId;
    private volatile long pendingExpiresAtMs;
    private volatile String pendingFingerprint;
    private volatile List<Map<String, Object>> pendingSteps;
    private volatile String pendingSummary;
    private volatile Map<String, Object> pendingCorrelationDigest;
    private volatile int pendingFindingsCount;
    private volatile String pendingClaimedBy;
    private volatile long pendingClaimedAtMs;

    /** 最近一轮巡检：各 finding 的修复车道（AUTO / CONFIRM / MIXED / MANUAL / NONE） */
    private volatile List<Map<String, Object>> lastRemediationCoverage = List.of();

    private volatile Map<String, Object> lastSummary = Map.of();

    public OpsAutoRemediationService(
            CleanTempTool cleanTempTool,
            LogCleanupTool logCleanupTool,
            ServiceRestartTool serviceRestartTool,
            OpsPathPolicy opsPathPolicy,
            ObjectMapper objectMapper,
            AgenticRiskScoreEngine agenticRiskScoreEngine,
            McpInvocationSecurityGate mcpInvocationSecurityGate,
            OpsGovernanceService opsGovernanceService,
            OpsAuditTraceService opsAuditTraceService,
            HttpAuditSubject httpAuditSubject) {
        this.cleanTempTool = cleanTempTool;
        this.logCleanupTool = logCleanupTool;
        this.serviceRestartTool = serviceRestartTool;
        this.opsPathPolicy = opsPathPolicy;
        this.objectMapper = objectMapper;
        this.agenticRiskScoreEngine = agenticRiskScoreEngine;
        this.mcpInvocationSecurityGate = mcpInvocationSecurityGate;
        this.opsGovernanceService = opsGovernanceService;
        this.opsAuditTraceService = opsAuditTraceService;
        this.httpAuditSubject = httpAuditSubject;
    }

    public void applyHotConfig(boolean enabled,
                               String runMode,
                               double riskPatrolAutoMax,
                               double proposeTempCleanDiskMin,
                               double proposeLogCleanDiskMin) {
        this.enabled = enabled;
        this.runMode = runMode;
        this.riskPatrolAutoMax = riskPatrolAutoMax;
        this.proposeTempCleanDiskMin = proposeTempCleanDiskMin;
        this.proposeLogCleanDiskMin = proposeLogCleanDiskMin;
    }

    public Map<String, Object> getLastSummary() {
        return lastSummary;
    }

    /**
     * 最近一轮巡检中，各条线索对应的「自动修复 vs 确认后修复 vs 仅人工」策略说明。
     */
    public List<Map<String, Object>> getRemediationCoverage() {
        List<Map<String, Object>> c = lastRemediationCoverage;
        return c != null ? List.copyOf(c) : List.of();
    }

    /**
     * 当前是否有待用户确认的方案（与 MCP 控制台「确认执行」口令一致）。
     */
    public Map<String, Object> getPendingProposalView() {
        return getPendingProposalView(currentRequester());
    }

    public Map<String, Object> getPendingProposalView(String requester) {
        synchronized (pendingLock) {
            return buildPendingViewLocked(normalizeRequester(requester));
        }
    }

    /** 调用方已持有 {@link #pendingLock} 时使用，避免嵌套同步死锁。 */
    private Map<String, Object> buildPendingViewLocked(String requester) {
        if (pendingProposalId == null) {
            return Map.of("hasPending", false);
        }
        if (System.currentTimeMillis() > pendingExpiresAtMs) {
            clearPendingLocked();
            return Map.of("hasPending", false);
        }
        if (pendingClaimedBy != null && !pendingClaimedBy.equals(requester)) {
            return Map.of(
                    "hasPending", false,
                    "claimedByOther", true,
                    "expiresAtMs", pendingExpiresAtMs);
        }
        if (pendingClaimedBy == null) {
            pendingClaimedBy = requester;
            pendingClaimedAtMs = System.currentTimeMillis();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasPending", true);
        m.put("proposalId", pendingProposalId);
        m.put("expiresAtMs", pendingExpiresAtMs);
        m.put("summary", pendingSummary != null ? pendingSummary : "");
        m.put("steps", pendingSteps != null ? List.copyOf(pendingSteps) : List.of());
        m.put("correlationDigest", pendingCorrelationDigest != null ? pendingCorrelationDigest : Map.of());
        m.put("findingsCount", pendingFindingsCount);
        m.put("riskPatrolAutoMax", riskPatrolAutoMax);
        m.put("claimOwner", pendingClaimedBy);
        m.put("confirmHint", "调用确认接口时 confirmCode 须为「" + CONFIRM_CODE + "」");
        return m;
    }

    private Map<String, Object> buildPendingBroadcastLocked() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasPending", true);
        m.put("proposalId", pendingProposalId);
        m.put("expiresAtMs", pendingExpiresAtMs);
        m.put("summary", pendingSummary != null ? pendingSummary : "");
        m.put("steps", pendingSteps != null ? List.copyOf(pendingSteps) : List.of());
        m.put("correlationDigest", pendingCorrelationDigest != null ? pendingCorrelationDigest : Map.of());
        m.put("findingsCount", pendingFindingsCount);
        m.put("riskPatrolAutoMax", riskPatrolAutoMax);
        m.put("claimOwner", pendingClaimedBy);
        return m;
    }

    /**
     * 用户确认后执行待处理步骤；执行成功后清除 pending。
     */
    /**
     * 工作台助手「继续处理」等价确认：内部使用标准确认码，避免用户重复输入口令。
     */
    public Map<String, Object> confirmPendingFromAssistant(String proposalId) {
        return confirmPending(proposalId, CONFIRM_CODE, currentRequester());
    }

    public Map<String, Object> confirmPending(String proposalId, String confirmCode) {
        return confirmPending(proposalId, confirmCode, currentRequester());
    }

    public Map<String, Object> confirmPending(String proposalId, String confirmCode, String requester) {
        if (proposalId == null || proposalId.isBlank()) {
            return Map.of("success", false, "error", "proposalId 不能为空");
        }
        if (!CONFIRM_CODE.equals(confirmCode)) {
            return Map.of("success", false, "error", "确认码不正确，请输入「" + CONFIRM_CODE + "」");
        }
        String normalizedRequester = normalizeRequester(requester);
        List<Map<String, Object>> stepsCopy;
        Map<String, Object> digestCopy;
        int findingsCountSnap;
        synchronized (pendingLock) {
            if (pendingProposalId == null || !pendingProposalId.equals(proposalId)) {
                return Map.of("success", false, "error", "无匹配的待确认方案或已过期");
            }
            if (System.currentTimeMillis() > pendingExpiresAtMs) {
                clearPendingLocked();
                broadcastPendingCleared(LocalDateTime.now().format(TS), "EXPIRED");
                return Map.of("success", false, "error", "方案已过期，请等待下一轮巡检");
            }
            if (pendingClaimedBy == null) {
                pendingClaimedBy = normalizedRequester;
                pendingClaimedAtMs = System.currentTimeMillis();
            }
            if (!normalizedRequester.equals(pendingClaimedBy)) {
                return Map.of("success", false, "error", "该巡检待办已被其他操作者接管");
            }
            stepsCopy = pendingSteps != null ? List.copyOf(pendingSteps) : List.of();
            digestCopy = pendingCorrelationDigest != null
                    ? new LinkedHashMap<>(pendingCorrelationDigest)
                    : new LinkedHashMap<>();
            findingsCountSnap = pendingFindingsCount;
            clearPendingLocked();
        }
        if (stepsCopy.isEmpty()) {
            broadcastPendingCleared(LocalDateTime.now().format(TS), "EMPTY_PLAN");
            return Map.of("success", false, "error", "方案中无可执行步骤");
        }
        String ts = LocalDateTime.now().format(TS);
        Map<String, Object> exec = executeSteps(stepsCopy, ts, digestCopy, findingsCountSnap, "USER_CONFIRMED");
        armCooldownsForSteps(stepsCopy, System.currentTimeMillis());
        boolean allOk = true;
        int okCount = 0;
        int total = 0;
        Object actionsObj = exec.get("actions");
        if (actionsObj instanceof List<?> actions) {
            total = actions.size();
            for (Object item : actions) {
                if (item instanceof Map<?, ?> action) {
                    if (Boolean.TRUE.equals(action.get("success"))) {
                        okCount++;
                    } else {
                        allOk = false;
                    }
                }
            }
            if (total == 0) {
                allOk = false;
            }
        } else {
            allOk = Boolean.TRUE.equals(exec.get("success"));
        }
        exec.put("success", allOk);
        exec.put("successCount", okCount);
        exec.put("actionCount", total);
        exec.put("source", "USER_CONFIRMED");
        if (allOk) {
            broadcastPendingCleared(ts, "CONFIRMED_EXECUTED");
        } else {
            broadcastPendingCleared(ts, okCount > 0 ? "CONFIRMED_PARTIAL" : "CONFIRMED_FAILED");
        }
        return exec;
    }

    public void afterPatrol(Map<String, Object> correlation, List<Map<String, Object>> findings) {
        String ts = LocalDateTime.now().format(TS);
        int findingsCount = findings == null ? 0 : findings.size();
        if (!enabled) {
            lastRemediationCoverage = List.of();
            clearPendingProposalAndBroadcast(ts, "DISABLED");
            finalizePatrolState(ts, -1.0, findingsCount, 0, 0,
                    "DISABLED", "DISABLED", "自动修复已关闭。");
            return;
        }
        if (correlation == null || correlation.isEmpty()) {
            lastRemediationCoverage = List.of();
            clearPendingProposalAndBroadcast(ts, "INVALID_CORRELATION");
            finalizePatrolState(ts, -1.0, findingsCount, 0, 0,
                    "PATROL", "INVALID_CORRELATION", "本轮巡检缺少关联指标，未生成写类修复计划。");
            return;
        }

        double disk = asDouble(correlation.get("diskUsagePct"));
        double cpu = asDouble(correlation.get("cpuUsagePct"));
        double mem = asDouble(correlation.get("memoryUsagePct"));
        Set<String> codes = findingCodes(findings);
        List<Map<String, Object>> planSteps = buildPlanSteps(correlation, findings, codes, disk, cpu);

        boolean diskMetricsOk = disk > 0.0 && disk <= 100.0;
        String mode = normalizeMode(runMode);
        RiskSplit hybridSplit = diskMetricsOk && MODE_HYBRID.equals(mode) && !planSteps.isEmpty()
                ? splitByPatrolRisk(planSteps)
                : null;

        lastRemediationCoverage = buildRemediationCoverage(findings, planSteps, hybridSplit, mode, diskMetricsOk);

        if (!diskMetricsOk) {
            clearPendingProposalAndBroadcast(ts, "INVALID_METRICS");
            finalizePatrolState(ts, disk, findingsCount, planSteps.size(), 0,
                    mode, "INVALID_METRICS", "磁盘使用率指标异常或未采集，未执行自动修复。");
            return;
        }

        if (planSteps.isEmpty()) {
            clearPendingProposalAndBroadcast(ts, "NO_ACTION");
            finalizePatrolState(ts, disk, findingsCount, 0, 0,
                    mode, "NO_ACTION", "本轮没有匹配到可编排的自动修复步骤。");
            return;
        }

        if (MODE_CONFIRM_FIRST.equals(mode)) {
            offerPendingIfNeeded(planSteps, correlation, findings, disk, cpu, mem, codes, ts, "", "");
            finalizePatrolState(ts, disk, findingsCount, planSteps.size(), planSteps.size(),
                    mode, "PENDING_CONFIRMATION", "CONFIRM_FIRST 模式下，本轮修复步骤已进入待确认。");
            return;
        }

        if (MODE_HYBRID.equals(mode)) {
            if (hybridSplit == null) {
                hybridSplit = splitByPatrolRisk(planSteps);
            }
            int autoCount = 0;
            boolean autoSkippedByCooldown = false;
            if (!hybridSplit.low().isEmpty()) {
                long now = System.currentTimeMillis();
                if (cooldownAllows(hybridSplit.low(), now)) {
                    executeSteps(hybridSplit.low(), ts, correlation, findings == null ? 0 : findings.size(), "HYBRID_AUTO");
                    armCooldownsForSteps(hybridSplit.low(), now);
                    autoCount = hybridSplit.low().size();
                } else {
                    autoSkippedByCooldown = true;
                }
            }
            if (!hybridSplit.high().isEmpty()) {
                String prefix = autoCount > 0
                        ? String.format(Locale.ROOT, "已自动执行 %d 个低分项（风险分 < %.1f）。", autoCount, riskPatrolAutoMax)
                        : "";
                String fpSuffix = "|H:" + highStepsSignature(hybridSplit.high());
                offerPendingIfNeeded(hybridSplit.high(), correlation, findings, disk, cpu, mem, codes, ts, prefix, fpSuffix);
                if (autoCount > 0) {
                    markPendingOnLastSummary(hybridSplit.high().size());
                } else {
                    String reason = autoSkippedByCooldown
                            ? "高风险步骤已进入待确认；低风险步骤仍在冷却期，未重复执行。"
                            : "高风险步骤已进入待确认，需确认后执行。";
                    finalizePatrolState(ts, disk, findingsCount, planSteps.size(), hybridSplit.high().size(),
                            mode, "PENDING_CONFIRMATION", reason);
                }
                return;
            }
            if (autoCount > 0) {
                return;
            }
            if (autoSkippedByCooldown) {
                finalizePatrolState(ts, disk, findingsCount, planSteps.size(), 0,
                        mode, "SKIPPED_COOLDOWN", "低风险自动修复步骤仍在冷却期，未重复执行。");
                return;
            }
            finalizePatrolState(ts, disk, findingsCount, planSteps.size(), 0,
                    mode, "MANUAL_REVIEW", "本轮步骤未被安全门或治理策略放入自动/待确认车道。");
            return;
        }

        // IMMEDIATE：按更严条件过滤后再执行，并应用冷却
        List<Map<String, Object>> toRun = filterStepsForImmediate(planSteps, disk, cpu, codes);
        if (toRun.isEmpty()) {
            finalizePatrolState(ts, disk, findingsCount, planSteps.size(), 0,
                    mode, "NO_IMMEDIATE_ACTION", "IMMEDIATE 模式下未满足立即执行条件。");
            return;
        }
        long now = System.currentTimeMillis();
        if (!cooldownAllows(toRun, now)) {
            finalizePatrolState(ts, disk, findingsCount, toRun.size(), 0,
                    mode, "SKIPPED_COOLDOWN", "立即执行步骤仍在冷却期，未重复执行。");
            return;
        }
        executeSteps(toRun, ts, correlation, findings == null ? 0 : findings.size(), "IMMEDIATE");
        armCooldownsForSteps(toRun, now);
    }

    private boolean clearPendingProposal() {
        synchronized (pendingLock) {
            boolean hadPending = pendingProposalId != null;
            clearPendingLocked();
            return hadPending;
        }
    }

    private void clearPendingProposalAndBroadcast(String ts, String reason) {
        if (clearPendingProposal()) {
            broadcastPendingCleared(ts, reason);
        }
    }

    private void finalizePatrolState(String ts,
                                     double disk,
                                     int findingsCount,
                                     int plannedCount,
                                     int pendingCount,
                                     String source,
                                     String status,
                                     String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", ts);
        summary.put("diskUsagePct", disk);
        summary.put("findingsCount", findingsCount);
        summary.put("plannedCount", plannedCount);
        summary.put("pendingCount", pendingCount);
        summary.put("actions", List.of());
        summary.put("source", source);
        summary.put("status", status);
        summary.put("reason", reason);
        lastSummary = summary;
    }

    private void markPendingOnLastSummary(int pendingCount) {
        if (pendingCount <= 0 || lastSummary == null || lastSummary.isEmpty()) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>(lastSummary);
        summary.put("pendingCount", pendingCount);
        summary.put("status", "EXECUTED_WITH_PENDING");
        lastSummary = summary;
    }

    private void offerPendingIfNeeded(
            List<Map<String, Object>> planSteps,
            Map<String, Object> correlation,
            List<Map<String, Object>> findings,
            double disk, double cpu, double mem,
            Set<String> codes,
            String ts,
            String summaryPrefix,
            String fingerprintSuffix) {
        String fp = fingerprint(disk, cpu, mem, codes) + (fingerprintSuffix != null ? fingerprintSuffix : "");
        String newId;
        synchronized (pendingLock) {
            long now = System.currentTimeMillis();
            if (pendingProposalId != null && now <= pendingExpiresAtMs && fp.equals(pendingFingerprint)) {
                return;
            }
            newId = UUID.randomUUID().toString();
            pendingProposalId = newId;
            pendingExpiresAtMs = now + Math.max(120_000L, proposalTtlMs);
            pendingFingerprint = fp;
            pendingSteps = List.copyOf(planSteps);
            String core = buildSummaryText(planSteps, codes, disk, cpu);
            pendingSummary = (summaryPrefix != null && !summaryPrefix.isBlank() ? summaryPrefix : "") + core;
            pendingCorrelationDigest = correlationDigest(correlation);
            pendingFindingsCount = findings == null ? 0 : findings.size();
            pendingClaimedBy = null;
            pendingClaimedAtMs = 0L;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("channel", "patrol_remediation_pending");
        envelope.put("timestamp", ts);
        synchronized (pendingLock) {
            envelope.putAll(buildPendingBroadcastLocked());
        }
        broadcastJson(envelope);
        log.info("[自动修复] 已生成待确认方案 proposalId={} 步骤数={}", newId, planSteps.size());
    }

    private void clearPendingLocked() {
        pendingProposalId = null;
        pendingExpiresAtMs = 0L;
        pendingFingerprint = null;
        pendingSteps = null;
        pendingSummary = null;
        pendingCorrelationDigest = null;
        pendingFindingsCount = 0;
        pendingClaimedBy = null;
        pendingClaimedAtMs = 0L;
    }

    private record RiskSplit(List<Map<String, Object>> low, List<Map<String, Object>> high) {
    }

    /**
     * 按 {@link AgenticRiskScoreEngine} 与 MCP 同口径评分拆分：&lt; riskPatrolAutoMax 为低分项，否则为待您确认的高分项。
     */
    private RiskSplit splitByPatrolRisk(List<Map<String, Object>> planSteps) {
        List<Map<String, Object>> low = new ArrayList<>();
        List<Map<String, Object>> high = new ArrayList<>();
        double confirmMax = agenticRiskScoreEngine.getConfirmMax();
        double autoTh = Math.max(0.1, Math.min(10.0, riskPatrolAutoMax));
        for (Map<String, Object> raw : planSteps) {
            String tool = toolNameForStep(raw);
            if ("Unknown".equals(tool)) {
                continue;
            }
            Map<String, Object> params = paramsForMcpScore(raw);
            String instruction = mcpInvocationSecurityGate.buildInstruction(tool, params);
            AgenticRiskScoreEngine.ScoreResult sr = agenticRiskScoreEngine.score(tool, params, instruction);
            double total = sr.total();
            if (total > confirmMax && !allowAboveConfirmMaxInPending) {
                log.warn("[自动修复] 跳过步骤（风险分 {} > confirmMax {}）：{}", total, confirmMax, instruction);
                continue;
            }
            Map<String, Object> tagged = new LinkedHashMap<>(raw);
            tagged.put("riskScore", total);
            tagged.put("riskDimensions", sr.dimensions());
            tagged.put("riskExplanation", sr.explanation());
            GovernanceAdmissionVerdict governanceVerdict = readGovernanceVerdict(raw);
            if (governanceVerdict == GovernanceAdmissionVerdict.CONFIRM_ONLY) {
                high.add(tagged);
            } else if (total < autoTh) {
                low.add(tagged);
            } else {
                high.add(tagged);
            }
        }
        return new RiskSplit(dedupeSteps(low), dedupeSteps(high));
    }

    private static String toolNameForStep(Map<String, Object> step) {
        String kind = String.valueOf(step.getOrDefault("kind", ""));
        return switch (kind) {
            case "CLEAN_TEMP" -> "CleanTempTool";
            case "CLEAN_LOG" -> "LogCleanupTool";
            case "RESTART_SERVICE" -> "ServiceRestartTool";
            default -> "Unknown";
        };
    }

    private Map<String, Object> paramsForMcpScore(Map<String, Object> step) {
        String kind = String.valueOf(step.getOrDefault("kind", ""));
        return switch (kind) {
            case "CLEAN_TEMP" -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("path", step.get("path"));
                p.put("days", step.get("days"));
                p.put("dryRun", false);
                p.put("confirmDelete", true);
                yield p;
            }
            case "CLEAN_LOG" -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("path", step.get("path"));
                p.put("days", step.get("days"));
                p.put("dryRun", false);
                p.put("confirmDelete", true);
                yield p;
            }
            case "RESTART_SERVICE" -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("serviceName", step.get("serviceName"));
                p.put("dryRun", false);
                p.put("confirmRestart", true);
                yield p;
            }
            default -> Map.of();
        };
    }

    private static String highStepsSignature(List<Map<String, Object>> highs) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : highs) {
            sb.append(String.valueOf(m.get("kind")))
                    .append(':')
                    .append(String.valueOf(m.get("path")))
                    .append(':')
                    .append(String.valueOf(m.get("serviceName")))
                    .append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private List<Map<String, Object>> buildPlanSteps(
            Map<String, Object> correlation,
            List<Map<String, Object>> findings,
            Set<String> codes,
            double disk, double cpu) {

        List<Map<String, Object>> steps = new ArrayList<>();
        Set<String> tempPaths = new LinkedHashSet<>();

        for (String root : opsPathPolicy.snapshotTempCleanRoots()) {
            if (root != null && !root.isBlank()) {
                tempPaths.add(root.trim());
            }
        }
        String hotspotClean = pickHotspotCleanPath(correlation);
        if (hotspotClean != null) {
            tempPaths.add(hotspotClean);
        }

        boolean wantTemp = disk >= proposeTempCleanDiskMin
                || codes.contains("disk")
                || codes.contains("memory")
                || (hotspotClean != null && codes.contains("disk_hotspot"));
        if (wantTemp) {
            for (String p : tempPaths) {
                steps.add(stepCleanTemp(p, tempCleanDays));
            }
        }

        boolean wantLog = disk >= proposeLogCleanDiskMin
                || codes.contains("disk")
                || codes.contains("disk_hotspot")
                || (onLogAnomalyProposeLogClean && codes.contains("log_anomaly"))
                || (onAlarmSeverityProposeLogClean && codes.contains("alarm_severity"));
        if (wantLog) {
            for (String root : opsPathPolicy.snapshotLogCleanupRoots()) {
                if (root != null && !root.isBlank()) {
                    steps.add(stepCleanLog(root.trim(), logCleanDays));
                }
            }
        }

        if (codes.contains("cpu") && cpu >= cpuRestartCpuMinPercent) {
            for (String svc : parseCsvServices(cpuPainRestartServicesRaw)) {
                steps.add(stepRestart(svc));
            }
        }

        return opsGovernanceService.filterPlanSteps(dedupeSteps(steps));
    }

    private static GovernanceAdmissionVerdict readGovernanceVerdict(Map<String, Object> step) {
        Object raw = step.get("governanceVerdict");
        if (raw == null) {
            return GovernanceAdmissionVerdict.ALLOW_AUTO;
        }
        try {
            return GovernanceAdmissionVerdict.valueOf(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return GovernanceAdmissionVerdict.ALLOW_AUTO;
        }
    }

    private static boolean isGovernanceAutoAllowed(Map<String, Object> step) {
        return readGovernanceVerdict(step) == GovernanceAdmissionVerdict.ALLOW_AUTO;
    }

    private static boolean isAutoExecutionSource(String source) {
        return "HYBRID_AUTO".equals(source) || "IMMEDIATE".equals(source);
    }

    private static String targetOfStep(Map<String, Object> step) {
        if ("RESTART_SERVICE".equals(String.valueOf(step.get("kind")))) {
            return String.valueOf(step.get("serviceName"));
        }
        return String.valueOf(step.get("path"));
    }

    private List<Map<String, Object>> filterStepsForImmediate(
            List<Map<String, Object>> plan,
            double disk, double cpu,
            Set<String> codes) {

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : plan) {
            if (!isGovernanceAutoAllowed(s)) {
                continue;
            }
            String k = String.valueOf(s.getOrDefault("kind", ""));
            switch (k) {
                case "CLEAN_TEMP" -> {
                    if (disk >= immediateTempCleanDiskMin
                            || codes.contains("disk")
                            || codes.contains("memory")
                            || (codes.contains("disk_hotspot") && s.get("path") != null)) {
                        out.add(s);
                    }
                }
                case "CLEAN_LOG" -> {
                    if (disk >= immediateLogCleanDiskMin
                            || codes.contains("disk")
                            || codes.contains("disk_hotspot")
                            || (onLogAnomalyProposeLogClean && codes.contains("log_anomaly"))
                            || (onAlarmSeverityProposeLogClean && codes.contains("alarm_severity"))) {
                        out.add(s);
                    }
                }
                case "RESTART_SERVICE" -> {
                    if (codes.contains("cpu") && cpu >= cpuRestartCpuMinPercent) {
                        out.add(s);
                    }
                }
                default -> {
                    /* skip */
                }
            }
        }
        return dedupeSteps(out);
    }

    private boolean cooldownAllows(List<Map<String, Object>> toRun, long now) {
        boolean needTemp = toRun.stream().anyMatch(s -> "CLEAN_TEMP".equals(s.get("kind")));
        boolean needLog = toRun.stream().anyMatch(s -> "CLEAN_LOG".equals(s.get("kind")));
        boolean needRestart = toRun.stream().anyMatch(s -> "RESTART_SERVICE".equals(s.get("kind")));
        if (needTemp && !cooldownExpired("temp_clean", now)) {
            return false;
        }
        if (needLog && !cooldownExpired("log_clean", now)) {
            return false;
        }
        if (needRestart && !cooldownExpired("service_restart", now)) {
            return false;
        }
        return true;
    }

    private void armCooldownsForSteps(List<Map<String, Object>> toRun, long now) {
        long cool = Math.max(60_000L, cooldownMs);
        for (Map<String, Object> s : toRun) {
            String k = String.valueOf(s.getOrDefault("kind", ""));
            switch (k) {
                case "CLEAN_TEMP" -> cooldownUntil.put("temp_clean", now + cool);
                case "CLEAN_LOG" -> cooldownUntil.put("log_clean", now + cool);
                case "RESTART_SERVICE" -> cooldownUntil.put("service_restart", now + cool);
                default -> {
                }
            }
        }
    }

    private Map<String, Object> executeSteps(
            List<Map<String, Object>> steps,
            String ts,
            Map<String, Object> correlation,
            int findingsCount,
            String source) {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (opsGovernanceService.isEnabled()) {
                GovernanceAdmissionVerdict gv = readGovernanceVerdict(step);
                if (gv == GovernanceAdmissionVerdict.FORBIDDEN) {
                    log.warn("[自动修复] 治理禁止步骤 kind={} : {}", step.get("kind"), step.get("governanceReason"));
                    actions.add(actionRow(String.valueOf(step.get("kind")),
                            targetOfStep(step), false,
                            "治理策略禁止：" + step.get("governanceReason")));
                    continue;
                }
                if (isAutoExecutionSource(source) && gv != GovernanceAdmissionVerdict.ALLOW_AUTO) {
                    log.warn("[自动修复] 治理拦截自动步骤 kind={} verdict={}", step.get("kind"), gv);
                    actions.add(actionRow(String.valueOf(step.get("kind")),
                            targetOfStep(step), false,
                            "治理策略不允许自动执行：" + step.get("governanceReason")));
                    continue;
                }
            }
            String kind = String.valueOf(step.getOrDefault("kind", ""));
            try {
                switch (kind) {
                    case "CLEAN_TEMP" -> {
                        String path = String.valueOf(step.get("path"));
                        int days = asInt(step.get("days"), tempCleanDays);
                        String out = cleanTempTool.cleanTempFiles(path, days, false, true);
                        boolean ok = parseToolJsonSuccess(out);
                        actions.add(actionRow("CleanTempTool", path, ok, truncate(out, 1200)));
                    }
                    case "CLEAN_LOG" -> {
                        String path = String.valueOf(step.get("path"));
                        int days = asInt(step.get("days"), logCleanDays);
                        String out = logCleanupTool.cleanupOldLogs(path, days, false, true);
                        boolean ok = parseToolJsonSuccess(out);
                        actions.add(actionRow("LogCleanupTool", path, ok, truncate(out, 1200)));
                    }
                    case "RESTART_SERVICE" -> {
                        String svc = String.valueOf(step.get("serviceName"));
                        String out = serviceRestartTool.restartService(svc, false, true);
                        boolean ok = parseToolJsonSuccess(out);
                        actions.add(actionRow("ServiceRestartTool", svc, ok, truncate(out, 1200)));
                    }
                    default -> actions.add(actionRow(kind, String.valueOf(step), false, "未知步骤类型"));
                }
            } catch (Exception e) {
                log.warn("[自动修复] 步骤失败 kind={} : {}", kind, e.getMessage());
                String tgt = "RESTART_SERVICE".equals(kind)
                        ? String.valueOf(step.get("serviceName"))
                        : String.valueOf(step.get("path"));
                actions.add(actionRow(kind, tgt, false, e.getMessage()));
            }
        }
        double disk = correlation == null ? -1.0 : asDouble(correlation.get("diskUsagePct"));
        String resultSummary = summarizeActions(actions);
        persistExecutionAudit(traceId, steps, correlation, findingsCount, source, actions, resultSummary, startTime);
        finalizeSummary(ts, disk, findingsCount, actions, source, traceId, resultSummary);
        broadcastRemediationDone(ts, lastSummary);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("timestamp", ts);
        res.put("traceId", traceId);
        res.put("actions", actions);
        res.put("resultSummary", resultSummary);
        return res;
    }

    private void finalizeSummary(String ts, double disk, int findingsCount,
                                  Object actions, String source, String traceId, String resultSummary) {
        int actionCount = 0;
        int successCount = 0;
        if (actions instanceof List<?> list) {
            actionCount = list.size();
            for (Object item : list) {
                if (item instanceof Map<?, ?> action && Boolean.TRUE.equals(action.get("success"))) {
                    successCount++;
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("timestamp", ts);
        summary.put("diskUsagePct", disk);
        summary.put("findingsCount", findingsCount);
        summary.put("actions", actions);
        summary.put("source", source);
        summary.put("traceId", traceId);
        summary.put("resultSummary", resultSummary);
        summary.put("status", actionCount == successCount ? "EXECUTED" : "EXECUTION_PARTIAL");
        summary.put("executedCount", actionCount);
        summary.put("successCount", successCount);
        summary.put("pendingCount", 0);
        lastSummary = summary;
    }

    private void persistExecutionAudit(
            String traceId,
            List<Map<String, Object>> planSteps,
            Map<String, Object> correlation,
            int findingsCount,
            String source,
            List<Map<String, Object>> actions,
            String resultSummary,
            long startTime) {
        List<Map<String, Object>> traceSteps = new ArrayList<>();
        traceSteps.add(TraceService.cotStep(1, "接收", "接收巡检修复执行请求，source=" + source));
        traceSteps.add(TraceService.cotStep(2, "感知", "关联快照: " + truncate(String.valueOf(correlation), 1200)));
        traceSteps.add(TraceService.cotStep(3, "推理",
                "计划步骤数=" + (planSteps == null ? 0 : planSteps.size()) + "，findingsCount=" + findingsCount));
        traceSteps.add(TraceService.cotStep(4, "执行", resultSummary));
        traceSteps.add(TraceService.step("plan", truncate(String.valueOf(planSteps), 2000)));
        traceSteps.add(TraceService.step("actions", truncate(String.valueOf(actions), 2000)));

        boolean allOk = actions.stream().allMatch(action -> Boolean.TRUE.equals(action.get("success")));
        String securityOutcome = allOk ? "PASS" : "EXECUTION_PARTIAL";
        opsAuditTraceService.save(
                traceId,
                "PATROL",
                "[PATROL_REMEDIATION] " + source,
                riskLevelForSteps(planSteps, source).name(),
                securityOutcome,
                "PatrolRemediation",
                allOk,
                resultSummary,
                traceSteps,
                System.currentTimeMillis() - startTime,
                currentRequester(),
                opsPathPolicy.getPolicyVersion());
    }

    private RiskLevel riskLevelForSteps(List<Map<String, Object>> planSteps, String source) {
        double maxRisk = 0.0;
        if (planSteps != null) {
            for (Map<String, Object> step : planSteps) {
                Object riskScore = step.get("riskScore");
                if (riskScore instanceof Number number) {
                    maxRisk = Math.max(maxRisk, number.doubleValue());
                }
            }
        }
        if (maxRisk >= agenticRiskScoreEngine.getConfirmMax()) {
            return RiskLevel.HIGH;
        }
        if (maxRisk >= riskPatrolAutoMax || "USER_CONFIRMED".equals(source)) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static String summarizeActions(List<Map<String, Object>> actions) {
        if (actions == null || actions.isEmpty()) {
            return "本次没有执行任何修复动作。";
        }
        int successCount = 0;
        List<String> detail = new ArrayList<>();
        for (Map<String, Object> action : actions) {
            if (Boolean.TRUE.equals(action.get("success"))) {
                successCount++;
            }
            detail.add(summarizeAction(action));
        }
        int failedCount = actions.size() - successCount;
        StringBuilder sb = new StringBuilder();
        sb.append("已执行巡检修复动作 ").append(actions.size()).append(" 个，成功 ")
                .append(successCount).append(" 个");
        if (failedCount > 0) {
            sb.append("，失败 ").append(failedCount).append(" 个");
        }
        sb.append("。");
        if (!detail.isEmpty()) {
            sb.append(String.join("；", detail));
            sb.append("。");
        }
        return truncate(sb.toString(), 1200);
    }

    private static String summarizeAction(Map<String, Object> action) {
        if (action == null || action.isEmpty()) {
            return "未知动作未返回结果";
        }
        String tool = String.valueOf(action.getOrDefault("tool", "未知工具"));
        String target = String.valueOf(action.getOrDefault("path", ""));
        boolean ok = Boolean.TRUE.equals(action.get("success"));
        String detail = String.valueOf(action.getOrDefault("detail", ""));
        StringBuilder sb = new StringBuilder();
        sb.append(toolDisplayName(tool));
        if (!target.isBlank() && !"null".equalsIgnoreCase(target)) {
            sb.append(" `").append(target).append("`");
        }
        sb.append(ok ? " 执行成功" : " 执行失败");
        String effect = actionEffect(detail);
        if (!effect.isBlank()) {
            sb.append("，").append(effect);
        } else if (!ok && !detail.isBlank() && !"null".equalsIgnoreCase(detail)) {
            sb.append("，原因：").append(truncate(detail, 160));
        }
        return sb.toString();
    }

    private static String toolDisplayName(String tool) {
        return switch (tool) {
            case "CleanTempTool" -> "临时文件清理";
            case "LogCleanupTool" -> "日志裁剪";
            case "ServiceRestartTool" -> "服务重启";
            default -> tool == null || tool.isBlank() ? "未知工具" : tool;
        };
    }

    private static String actionEffect(String detail) {
        if (detail == null || detail.isBlank() || "null".equalsIgnoreCase(detail)) {
            return "";
        }
        try {
            JsonNode root = new ObjectMapper().readTree(detail);
            JsonNode data = root.path("data");
            if (data.isMissingNode() && root.has("result")) {
                data = root.path("result");
            }
            if (data.isTextual()) {
                data = new ObjectMapper().readTree(data.asText());
            }
            if (data.isObject()) {
                int deleted = data.path("filesDeleted").asInt(-1);
                int found = data.path("filesFound").asInt(-1);
                int protectedSkipped = data.path("protectedSkipped").asInt(0);
                int lockedSkipped = data.path("lockedSkipped").asInt(0);
                long bytesFreed = data.path("bytesFreed").asLong(-1);
                String businessEffect = data.path("businessEffect").asText("");
                StringBuilder sb = new StringBuilder();
                if (deleted >= 0) {
                    sb.append("删除 ").append(deleted).append(" 个文件");
                } else if (found >= 0) {
                    sb.append("扫描到 ").append(found).append(" 个文件");
                }
                if (bytesFreed > 0) {
                    if (sb.length() > 0) {
                        sb.append("，");
                    }
                    sb.append("释放约 ").append(formatBytes(bytesFreed));
                }
                if (protectedSkipped > 0) {
                    if (sb.length() > 0) {
                        sb.append("，");
                    }
                    sb.append("跳过受保护文件 ").append(protectedSkipped).append(" 个");
                }
                if (bytesFreed == 0 && "NO_EFFECT".equalsIgnoreCase(businessEffect)) {
                    if (sb.length() > 0) {
                        sb.append("；");
                    }
                    sb.append("未释放空间");
                }
                if (lockedSkipped > 0) {
                    if (sb.length() > 0) {
                        sb.append("；");
                    }
                    sb.append("跳过被占用文件 ").append(lockedSkipped).append(" 个");
                }
                String message = data.path("message").asText("");
                if (sb.length() == 0 && !message.isBlank()) {
                    sb.append(message);
                }
                return truncate(sb.toString(), 180);
            }
        } catch (Exception ignored) {
            // fall back below
        }
        if (detail.length() <= 180 && !detail.startsWith("{") && !detail.startsWith("[")) {
            return detail;
        }
        return "";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.1f GiB", mib / 1024.0);
    }

    private void broadcastRemediationDone(String ts, Map<String, Object> summary) {
        if (performanceWebSocketHandler == null) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("channel", "patrol_remediation");
        envelope.put("timestamp", ts);
        envelope.put("remediation", summary);
        broadcastJson(envelope);
    }

    private void broadcastPendingCleared(String ts, String reason) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("channel", "patrol_remediation_pending");
        envelope.put("timestamp", ts);
        envelope.put("hasPending", false);
        envelope.put("reason", reason);
        broadcastJson(envelope);
    }

    private void broadcastJson(Map<String, Object> envelope) {
        if (performanceWebSocketHandler == null) {
            return;
        }
        try {
            performanceWebSocketHandler.broadcastJson(envelope);
        } catch (Exception e) {
            log.debug("自动修复 WebSocket 推送失败: {}", e.getMessage());
        }
    }

    private boolean parseToolJsonSuccess(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            if (!n.path("success").asBoolean(false)) {
                return false;
            }
            String status = n.path("status").asText("SUCCESS");
            if ("WARN".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                return false;
            }
            JsonNode data = n.path("data");
            if (data.isTextual()) {
                data = objectMapper.readTree(data.asText());
            }
            String effect = data.path("businessEffect").asText("");
            String mode = data.path("mode").asText("");
            if ("NO_EFFECT".equalsIgnoreCase(effect)
                    || "NOOP".equalsIgnoreCase(mode)
                    || "SKIP".equalsIgnoreCase(mode)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return !json.contains("\"success\":false");
        }
    }

    private static Map<String, Object> stepCleanTemp(String path, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "CLEAN_TEMP");
        m.put("path", path);
        m.put("days", days);
        return m;
    }

    private static Map<String, Object> stepCleanLog(String path, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "CLEAN_LOG");
        m.put("path", path);
        m.put("days", days);
        return m;
    }

    private static Map<String, Object> stepRestart(String service) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "RESTART_SERVICE");
        m.put("serviceName", service);
        return m;
    }

    private List<Map<String, Object>> dedupeSteps(List<Map<String, Object>> steps) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            String k = String.valueOf(s.getOrDefault("kind", ""));
            String key = switch (k) {
                case "CLEAN_TEMP" -> k + "|" + s.get("path") + "|" + s.get("days");
                case "CLEAN_LOG" -> k + "|" + s.get("path") + "|" + s.get("days");
                case "RESTART_SERVICE" -> k + "|" + s.get("serviceName");
                default -> k + "|" + s;
            };
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    private String pickHotspotCleanPath(Map<String, Object> correlation) {
        Object top = correlation.get("diskHotspotsTop");
        if (!(top instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object row = list.get(0);
        if (!(row instanceof Map<?, ?> m)) {
            return null;
        }
        Object p = m.get("path");
        if (p == null) {
            return null;
        }
        String path = String.valueOf(p).trim();
        if (path.isEmpty() || !opsPathPolicy.isAllowedCleanDirectory(path)) {
            return null;
        }
        return path;
    }

    private static Set<String> findingCodes(List<Map<String, Object>> findings) {
        Set<String> codes = new LinkedHashSet<>();
        if (findings == null) {
            return codes;
        }
        for (Map<String, Object> f : findings) {
            Object c = f.get("code");
            if (c != null && !String.valueOf(c).isBlank()) {
                codes.add(String.valueOf(c));
            }
        }
        return codes;
    }

    private static List<String> parseCsvServices(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String fingerprint(double disk, double cpu, double mem, Set<String> codes) {
        return String.format(Locale.ROOT, "%.1f|%.1f|%.1f|%s", disk, cpu, mem, String.join(",", codes));
    }

    private static Map<String, Object> correlationDigest(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diskUsagePct", c.get("diskUsagePct"));
        m.put("cpuUsagePct", c.get("cpuUsagePct"));
        m.put("memoryUsagePct", c.get("memoryUsagePct"));
        m.put("timestamp", c.get("timestamp"));
        return m;
    }

    private static String buildSummaryText(List<Map<String, Object>> steps, Set<String> codes, double disk, double cpu) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "磁盘约 %.1f%%、CPU 约 %.1f%%；巡检码: %s。", disk, cpu, codes.isEmpty() ? "—" : String.join(",", codes)));
        sb.append(" 将执行：");
        List<String> bits = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            bits.add(summarizeStep(s));
        }
        sb.append(String.join("；", bits));
        sb.append(" 确认后生效。");
        return sb.toString();
    }

    private static String summarizeStep(Map<String, Object> s) {
        String base = switch (String.valueOf(s.getOrDefault("kind", ""))) {
            case "CLEAN_TEMP" -> "清理临时文件 " + s.get("path");
            case "CLEAN_LOG" -> "裁剪陈旧日志 " + s.get("path");
            case "RESTART_SERVICE" -> "重启服务 " + s.get("serviceName");
            default -> String.valueOf(s.get("kind"));
        };
        Object rs = s.get("riskScore");
        if (rs instanceof Number n) {
            return base + String.format(Locale.ROOT, "（风险分 %.1f）", n.doubleValue());
        }
        return base;
    }

    private static Map<String, Object> actionRow(String tool, String target, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("path", target);
        m.put("success", ok);
        m.put("detail", detail);
        return m;
    }

    private String currentRequester() {
        return normalizeRequester(httpAuditSubject.currentOperatorId());
    }

    private static String normalizeRequester(String requester) {
        if (requester == null || requester.isBlank()) {
            return "anonymous";
        }
        return requester.trim();
    }

    private boolean cooldownExpired(String key, long nowMs) {
        Long until = cooldownUntil.get(key);
        return until == null || nowMs >= until;
    }

    private static double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
                /* fallthrough */
            }
        }
        return def;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private List<Map<String, Object>> buildRemediationCoverage(
            List<Map<String, Object>> findings,
            List<Map<String, Object>> planSteps,
            RiskSplit hybridSplit,
            String mode,
            boolean diskMetricsOk) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", f.get("code"));
            row.put("level", f.get("level"));
            row.put("title", f.get("title"));
            row.put("detail", f.get("detail"));
            String code = String.valueOf(f.getOrDefault("code", ""));
            row.put("remediation", classifyRemediationPolicy(code, mode, planSteps, hybridSplit, diskMetricsOk));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> classifyRemediationPolicy(
            String code,
            String mode,
            List<Map<String, Object>> planSteps,
            RiskSplit hybridSplit,
            boolean diskMetricsOk) {
        if (!diskMetricsOk) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "NONE");
            m.put("hint", "磁盘使用率指标异常或未采集，未生成写类修复步骤。");
            return m;
        }
        return switch (code) {
            case "process" -> manualRemediation(
                    "僵尸进程需修复父进程或缺陷服务；本平台不自动 kill/reparent。");
            case "drain" -> manualRemediation(
                    "新日志模板为观测信号；请结合日志分析与业务变更确认，无通用自动写修复。");
            case "cpu" -> classifyCpuRemediation(mode, planSteps, hybridSplit);
            case "disk", "disk_hotspot", "memory" -> classifyDiskFamilyRemediation(mode, planSteps, hybridSplit);
            case "log_anomaly" -> classifyLogAnomalyRemediation(mode, planSteps, hybridSplit);
            case "alarm_severity" -> classifyAlarmSeverityRemediation(mode, planSteps, hybridSplit);
            default -> manualRemediation("暂无可编程自动修复；请人工处理或在 playbook 中扩展。");
        };
    }

    private static Map<String, Object> manualRemediation(String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lane", "MANUAL");
        m.put("hint", hint);
        return m;
    }

    private Map<String, Object> classifyCpuRemediation(String mode, List<Map<String, Object>> planSteps, RiskSplit split) {
        if (!planHasKind(planSteps, "RESTART_SERVICE")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "NONE");
            m.put("hint", "未配置 ops.auto-remediation.cpu-pain-restart-services 或条件未满足，未生成服务重启步骤。");
            return m;
        }
        if (MODE_IMMEDIATE.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "AUTO");
            m.put("hint", "IMMEDIATE：满足立即条件时直接执行白名单服务重启。");
            return m;
        }
        if (MODE_CONFIRM_FIRST.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "CONFIRM");
            m.put("hint", "全部写类步骤（含重启）需您确认后执行。");
            return m;
        }
        return classifyHybridSubset(planSteps, split, Set.of("RESTART_SERVICE"));
    }

    private Map<String, Object> classifyDiskFamilyRemediation(String mode, List<Map<String, Object>> planSteps, RiskSplit split) {
        boolean has = planHasKind(planSteps, "CLEAN_TEMP") || planHasKind(planSteps, "CLEAN_LOG");
        if (!has) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "NONE");
            m.put("hint", "当前策略未纳入临时目录/日志裁剪步骤。");
            return m;
        }
        if (MODE_IMMEDIATE.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "AUTO");
            m.put("hint", "IMMEDIATE：满足立即条件时直接执行清理类步骤。");
            return m;
        }
        if (MODE_CONFIRM_FIRST.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "CONFIRM");
            m.put("hint", "全部清理类步骤需您确认后执行。");
            return m;
        }
        return classifyHybridSubset(planSteps, split, Set.of("CLEAN_TEMP", "CLEAN_LOG"));
    }

    private Map<String, Object> classifyLogAnomalyRemediation(String mode, List<Map<String, Object>> planSteps, RiskSplit split) {
        if (!planHasKind(planSteps, "CLEAN_LOG")) {
            return manualRemediation("异常日志量上升：请人工分析根因；可在策略中启用日志裁剪 playbook。");
        }
        if (MODE_IMMEDIATE.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "AUTO");
            m.put("hint", "IMMEDIATE：满足条件时直接执行日志裁剪。");
            return m;
        }
        if (MODE_CONFIRM_FIRST.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "CONFIRM");
            m.put("hint", "日志裁剪需您确认后执行。");
            return m;
        }
        return classifyHybridSubset(planSteps, split, Set.of("CLEAN_LOG"));
    }

    private Map<String, Object> classifyAlarmSeverityRemediation(String mode, List<Map<String, Object>> planSteps, RiskSplit split) {
        if (!planHasKind(planSteps, "CLEAN_LOG")) {
            return manualRemediation("ERROR/FATAL 告警较多：请在告警台做归因；可开启 on-alarm-severity-propose-log-clean 以纳入日志裁剪。");
        }
        if (MODE_IMMEDIATE.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "AUTO");
            m.put("hint", "IMMEDIATE：满足条件时直接执行日志裁剪。");
            return m;
        }
        if (MODE_CONFIRM_FIRST.equals(mode)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", "CONFIRM");
            m.put("hint", "日志裁剪需您确认后执行。");
            return m;
        }
        return classifyHybridSubset(planSteps, split, Set.of("CLEAN_LOG"));
    }

    private Map<String, Object> classifyHybridSubset(
            List<Map<String, Object>> planSteps,
            RiskSplit split,
            Set<String> kinds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("riskPatrolAutoMax", riskPatrolAutoMax);
        if (split == null) {
            m.put("lane", "MANUAL");
            m.put("hint", "HYBRID 风险拆分不可用。");
            return m;
        }
        List<Map<String, Object>> rel = new ArrayList<>();
        for (Map<String, Object> s : planSteps) {
            if (kinds.contains(String.valueOf(s.get("kind")))) {
                rel.add(s);
            }
        }
        int nLow = 0;
        int nHigh = 0;
        for (Map<String, Object> s : rel) {
            if (stepInBucket(split.low(), s)) {
                nLow++;
            } else if (stepInBucket(split.high(), s)) {
                nHigh++;
            }
        }
        if (nLow > 0 && nHigh > 0) {
            m.put("lane", "MIXED");
            m.put("hint", String.format(Locale.ROOT,
                    "风险分 < %.1f 的 %d 步将自动执行；≥ %.1f 的 %d 步待您确认。",
                    riskPatrolAutoMax, nLow, riskPatrolAutoMax, nHigh));
        } else if (nLow > 0) {
            m.put("lane", "AUTO");
            m.put("hint", String.format(Locale.ROOT,
                    "相关写步骤风险分均 < %.1f，将自动执行（仍受冷却约束）。", riskPatrolAutoMax));
        } else if (nHigh > 0) {
            m.put("lane", "CONFIRM");
            m.put("hint", String.format(Locale.ROOT,
                    "相关写步骤风险分均 ≥ %.1f，仅待您确认后执行。", riskPatrolAutoMax));
        } else {
            m.put("lane", "NONE");
            m.put("hint", "无关联写步骤（可能已被安全门剔除）。");
        }
        return m;
    }

    private static boolean planHasKind(List<Map<String, Object>> plan, String kind) {
        if (plan == null) {
            return false;
        }
        for (Map<String, Object> s : plan) {
            if (kind.equals(String.valueOf(s.get("kind")))) {
                return true;
            }
        }
        return false;
    }

    private static String stepSignature(Map<String, Object> s) {
        return String.valueOf(s.get("kind")) + "|" + String.valueOf(s.get("path")) + "|" + String.valueOf(s.get("serviceName"));
    }

    private static boolean stepInBucket(List<Map<String, Object>> bucket, Map<String, Object> step) {
        if (bucket == null) {
            return false;
        }
        String sig = stepSignature(step);
        for (Map<String, Object> b : bucket) {
            if (sig.equals(stepSignature(b))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_HYBRID;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (MODE_IMMEDIATE.equals(u)) {
            return MODE_IMMEDIATE;
        }
        if (MODE_CONFIRM_FIRST.equals(u)) {
            return MODE_CONFIRM_FIRST;
        }
        if (MODE_HYBRID.equals(u)) {
            return MODE_HYBRID;
        }
        return MODE_HYBRID;
    }
}
