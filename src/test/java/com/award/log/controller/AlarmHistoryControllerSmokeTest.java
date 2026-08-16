package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.AiLogAlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AlarmHistoryControllerSmokeTest {

    @Mock private AiLogAlarmService aiLogAlarmService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AlarmHistoryController controller = new AlarmHistoryController(
                aiLogAlarmService, jdbcTemplate, requestUserResolver);
        ReflectionTestUtils.setField(controller, "datasourceUrl", "jdbc:mysql://localhost/test");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsAlarmHistory() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(false);
        when(aiLogAlarmService.getAlarmHistoryForUser(anyInt(), anyInt(), any(), any(), eq(1), eq(false)))
                .thenReturn(Map.of("list", List.of(), "total", 0));

        mockMvc.perform(get("/api/alarm/history/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void statisticsReturnsSummary() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(false);
        when(aiLogAlarmService.getAlarmStatisticsForUser(anyInt(), any(), any(), eq(1), eq(false)))
                .thenReturn(Map.of("total", 5));

        mockMvc.perform(get("/api/alarm/history/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(5));
    }
}
