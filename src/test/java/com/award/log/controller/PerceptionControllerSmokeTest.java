package com.award.log.controller;

import com.award.log.agent.AutonomousOpsOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PerceptionControllerSmokeTest {

    @Mock private AutonomousOpsOrchestrator autonomousOpsOrchestrator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PerceptionController controller = new PerceptionController(autonomousOpsOrchestrator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void snapshotReturnsPerceptionView() throws Exception {
        when(autonomousOpsOrchestrator.buildPerceptionView(anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("findings", 0));

        mockMvc.perform(get("/api/ops/perception/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.findings").value(0));
    }
}
