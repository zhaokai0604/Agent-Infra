package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.AuthInterceptor;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.OpsPathPolicy;
import com.award.log.security.RequestUserResolver;
import com.award.log.security.RiskLevel;
import com.award.log.service.OpsAuditTraceService;
import com.award.log.service.impl.RunbookApprovalService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RunbookApprovalControllerIntegrationTest {

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
    private final RequestUserResolver requestUserResolver = new RequestUserResolver();

    private InMemoryJdbcTemplate jdbcTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new InMemoryJdbcTemplate();
        mockMvc = buildMvc(createService(jdbcTemplate));
    }

    @Test
    void submitApproveExecuteAcrossServiceRestartsPersistsLifecycle() throws Exception {
        when(mcpToolCatalog.isRegistered("CleanTempTool")).thenReturn(true);
        when(mcpToolCatalog.isHttpAllowed("CleanTempTool")).thenReturn(true);
        when(opsPathPolicy.getPolicyVersion()).thenReturn("policy-v1");
        when(mcpSecurityService.evaluatePostConfirm(anyString(), anyMap(), anyString()))
                .thenReturn(GateDecision.allow(RiskLevel.MEDIUM, 4.0, Map.of(), "ok"));
        when(mcpSecurityService.buildInstruction(anyString(), anyMap())).thenReturn("instr");

        Map<String, Object> execResponse = new LinkedHashMap<>();
        execResponse.put("success", true);
        execResponse.put("data", "{\"success\":true,\"data\":\"{\\\"mode\\\":\\\"DELETE\\\"}\"}");
        when(mcpExecutionService.execute(anyString(), anyMap(), anyLong(), anyString(), anyString(), anyBoolean()))
                .thenReturn(execResponse);

        Map<String, Object> submitPayload = Map.of(
                "title", "Disk cleanup",
                "toolName", "CleanTempTool",
                "parameters", Map.of(
                        "path", "/tmp/award-extract",
                        "removeDirectory", true,
                        "dryRun", false,
                        "confirmDelete", true));

        MvcResult submit = mockMvc.perform(post("/api/runbook/submit")
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 2001)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.requester").value("2001"))
                .andReturn();

        long id = readData(submit).path("id").asLong();

        mockMvc.perform(post("/api/runbook/{id}/approve", id)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 1)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approver").value("1"));

        MockMvc secondServiceMvc = buildMvc(createService(jdbcTemplate));
        secondServiceMvc.perform(post("/api/runbook/{id}/execute", id)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 1)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.executionImplemented").value(true))
                .andExpect(jsonPath("$.data.mcpSuccess").value(true))
                .andExpect(jsonPath("$.data.operator").value("1"));

        MockMvc thirdServiceMvc = buildMvc(createService(jdbcTemplate));
        MvcResult list = thirdServiceMvc.perform(get("/api/runbook/list")
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 1)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode items = readData(list);
        assertEquals(1, items.size());
        JsonNode item = items.get(0);
        assertEquals(id, item.path("id").asLong());
        assertEquals("EXECUTED", item.path("status").asText());
        assertEquals("1", item.path("approver").asText());
        assertEquals("1", item.path("operator").asText());
        assertTrue(item.path("traceId").asText().length() > 10);

        verify(mcpExecutionService).execute(
                eq("CleanTempTool"),
                anyMap(),
                anyLong(),
                anyString(),
                anyString(),
                eq(true));
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
                eq("1"),
                eq("policy-v1"));
    }

    @Test
    void nonAdminCannotApproveOrExecuteRunbook() throws Exception {
        when(mcpToolCatalog.isRegistered("DiskTool")).thenReturn(true);

        MvcResult submit = mockMvc.perform(post("/api/runbook/submit")
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 3001)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Inspect disk",
                                "toolName", "DiskTool",
                                "parameters", Map.of("path", "/tmp")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        long id = readData(submit).path("id").asLong();

        mockMvc.perform(post("/api/runbook/{id}/approve", id)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 3001)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/runbook/{id}/execute", id)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ID, 3001)
                        .sessionAttr(AuthInterceptor.SESSION_USER_ROLE, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    private RunbookApprovalService createService(InMemoryJdbcTemplate jdbc) {
        RunbookApprovalService service = new RunbookApprovalService(
                mcpExecutionService,
                mcpSecurityService,
                mcpToolCatalog,
                objectMapper,
                opsAuditTraceService,
                opsPathPolicy,
                jdbc);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    private MockMvc buildMvc(RunbookApprovalService service) {
        return MockMvcBuilders.standaloneSetup(new RunbookApprovalController(service, requestUserResolver)).build();
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
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
            if (sql.contains("WHERE id = ?")) {
                long id = toLong(args[0]);
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    if (toLong(row.get("id")) == id) {
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
