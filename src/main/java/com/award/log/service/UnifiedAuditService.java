package com.award.log.service;

import com.award.log.service.impl.AiAuditLogService;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified read model for access audit and execution audit.
 */
@Service
public class UnifiedAuditService {

    private final AiAuditLogService aiAuditLogService;
    private final OpsAuditTraceService opsAuditTraceService;

    public UnifiedAuditService(AiAuditLogService aiAuditLogService,
                               OpsAuditTraceService opsAuditTraceService) {
        this.aiAuditLogService = aiAuditLogService;
        this.opsAuditTraceService = opsAuditTraceService;
    }

    public List<Map<String, Object>> feed(int limit, String kind) {
        int capped = Math.max(1, Math.min(limit, 300));
        String normalizedKind = normalizeKind(kind);
        List<Map<String, Object>> merged = new ArrayList<>();

        if (normalizedKind == null || "access".equals(normalizedKind)) {
            for (Map<String, Object> row : aiAuditLogService.listRecent(capped)) {
                Map<String, Object> event = fromAccessRow(row);
                if (matchesKind(event, normalizedKind)) {
                    merged.add(event);
                }
            }
        }

        if (normalizedKind == null || !"access".equals(normalizedKind)) {
            for (Map<String, Object> row : opsAuditTraceService.listRecentWithSteps(capped)) {
                Map<String, Object> event = fromTraceRow(row);
                if (matchesKind(event, normalizedKind)) {
                    merged.add(event);
                }
            }
        }

        merged.sort(Comparator.comparing(this::createdAtInstant).reversed());
        if (merged.size() > capped) {
            return List.copyOf(merged.subList(0, capped));
        }
        return List.copyOf(merged);
    }

