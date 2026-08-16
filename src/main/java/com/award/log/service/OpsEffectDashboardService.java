package com.award.log.service;

import com.award.log.agent.RemediationEffectEvaluator;
import com.award.log.mcp.McpToolPayloadParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聚合审计、巡检、自愈与资源快照，生成真实口径的运维效果看板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsEffectDashboardService {

    private static final Pattern HEALING_SCORE_IN_TEXT = Pattern.compile("自愈评分\\s*(\\d+)/100");

    private final JdbcTemplate jdbcTemplate;
    private final OpsAuditTraceService opsAuditTraceService;
    private final PatrolHistoryService patrolHistoryService;
    private final StatisticsService statisticsService;
    private final RemediationEffectEvaluator remediationEffectEvaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> buildDashboard(int days) {
        int periodDays = Math.min(30, Math.max(1, days));
        AuditAggregate audit = loadAuditAggregate(periodDays);
        List<Map<String, Object>> patrolTrend = patrolHistoryService.countByDay(periodDays);
        List<Map<String, Object>> metricTrend = patrolHistoryService.metricsTrend(periodDays, 500);
        PatrolAggregate patrol = aggregatePatrol(patrolTrend);
        HealingAggregate healing = loadHealingAggregate(periodDays);
        Map<String, Object> resource = loadResourceSnapshot();
        double releasedSpaceGb = estimateReleasedSpaceGb(periodDays);

        Map<String, Object> dimensions = scoreDimensions(audit, patrol, healing, resource, releasedSpaceGb);
        int overall = weightedOverall(dimensions);
        String grade = gradeLabel(overall);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodDays", periodDays);
        body.put("generatedAt", Instant.now().toString());
        body.put("overallScore", overall);
        body.put("overallGrade", grade);
        body.put("summary", buildSummary(overall, grade, audit, patrol, healing, releasedSpaceGb));
        body.put("dimensions", dimensions);
        body.put("kpis", buildKpis(audit, patrol, healing, releasedSpaceGb));
        body.put("valueStatement", buildValueStatement(audit, patrol, releasedSpaceGb, healing));
        body.put("trends", Map.of(
                "auditByDay", audit.byDay,
                "patrolByDay", patrolTrend,
                "metricTrend", metricTrend,
                "dimensionRadar", radarFromDimensions(dimensions)
        ));
        body.put("valueHighlights", buildHighlights(audit, patrol, healing));
        body.put("recentEffectRuns", healing.recentRuns);
        body.put("currentResource", resource);
        return body;
    }

    private Map<String, Object> buildValueStatement(AuditAggregate audit,
                                                    PatrolAggregate patrol,
                                                    double releasedSpaceGb,
                                                    HealingAggregate healing) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("securityBlocked", audit.blocked);
        value.put("executionSuccess", audit.successExec);
        value.put("patrolClosedLoops", patrol.runs);
        value.put("releasedSpaceGb", round2(releasedSpaceGb));
        value.put("avgHealingScore", healing.samples > 0 ? Math.round(healing.avgScore) : null);
        return value;
    }

    private Map<String, Object> buildSummary(int overall,
                                             String grade,
                                             AuditAggregate audit,
                                             PatrolAggregate patrol,
                                             HealingAggregate healing,
                                             double releasedSpaceGb) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("headline", String.format(Locale.ROOT,
                "近 %d 日综合运维效果 %d 分（%s）", audit.periodDays, overall, grade));

        List<String> bullets = new ArrayList<>();
        if (audit.blocked > 0) {
            bullets.add(String.format(Locale.ROOT, "安全护栏累计拦截高危或越权请求 %d 次", audit.blocked));
        }
        if (audit.successExec > 0) {
            bullets.add(String.format(Locale.ROOT, "审计链路成功执行 %d 次，平均耗时 %.1f 秒",
                    audit.successExec, audit.avgDurationMs / 1000.0));
        }
        if (patrol.runs > 0) {
            bullets.add(String.format(Locale.ROOT, "巡检闭环 %d 轮，发现线索 %d 条", patrol.runs, patrol.findings));
        }
        if (releasedSpaceGb > 0.01) {
            bullets.add(String.format(Locale.ROOT, "仅按真实执行回写统计，释放磁盘空间 %.2f GB", releasedSpaceGb));
        }
        if (healing.samples > 0) {
            bullets.add(String.format(Locale.ROOT, "自愈评分样本 %d 条，平均 %.0f/100", healing.samples, healing.avgScore));
        } else {
            bullets.add("暂无真实自愈评分样本，待实际处置后自动回写更新");
        }
        if (bullets.isEmpty()) {
            bullets.add("当前仍缺少真实运维样本，产生审计或巡检记录后此处会自动更新");
        }
        summary.put("bullets", bullets);
        return summary;
    }

    private Map<String, Object> buildKpis(AuditAggregate audit,
                                          PatrolAggregate patrol,
                                          HealingAggregate healing,
                                          double releasedSpaceGb) {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("auditTotal", audit.total);
        kpis.put("auditSuccessExec", audit.successExec);
        kpis.put("auditBlocked", audit.blocked);
        kpis.put("auditNeedConfirm", audit.needConfirm);
        kpis.put("auditPass", audit.pass);
        kpis.put("avgDurationMs", audit.avgDurationMs);
        kpis.put("mcpChannelCount", audit.mcpCount);
        kpis.put("assistantChannelCount", audit.assistantCount);
        kpis.put("patrolRuns", patrol.runs);
        kpis.put("patrolClosedLoops", patrol.runs);
        kpis.put("patrolFindings", patrol.findings);
        kpis.put("releasedSpaceGb", round2(releasedSpaceGb));
        kpis.put("avgHealingScore", healing.samples > 0 ? round2(healing.avgScore) : null);
        kpis.put("healingSamples", healing.samples);
        return kpis;
    }

    private List<Map<String, Object>> buildHighlights(AuditAggregate audit,
                                                      PatrolAggregate patrol,
                                                      HealingAggregate healing) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        if (audit.blocked > 0) {
            highlights.add(highlight("security", "安全护栏生效",
                    String.format(Locale.ROOT, "累计拦截 %d 次高风险或越权动作", audit.blocked)));
        }
        if (audit.needConfirm > 0) {
            highlights.add(highlight("confirm", "人机协同确认",
                    String.format(Locale.ROOT, "%d 次中风险写操作经二次确认后执行", audit.needConfirm)));
        }
        if (audit.successExec > 0) {
            highlights.add(highlight("execution", "处置落地",
                    String.format(Locale.ROOT, "%d 次工具调用成功落地并留痕", audit.successExec)));
        }
        if (patrol.trendImproving) {
            highlights.add(highlight("patrol", "巡检趋势向好", "近期告警线索呈下降趋势"));
        }
        if (healing.bestScore > 0) {
            highlights.add(highlight("healing", "自愈成效",
                    String.format(Locale.ROOT, "最佳自愈评分 %d/100", healing.bestScore)));
        }
        return highlights;
    }

    private Map<String, Object> scoreDimensions(AuditAggregate audit,
                                                PatrolAggregate patrol,
                                                HealingAggregate healing,
                                                Map<String, Object> resource,
                                                double releasedSpaceGb) {
        Map<String, Object> dims = new LinkedHashMap<>();

        int security = 78;
        if (audit.total > 0) {
            if (audit.blocked == 0 && audit.needConfirm == 0) {
                security = (int) Math.min(96, 84 + audit.successRate * 12);
            } else {
                double blockRate = audit.blocked * 1.0 / audit.total;
                security = (int) Math.min(100, 58 + audit.blocked * 2 + audit.needConfirm * 2
                        + blockRate * 25 + audit.successRate * 10);
            }
        }
        dims.put("security", dim("security", "安全拦截", 0.22, security,
                audit.total > 0
                        ? String.format(Locale.ROOT, "拦截 %d 次，需确认 %d 次", audit.blocked, audit.needConfirm)
                        : "暂无审计样本"));

        int execution = audit.total > 0
                ? (int) Math.min(100, 35 + audit.successRate * 65)
                : 70;
        dims.put("execution", dim("execution", "成功执行", 0.22, execution,
                String.format(Locale.ROOT, "成功执行 %d / 审计 %d", audit.successExec, audit.total)));

        int patrolScore = 65;
        if (patrol.runs > 0) {
            patrolScore = (int) Math.min(100, 55 + Math.min(25, patrol.runs * 2)
                    - Math.min(20, patrol.avgFindingsPerRun * 2)
                    + (patrol.trendImproving ? 15 : 0));
        }
        dims.put("patrol", dim("patrol", "巡检闭环", 0.18, patrolScore,
                String.format(Locale.ROOT, "闭环巡检 %d 轮，线索 %d 条", patrol.runs, patrol.findings)));

        int resourceScore = resourceOptimizationScore(resource, releasedSpaceGb, healing);
        String resourceDetail;
        if (releasedSpaceGb > 0.01) {
            resourceDetail = String.format(Locale.ROOT, "真实执行回写释放 %.2f GB，当前磁盘 %.1f%%",
                    releasedSpaceGb, toDouble(resource.get("diskUsagePct")));
        } else if (healing.totalDiskDeltaPct > 0.5) {
            resourceDetail = String.format(Locale.ROOT, "相较处置前磁盘下降 %.1f%%，当前 %.1f%%",
                    healing.totalDiskDeltaPct, toDouble(resource.get("diskUsagePct")));
        } else {
            resourceDetail = String.format(Locale.ROOT, "当前磁盘占用 %.1f%%（无真实释放空间回写）",
                    toDouble(resource.get("diskUsagePct")));
        }
        dims.put("resource", dim("resource", "资源优化", 0.18, resourceScore, resourceDetail));

        Integer healingScore = healing.samples > 0 ? (int) Math.round(healing.avgScore) : null;
        dims.put("healing", dim("healing", "自愈效果", 0.20, healingScore,
                healing.samples > 0
                        ? String.format(Locale.ROOT, "均分 %.0f（%d 条样本）", healing.avgScore, healing.samples)
                        : "暂无真实自愈评分样本，待实际处置后自动回写",
                healing.samples > 0 ? gradeLabel(healingScore) : "暂无样本"));

        return dims;
    }

    private static int resourceOptimizationScore(Map<String, Object> resource,
                                                 double releasedGb,
                                                 HealingAggregate healing) {
        int score = 60;
        if (releasedGb > 0.01) {
            score += (int) Math.min(25, Math.sqrt(releasedGb) * 4);
        }
        if (healing.totalDiskDeltaPct > 0.5) {
            score += (int) Math.min(15, healing.totalDiskDeltaPct * 2);
        }
        double disk = toDouble(resource.get("diskUsagePct"));
        if (disk > 0 && disk < 75) {
            score += 10;
        } else if (disk >= 90) {
            score -= 10;
        }
        return Math.max(40, Math.min(100, score));
    }

    private double estimateReleasedSpaceGb(int days) {
        long releasedBytes = 0L;
        try {
            List<String> stepRows = jdbcTemplate.queryForList(
                    """
                    SELECT steps_json
                    FROM ops_audit_trace
                    WHERE execution_ok = 1
                      AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                      AND (
                           steps_json LIKE '%CleanTempTool%'
                           OR steps_json LIKE '%LogCleanupTool%'
                           OR tool_name IN ('CleanTempTool', 'LogCleanupTool', 'AssistantOrchestrator', 'AutonomousOpsOrchestrator')
                      )
                    ORDER BY id DESC
                    LIMIT 200
                    """,
                    String.class,
                    days);
            for (String stepsJson : stepRows) {
                releasedBytes += extractReleasedBytes(stepsJson);
            }
        } catch (Exception e) {
            log.debug("released space estimation skipped: {}", e.getMessage());
        }
        return round2(releasedBytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Map<String, Object> dim(String id,
                                           String name,
                                           double weight,
                                           Integer score,
                                           String detail) {
        return dim(id, name, weight, score, detail, score != null ? gradeLabel(score) : "N/A");
    }

    private static Map<String, Object> dim(String id,
                                           String name,
                                           double weight,
                                           Integer score,
                                           String detail,
                                           String grade) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        value.put("weight", weight);
        value.put("score", score);
        value.put("detail", detail);
        value.put("grade", grade);
        value.put("measured", score != null);
        return value;
    }

    private static int weightedOverall(Map<String, Object> dimensions) {
        double sum = 0;
        double weightSum = 0;
        for (Object value : dimensions.values()) {
            if (!(value instanceof Map<?, ?> dim)) {
                continue;
            }
            Object score = dim.get("score");
            Object weight = dim.get("weight");
            if (score instanceof Number scoreNumber && weight instanceof Number weightNumber) {
                sum += scoreNumber.doubleValue() * weightNumber.doubleValue();
                weightSum += weightNumber.doubleValue();
            }
        }
        return weightSum > 0 ? (int) Math.round(sum / weightSum) : 0;
    }

    private static List<Map<String, Object>> radarFromDimensions(Map<String, Object> dimensions) {
        List<Map<String, Object>> radar = new ArrayList<>();
        for (Object value : dimensions.values()) {
            if (!(value instanceof Map<?, ?> dim)) {
                continue;
            }
            Object score = dim.get("score");
            if (!(score instanceof Number)) {
                continue;
            }
            radar.add(Map.of(
                    "name", dim.get("name"),
                    "value", score
            ));
        }
        return radar;
    }

    private static String gradeLabel(int score) {
        if (score >= 85) {
            return "优秀";
        }
        if (score >= 70) {
            return "良好";
        }
        if (score >= 55) {
            return "合格";
        }
        return "待提升";
    }

    private Map<String, Object> loadResourceSnapshot() {
        Map<String, Object> snapshot = remediationEffectEvaluator.captureMetrics();
        try {
            Map<String, Object> performance = statisticsService.getSystemPerformance(null);
            if (performance != null) {
                snapshot.put("diskUsagePct", toDouble(performance.get("diskUsage")));
                snapshot.put("cpuUsagePct", toDouble(performance.get("cpuUsage")));
                snapshot.put("memoryUsagePct", toDouble(performance.get("memoryUsage")));
            }
        } catch (Exception ignored) {
            // Keep evaluator snapshot when the performance endpoint is unavailable.
        }
        return snapshot;
    }

    private AuditAggregate loadAuditAggregate(int days) {
        AuditAggregate aggregate = new AuditAggregate();
        aggregate.periodDays = days;
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    """
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN execution_ok = 1 THEN 1 ELSE 0 END) AS success_exec,
                           SUM(CASE WHEN security_outcome LIKE 'REJECT%' OR security_outcome = 'SECURITY_REJECT' THEN 1 ELSE 0 END) AS blocked,
                           SUM(CASE WHEN security_outcome = 'NEED_CONFIRM' THEN 1 ELSE 0 END) AS need_confirm,
                           SUM(CASE WHEN security_outcome = 'PASS' THEN 1 ELSE 0 END) AS pass_cnt,
                           SUM(CASE WHEN channel = 'MCP' THEN 1 ELSE 0 END) AS mcp_cnt,
                           SUM(CASE WHEN channel = 'ASSISTANT' THEN 1 ELSE 0 END) AS assistant_cnt,
                           AVG(duration_ms) AS avg_duration
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    """,
                    days);
            aggregate.total = intVal(row.get("total"));
            aggregate.successExec = intVal(row.get("success_exec"));
            aggregate.blocked = intVal(row.get("blocked"));
            aggregate.needConfirm = intVal(row.get("need_confirm"));
            aggregate.pass = intVal(row.get("pass_cnt"));
            aggregate.mcpCount = intVal(row.get("mcp_cnt"));
            aggregate.assistantCount = intVal(row.get("assistant_cnt"));
            aggregate.avgDurationMs = row.get("avg_duration") instanceof Number number ? number.longValue() : 0L;
            aggregate.successRate = aggregate.total > 0 ? aggregate.successExec * 1.0 / aggregate.total : 0;

            aggregate.byDay = jdbcTemplate.query(
                    """
                    SELECT DATE(created_at) AS day,
                           COUNT(*) AS total,
                           SUM(CASE WHEN execution_ok = 1 THEN 1 ELSE 0 END) AS success_exec,
                           SUM(CASE WHEN security_outcome LIKE 'REJECT%' OR security_outcome = 'SECURITY_REJECT' THEN 1 ELSE 0 END) AS blocked
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    GROUP BY DATE(created_at)
                    ORDER BY day ASC
                    """,
                    (rs, index) -> {
                        Map<String, Object> rowMap = new LinkedHashMap<>();
                        rowMap.put("day", rs.getDate("day").toString());
                        rowMap.put("total", rs.getInt("total"));
                        rowMap.put("successExec", rs.getInt("success_exec"));
                        rowMap.put("blocked", rs.getInt("blocked"));
                        return rowMap;
                    },
                    days);
        } catch (Exception e) {
            log.warn("load audit aggregate failed: {}", e.getMessage());
            aggregate.byDay = List.of();
        }
        return aggregate;
    }

    private HealingAggregate loadHealingAggregate(int days) {
        HealingAggregate aggregate = new HealingAggregate();
        List<Integer> scores = new ArrayList<>();
        List<Map<String, Object>> recentRuns = new ArrayList<>();
        Set<String> recentTraceIds = new HashSet<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                    SELECT steps_json, result_summary, created_at, trace_id
                    FROM ops_audit_trace
                    WHERE created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                      AND (steps_json LIKE '%healingScore%' OR steps_json LIKE '%effect%' OR result_summary LIKE '%自愈评分%')
                    ORDER BY id DESC
                    LIMIT 80
                    """,
                    (rs, index) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("stepsJson", rs.getString("steps_json"));
                        row.put("resultSummary", rs.getString("result_summary"));
                        row.put("createdAt", rs.getTimestamp("created_at") != null
                                ? rs.getTimestamp("created_at").toInstant().toString() : null);
                        row.put("traceId", rs.getString("trace_id"));
                        return row;
                    },
                    days);

            for (Map<String, Object> row : rows) {
                int before = scores.size();
                extractHealingScores(str(row.get("stepsJson")), scores, aggregate);
                if (scores.size() == before) {
                    extractHealingScoreFromText(str(row.get("resultSummary")), scores);
                }
            }

            List<Map<String, Object>> traces = opsAuditTraceService.listRecent(30);
            for (Map<String, Object> trace : traces) {
                if (recentRuns.size() >= 8) {
                    break;
                }
                String traceId = str(trace.get("traceId"));
                if (!recentTraceIds.add(traceId)) {
                    continue;
                }
                String summary = str(trace.getOrDefault("resultSummary", ""));
                Matcher matcher = HEALING_SCORE_IN_TEXT.matcher(summary);
                if (!matcher.find()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("traceId", traceId);
                row.put("healingScore", Integer.parseInt(matcher.group(1)));
                row.put("createdAt", trace.get("createdAt"));
                row.put("channel", trace.get("channel"));
                recentRuns.add(row);
            }
        } catch (Exception e) {
            log.warn("load healing aggregate failed: {}", e.getMessage());
        }
        aggregate.samples = scores.size();
        aggregate.avgScore = scores.isEmpty() ? 0 : scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        aggregate.bestScore = scores.isEmpty() ? 0 : scores.stream().mapToInt(Integer::intValue).max().orElse(0);
        aggregate.recentRuns = recentRuns;
        return aggregate;
    }

    private void extractHealingScores(String stepsJson, List<Integer> scores, HealingAggregate aggregate) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            for (Map<String, Object> step : steps) {
                if (!"effect".equals(step.get("phase"))) {
                    continue;
                }
                Object detail = step.get("detail");
                if (detail instanceof Map<?, ?> effect) {
                    Object score = effect.get("healingScore");
                    if (score instanceof Number number) {
                        scores.add(number.intValue());
                    }
                    Object delta = effect.get("diskUsageDeltaPct");
                    if (delta instanceof Number number && number.doubleValue() > 0) {
                        aggregate.totalDiskDeltaPct += number.doubleValue();
                    }
                } else if (detail != null) {
                    extractHealingScoreFromText(detail.toString(), scores);
                }
            }
        } catch (Exception ignored) {
            extractHealingScoreFromText(stepsJson, scores);
        }
    }

    private void extractHealingScoreFromText(String text, List<Integer> scores) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = HEALING_SCORE_IN_TEXT.matcher(text);
        while (matcher.find()) {
            scores.add(Integer.parseInt(matcher.group(1)));
        }
    }

    private long extractReleasedBytes(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return 0L;
        }
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(stepsJson, new TypeReference<>() {});
            long total = 0L;
            for (Map<String, Object> step : steps) {
                total += extractReleasedBytes(step);
            }
            return total;
        } catch (Exception e) {
            log.debug("parse released bytes skipped: {}", e.getMessage());
            return 0L;
        }
    }

    private long extractReleasedBytes(Map<String, Object> step) {
        if (step == null) {
            return 0L;
        }
        if (!"execute".equalsIgnoreCase(str(step.get("phase")))) {
            return 0L;
        }
        if (!"EXECUTE".equalsIgnoreCase(str(step.get("mode")))) {
            return 0L;
        }
        if (!Boolean.TRUE.equals(step.get("success"))) {
            return 0L;
        }
        String toolName = str(step.get("toolName"));
        if (!"CleanTempTool".equals(toolName) && !"LogCleanupTool".equals(toolName)) {
            return 0L;
        }
        return extractBytesFreed(step.get("detail"));
    }

    private long extractBytesFreed(Object detail) {
        JsonNode payload = toJsonNode(detail);
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return 0L;
        }
        long direct = payload.path("bytesFreed").asLong(0L);
        if (direct > 0L) {
            return direct;
        }
        JsonNode nestedDetail = payload.path("detail");
        if (!nestedDetail.isMissingNode() && !nestedDetail.isNull()) {
            long nested = extractBytesFreed(nestedDetail);
            if (nested > 0L) {
                return nested;
            }
        }
        JsonNode data = payload.path("data");
        if (!data.isMissingNode() && !data.isNull()) {
            long nested = extractBytesFreed(data);
            if (nested > 0L) {
                return nested;
            }
        }
        return 0L;
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof String raw) {
            return McpToolPayloadParser.parsePayload(objectMapper, raw);
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PatrolAggregate aggregatePatrol(List<Map<String, Object>> trend) {
        PatrolAggregate aggregate = new PatrolAggregate();
        if (trend == null || trend.isEmpty()) {
            return aggregate;
        }
        long runs = 0;
        long findings = 0;
        List<Long> dailyAlerts = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            long runCount = longVal(row.get("runCount"));
            long alertCount = longVal(row.get("alertCount"));
            runs += runCount;
            findings += alertCount;
            dailyAlerts.add(alertCount);
        }
        aggregate.runs = runs;
        aggregate.findings = findings;
        aggregate.avgFindingsPerRun = runs > 0 ? findings * 1.0 / runs : 0;
        if (dailyAlerts.size() >= 2) {
            int mid = dailyAlerts.size() / 2;
            double first = dailyAlerts.subList(0, mid).stream().mapToLong(Long::longValue).average().orElse(0);
            double second = dailyAlerts.subList(mid, dailyAlerts.size()).stream().mapToLong(Long::longValue).average().orElse(0);
            aggregate.trendImproving = second < first * 0.85;
        }
        return aggregate;
    }

    private static Map<String, Object> highlight(String type, String title, String detail) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("title", title);
        value.put("detail", detail);
        return value;
    }

    private static int intVal(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long longVal(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static class AuditAggregate {
        int periodDays;
        int total;
        int successExec;
        int blocked;
        int needConfirm;
        int pass;
        int mcpCount;
        int assistantCount;
        long avgDurationMs;
        double successRate;
        List<Map<String, Object>> byDay = List.of();
    }

    private static class PatrolAggregate {
        long runs;
        long findings;
        double avgFindingsPerRun;
        boolean trendImproving;
    }

    private static class HealingAggregate {
        int samples;
        double avgScore;
        int bestScore;
        double totalDiskDeltaPct;
        List<Map<String, Object>> recentRuns = List.of();
    }
}
