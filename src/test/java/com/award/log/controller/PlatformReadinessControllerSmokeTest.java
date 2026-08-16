package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.KafkaMonitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlatformReadinessControllerSmokeTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private KafkaMonitorService kafkaMonitorService;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PlatformReadinessController controller = new PlatformReadinessController();
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(controller, "kafkaMonitorService", kafkaMonitorService);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);
        ReflectionTestUtils.setField(controller, "aiApiKey", "test-key");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void readinessRequiresAdmin() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(false);

        mockMvc.perform(get("/api/platform/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void readinessReturnsChecksForAdmin() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        mockMvc.perform(get("/api/platform/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.checks.database.status").value("UP"));
    }
}