    public Map<String, Object> detail(String entryId, String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            return detailTrace(traceId.trim());
        }
        if (entryId == null || entryId.isBlank()) {
            return Map.of();
        }
        if (entryId.startsWith("trace:")) {
            return detailTrace(entryId.substring("trace:".length()));
        }
        if (entryId.startsWith("api:")) {
            try {
                return detailAccess(Long.parseLong(entryId.substring("api:".length())));
            } catch (NumberFormatException ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private Map<String, Object> detailTrace(String traceId) {
        Map<String, Object> row = opsAuditTraceService.findByTraceId(traceId);
        if (row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> detail = new LinkedHashMap<>(fromTraceRow(row));
        detail.put("raw", row);
        detail.put("steps", row.getOrDefault("steps", List.of()));
        detail.put("resultSummary", row.get("resultSummary"));
        detail.put("effectSummary", row.get("effectSummary"));
        return detail;
    }

    private Map<String, Object> detailAccess(long id) {
        Map<String, Object> row = aiAuditLogService.findById(id);
        if (row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> detail = new LinkedHashMap<>(fromAccessRow(row));
        detail.put("raw", row);
        detail.put("steps", List.of(
                Map.of("phase", "request", "detail", String.valueOf(row.getOrDefault("method", "")) + " " + row.getOrDefault("path", "")),
                Map.of("phase", "identity", "detail", "userId=" + row.getOrDefault("user_id", "") + ", role=" + row.getOrDefault("user_role", "")),
                Map.of("phase", "result", "detail", "status=" + row.getOrDefault("status", "") + ", durationMs=" + row.getOrDefault("duration_ms", ""))
        ));
        return detail;
    }

    private Map<String, Object> fromAccessRow(Map<String, Object> row) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        long id = number(row.get("id")).longValue();
        String method = String.valueOf(row.getOrDefault("method", ""));
        String path = String.valueOf(row.getOrDefault("path", ""));
        int status = number(row.get("status")).intValue();
        event.put("entryId", "api:" + id);
        event.put("traceId", null);
        event.put("auditKind", "access");
        event.put("auditKindLabel", "访问");
        event.put("requestChannel", "HTTP");
        event.put("channel", "HTTP");
        event.put("stage", "request");
        event.put("decision", status >= 400 ? "ERROR" : "PASS");
        event.put("securityOutcome", status >= 400 ? "HTTP_ERROR" : "HTTP_PASS");
        event.put("targetType", "endpoint");
        event.put("targetName", method + " " + path);
        event.put("toolName", "");
        event.put("summary", path);
        event.put("resultSummary", "status=" + status + ", durationMs=" + row.getOrDefault("duration_ms", 0));
        event.put("createdAt", toIso(row.get("created_at")));
        event.put("executionOk", status < 400);
        event.put("durationMs", number(row.get("duration_ms")).longValue());
        event.put("userInput", method + " " + path);
        event.put("operatorUserId", stringValue(row.get("user_id")));
        event.put("remoteIp", stringValue(row.get("remote_ip")));
        event.put("httpStatus", status);
        event.put("userRole", stringValue(row.get("user_role")));
        event.put("sourceTable", "ai_audit_log");
        return event;
    }

    private Map<String, Object> fromTraceRow(Map<String, Object> row) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        String traceId = stringValue(row.get("traceId"));
        String derivedKind = deriveTraceKind(row);
        String channel = stringValue(row.get("channel"));
        String decision = firstNonBlank(
                stringValue(row.get("decision")),
                stringValue(row.get("securityOutcome")));
        String targetName = firstNonBlank(
                stringValue(row.get("targetName")),
                stringValue(row.get("toolName")),
                stringValue(row.get("targetHostLabel")));
        event.put("entryId", "trace:" + traceId);
        event.put("traceId", traceId);
        event.put("auditKind", derivedKind);
        event.put("auditKindLabel", kindLabel(derivedKind));
        event.put("requestChannel", firstNonBlank(stringValue(row.get("requestChannel")), channel));
        event.put("channel", channel);
        event.put("stage", firstNonBlank(stringValue(row.get("stage")), "complete"));
        event.put("decision", decision);
        event.put("securityOutcome", stringValue(row.get("securityOutcome")));
        event.put("targetType", firstNonBlank(stringValue(row.get("targetType")), "tool"));
        event.put("targetName", targetName);
        event.put("toolName", stringValue(row.get("toolName")));
        event.put("summary", firstNonBlank(stringValue(row.get("resultSummary")), targetName));
        event.put("resultSummary", row.get("resultSummary"));
        event.put("effectSummary", row.get("effectSummary"));
        event.put("createdAt", toIso(row.get("createdAt")));
        event.put("executionOk", Boolean.TRUE.equals(row.get("executionOk")));
        event.put("durationMs", number(row.get("durationMs")).longValue());
        event.put("userInput", stringValue(row.get("userInput")));
        event.put("operatorUserId", stringValue(row.get("operatorUserId")));
        event.put("sourceTable", "ops_audit_trace");
        event.put("confirmationId", stringValue(row.get("confirmationId")));
        return event;
    }

    private String deriveTraceKind(Map<String, Object> row) {
        String explicit = stringValue(row.get("auditKind"));
        if (!explicit.isBlank()) {
            return explicit;
        }
        String channel = stringValue(row.get("channel")).toUpperCase(Locale.ROOT);
        String outcome = stringValue(row.get("securityOutcome")).toUpperCase(Locale.ROOT);
        if (outcome.contains("REJECT")) {
            return "block";
        }
        if ("NEED_CONFIRM".equals(outcome) || row.get("confirmationId") != null) {
            return "confirm";
        }
        if ("PATROL".equals(channel) || "RUNBOOK".equals(channel)) {
            return "remediation";
        }
        if ("ASSISTANT".equals(channel) || "CHAT".equals(channel)) {
            return "dialogue";
        }
        return "tool";
    }

    private boolean matchesKind(Map<String, Object> event, String kind) {
        if (kind == null || kind.isBlank()) {
            return true;
        }
        return kind.equals(event.get("auditKind"));
    }

    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind.trim())) {
            return null;
        }
        return kind.trim().toLowerCase(Locale.ROOT);
    }

    private String kindLabel(String kind) {
        return switch (kind) {
            case "access" -> "访问";
            case "dialogue" -> "对话";
            case "tool" -> "工具";
            case "remediation" -> "修复";
            case "block" -> "拦截";
            case "confirm" -> "确认";
            default -> "审计";
        };
    }

    private Instant createdAtInstant(Map<String, Object> row) {
        return Instant.parse(String.valueOf(row.getOrDefault("createdAt", Instant.EPOCH.toString())));
    }

    private String toIso(Object raw) {
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (raw instanceof Instant instant) {
            return instant.toString();
        }
        String value = stringValue(raw);
        if (value.isBlank()) {
            return Instant.EPOCH.toString();
        }
        try {
            return Instant.parse(value).toString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
