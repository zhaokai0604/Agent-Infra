package com.award.log.controller;

import com.award.log.decision.RandomForestDecisionEngine;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModelHealthControllerSmokeTest {

    @Mock private RandomForestDecisionEngine randomForestDecisionEngine;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ModelHealthController controller = new ModelHealthController(randomForestDecisionEngine);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void healthReturnsSnapshot() throws Exception {
        when(randomForestDecisionEngine.healthSnapshot()).thenReturn(Map.of("loaded", true));

        mockMvc.perform(get("/api/v1/model/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.loaded").value(true));
    }

    @Test
    void reloadReturnsSuccess() throws Exception {
        when(randomForestDecisionEngine.manualReload()).thenReturn(true);

        mockMvc.perform(post("/api/v1/model/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }
}
