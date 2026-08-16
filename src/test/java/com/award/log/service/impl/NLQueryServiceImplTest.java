package com.award.log.service.impl;

import com.award.log.service.IntentRecognitionService.Intent;
import com.award.log.service.IntentRecognitionService.RecognitionResult;
import com.award.log.service.NLQueryService.QueryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NLQueryServiceImplTest {

    private NLQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NLQueryServiceImpl();
    }

    @Test
    void buildQueryShouldPopulateContext() {
        RecognitionResult recognition = new RecognitionResult(Intent.QUERY_ERRORS, "error logs");
        recognition.setKeywords(List.of("ERROR"));
        recognition.setTargetService("nginx");

        QueryContext context = service.buildQuery(recognition);

        assertNotNull(context.getElasticsearchQuery());
        assertNotNull(context.getGeneratedLuceneQuery());
        assertNotNull(context.getExplanation());
        assertTrue(context.getGeneratedLuceneQuery().contains("severity"));
    }

    @Test
    void generateLuceneQueryForStatistics() {
        RecognitionResult recognition = new RecognitionResult(Intent.STATISTICS, "统计");
        String query = service.generateLuceneQuery(recognition);
        assertEquals("*:*", query);
    }

    @Test
    void generateElasticsearchDslShouldDelegateToBuildQuery() {
        RecognitionResult recognition = new RecognitionResult(Intent.QUERY_ERRORS, "错误");
        recognition.setKeywords(List.of("ERROR"));
        String dsl = service.generateElasticsearchDsl(recognition);
        assertNotNull(dsl);
        assertTrue(dsl.contains("severity"));
    }

    @Test
    void generateLuceneQueryShouldAppendProtocolFilter() {
        RecognitionResult recognition = new RecognitionResult(Intent.QUERY_ANOMALIES, "异常");
        recognition.setKeywords(List.of("ERROR"));
        recognition.setTargetService("redis");
        String query = service.generateLuceneQuery(recognition);
        assertTrue(query.contains("protocol:redis"));
    }
}
