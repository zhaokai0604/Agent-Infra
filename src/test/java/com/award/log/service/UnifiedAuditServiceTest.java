package com.award.log.service;

import com.award.log.service.impl.AiAuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedAuditServiceTest {

    @Mock
    private AiAuditLogService aiAuditLogService;

    @Mock
    private OpsAuditTraceService opsAuditTraceService;

    private UnifiedAuditService service;

    @BeforeEach
    void setUp() {
        service = new UnifiedAuditService(aiAuditLogService, opsAuditTraceService);
    }

    @Test
    void feedMergesAndDerivesKindsAcrossSources() {
        when(aiAuditLogService.listRecent(10)).thenReturn(List.of(
                accessRow(7L, "POST", "/api/mcp/execute", 500, 33L, Timestamp.from(Instant.parse("2026-07-04T04:00:00Z")))
        ));
        when(opsAuditTraceService.listRecentWithSteps(10)).thenReturn(List.of(
                traceRow("block-1", "MCP", null, "REJECT_INJECTION", null, null,
                        "DiskTool", "blocked", "2026-07-04T04:00:01Z"),
                traceRow("confirm-1", "MCP", null, "NEED_CONFIRM", "confirm-1", null,
                        "ServiceRestartTool", "preview", "2026-07-04T04:00:02Z"),
                traceRow("remediate-1", "RUNBOOK", null, "PASS", null, null,
                        "LogCleanupTool", "cleaned", "2026-07-04T04:00:03Z"),
                traceRow("chat-1", "CHAT", null, "EXECUTED", null, null,
                        "AssistantOrchestrator", "done", "2026-07-04T04:00:04Z"),
                traceRow("tool-1", "MCP", "tool", "PASS", null, "disk-host",
                        "DiskAnalyzeTool", "healthy", "2026-07-04T04:00:05Z")
        ));

        List<Map<String, Object>> feed = service.feed(10, null);

        assertEquals(6, feed.size());
        assertEquals("trace:tool-1", feed.get(0).get("entryId"));
        assertTrue(feed.stream().anyMatch(row -> "block".equals(row.get("auditKind"))));
        assertTrue(feed.stream().anyMatch(row -> "confirm".equals(row.get("auditKind"))));
        assertTrue(feed.stream().anyMatch(row -> "remediation".equals(row.get("auditKind"))));
        assertTrue(feed.stream().anyMatch(row -> "dialogue".equals(row.get("auditKind"))));
        assertTrue(feed.stream().anyMatch(row -> "access".equals(row.get("auditKind")) && Boolean.FALSE.equals(row.get("executionOk"))));
    }

    @Test
    void feedCapsLimitAndSkipsTraceLookupsForAccessOnly() {
        when(aiAuditLogService.listRecent(300)).thenReturn(List.of(
                accessRow(8L, "GET", "/health", 200, 12L, Timestamp.from(Instant.parse("2026-07-04T04:05:00Z")))
        ));

        List<Map<String, Object>> feed = service.feed(999, "access");

        assertEquals(1, feed.size());
        assertEquals("api:8", feed.get(0).get("entryId"));
        assertEquals("HTTP_PASS", feed.get(0).get("securityOutcome"));
        verify(aiAuditLogService).listRecent(300);
        verifyNoInteractions(opsAuditTraceService);
    }

    @Test
    void feedFiltersToolKindAndUsesNormalizedRequestKind() {
        when(opsAuditTraceService.listRecentWithSteps(5)).thenReturn(List.of(
                traceRow("tool-9", "MCP", null, "PASS", null, "node-a",
                        "DiskTool", "ok", "2026-07-04T04:10:00Z"),
                traceRow("chat-9", "CHAT", null, "PASS", null, null,
                        "AssistantOrchestrator", "reply", "2026-07-04T04:09:00Z")
        ));

        List<Map<String, Object>> feed = service.feed(5, " TOOL ");

        assertEquals(1, feed.size());
        assertEquals("trace:tool-9", feed.get(0).get("entryId"));
        assertEquals("tool", feed.get(0).get("auditKind"));
    }

    @Test
    void detailSupportsTraceIdAndApiEntryVariants() {
        Map<String, Object> traceRow = new LinkedHashMap<>();
        traceRow.put("traceId", "trace-1");
        traceRow.put("channel", "MCP");
        traceRow.put("requestChannel", "mcp");
        traceRow.put("stage", "execute");
        traceRow.put("decision", "PASS");
        traceRow.put("securityOutcome", "PASS");
        traceRow.put("toolName", "DiskTool");
        traceRow.put("resultSummary", "healthy");
        traceRow.put("steps", List.of(Map.of("phase", "execute")));
        traceRow.put("createdAt", "2026-07-04T04:15:00Z");
        traceRow.put("executionOk", true);
        traceRow.put("durationMs", 80L);
        traceRow.put("userInput", "check disk");
        traceRow.put("operatorUserId", "admin");
        when(opsAuditTraceService.findByTraceId("trace-1")).thenReturn(traceRow);
        when(aiAuditLogService.findById(9L)).thenReturn(accessRow(
                9L, "GET", "/api/audit/feed", 200, 22L, Timestamp.from(Instant.parse("2026-07-04T04:16:00Z"))));

        Map<String, Object> traceDetail = service.detail("trace:trace-1", null);
        Map<String, Object> accessDetail = service.detail("api:9", null);

        assertEquals("trace:trace-1", traceDetail.get("entryId"));
        assertEquals("healthy", traceDetail.get("resultSummary"));
        assertEquals(1, ((List<?>) traceDetail.get("steps")).size());
        assertEquals("api:9", accessDetail.get("entryId"));
        assertEquals(3, ((List<?>) accessDetail.get("steps")).size());
        assertEquals("ai_audit_log", accessDetail.get("sourceTable"));
    }

    @Test
    void detailReturnsEmptyForInvalidIdentifiers() {
        when(opsAuditTraceService.findByTraceId("trace-ok")).thenReturn(Map.of());
        assertTrue(service.detail("api:not-a-number", null).isEmpty());
        assertTrue(service.detail(" ", null).isEmpty());
        assertTrue(service.detail(null, " ").isEmpty());
        assertTrue(service.detail(null, "trace-ok").isEmpty());
    }

    private static Map<String, Object> accessRow(long id,
                                                 String method,
                                                 String path,
                                                 int status,
                                                 long durationMs,
                                                 Timestamp createdAt) {
        return Map.of(
                "id", id,
                "method", method,
                "path", path,
                "status", status,
                "duration_ms", durationMs,
                "created_at", createdAt,
                "user_id", "42");
    }

    private static Map<String, Object> traceRow(String traceId,
                                                String channel,
                                                String auditKind,
                                                String securityOutcome,
                                                String confirmationId,
                                                String targetName,
                                                String toolName,
                                                String resultSummary,
                                                String createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", traceId);
        row.put("channel", channel);
        row.put("auditKind", auditKind == null ? "" : auditKind);
        row.put("requestChannel", "");
        row.put("stage", "");
        row.put("decision", "");
        row.put("securityOutcome", securityOutcome);
        row.put("targetType", "");
        row.put("targetName", targetName == null ? "" : targetName);
        row.put("toolName", toolName);
        row.put("resultSummary", resultSummary);
        row.put("createdAt", createdAt);
        row.put("executionOk", true);
        row.put("durationMs", 45L);
        row.put("userInput", "run " + toolName);
        row.put("operatorUserId", "admin");
        row.put("confirmationId", confirmationId);
        row.put("targetHostLabel", "host-1");
        return row;
    }
}
