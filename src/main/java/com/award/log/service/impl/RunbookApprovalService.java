package com.award.log.service.impl;

import com.award.log.mcp.McpToolCatalog;
import com.award.log.mcp.WriteToolResultSupport;
import com.award.log.mcp.dispatch.McpToolParamReader;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.award.log.trace.TraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RunbookApprovalService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final String STATUS_EXECUTION_FAILED = "EXECUTION_FAILED";
    private static final String RESULT_PENDING = "PENDING_APPROVAL";
    private static final String RESULT_REJECTED = "REJECTED";

    private final AtomicLong seq = new AtomicLong(1L);
    private final Map<Long, Map<String, Object>> cache = new ConcurrentHashMap<>();
    private final McpExecutionService mcpExecutionService;
    private final McpSecurityService mcpSecurityService;
    private final McpToolCatalog mcpToolCatalog;
    private final ObjectMapper objectMapper;
    private final OpsAuditTraceService opsAuditTraceService;
    private final OpsPathPolicy opsPathPolicy;
    private final JdbcTemplate jdbcTemplate;

    public RunbookApprovalService(
            McpExecutionService mcpExecutionService,
            McpSecurityService mcpSecurityService,
            McpToolCatalog mcpToolCatalog,
            ObjectMapper objectMapper,
            OpsAuditTraceService opsAuditTraceService,
            OpsPathPolicy opsPathPolicy,
            JdbcTemplate jdbcTemplate) {
        this.mcpExecutionService = mcpExecutionService;
        this.mcpSecurityService = mcpSecurityService;
        this.mcpToolCatalog = mcpToolCatalog;
        this.objectMapper = objectMapper;
        this.opsAuditTraceService = opsAuditTraceService;
        this.opsPathPolicy = opsPathPolicy;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void init() {
        ensureTable();
        initializeSequence();
        warmCache();
    }

    public Map<String, Object> submit(
            String title,
            String action,
            String command,
            String toolName,
            Map<String, Object> parameters,
            Object requester) {
        String safeTitle = blankToDefault(title, "Runbook");
        String safeAction = blankToNull(action);
        String safeCommand = blankToNull(command);
        String resolvedTool = blankToNull(toolName);
        if (resolvedTool == null && safeAction != null && safeAction.endsWith("Tool")) {
            resolvedTool = safeAction;
        }
        Map<String, Object> resolvedParams = parameters != null
                ? new LinkedHashMap<>(parameters)
                : parseCommandParameters(safeCommand);

        if (resolvedTool == null && safeAction == null && safeCommand == null) {
            throw new IllegalArgumentException("Runbook requires toolName+parameters or action/command");
        }
        if (resolvedTool != null && !mcpToolCatalog.isRegistered(resolvedTool)) {
            throw new IllegalArgumentException("Unknown MCP tool: " + resolvedTool);
        }

        long id = seq.getAndIncrement();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("title", safeTitle);
        item.put("action", safeAction == null ? "" : safeAction);
        item.put("command", safeCommand == null ? "" : safeCommand);
        item.put("toolName", resolvedTool == null ? "" : resolvedTool);
        item.put("parameters", resolvedParams);
        item.put("requester", asActor(requester));
        item.put("status", STATUS_PENDING);
        item.put("result", RESULT_PENDING);
        item.put("executionImplemented", false);
        item.put("createdAt", System.currentTimeMillis());

        persistNew(item);
        cache.put(id, mutableCopy(item));
        return snapshot(item);
    }

    public Map<String, Object> approve(long id, Object approver) {
        Map<String, Object> item = mutableCopy(getById(id));
        ensureStatus(item, STATUS_PENDING, "Only pending runbooks can be approved");
        item.put("status", STATUS_APPROVED);
        item.put("approver", asActor(approver));
        item.put("approvedAt", System.currentTimeMillis());
        persistExisting(item);
        cache.put(id, mutableCopy(item));
        return snapshot(item);
    }

    public Map<String, Object> reject(long id, Object approver, String reason) {
        Map<String, Object> item = mutableCopy(getById(id));
        String status = String.valueOf(item.get("status"));
        if (!STATUS_PENDING.equals(status) && !STATUS_APPROVED.equals(status)) {
            throw new IllegalStateException("Only pending or approved runbooks can be rejected");
        }
        item.put("status", STATUS_REJECTED);
        item.put("approver", asActor(approver));
        item.put("reason", reason == null ? "" : reason.trim());
        item.put("result", RESULT_REJECTED);
        item.put("approvedAt", System.currentTimeMillis());
        persistExisting(item);
        cache.put(id, mutableCopy(item));
        return snapshot(item);
    }

    public Map<String, Object> execute(long id, Object operator) {
        Map<String, Object> item = mutableCopy(getById(id));
        ensureStatus(item, STATUS_APPROVED, "Only approved runbooks can be executed");

        String toolName = String.valueOf(item.getOrDefault("toolName", "")).trim();
        if (toolName.isEmpty()) {
            throw new IllegalStateException("Runbook is missing toolName");
        }
        if (!mcpToolCatalog.isHttpAllowed(toolName)) {
            throw new IllegalStateException("Tool is not allowed for HTTP execution: " + toolName);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = item.get("parameters") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();

        String userMessage = "[RUNBOOK] " + item.get("title");
        McpToolParamReader.forceConfirmedWriteToolParams(toolName, parameters);
        GateDecision decision = mcpSecurityService.evaluatePostConfirm(toolName, parameters, userMessage);

        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        item.put("operator", asActor(operator));
        item.put("executedAt", startTime);
        item.put("traceId", traceId);

        if (decision.getType() == GateDecision.Type.BLOCK) {
            item.put("status", STATUS_EXECUTION_FAILED);
            item.put("result", decision.getCode());
            item.put("executionImplemented", true);
            item.put("executionMessage", decision.getMessage());
            item.put("mcpSuccess", false);
            persistAudit(id, traceId, userMessage, toolName, parameters, decision, null, false, startTime, asActor(operator));
            persistExisting(item);
            cache.put(id, mutableCopy(item));
            return snapshot(item);
        }

        String instruction = userMessage + " | " + mcpSecurityService.buildInstruction(toolName, parameters);
        Map<String, Object> execResponse = mcpExecutionService.execute(
                toolName, parameters, startTime, traceId, instruction, true);

        boolean success = Boolean.TRUE.equals(execResponse.get("success"));
        item.put("status", success ? STATUS_EXECUTED : STATUS_EXECUTION_FAILED);
        item.put("mcpResult", execResponse);
        item.put("mcpSuccess", success);
        item.put("executionImplemented", true);
        if (execResponse.get("writeMismatch") != null) {
            item.put("writeMismatch", execResponse.get("writeMismatch"));
        }
        if (WriteToolResultSupport.requestedRealWrite(parameters)) {
            String mode = WriteToolResultSupport.extractMode(
                    execResponse.get("data") != null ? String.valueOf(execResponse.get("data")) : "");
            if (!mode.isBlank()) {
                item.put("writeMode", mode);
            }
        }
        item.put("result", success
                ? "EXECUTED"
                : String.valueOf(execResponse.getOrDefault("error", "EXECUTION_FAILED")));
        item.put("executionMessage", success
                ? "Executed through MCP security gate: " + toolName
                : String.valueOf(execResponse.getOrDefault("error", "MCP execution failed")));

        persistAudit(id, traceId, userMessage, toolName, parameters, decision, execResponse, success, startTime, asActor(operator));
        persistExisting(item);
        cache.put(id, mutableCopy(item));
        return snapshot(item);
    }

    public List<Map<String, Object>> list(String requester, boolean admin) {
        List<Map<String, Object>> rows = queryList(requester, admin);
        if (rows == null) {
            return cache.values().stream()
                    .filter(item -> admin || String.valueOf(item.get("requester")).equals(requester))
                    .sorted(Comparator.comparingLong(this::createdAt).reversed())
                    .map(this::snapshot)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        List<Map<String, Object>> items = rows.stream()
                .map(this::fromDbRow)
                .sorted(Comparator.comparingLong(this::createdAt).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        for (Map<String, Object> item : items) {
            cache.put(toLong(item.get("id")), mutableCopy(item));
        }
        return items.stream().map(this::snapshot).collect(Collectors.toCollection(ArrayList::new));
    }

    private void ensureTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS ops_runbook_approval (
                  id BIGINT NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  action_name VARCHAR(255) DEFAULT NULL,
                  command_text TEXT,
                  tool_name VARCHAR(128) DEFAULT NULL,
                  parameters_json LONGTEXT,
                  requester VARCHAR(64) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  result_code VARCHAR(128) DEFAULT NULL,
                  execution_implemented TINYINT(1) NOT NULL DEFAULT 0,
                  approver VARCHAR(64) DEFAULT NULL,
                  reason TEXT,
                  operator VARCHAR(64) DEFAULT NULL,
                  trace_id VARCHAR(64) DEFAULT NULL,
                  mcp_result_json LONGTEXT,
                  mcp_success TINYINT(1) DEFAULT NULL,
                  execution_message TEXT,
                  write_mismatch TINYINT(1) DEFAULT NULL,
                  write_mode VARCHAR(64) DEFAULT NULL,
                  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                  approved_at DATETIME DEFAULT NULL,
                  executed_at DATETIME DEFAULT NULL,
                  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (id),
                  KEY idx_runbook_requester_created (requester, created_at),
                  KEY idx_runbook_status_created (status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runbook approval and execution records'
                """);
    }

    private void initializeSequence() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM ops_runbook_approval",
                Long.class);
        seq.set((maxId == null ? 0L : maxId) + 1L);
    }

    private void warmCache() {
        List<Map<String, Object>> rows = queryAllRows();
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = fromDbRow(row);
            cache.put(toLong(item.get("id")), mutableCopy(item));
        }
    }

    private void persistNew(Map<String, Object> item) {
        int updated = jdbcTemplate.update(
                """
                INSERT INTO ops_runbook_approval (
                  id, title, action_name, command_text, tool_name, parameters_json,
                  requester, status, result_code, execution_implemented,
                  approver, reason, operator, trace_id, mcp_result_json, mcp_success,
                  execution_message, write_mismatch, write_mode,
                  created_at, approved_at, executed_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                item.get("id"),
                item.get("title"),
                emptyToNull(String.valueOf(item.getOrDefault("action", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("command", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("toolName", ""))),
                toJson(item.get("parameters")),
                String.valueOf(item.get("requester")),
                item.get("status"),
                emptyToNull(String.valueOf(item.getOrDefault("result", ""))),
                boolToDb(item.get("executionImplemented")),
                emptyToNull(String.valueOf(item.getOrDefault("approver", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("reason", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("operator", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("traceId", ""))),
                toJson(item.get("mcpResult")),
                nullableBoolToDb(item.get("mcpSuccess")),
                emptyToNull(String.valueOf(item.getOrDefault("executionMessage", ""))),
                nullableBoolToDb(item.get("writeMismatch")),
                emptyToNull(String.valueOf(item.getOrDefault("writeMode", ""))),
                toTimestamp(item.get("createdAt")),
                toTimestamp(item.get("approvedAt")),
                toTimestamp(item.get("executedAt")));
        if (updated <= 0) {
            throw new IllegalStateException("Failed to persist runbook approval");
        }
    }

    private void persistExisting(Map<String, Object> item) {
        int updated = jdbcTemplate.update(
                """
                UPDATE ops_runbook_approval
                SET title = ?,
                    action_name = ?,
                    command_text = ?,
                    tool_name = ?,
                    parameters_json = ?,
                    requester = ?,
                    status = ?,
                    result_code = ?,
                    execution_implemented = ?,
                    approver = ?,
                    reason = ?,
                    operator = ?,
                    trace_id = ?,
                    mcp_result_json = ?,
                    mcp_success = ?,
                    execution_message = ?,
                    write_mismatch = ?,
                    write_mode = ?,
                    approved_at = ?,
                    executed_at = ?
                WHERE id = ?
                """,
                item.get("title"),
                emptyToNull(String.valueOf(item.getOrDefault("action", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("command", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("toolName", ""))),
                toJson(item.get("parameters")),
                String.valueOf(item.get("requester")),
                item.get("status"),
                emptyToNull(String.valueOf(item.getOrDefault("result", ""))),
                boolToDb(item.get("executionImplemented")),
                emptyToNull(String.valueOf(item.getOrDefault("approver", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("reason", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("operator", ""))),
                emptyToNull(String.valueOf(item.getOrDefault("traceId", ""))),
                toJson(item.get("mcpResult")),
                nullableBoolToDb(item.get("mcpSuccess")),
                emptyToNull(String.valueOf(item.getOrDefault("executionMessage", ""))),
                nullableBoolToDb(item.get("writeMismatch")),
                emptyToNull(String.valueOf(item.getOrDefault("writeMode", ""))),
                toTimestamp(item.get("approvedAt")),
                toTimestamp(item.get("executedAt")),
                item.get("id"));
        if (updated <= 0) {
            throw new NoSuchElementException("Runbook request not found");
        }
    }

    private void persistAudit(
            long runbookId,
            String traceId,
            String userMessage,
            String toolName,
            Map<String, Object> parameters,
            GateDecision decision,
            Map<String, Object> execResponse,
            boolean success,
            long startTime,
            String operator) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(TraceService.cotStep(1, "receive", "Receive runbook execution request, runbookId=" + runbookId));
        steps.add(TraceService.cotStep(2, "confirm", "Approval passed, operator=" + operator));
        steps.add(TraceService.cotStep(3, "reason", "Invoke tool " + toolName + " with params: " + truncate(String.valueOf(parameters), 1200)));
        String executionDetail = decision.getType() == GateDecision.Type.BLOCK
                ? "Blocked by security gate before execution: " + decision.getMessage()
                : "Execution result: " + truncate(String.valueOf(execResponse), 1200);
        steps.add(TraceService.cotStep(4, "execute", executionDetail));
        steps.add(TraceService.step("runbook", "title=" + userMessage + ", tool=" + toolName));

        String securityOutcome;
        String summary;
        boolean executionOk;
        if (decision.getType() == GateDecision.Type.BLOCK) {
            securityOutcome = McpSecurityService.mapGateCodeToOutcome(decision.getCode());
            summary = decision.getMessage();
            executionOk = false;
        } else {
            securityOutcome = success ? "PASS" : "EXECUTION_FAILED";
            summary = success
                    ? String.valueOf(execResponse.get("data"))
                    : String.valueOf(execResponse.getOrDefault("error", "EXECUTION_FAILED"));
            executionOk = success;
        }

        opsAuditTraceService.save(
                traceId,
                "RUNBOOK",
                userMessage,
                decision.getRiskLevel().name(),
                securityOutcome,
                toolName,
                executionOk,
                summary,
                steps,
                System.currentTimeMillis() - startTime,
                operator,
                opsPathPolicy.getPolicyVersion());
    }

    private Map<String, Object> parseCommandParameters(String command) {
        if (command == null || command.isBlank()) {
            return new LinkedHashMap<>();
        }
        String trimmed = command.trim();
        if (!trimmed.startsWith("{")) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("command must be a valid JSON object: " + e.getMessage());
        }
    }

    private void ensureStatus(Map<String, Object> item, String expected, String message) {
        String actual = String.valueOf(item.get("status"));
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message);
        }
    }

    private Map<String, Object> getById(long id) {
        Map<String, Object> cached = cache.get(id);
        if (cached != null) {
            return snapshot(cached);
        }
        Map<String, Object> loaded = loadOneFromDb(id);
        if (loaded != null) {
            cache.put(id, mutableCopy(loaded));
            return loaded;
        }
        throw new NoSuchElementException("Runbook request not found");
    }

    private List<Map<String, Object>> queryList(String requester, boolean admin) {
        try {
            if (admin) {
                return jdbcTemplate.queryForList(
                        "SELECT * FROM ops_runbook_approval ORDER BY created_at DESC, id DESC");
            }
            return jdbcTemplate.queryForList(
                    "SELECT * FROM ops_runbook_approval WHERE requester = ? ORDER BY created_at DESC, id DESC",
                    requester);
        } catch (Exception e) {
            log.warn("failed to list runbook approvals: {}", e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> queryAllRows() {
        try {
            return jdbcTemplate.queryForList("SELECT * FROM ops_runbook_approval ORDER BY id ASC");
        } catch (Exception e) {
            log.warn("failed to warm runbook cache: {}", e.getMessage());
            return null;
        }
    }

    private long createdAt(Map<String, Object> item) {
        Object value = item.get("createdAt");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Object> snapshot(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> mutableCopy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> loadOneFromDb(long id) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM ops_runbook_approval WHERE id = ?",
                    id);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            return fromDbRow(rows.get(0));
        } catch (Exception e) {
            log.warn("failed to query runbook approval {}: {}", id, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> fromDbRow(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", toLong(row.get("id")));
        item.put("title", String.valueOf(row.getOrDefault("title", "Runbook")));
        item.put("action", safeDbString(row.get("action_name")));
        item.put("command", safeDbString(row.get("command_text")));
        item.put("toolName", safeDbString(row.get("tool_name")));
        item.put("parameters", parseJsonMap(row.get("parameters_json")));
        item.put("requester", String.valueOf(row.getOrDefault("requester", "unknown")));
        item.put("status", String.valueOf(row.getOrDefault("status", STATUS_PENDING)));
        item.put("result", String.valueOf(row.getOrDefault("result_code", RESULT_PENDING)));
        item.put("executionImplemented", toBoolean(row.get("execution_implemented")));
        putIfNotBlank(item, "approver", row.get("approver"));
        putIfNotBlank(item, "reason", row.get("reason"));
        putIfNotBlank(item, "operator", row.get("operator"));
        putIfNotBlank(item, "traceId", row.get("trace_id"));
        Map<String, Object> mcpResult = parseJsonMap(row.get("mcp_result_json"));
        if (!mcpResult.isEmpty()) {
            item.put("mcpResult", mcpResult);
        }
        if (row.get("mcp_success") != null) {
            item.put("mcpSuccess", toBoolean(row.get("mcp_success")));
        }
        putIfNotBlank(item, "executionMessage", row.get("execution_message"));
        if (row.get("write_mismatch") != null) {
            item.put("writeMismatch", toBoolean(row.get("write_mismatch")));
        }
        putIfNotBlank(item, "writeMode", row.get("write_mode"));
        putIfTimestamp(item, "createdAt", row.get("created_at"));
        putIfTimestamp(item, "approvedAt", row.get("approved_at"));
        putIfTimestamp(item, "executedAt", row.get("executed_at"));
        return item;
    }

    private static String asActor(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToDefault(String value, String defaultValue) {
        String trimmed = blankToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize runbook payload", e);
        }
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        String json = String.valueOf(raw).trim();
        if (json.isEmpty() || "null".equalsIgnoreCase(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("failed to parse runbook json payload: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Timestamp toTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof Number number) {
            return new Timestamp(number.longValue());
        }
        return null;
    }

    private static Long nullableBoolToDb(Object value) {
        if (value == null) {
            return null;
        }
        return toBoolean(value) ? 1L : 0L;
    }

    private static int boolToDb(Object value) {
        return toBoolean(value) ? 1 : 0;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }

    private static String safeDbString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void putIfNotBlank(Map<String, Object> item, String key, Object raw) {
        if (raw == null) {
            return;
        }
        String value = String.valueOf(raw);
        if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
            item.put(key, value);
        }
    }

    private static void putIfTimestamp(Map<String, Object> item, String key, Object raw) {
        if (raw instanceof Timestamp timestamp) {
            item.put(key, timestamp.getTime());
        }
    }
}
