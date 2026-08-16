package com.award.log.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link com.award.log.mcp.dispatch.McpToolDispatcher} through HTTP for read-mostly tools.
 */
class McpDispatchIntegrationTest extends IntegrationTestBase {

    private static final List<String> READ_PROBE_TOOLS = List.of(
            "DiskTool",
            "SystemLoadTool",
            "ProcessTool",
            "NetworkTool",
            "ConfigCheckTool",
            "OsInsightTool",
            "PortHealthTool",
            "DiskAnalyzeTool",
            "DiskOpsTool",
            "LogOpsTool",
            "ServiceOpsTool",
            "ContainerOpsTool",
            "ProcessOpsTool"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        session = IntegrationTestSupport.registerAndLogin(
                mockMvc, "mcp_" + UUID.randomUUID().toString().substring(0, 8), jdbcTemplate);
    }

    @Test
    void dispatchReadProbeTools() throws Exception {
        for (String toolName : READ_PROBE_TOOLS) {
            mockMvc.perform(IntegrationTestSupport.withSession(
                            post("/api/mcp/execute")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"toolName\":\"" + toolName
                                            + "\",\"parameters\":{},\"userMessage\":\"integration probe\"}"),
                            session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").exists());
        }
    }
}
