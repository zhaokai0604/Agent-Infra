package com.award.log.service.impl;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionResult;
import com.award.log.decision.EngineType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MvpStreamProcessorTest {

    @Mock
    private CollaborativeDecisionService collaborativeDecisionService;

    private MvpStreamProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MvpStreamProcessor();
        ReflectionTestUtils.setField(processor, "collaborativeDecisionService", collaborativeDecisionService);
        ReflectionTestUtils.setField(processor, "errorRateThreshold", 0.3);
        ReflectionTestUtils.setField(processor, "minSampleSize", 3);
        ReflectionTestUtils.setField(processor, "cooldownMs", 300000L);
    }

    @Test
    void processShouldNormalizeLevelFromContent() {
        when(collaborativeDecisionService.decide(any())).thenReturn(DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(false)
                .confidence(0.2)
                .build());

        for (int i = 0; i < 4; i++) {
            RawLogEvent event = new RawLogEvent();
            event.setContent("java.lang.NullPointerException at handler-" + i);
            event.setHost("host-1");
            processor.process(event);
            if (i == 0) {
                assertEquals("ERROR", event.getLevel());
            }
        }
        verify(collaborativeDecisionService, atLeastOnce()).decide(any());
    }

    @Test
    void processShouldSkipAlertBelowSampleThreshold() {
        RawLogEvent event = new RawLogEvent();
        event.setLevel("ERROR");
        event.setContent("error once");
        processor.process(event);
        verify(collaborativeDecisionService, never()).decide(any());
    }

    @Test
    void processShouldEvaluateDecisionAfterEnoughSamples() {
        when(collaborativeDecisionService.decide(any())).thenReturn(DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(false)
                .confidence(0.4)
                .reason("stable")
                .build());

        for (int i = 0; i < 4; i++) {
            RawLogEvent event = new RawLogEvent();
            event.setLevel(i % 2 == 0 ? "ERROR" : "INFO");
            event.setContent("event-" + i);
            event.setHost("host-a");
            processor.process(event);
        }

        verify(collaborativeDecisionService, atLeastOnce()).decide(any());
    }
}
