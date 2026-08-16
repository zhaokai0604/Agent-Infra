package com.award.log.controller;

import com.award.log.service.OpsEffectDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpsEffectDashboardControllerSmokeTest {

    @Mock private OpsEffectDashboardService opsEffectDashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsEffectDashboardController controller = new OpsEffectDashboardController(opsEffectDashboardService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void dashboardReturnsMetrics() throws Exception {
        when(opsEffectDashboardService.buildDashboard(7)).thenReturn(Map.of("score", 85));

        mockMvc.perform(get("/api/ops/effect/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.score").value(85));
    }
}
