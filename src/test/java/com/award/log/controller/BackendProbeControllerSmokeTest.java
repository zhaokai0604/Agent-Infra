package com.award.log.controller;

import com.award.log.config.OpsDryRunProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BackendProbeControllerSmokeTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private OpsDryRunProperties opsDryRunProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BackendProbeController controller = new BackendProbeController(jdbcTemplate, opsDryRunProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void backendProbeReturnsSchemaSnapshot() throws Exception {
        when(opsDryRunProperties.isGlobalDryRun()).thenReturn(false);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of("id", "name"));
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM log_alarm"), eq(Long.class))).thenReturn(5L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM alarm_rule"), eq(Long.class))).thenReturn(2L);

        mockMvc.perform(get("/api/platform/backend-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.logAlarmCount").value(5))
                .andExpect(jsonPath("$.data.alarmRuleCount").value(2));
    }
}
