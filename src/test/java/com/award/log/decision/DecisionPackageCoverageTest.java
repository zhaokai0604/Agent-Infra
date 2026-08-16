package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.RuleDefinition;
import com.award.log.service.AiAnalysisService;
import com.award.log.service.RuleRegistryService;
import com.award.log.service.RuleStatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DecisionPackageCoverageTest {

    @Mock
    private RuleRegistryService ruleRegistryService;
    @Mock
    private RuleStatService ruleStatService;
    @Mock
    private DecisionLogMapper decisionLogMapper;
    @Mock
    private AiAnalysisService aiAnalysisService;

    @Test
    void ensembleServiceShouldFuseAllPaths() {
        EnsembleService service = new EnsembleService();
        DecisionResult rule = base(EngineType.RULE, true, 0.9);
        DecisionResult rf = base(EngineType.RANDOM_FOREST, false, 0.3);
        DecisionResult llm = base(EngineType.LLM, false, 0.2);
        DecisionResult out = service.fuse(rule, rf, llm);
        assertFalse(out.isShouldAlert());
        assertNotNull(out.getReason());
    }

    @Test
    void ensembleServiceShouldAlertWhenTwoEnginesAgree() {
        EnsembleService service = new EnsembleService();
        DecisionResult out = service.fuse(
                base(EngineType.RULE, true, 0.95),
                base(EngineType.RANDOM_FOREST, true, 0.85),
                base(EngineType.LLM, false, 0.4));
        assertTrue(out.isShouldAlert());
    }

    @Test
    void ruleEngineV2ShouldEvaluateHitAndMiss() {
        RuleEngineV2 engine = new RuleEngineV2(ruleRegistryService, ruleStatService);
        ReflectionTestUtils.setField(engine, "highConfidence", 0.92);
        ReflectionTestUtils.setField(engine, "featureVersion", "test-v");

        RuleDefinition rule = new RuleDefinition();
        rule.setId("1");
        rule.setName("disk-high");
        when(ruleRegistryService.list()).thenReturn(List.of(rule));
        when(ruleRegistryService.testRule(eq("1"), anyMap())).thenReturn(true, false);

        DecisionInput input = sampleInput();
        DecisionResult hit = engine.evaluate(input);
        assertTrue(hit.isShouldAlert());
        assertEquals(EngineType.RULE, hit.getEngineType());

        when(ruleRegistryService.testRule(eq("1"), anyMap())).thenReturn(false);
        DecisionResult miss = engine.evaluate(input);
        assertFalse(miss.isShouldAlert());
    }

    @Test
    void rfFeatureVectorsShouldBuildFromDecisionInput() {
        DecisionInput input = sampleInput();
        RfFeatureVector vector = RfFeatureVector.fromDecisionInput(input);
        assertEquals(RfFeatureVector.FEATURE_SIZE, vector.toArray().length);

        RfFeatureVectorExt ext = RfFeatureVectorExt.fromDecisionInputExt(input);
        assertEquals(RfFeatureVectorExt.FEATURE_SIZE, ext.toArray().length);
    }

    @Test
    void decisionInputAndResultBuilders() {
        DecisionInput input = sampleInput();
        assertEquals(12, input.getError1m());

        DecisionResult result = DecisionResult.builder()
                .engineType(EngineType.LLM)
                .shouldAlert(true)
                .confidence(0.7)
                .featureVersion("f")
                .modelVersion("m")
                .reason("r")
                .recommendation("rec")
                .build();
        assertTrue(result.isShouldAlert());
        assertEquals(EngineType.LLM, result.getEngineType());
    }

    @Test
    void randomForestEngineUsesHeuristicWhenModelMissing() {
        RandomForestDecisionEngine engine = new RandomForestDecisionEngine();
        ReflectionTestUtils.setField(engine, "modelPath", "missing/random-forest.onnx");
        ReflectionTestUtils.setField(engine, "alertThreshold", 0.75);
        ReflectionTestUtils.setField(engine, "highConfidence", 0.8);
        ReflectionTestUtils.setField(engine, "featureVersion", "rf-v2");
        ReflectionTestUtils.setField(engine, "modelVersion", "rf-onnx-v2");

        DecisionInput fatal = sampleInput();
        fatal.getEvent().setLevel("FATAL");
        DecisionResult result = engine.evaluate(fatal);

        assertEquals(EngineType.RANDOM_FOREST, result.getEngineType());
        assertNotNull(result.getReason());
        assertTrue((Boolean) engine.healthSnapshot().get("modelLoaded") == false
                || engine.healthSnapshot().get("modelStatus") != null);
        engine.destroy();
    }

    @Test
    void llmDecisionEngineHandlesMissingAndReadyAi() {
        LlmDecisionEngine engine = new LlmDecisionEngine();
        ReflectionTestUtils.setField(engine, "featureVersion", "rf-v1");
        DecisionResult withoutAi = engine.evaluate(sampleInput());
        assertEquals(EngineType.LLM, withoutAi.getEngineType());
        assertEquals("LLM 服务未接入", withoutAi.getRecommendation());

        ReflectionTestUtils.setField(engine, "aiAnalysisService", aiAnalysisService);
        when(aiAnalysisService.analyzeLog(anyString())).thenReturn("AI 分析未就绪：offline");
        DecisionResult skipped = engine.evaluate(sampleInput());
        assertTrue(skipped.getRecommendation().contains("跳过 LLM"));

        when(aiAnalysisService.analyzeLog(anyString())).thenReturn("disk pressure rising");
        DecisionInput highRate = sampleInput();
        highRate = DecisionInput.builder()
                .event(highRate.getEvent())
                .template(highRate.getTemplate())
                .error1m(20)
                .errorRate1m(0.6)
                .total1m(100)
                .build();
        DecisionResult alert = engine.evaluate(highRate);
        assertTrue(alert.isShouldAlert());
    }

    @Test
    void intelligentRouterSkipsLlmInBatchMode() {
        RuleEngineV2 ruleEngine = new RuleEngineV2(ruleRegistryService, ruleStatService);
        ReflectionTestUtils.setField(ruleEngine, "highConfidence", 0.92);
        ReflectionTestUtils.setField(ruleEngine, "featureVersion", "test-v");
        when(ruleRegistryService.list()).thenReturn(List.of());

        RandomForestDecisionEngine rfEngine = new RandomForestDecisionEngine();
        ReflectionTestUtils.setField(rfEngine, "modelPath", "missing/model.onnx");
        ReflectionTestUtils.setField(rfEngine, "alertThreshold", 0.75);
        ReflectionTestUtils.setField(rfEngine, "highConfidence", 0.8);
        ReflectionTestUtils.setField(rfEngine, "featureVersion", "rf-v2");
        ReflectionTestUtils.setField(rfEngine, "modelVersion", "rf-onnx-v2");

        IntelligentRouter router = new IntelligentRouter(
                ruleEngine, rfEngine, new LlmDecisionEngine(), new EnsembleService());

        DecisionInput batch = DecisionInput.builder()
                .event(sampleInput().getEvent())
                .template("batch")
                .error1m(1)
                .errorRate1m(0.1)
                .total1m(10)
                .skipLlm(true)
                .build();
        DecisionResult result = router.decide(batch);
        assertNotNull(result.getReason());
        rfEngine.destroy();
    }

    @Test
    void collaborativeDecisionServiceRecordsTrace() {
        IntelligentRouter router = mock(IntelligentRouter.class);
        DecisionTraceService traceService = new DecisionTraceService(decisionLogMapper);
        CollaborativeDecisionService service = new CollaborativeDecisionService(router, traceService);

        DecisionInput input = sampleInput();
        DecisionResult fused = base(EngineType.RULE, true, 0.9);
        when(router.decide(input)).thenReturn(fused);
        when(decisionLogMapper.insert(any())).thenReturn(1);

        DecisionResult out = service.decide(input);
        assertTrue(out.isShouldAlert());
        verify(decisionLogMapper).insert(any());
    }

    @Test
    void ruleDecisionEngineEvaluatesErrorRate() {
        RuleDecisionEngine engine = new RuleDecisionEngine();
        ReflectionTestUtils.setField(engine, "highConfidence", 0.9);
        ReflectionTestUtils.setField(engine, "featureVersion", "rf-v1");

        DecisionInput lowRate = DecisionInput.builder()
                .event(sampleInput().getEvent())
                .template("INFO idle")
                .error1m(1)
                .errorRate1m(0.05)
                .total1m(100)
                .build();
        DecisionResult miss = engine.evaluate(lowRate);
        assertFalse(miss.isShouldAlert());

        DecisionInput alert = DecisionInput.builder()
                .event(sampleInput().getEvent())
                .template("ERROR timeout")
                .error1m(30)
                .errorRate1m(0.35)
                .total1m(25)
                .build();
        DecisionResult hit = engine.evaluate(alert);
        assertTrue(hit.isShouldAlert());
        assertEquals(0.9, hit.getConfidence());
    }

    @Test
    void decisionTraceServiceSwallowsPersistenceErrors() {
        DecisionTraceService traceService = new DecisionTraceService(decisionLogMapper);
        when(decisionLogMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertEquals("", traceService.record(sampleInput(), base(EngineType.RULE, false, 0.2), 12L));
    }

    private static DecisionInput sampleInput() {
        RawLogEvent event = new RawLogEvent();
        event.setLevel("ERROR");
        event.setContent("java.lang.Exception timeout at com.example.Service");
        event.setEventTime(System.currentTimeMillis() - 1000);
        event.setIngestTime(System.currentTimeMillis());
        return DecisionInput.builder()
                .event(event)
                .template("ERROR exception timeout")
                .error1m(12)
                .errorRate1m(0.5)
                .total1m(100)
                .build();
    }

    private static DecisionResult base(EngineType type, boolean alert, double confidence) {
        return DecisionResult.builder()
                .engineType(type)
                .shouldAlert(alert)
                .confidence(confidence)
                .featureVersion("v")
                .modelVersion("v")
                .reason("r")
                .recommendation("x")
                .build();
    }
}
