package com.award.log.analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerExtendedCoverageTest {

    @Test
    void defaultCleanRulesShouldExposeKeywords() {
        Map<String, List<String>> keywords = DefaultCleanRules.getEnhancedKeywords();
        assertNotNull(keywords);
        assertFalse(keywords.isEmpty());
    }

    @Test
    void logCleanerShouldApplyRegexRule() {
        LogCleaner cleaner = new LogCleaner();
        LogCleaner.CleanRule rule = new LogCleaner.CleanRule();
        rule.setEnabled(true);
        rule.setType("regex");
        rule.setPattern("\\d+\\.\\d+\\.\\d+\\.\\d+");
        rule.setReplacement("<IP>");

        String cleaned = cleaner.cleanLog("connect 192.168.1.1 failed", List.of(rule));
        assertTrue(cleaned.contains("<IP>"));
    }

    @Test
    void drainParserFactoryShouldExposeStats() {
        DrainPlusParser plus = new DrainPlusParser(8, 50);
        DrainParserFactory factory = new DrainParserFactory(plus);
        plus.parse("ERROR user timeout");

        Map<String, Object> stats = factory.getAllStats();
        assertNotNull(stats.get("plus"));
        assertEquals("DrainParserPlus", stats.get("active"));
    }

    @Test
    void realTimeLogAnalyzerLifecycle() {
        RealTimeLogAnalyzer analyzer = new RealTimeLogAnalyzer();
        ReflectionTestUtils.setField(analyzer, "corePoolSize", 1);
        ReflectionTestUtils.setField(analyzer, "bufferThreshold", 10);
        ReflectionTestUtils.setField(analyzer, "logCleaner", new LogCleaner());
        ReflectionTestUtils.setField(analyzer, "drainParserFactory",
                new DrainParserFactory(new DrainPlusParser(8, 50)));

        analyzer.init();
        analyzer.startRealTimeAnalysis();
        analyzer.addLog(Map.of("content", "ERROR timeout", "timestamp", System.currentTimeMillis()));
        analyzer.resetAnalysisState();
        analyzer.stopRealTimeAnalysis();
        analyzer.shutdown();
    }
}
