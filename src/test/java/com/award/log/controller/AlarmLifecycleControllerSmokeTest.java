package com.award.log.controller;

import com.award.log.service.AlarmLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AlarmLifecycleControllerSmokeTest {

    @Mock private AlarmLifecycleService alarmLifecycleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AlarmLifecycleController controller = new AlarmLifecycleController(alarmLifecycleService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ackReturnsSuccess() throws Exception {
        when(alarmLifecycleService.acknowledge("a1", "ops")).thenReturn(true);

        mockMvc.perform(post("/api/v1/alarm/lifecycle/a1/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void silenceReturnsWindow() throws Exception {
        when(alarmLifecycleService.silenceWindow("2026-01-01", "2026-01-02"))
                .thenReturn(Map.of("active", true));

        mockMvc.perform(post("/api/v1/alarm/lifecycle/silence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-01-01\",\"endTime\":\"2026-01-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.active").value(true));
    }
}
