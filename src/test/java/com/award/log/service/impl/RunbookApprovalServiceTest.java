package com.award.log.service.impl;

import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunbookApprovalServiceTest {

    @Mock
    private McpExecutionService mcpExecutionService;
    @Mock
    private McpSecurityService mcpSecurityService;
    @Mock
    private McpToolCatalog mcpToolCatalog;
    @Mock
    private OpsAuditTraceService opsAuditTraceService;
    @Mock
    private OpsPathPolicy opsPathPolicy;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryJdbcTemplate jdbcTemplate;
    private RunbookApprovalService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new InMemoryJdbcTemplate();
        service = new RunbookApprovalService(
                mcpExecutionService,
                mcpSecurityService,
                mcpToolCatalog,
                objectMapper,
                opsAuditTraceService,
                opsPathPolicy,
                jdbcTemplate);
        service.init();
    }

    @Test
    void executeCallsMcpAndPersistsAuditAfterApproval() {
        when(opsPathPolicy.getPolicyVersion()).thenReturn("policy-v1");
        when(mcpToolCatalog.isRegistered("CleanTempTool")).thenReturn(true);
        when(mcpToolCatalog.isHttpAllowed("CleanTempTool")).thenReturn(true);
        when(mcpSecurityService.evaluatePostConfirm(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.allow(RiskLevel.MEDIUM, 4.0, Map.of(), "ok"));
        when(mcpSecurityService.buildInstruction(anyString(), anyMap())).thenReturn("instr");

        Map<String, Object> mcpResult = new HashMap<>();
        mcpResult.put("success", true);
        mcpResult.put("data", "{\"success\":true,\"data\":\"{\\\"mode\\\":\\\"DELETE\\\"}\"}");
        when(mcpExecutionService.execute(anyString(), anyMap(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenReturn(mcpResult);

        Map<String, Object> params = Map.of(
                "path", "/tmp/award-extract",
                "removeDirectory", true,
                "dryRun", false,
                "confirmDelete", true);
        Map<String, Object> submitted = service.submit(
                "Disk cleanup",
                "CleanTempTool",
                "",
                "CleanTempTool",
                params,
                "u1");
        long id = ((Number) submitted.get("id")).longValue();
        service.approve(id, "admin");

        Map<String, Object> executed = service.execute(id, "admin");

        assertEquals("EXECUTED", executed.get("status"));
        assertEquals("EXECUTED", executed.get("result"));
        assertEquals(Boolean.TRUE, executed.get("executionImplemented"));
        assertEquals(Boolean.TRUE, executed.get("mcpSuccess"));
        verify(opsAuditTraceService).save(
                anyString(),
                eq("RUNBOOK"),
                anyString(),
                eq("MEDIUM"),
                eq("PASS"),
                eq("CleanTempTool"),
                eq(true),
                anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                anyLong(),
                eq("admin"),
                eq("policy-v1"));
    }

    @Test
    void nonAdminListOnlyReturnsOwnRunbooks() {
        when(mcpToolCatalog.isRegistered("DiskTool")).thenReturn(true);
        service.submit("A", "DiskTool", "", "DiskTool", Map.of(), "u1");
        service.submit("B", "DiskTool", "", "DiskTool", Map.of(), "u2");

        List<Map<String, Object>> own = service.list("u1", false);
        List<Map<String, Object>> admin = service.list("admin", true);

        assertEquals(1, own.size());
        assertEquals("u1", own.get(0).get("requester"));
        assertEquals(2, admin.size());
    }

    @Test
    void submitRequiresToolOrAction() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("Bad", " ", " ", null, null, "u1"));
    }

    @Test
    void submitRejectsUnknownTool() {
        when(mcpToolCatalog.isRegistered("EvilTool")).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("Bad", "EvilTool", "", "EvilTool", Map.of(), "u1"));
    }

    @Test
    void rejectShouldMarkRunbookRejected() {
        when(mcpToolCatalog.isRegistered("DiskTool")).thenReturn(true);
        Map<String, Object> submitted = service.submit("Reject me", "DiskTool", "", "DiskTool", Map.of(), "u1");
        long id = ((Number) submitted.get("id")).longValue();

        Map<String, Object> rejected = service.reject(id, "admin", "too risky");

        assertEquals("REJECTED", rejected.get("status"));
        assertEquals("REJECTED", rejected.get("result"));
        assertEquals("too risky", rejected.get("reason"));
    }

    @Test
    void executeShouldFailWhenSecurityGateBlocks() {
        when(opsPathPolicy.getPolicyVersion()).thenReturn("policy-v1");
        when(mcpToolCatalog.isRegistered("CleanTempTool")).thenReturn(true);
        when(mcpToolCatalog.isHttpAllowed("CleanTempTool")).thenReturn(true);
        when(mcpSecurityService.evaluatePostConfirm(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.block("HIGH_INTENT", "blocked"));

        Map<String, Object> submitted = service.submit(
                "Blocked cleanup", "CleanTempTool", "", "CleanTempTool", Map.of("path", "/tmp"), "u1");
        long id = ((Number) submitted.get("id")).longValue();
        service.approve(id, "admin");

        Map<String, Object> executed = service.execute(id, "admin");

        assertEquals("EXECUTION_FAILED", executed.get("status"));
        assertEquals(Boolean.FALSE, executed.get("mcpSuccess"));
        verify(opsAuditTraceService).save(
                anyString(), eq("RUNBOOK"), anyString(), anyString(), eq("REJECT_HIGH_INTENT"),
                eq("CleanTempTool"), eq(false), anyString(), anyList(), anyLong(), eq("admin"), eq("policy-v1"));
    }

    private static final class InMemoryJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final AtomicLong maxId = new AtomicLong();

        @Override
        public void execute(String sql) {
            // no-op for tests
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (Long.class.equals(requiredType)) {
                return requiredType.cast(maxId.get());
            }
            return null;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql) {
            List<Map<String, Object>> copies = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                copies.add(new HashMap<>(row));
            }
            return copies;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("WHERE requester = ?")) {
                String requester = String.valueOf(args[0]);
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    if (requester.equals(String.valueOf(row.get("requester")))) {
                        filtered.add(new HashMap<>(row));
                    }
                }
                return filtered;
            }
            return queryForList(sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO ops_runbook_approval")) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", toLong(args[0]));
                row.put("title", args[1]);
                row.put("action_name", args[2]);
                row.put("command_text", args[3]);
                row.put("tool_name", args[4]);
                row.put("parameters_json", args[5]);
                row.put("requester", args[6]);
                row.put("status", args[7]);
                row.put("result_code", args[8]);
                row.put("execution_implemented", args[9]);
                row.put("approver", args[10]);
                row.put("reason", args[11]);
                row.put("operator", args[12]);
                row.put("trace_id", args[13]);
                row.put("mcp_result_json", args[14]);
                row.put("mcp_success", args[15]);
                row.put("execution_message", args[16]);
                row.put("write_mismatch", args[17]);
                row.put("write_mode", args[18]);
                row.put("created_at", toTimestamp(args[19]));
                row.put("approved_at", toTimestamp(args[20]));
                row.put("executed_at", toTimestamp(args[21]));
                rows.add(row);
                maxId.set(Math.max(maxId.get(), toLong(args[0])));
                return 1;
            }
            if (sql.startsWith("UPDATE ops_runbook_approval")) {
                long id = toLong(args[20]);
                Map<String, Object> row = rows.stream()
                        .filter(r -> toLong(r.get("id")) == id)
                        .findFirst()
                        .orElseThrow();
                row.put("title", args[0]);
                row.put("action_name", args[1]);
                row.put("command_text", args[2]);
                row.put("tool_name", args[3]);
                row.put("parameters_json", args[4]);
                row.put("requester", args[5]);
                row.put("status", args[6]);
                row.put("result_code", args[7]);
                row.put("execution_implemented", args[8]);
                row.put("approver", args[9]);
                row.put("reason", args[10]);
                row.put("operator", args[11]);
                row.put("trace_id", args[12]);
                row.put("mcp_result_json", args[13]);
                row.put("mcp_success", args[14]);
                row.put("execution_message", args[15]);
                row.put("write_mismatch", args[16]);
                row.put("write_mode", args[17]);
                row.put("approved_at", toTimestamp(args[18]));
                row.put("executed_at", toTimestamp(args[19]));
                return 1;
            }
            return 1;
        }

        private static long toLong(Object value) {
            if (value instanceof Number n) {
                return n.longValue();
            }
            return Long.parseLong(String.valueOf(value));
        }

        private static Timestamp toTimestamp(Object value) {
            if (value instanceof Timestamp ts) {
                return ts;
            }
            if (value instanceof Number n) {
                return new Timestamp(n.longValue());
            }
            return null;
        }
    }
}
