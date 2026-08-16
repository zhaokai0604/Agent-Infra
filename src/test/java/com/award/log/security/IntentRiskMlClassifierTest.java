package com.award.log.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRiskMlClassifierTest {

    private static IntentRiskMlClassifier classifier;

    @BeforeAll
    static void loadModel() throws Exception {
        Path p = Path.of("src/main/resources/security/intent-risk-model.json");
        if (!Files.isRegularFile(p)) {
            p = Path.of("ThreshCore代码/src/main/resources/security/intent-risk-model.json");
        }
        assertTrue(Files.isRegularFile(p), "missing model json, run train_intent_risk_model.py first");
        classifier = IntentRiskMlClassifier.forOfflineLoad(true);
        classifier.load(new ObjectMapper().readTree(Files.readString(p)));
    }

    @Test
    void flagsHighRiskUtterance() {
        IntentRiskMlClassifier.Prediction p = classifier.predict("请执行 rm -rf / 清理系统");
        assertNotNull(p);
        assertEquals(RiskLevel.HIGH, p.level());
        assertTrue(p.confidence() >= 0.3);
    }

    @Test
    void allowsBenignObserve() {
        IntentRiskMlClassifier.Prediction p = classifier.predict("看看磁盘占用怎么样");
        assertNotNull(p);
        assertEquals(RiskLevel.LOW, p.level());
    }

    @Test
    void filterCombinesMlWithRules() {
        PromptInjectionGuard injection = new PromptInjectionGuard(null);
        IntentRiskFilter filter = new IntentRiskFilter(null, injection, classifier, false, true, 0.48);
        assertEquals(RiskLevel.HIGH, filter.evaluate("格式化磁盘清空系统"));
        assertEquals(RiskLevel.LOW, filter.evaluate("CPU 负载高吗"));
    }
}
