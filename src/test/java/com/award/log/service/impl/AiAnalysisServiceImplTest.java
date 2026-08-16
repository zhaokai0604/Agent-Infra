package com.award.log.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AiAnalysisServiceImplTest {

    private AiAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiAnalysisServiceImpl(null);
        ReflectionTestUtils.setField(service, "apiKey", "");
    }

    @Test
    void analyzeLogShouldReportNotReadyWithoutApiKey() {
        String result = service.analyzeLog("NullPointerException in handler");
        assertTrue(result.contains("未就绪"));
    }

    @Test
    void analyzePerformanceShouldReportNotReadyWithoutApiKey() {
        String result = service.analyzePerformance("cpuUsage", 92.5);
        assertTrue(result.contains("未就绪"));
    }

    @Test
    void analyzeAlertShouldReportNotReadyWithoutApiKey() {
        String result = service.analyzeAlert("disk usage > 90%");
        assertTrue(result.contains("未就绪"));
    }

    @Test
    void generateReportShouldReportNotReadyWithoutApiKey() {
        String result = service.generateReport("24h");
        assertTrue(result.contains("未就绪"));
    }
}
