package com.award.log.controller;

import com.award.log.model.RuleDefinition;
import com.award.log.service.RuleRegistryService;
import com.award.log.service.RuleStatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuleManagementControllerSmokeTest {

    @Mock private RuleRegistryService ruleRegistryService;
    @Mock private RuleStatService ruleStatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RuleManagementController controller = new RuleManagementController(ruleRegistryService, ruleStatService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsRules() throws Exception {
        when(ruleRegistryService.list()).thenReturn(List.of(new RuleDefinition()));

        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void evaluateReturnsHitRate() throws Exception {
        when(ruleRegistryService.list()).thenReturn(List.of());
        when(ruleStatService.summary()).thenReturn(Map.of("hitRate", 0.5, "totalHit", 1, "totalMiss", 1));

        mockMvc.perform(get("/api/v1/rules/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hitRate").value(0.5));
    }
}
