package com.award.log.controller;

import com.award.log.collector.CollectorDispatchScheduler;
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
class CollectorStatusControllerSmokeTest {

    @Mock private CollectorDispatchScheduler collectorDispatchScheduler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CollectorStatusController controller = new CollectorStatusController(collectorDispatchScheduler);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReturnsCollectorSnapshot() throws Exception {
        when(collectorDispatchScheduler.snapshot()).thenReturn(Map.of("running", true));

        mockMvc.perform(get("/api/collector/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.running").value(true));
    }
}
