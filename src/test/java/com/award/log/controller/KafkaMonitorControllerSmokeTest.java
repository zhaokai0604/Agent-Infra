package com.award.log.controller;

import com.award.log.service.KafkaMonitorService;
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
class KafkaMonitorControllerSmokeTest {

    @Mock private KafkaMonitorService kafkaMonitorService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KafkaMonitorController controller = new KafkaMonitorController(kafkaMonitorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReturnsSnapshotWhenEnabled() throws Exception {
        when(kafkaMonitorService.snapshot()).thenReturn(Map.of("online", true));

        mockMvc.perform(get("/api/kafka/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.online").value(true));
    }

    @Test
    void statusReturnsDisabledMessageWhenServiceNull() throws Exception {
        KafkaMonitorController controller = new KafkaMonitorController(null);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(controller).build();

        disabledMvc.perform(get("/api/kafka/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.online").value(false));
    }
}
