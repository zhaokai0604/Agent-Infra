package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FailureInsightShouldCaptureTest {

    @Test
    void capturesChatStreamCodesWithoutRejectPrefix() {
        FailureInsightService svc = new FailureInsightService(mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new TrajectoryEvaluator());
        ReflectionTestUtils.setField(svc, "enabled", true);

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "HIGH_INTENT"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "INJECTION"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "HIGH_RISK_COMMAND"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "NEED_CONFIRM"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "REJECT_RISK_SCORE"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "OK"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(svc, "shouldCapture", "PASS"));
    }
}
