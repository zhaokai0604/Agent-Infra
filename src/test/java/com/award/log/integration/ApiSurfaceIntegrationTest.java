package com.award.log.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSurfaceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        session = IntegrationTestSupport.registerAndLogin(
                mockMvc, "api_" + UUID.randomUUID().toString().substring(0, 8), jdbcTemplate);
    }

    @Test
    void browseAuthenticatedApis() throws Exception {
        String[] paths = {
                "/api/platform/backend-probe",
                "/api/ops/effect/dashboard",
                "/api/ops-trace/recent",
                "/api/alarm/history/list",
                "/api/v1/decision/history",
                "/api/v1/knowledge/status",
                "/api/collector/status",
                "/api/ops/perception/snapshot",
                "/log/history",
                "/api/assistant/context",
                "/admin/statistics/task-status",
                "/admin/statistics/anomaly-statistics"
        };
        for (String path : paths) {
            mockMvc.perform(get(path).session(session))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void publicAcceptanceEndpoint() throws Exception {
        mockMvc.perform(get("/api/platform/acceptance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
