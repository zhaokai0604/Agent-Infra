package com.award.log.service.impl;

import com.award.log.mapper.ModelEvaluationMapper;
import com.award.log.model.ModelEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelEvaluationServiceImplTest {

    @Mock
    private ModelEvaluationMapper modelEvaluationMapper;

    private ModelEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModelEvaluationServiceImpl(modelEvaluationMapper);
    }

    @Test
    void evaluateShouldComputeMetricsAndPersist() {
        List<Map<String, Object>> dataset = List.of(
                Map.of("features", List.of(0.8, 0.9), "label", 1),
                Map.of("features", List.of(0.1), "label", 0));

        Map<String, Object> report = service.evaluate("v1", dataset);

        assertEquals("v1", report.get("modelVersion"));
        assertEquals(2, report.get("sampleSize"));
        assertTrue((Double) report.get("accuracy") >= 0);
        ArgumentCaptor<ModelEvaluation> captor = ArgumentCaptor.forClass(ModelEvaluation.class);
        verify(modelEvaluationMapper).insert(captor.capture());
        assertEquals("v1", captor.getValue().getModelVersion());
    }

    @Test
    void evaluateShouldHandleEmptyFeatures() {
        Map<String, Object> report = service.evaluate("v2", List.of(
                Map.of("features", List.of(), "label", 0)));
        assertEquals(1, report.get("sampleSize"));
    }
}
