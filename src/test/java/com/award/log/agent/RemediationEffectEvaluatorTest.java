package com.award.log.agent;

import com.award.log.service.StatisticsService;
import com.award.log.util.TestTimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemediationEffectEvaluatorTest {

    @Mock
    private StatisticsService statisticsService;

    @Test
    void captureMetricsMapsPerformanceFields() {
        when(statisticsService.getSystemPerformance(null)).thenReturn(Map.of(
                "diskUsage", 92.5,
                "cpuUsage", 70.0,
                "memoryUsage", 66.2
        ));
        RemediationEffectEvaluator evaluator = new RemediationEffectEvaluator(statisticsService, new TestTimeSource(123_456L));

        Map<String, Object> metrics = evaluator.captureMetrics();

        assertEquals(92.5, metrics.get("diskUsagePct"));
        assertEquals(70.0, metrics.get("cpuUsagePct"));
        assertEquals(66.2, metrics.get("memoryUsagePct"));
        assertTrue(metrics.containsKey("capturedAtMs"));
    }

    @Test
    void evaluateRewardsImprovementAndRealExecution() {
        RemediationEffectEvaluator evaluator = new RemediationEffectEvaluator(statisticsService, new TestTimeSource(123_456L));

        Map<String, Object> effect = evaluator.evaluate(
                Map.of("diskUsagePct", 95.0, "cpuUsagePct", 85.0),
                Map.of("diskUsagePct", 70.0, "cpuUsagePct", 60.0),
                2
        );

        assertEquals(25.0, effect.get("diskUsageDeltaPct"));
        assertEquals(25.0, effect.get("cpuUsageDeltaPct"));
        assertEquals("effective", effect.get("verdict"));
        assertTrue(((Integer) effect.get("healingScore")) >= 70);
    }
}
