package com.award.log.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlaybookClassifierTest {

    private LlmPlaybookClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new LlmPlaybookClassifier(new ObjectMapper());
        classifier.setEnabledForTest(true);
        classifier.setMinConfidenceForTest(0.62);
    }

    @Test
    void parsesDiskCleanupJson() {
        Optional<OpsIntentRouter.Playbook> pb = classifier.parsePlaybook(
                "{\"playbook\":\"DISK_CLEANUP\",\"confidence\":0.91,\"reason\":\"空间告警\"}");
        assertEquals(OpsIntentRouter.Playbook.DISK_CLEANUP, pb.orElse(null));
    }

    @Test
    void rejectsLowConfidence() {
        assertTrue(classifier.parsePlaybook(
                "{\"playbook\":\"CPU_PRESSURE\",\"confidence\":0.4,\"reason\":\"含糊\"}").isEmpty());
    }

    @Test
    void rejectsNoneAndIllegalIntentLabel() {
        assertTrue(classifier.parsePlaybook(
                "{\"playbook\":\"NONE\",\"confidence\":0.99,\"reason\":\"闲聊\"}").isEmpty());
        assertTrue(classifier.parsePlaybook(
                "{\"playbook\":\"HACK_SYSTEM\",\"confidence\":0.99,\"reason\":\"x\"}").isEmpty());
    }

    @Test
    void extractsFencedJson() {
        String raw = "好的\n```json\n{\"playbook\":\"PATROL_AUTOMATION\",\"confidence\":0.8,\"reason\":\"体检\"}\n```\n";
        assertEquals(OpsIntentRouter.Playbook.PATROL_AUTOMATION,
                classifier.parsePlaybook(raw).orElse(null));
    }
}
