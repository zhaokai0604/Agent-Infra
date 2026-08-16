package com.award.log.rule.dsl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended coverage for rule.dsl expression combinations beyond {@link RuleExpressionEvaluatorTest}.
 */
class RuleDslPackageCoverageTest {

    private RuleExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RuleExpressionEvaluator();
    }

    @Test
    void inConditionShouldMatchCaseInsensitively() {
        assertTrue(evaluator.evaluate("LEVEL IN (ERROR, FATAL)", Map.of("LEVEL", "error")));
        assertFalse(evaluator.evaluate("LEVEL IN (ERROR, FATAL)", Map.of("LEVEL", "INFO")));
    }

    @Test
    void numericComparisonsShouldSupportGtAndLt() {
        assertTrue(evaluator.evaluate("RATE > 0.5", Map.of("RATE", 0.51)));
        assertFalse(evaluator.evaluate("RATE > 0.5", Map.of("RATE", 0.5)));
        assertTrue(evaluator.evaluate("COUNT < 10", Map.of("COUNT", 9)));
        assertFalse(evaluator.evaluate("COUNT < 10", Map.of("COUNT", 10)));
    }

    @Test
    void eqIdentAndFlagConditionsShouldEvaluate() {
        assertTrue(evaluator.evaluate("LEVEL = ERROR", Map.of("LEVEL", "ERROR")));
        assertFalse(evaluator.evaluate("LEVEL = ERROR", Map.of("LEVEL", "WARN")));
        assertTrue(evaluator.evaluate("SUPPRESSED", Map.of("SUPPRESSED", true)));
        assertFalse(evaluator.evaluate("SUPPRESSED", Map.of("SUPPRESSED", false)));
    }

    @Test
    void notAndGroupedExpressionsShouldCombine() {
        assertTrue(evaluator.evaluate("NOT SUPPRESSED", Map.of("SUPPRESSED", false)));
        assertFalse(evaluator.evaluate("NOT SUPPRESSED", Map.of("SUPPRESSED", true)));
        assertTrue(evaluator.evaluate("(LEVEL = ERROR)", Map.of("LEVEL", "ERROR")));
    }

    @Test
    void orExpressionShouldShortCircuitEitherBranch() {
        assertTrue(evaluator.evaluate("RATE > 1 OR COUNT > 5", Map.of("RATE", 0.1, "COUNT", 6)));
        assertTrue(evaluator.evaluate("RATE > 1 OR COUNT > 5", Map.of("RATE", 2.0, "COUNT", 1)));
        assertFalse(evaluator.evaluate("RATE > 1 OR COUNT > 5", Map.of("RATE", 0.1, "COUNT", 1)));
    }

    @Test
    void andExpressionRequiresAllBranches() {
        assertTrue(evaluator.evaluate("LEVEL IN (ERROR) AND RATE > 0.2 AND NOT SUPPRESSED",
                Map.of("LEVEL", "ERROR", "RATE", 0.3, "SUPPRESSED", false)));
        assertFalse(evaluator.evaluate("LEVEL IN (ERROR) AND RATE > 0.2 AND NOT SUPPRESSED",
                Map.of("LEVEL", "ERROR", "RATE", 0.1, "SUPPRESSED", false)));
    }

    @Test
    void nestedCompositeExpressionMatchesProductionRules() {
        boolean matched = evaluator.evaluate(
                "LEVEL IN (ERROR, FATAL) AND (RATE > 0.3 OR COUNT > 10) AND NOT SUPPRESSED",
                Map.of("LEVEL", "FATAL", "RATE", 0.1, "COUNT", 11, "SUPPRESSED", false));
        assertTrue(matched);

        boolean missed = evaluator.evaluate(
                "LEVEL IN (ERROR, FATAL) AND (RATE > 0.3 OR COUNT > 10) AND NOT SUPPRESSED",
                Map.of("LEVEL", "INFO", "RATE", 0.9, "COUNT", 99, "SUPPRESSED", false));
        assertFalse(missed);
    }

    @Test
    void expressionShouldNormalizeCaseAndWhitespace() {
        assertTrue(evaluator.evaluate("  level in (error) and rate > 0.1  ", Map.of("LEVEL", "ERROR", "RATE", 0.2)));
    }

    @Test
    void missingContextValuesShouldUseDefaults() {
        assertFalse(evaluator.evaluate("RATE > 0.1", Map.of()));
        assertFalse(evaluator.evaluate("LEVEL = ERROR", Map.of()));
        assertFalse(evaluator.evaluate("SUPPRESSED", Map.of()));
    }
}
