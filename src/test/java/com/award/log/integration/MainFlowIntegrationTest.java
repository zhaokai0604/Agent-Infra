package com.award.log.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MainFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        session = IntegrationTestSupport.registerAndLogin(
                mockMvc, "it_" + UUID.randomUUID().toString().substring(0, 8), jdbcTemplate);
    }

    @Test
    void platformInfoAndReadiness() throws Exception {
        mockMvc.perform(get("/api/platform/info").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/platform/readiness").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void mcpToolsAndDiskProbe() throws Exception {
        mockMvc.perform(get("/api/mcp/tools").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(IntegrationTestSupport.withSession(
                        post("/api/mcp/execute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"toolName\":\"DiskTool\",\"parameters\":{},\"userMessage\":\"check disk usage\"}"),
                        session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }

    @Test
    void patrolSnapshotsWithoutRun() throws Exception {
        mockMvc.perform(get("/api/ops/patrol/correlation/latest").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/ops/patrol/history").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void statisticsAndLogSummary() throws Exception {
        mockMvc.perform(get("/admin/statistics/log-summary").param("days", "7").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/statistics/performance").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void logUploadStartsAnalysisTask() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.log",
                "text/plain",
                ("2024-01-01 12:00:00 ERROR connection timeout\n"
                        + "2024-01-01 12:00:01 INFO service started\n").getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(IntegrationTestSupport.withSession(multipart("/log/upload").file(file), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void securitySelfCheckAndWorkflowMemory() throws Exception {
        mockMvc.perform(get("/api/security/self-check").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/ops/workflow/memory").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void systemConfigSnapshot() throws Exception {
        mockMvc.perform(get("/api/system-config/effective").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
