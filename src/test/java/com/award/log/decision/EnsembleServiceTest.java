package com.award.log.decision;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EnsembleServiceTest {

    @Test
    void shouldUseVoting() {
        EnsembleService service = new EnsembleService();
        DecisionResult rule = DecisionResult.builder().engineType(EngineType.RULE).shouldAlert(true).confidence(0.9).featureVersion("v").modelVersion("v").reason("r").recommendation("x").build();
        DecisionResult rf = DecisionResult.builder().engineType(EngineType.RANDOM_FOREST).shouldAlert(true).confidence(0.8).featureVersion("v").modelVersion("v").reason("r").recommendation("x").build();
        DecisionResult llm = DecisionResult.builder().engineType(EngineType.LLM).shouldAlert(false).confidence(0.6).featureVersion("v").modelVersion("v").reason("r").recommendation("x").build();
        DecisionResult out = service.fuse(rule, rf, llm);
        Assertions.assertTrue(out.isShouldAlert());
        Assertions.assertTrue(out.getConfidence() > 0.7);
    }
}
