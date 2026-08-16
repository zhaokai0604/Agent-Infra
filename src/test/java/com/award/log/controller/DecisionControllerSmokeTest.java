package com.award.log.controller;

import com.award.log.mapper.DecisionLogMapper;
import com.award.log.mapper.EngineOfflineMetricMapper;
import com.award.log.model.DecisionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DecisionControllerSmokeTest {

    @Mock private DecisionLogMapper decisionLogMapper;
    @Mock private EngineOfflineMetricMapper engineOfflineMetricMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DecisionController controller = new DecisionController(decisionLogMapper, engineOfflineMetricMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statsReturnsAggregates() throws Exception {
        when(decisionLogMapper.selectByFilter(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(new DecisionLog()));
        when(engineOfflineMetricMapper.selectLatest(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/decision/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void historyReturnsDecisionList() throws Exception {
        when(decisionLogMapper.selectByFilter(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/decision/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
