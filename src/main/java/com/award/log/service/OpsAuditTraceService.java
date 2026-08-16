package com.award.log.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维 Agent 全链路审计落库（与 TraceService 配合，面向赛题「可追溯」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAuditTraceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS ops_audit_trace (" +
                            "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                            "trace_id VARCHAR(64) NOT NULL," +
                            "channel VARCHAR(32) NOT NULL," +
                            "audit_kind VARCHAR(32)," +
                            "request_channel VARCHAR(32)," +
                            "stage VARCHAR(64)," +
                            "decision VARCHAR(64)," +
                            "user_input TEXT," +
                            "risk_level VARCHAR(32)," +
                            "security_outcome VARCHAR(64)," +
                            "tool_name VARCHAR(128)," +
                            "target_type VARCHAR(64)," +
                            "target_name VARCHAR(255)," +
                            "parent_trace_id VARCHAR(64)," +
                            "confirmation_id VARCHAR(64)," +
                            "effect_summary TEXT," +
                            "execution_ok TINYINT(1) DEFAULT 0," +
                            "result_summary TEXT," +
                            "steps_json LONGTEXT," +
                            "duration_ms BIGINT," +
                            "operator_user_id VARCHAR(64)," +
                            "policy_version VARCHAR(64)," +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                            "KEY idx_trace_id (trace_id)," +
                            "KEY idx_created_at (created_at)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
            migrateLegacyTable();
        } catch (Exception e) {
            log.warn("ops_audit_trace 表初始化失败（请确认数据库已启动且已执行 schema.sql）: {}", e.getMessage());
        }
    }

    /** 已有库升级列（忽略重复列错误）。 */
    private void migrateLegacyTable() {
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN operator_user_id VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN policy_version VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN target_host_id VARCHAR(32) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN target_host_label VARCHAR(256) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN audit_kind VARCHAR(32) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN request_channel VARCHAR(32) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN stage VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN decision VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN target_type VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN target_name VARCHAR(255) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN parent_trace_id VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN confirmation_id VARCHAR(64) NULL");
        tryAlter("ALTER TABLE ops_audit_trace ADD COLUMN effect_summary TEXT NULL");
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("migrate skip: {}", e.getMessage());
        }
    }

    public void save(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion
    ) {
        save(traceId, channel, userInput, riskLevel, securityOutcome, toolName, executionOk,
                resultSummary, steps, durationMs, operatorUserId, policyVersion, Map.of(), Map.of());
    }

    public void save(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion,
            Map<String, String> targetHostMeta
    ) {
        save(traceId, channel, userInput, riskLevel, securityOutcome, toolName, executionOk,
                resultSummary, steps, durationMs, operatorUserId, policyVersion, targetHostMeta, Map.of());
    }

    public void save(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion,
            Map<String, String> targetHostMeta,
            Map<String, Object> auditMeta
    ) {
        try {
            String stepsJson = steps == null || steps.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(steps);
            String summary = resultSummary == null ? "" : resultSummary;
            if (summary.length() > 4000) {
                summary = summary.substring(0, 4000) + "...";
            }
            String targetHostId = targetHostMeta != null ? targetHostMeta.get("targetHostId") : null;
            String targetHostLabel = targetHostMeta != null ? targetHostMeta.get("targetHostLabel") : null;
            String auditKind = metaString(auditMeta, "auditKind");
            String requestChannel = metaString(auditMeta, "requestChannel");
            String stage = metaString(auditMeta, "stage");
            String decision = metaString(auditMeta, "decision");
            String targetType = metaString(auditMeta, "targetType");
            String targetName = metaString(auditMeta, "targetName");
            String parentTraceId = metaString(auditMeta, "parentTraceId");
            String confirmationId = metaString(auditMeta, "confirmationId");
            String effectSummary = truncate(metaString(auditMeta, "effectSummary"), 2000);
            jdbcTemplate.update(
                    "INSERT INTO ops_audit_trace(trace_id,channel,audit_kind,request_channel,stage,decision,user_input," +
                            "risk_level,security_outcome,tool_name,target_type,target_name,parent_trace_id,confirmation_id," +
                            "effect_summary,execution_ok,result_summary,steps_json,duration_ms,operator_user_id,policy_version," +
                            "target_host_id,target_host_label,created_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    traceId,
                    channel,
                    auditKind,
                    requestChannel,
                    stage,
                    decision,
                    userInput,
                    riskLevel,
                    securityOutcome,
                    toolName,
                    targetType,
                    targetName,
                    parentTraceId,
                    confirmationId,
                    effectSummary,
                    executionOk ? 1 : 0,
                    summary,
                    stepsJson,
                    durationMs,
                    operatorUserId,
                    policyVersion,
                    targetHostId,
                    targetHostLabel,
                    new Timestamp(System.currentTimeMillis())
            );
        } catch (JsonProcessingException e) {
            log.warn("审计序列化失败: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("审计落库失败（不阻断业务）: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        try {
            return jdbcTemplate.query(
                    "SELECT id, trace_id, channel, user_input, risk_level, security_outcome, tool_name, " +
                            "audit_kind, request_channel, stage, decision, target_type, target_name, parent_trace_id, confirmation_id, effect_summary, " +
                            "execution_ok, LEFT(result_summary, 512) AS result_summary, duration_ms, " +
                            "operator_user_id, policy_version, target_host_id, target_host_label, created_at " +
                            "FROM ops_audit_trace ORDER BY id DESC LIMIT ?",
                    (rs, rowNum) -> mapTraceRow(rs, false),
                    cap
            );
        } catch (Exception e) {
            log.warn("查询审计列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> listRecentWithSteps(int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        try {
            return jdbcTemplate.query(
                    "SELECT id, trace_id, channel, user_input, risk_level, security_outcome, tool_name, " +
                            "audit_kind, request_channel, stage, decision, target_type, target_name, parent_trace_id, confirmation_id, effect_summary, " +
                            "execution_ok, LEFT(result_summary, 512) AS result_summary, duration_ms, " +
                            "operator_user_id, policy_version, target_host_id, target_host_label, created_at, steps_json " +
                            "FROM ops_audit_trace ORDER BY id DESC LIMIT ?",
                    (rs, rowNum) -> mapTraceRow(rs, true),
                    cap
            );
        } catch (Exception e) {
            log.warn("查询含步骤的审计列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 单条追溯：含完整摘要与 steps_json（思维链审计）。
     */
    public Map<String, Object> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    "SELECT id, trace_id, channel, user_input, risk_level, security_outcome, tool_name, " +
                            "audit_kind, request_channel, stage, decision, target_type, target_name, parent_trace_id, confirmation_id, effect_summary, " +
                            "execution_ok, result_summary, duration_ms, operator_user_id, policy_version, " +
                            "created_at, steps_json, target_host_id, target_host_label FROM ops_audit_trace WHERE trace_id = ? ORDER BY id DESC LIMIT 1",
                    (rs, rowNum) -> mapTraceRow(rs, true),
                    traceId.trim()
            );
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            log.warn("查询审计详情失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> mapTraceRow(ResultSet rs, boolean includeStepsJson) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("traceId", rs.getString("trace_id"));
        m.put("channel", rs.getString("channel"));
        putIfAvailable(m, "auditKind", rs, "audit_kind");
        putIfAvailable(m, "requestChannel", rs, "request_channel");
        putIfAvailable(m, "stage", rs, "stage");
        putIfAvailable(m, "decision", rs, "decision");
        m.put("userInput", rs.getString("user_input"));
        m.put("riskLevel", rs.getString("risk_level"));
        m.put("securityOutcome", rs.getString("security_outcome"));
        m.put("toolName", rs.getString("tool_name"));
        putIfAvailable(m, "targetType", rs, "target_type");
        putIfAvailable(m, "targetName", rs, "target_name");
        putIfAvailable(m, "parentTraceId", rs, "parent_trace_id");
        putIfAvailable(m, "confirmationId", rs, "confirmation_id");
        putIfAvailable(m, "effectSummary", rs, "effect_summary");
        m.put("executionOk", rs.getInt("execution_ok") == 1);
        m.put("durationMs", rs.getLong("duration_ms"));
        m.put("operatorUserId", rs.getString("operator_user_id"));
        m.put("policyVersion", rs.getString("policy_version"));
        try {
            m.put("targetHostId", rs.getString("target_host_id"));
            m.put("targetHostLabel", rs.getString("target_host_label"));
        } catch (SQLException ignored) {
            // legacy schema without remote columns
        }
        m.put("createdAt", rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant().toString()
                : null);
        if (includeStepsJson) {
            m.put("resultSummary", rs.getString("result_summary"));
            String stepsRaw = rs.getString("steps_json");
            m.put("steps", parseStepsJson(stepsRaw));
            m.put("stepsJsonRaw", stepsRaw);
        } else {
            m.put("resultSummary", rs.getString("result_summary"));
        }
        return m;
    }

    public int deleteOlderThanDays(int days) {
        int d = Math.min(365, Math.max(7, days));
        try {
            return jdbcTemplate.update(
                    "DELETE FROM ops_audit_trace WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)", d);
        } catch (Exception e) {
            log.warn("审计清理失败: {}", e.getMessage());
            return 0;
        }
    }

    public void saveWithEffect(
            String traceId,
            String channel,
            String userInput,
            String riskLevel,
            String securityOutcome,
            String toolName,
            boolean executionOk,
            String resultSummary,
            List<Map<String, Object>> steps,
            long durationMs,
            String operatorUserId,
            String policyVersion,
            Map<String, Object> remediationEffect
    ) {
        if (remediationEffect != null && !remediationEffect.isEmpty() && steps != null) {
            steps = new ArrayList<>(steps);
            steps.add(Map.of(
                    "phase", "effect",
                    "detail", remediationEffect));
        }
        save(traceId, channel, userInput, riskLevel, securityOutcome, toolName, executionOk,
                resultSummary, steps, durationMs, operatorUserId, policyVersion, Map.of(), Map.of(
                        "auditKind", "remediation",
                        "requestChannel", channel,
                        "stage", "effect",
                        "decision", securityOutcome,
                        "effectSummary", remediationEffect == null ? "" : truncate(String.valueOf(remediationEffect), 2000)));
    }

    private static String metaString(Map<String, Object> auditMeta, String key) {
        if (auditMeta == null || key == null) {
            return null;
        }
        Object value = auditMeta.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static void putIfAvailable(Map<String, Object> row, String key, ResultSet rs, String column) {
        try {
            row.put(key, rs.getString(column));
        } catch (SQLException ignored) {
            // legacy schema
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseStepsJson(String stepsRaw) {
        if (stepsRaw == null || stepsRaw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stepsRaw, List.class);
        } catch (Exception e) {
            log.debug("steps_json 解析降级为字符串: {}", e.getMessage());
            return List.of(Map.of("phase", "raw", "detail", stepsRaw));
        }
    }
}
