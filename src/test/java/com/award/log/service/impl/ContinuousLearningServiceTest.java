package com.award.log.service.impl;

import com.award.log.mapper.DecisionFeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContinuousLearningServiceTest {

    @Mock
    private DecisionFeedbackMapper decisionFeedbackMapper;

    private ContinuousLearningService service;

    @BeforeEach
    void setUp() {
        service = new ContinuousLearningService();
        ReflectionTestUtils.setField(service, "decisionFeedbackMapper", decisionFeedbackMapper);
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "sampleThreshold", 500);
    }

    @Test
    void getUntrainedSampleCountShouldReadMapper() {
        when(decisionFeedbackMapper.countUntrained()).thenReturn(12);
        assertEquals(12, service.getUntrainedSampleCount());
    }

    @Test
    void getUntrainedSampleCountShouldReturnZeroOnFailure() {
        when(decisionFeedbackMapper.countUntrained()).thenThrow(new RuntimeException("db down"));
        assertEquals(0, service.getUntrainedSampleCount());
    }

    @Test
    void dailyCollectAndRetrainShouldSkipWhenDisabled() {
        service.dailyCollectAndRetrain();
        verifyNoInteractions(decisionFeedbackMapper);
    }

    @Test
    void triggerManualTrainingShouldInvokeDailyJob() {
        when(decisionFeedbackMapper.countUntrained()).thenReturn(0);
        assertFalse(service.triggerManualTraining());
        verify(decisionFeedbackMapper).countUntrained();
    }
}
