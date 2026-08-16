package com.award.log.service.impl;

import com.award.log.mapper.DecisionLogMapper;
import com.award.log.mapper.EngineOfflineMetricMapper;
import com.award.log.model.EngineOfflineMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfflineMetricJobTest {

    @Mock
    private EngineOfflineMetricMapper mapper;
    @Mock
    private DecisionLogMapper decisionLogMapper;

    private OfflineMetricJob job;

    @BeforeEach
    void setUp() {
        job = new OfflineMetricJob(mapper, decisionLogMapper);
    }

    @Test
    void calcShouldPersistMetricsForEachEngine() {
        when(decisionLogMapper.selectOfflinePairs(anyString(), anyString())).thenReturn(List.of(
                Map.of("engineType", "RULE", "predicted", 1, "actual", 1),
                Map.of("engineType", "RULE", "predicted", 1, "actual", 0),
                Map.of("engineType", "LLM", "predicted", 0, "actual", 1)));

        job.calc();

        ArgumentCaptor<EngineOfflineMetric> captor = ArgumentCaptor.forClass(EngineOfflineMetric.class);
        verify(mapper, atLeast(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(m -> "RULE".equals(m.getEngineType())));
    }

    @Test
    void calcShouldSkipWhenNoSamples() {
        when(decisionLogMapper.selectOfflinePairs(anyString(), anyString())).thenReturn(List.of());
        job.calc();
        verify(mapper, never()).insert(any());
    }
}
