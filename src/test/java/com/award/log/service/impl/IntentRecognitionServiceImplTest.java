package com.award.log.service.impl;

import com.award.log.service.IntentRecognitionService.Intent;
import com.award.log.service.IntentRecognitionService.RecognitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class IntentRecognitionServiceImplTest {

    private IntentRecognitionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IntentRecognitionServiceImpl();
    }

    @Test
    void recognizeDiagnoseIntent() {
        RecognitionResult result = service.recognize("请诊断最近1小时的错误原因");
        assertEquals(Intent.DIAGNOSE_ISSUE, result.getIntent());
        assertNotNull(result.getTimeRange());
    }

    @Test
    void recognizeReportIntent() {
        RecognitionResult result = service.recognize("生成过去7天的日志报告");
        assertEquals(Intent.GENERATE_REPORT, result.getIntent());
    }

    @Test
    void recognizeErrorQueryIntent() {
        RecognitionResult result = service.recognize("查询最近30分钟的error日志");
        assertTrue(result.getIntent() == Intent.QUERY_ERRORS || result.getIntent() == Intent.QUERY_ANOMALIES);
        assertFalse(result.getKeywords().isEmpty());
    }

    @Test
    void recognizeShouldExtractMysqlService() {
        RecognitionResult result = service.recognize("mysql连接失败异常统计");
        assertEquals("mysql", result.getTargetService());
    }
}
