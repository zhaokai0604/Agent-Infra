package com.award.log.rule.dsl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class RuleExpressionEvaluatorTest {

    @Test
    void shouldEvaluateCompositeExpression() {
        RuleExpressionEvaluator evaluator = new RuleExpressionEvaluator();
        boolean matched = evaluator.evaluate(
                "LEVEL IN (ERROR, FATAL) AND (RATE > 0.3 OR COUNT > 10) AND NOT SUPPRESSED",
                Map.of("LEVEL", "ERROR", "RATE", 0.35, "COUNT", 3, "SUPPRESSED", false)
        );
        Assertions.assertTrue(matched);
    }
}
