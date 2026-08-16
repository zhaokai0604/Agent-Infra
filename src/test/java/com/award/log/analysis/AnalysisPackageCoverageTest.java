package com.award.log.analysis;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisPackageCoverageTest {

    @Test
    void logAnomalyHeuristicsShouldDetectKeywords() {
        EnhancedLogParseResultEntity entity = new EnhancedLogParseResultEntity("FATAL OutOfMemoryError in service");
        LogAnomalyHeuristics.applyKeywordDetection(List.of(entity));
        assertTrue(entity.isAnomaly() || entity.getAnomalyScore() > 0);

        assertEquals(LogSeverityLevel.ERROR_LEVEL,
                LogAnomalyHeuristics.inferSeverityFromLine("ERROR connection reset"));
    }

    @Test
    void generalLogSeverityResolverShouldClassifyLevels() {
        assertNotNull(GeneralLogSeverityResolver.resolve("INFO started"));
        assertNotNull(GeneralLogSeverityResolver.resolve("ERROR failed"));
        assertNotNull(GeneralLogSeverityResolver.resolve("WARN slow query"));
    }

    @Test
    void logFieldPlaceholderShouldExposeConstants() {
        assertNotNull(LogFieldPlaceholder.TIME_UNPARSED);
        assertFalse(LogFieldPlaceholder.TIME_UNPARSED.isBlank());
    }

    @Test
    void aiDiagnosisContextBuilderShouldSelectFocusLines() {
        EnhancedLogParseResultEntity anomaly = new EnhancedLogParseResultEntity("ERROR timeout");
        anomaly.setAnomaly(true);
        anomaly.setAnomalyScore(0.9);
        anomaly.setSeverity(LogSeverityLevel.ERROR_LEVEL);

        EnhancedLogParseResultEntity normal = new EnhancedLogParseResultEntity("INFO ok");
        normal.setAnomaly(false);
        normal.setAnomalyScore(0.0);

        List<EnhancedLogParseResultEntity> focus =
                AiDiagnosisContextBuilder.selectFocusLines(List.of(anomaly, normal));
        assertFalse(focus.isEmpty());
        assertEquals(anomaly, focus.get(0));
    }

    @Test
    void logLineParseSupportShouldDetectProtocolAndTime() {
        assertNotNull(LogLineParseSupport.detectProtocol("2024-01-01 12:00:00 INFO boot"));
        assertEquals(LogProtocolType.UNIVERSAL_TEXT_LOG,
                LogLineParseSupport.detectProtocol("plain text without markers"));

        String normalized = LogLineParseSupport.extractNormalizedTimeString("2024-01-01 12:00:00 INFO boot");
        assertNotNull(normalized);
        assertTrue(LogLineParseSupport.toEpochMillis(normalized) > 0);
    }
}
