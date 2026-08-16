package com.award.log.service.impl;

import com.award.log.analyzer.DrainParserFactory;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.model.RuleDefinition;
import com.award.log.service.ExperimentService;
import com.award.log.service.RuleRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineHealthServiceImplTest {

    @Mock
    private RandomForestDecisionEngine randomForestDecisionEngine;
    @Mock
    private RuleRegistryService ruleRegistryService;
    @Mock
    private ExperimentService experimentService;
    @Mock
    private OpsDryRunProperties opsDryRunProperties;
    @Mock
    private DrainParserFactory drainParserFactory;
    @Mock
    private ChatClient chatClient;

    private EngineHealthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EngineHealthServiceImpl(
                randomForestDecisionEngine,
                ruleRegistryService,
                experimentService,
                opsDryRunProperties);
    }

    @Test
    void collectAndSnapshotShouldAggregateEngineHealth() {
        RuleDefinition enabled = new RuleDefinition();
        enabled.setEnabled(true);
        RuleDefinition disabled = new RuleDefinition();
        disabled.setEnabled(false);
        when(ruleRegistryService.list()).thenReturn(List.of(enabled, disabled));
        when(randomForestDecisionEngine.healthSnapshot()).thenReturn(Map.of("modelLoaded", true));
        when(experimentService.report()).thenReturn(Map.of("experiments", 0));
        when(opsDryRunProperties.isGlobalDryRun()).thenReturn(true);
        ReflectionTestUtils.setField(service, "aiApiKey", "sk-test");
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "drainParserFactory", drainParserFactory);
        when(drainParserFactory.getAllStats()).thenReturn(Map.of(
                "active", "plus",
                "plus", Map.of("totalTemplates", 12)));

        service.collect();
        Map<String, Object> snap = service.snapshot();

        @SuppressWarnings("unchecked")
        Map<String, Object> ruleEngine = (Map<String, Object>) snap.get("ruleEngine");
        assertEquals(2, ruleEngine.get("ruleCount"));
        assertEquals(1L, ruleEngine.get("enabledRules"));
        assertTrue((Boolean) snap.get("opsDryRunGlobal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) snap.get("llm");
        assertTrue((Boolean) llm.get("configured"));
        @SuppressWarnings("unchecked")
        Map<String, Object> drain = (Map<String, Object>) snap.get("drain");
        assertEquals(12, drain.get("templateCount"));
    }

    @Test
    void drainSnapshotShouldReportUnavailableWhenFactoryMissing() {
        when(ruleRegistryService.list()).thenReturn(List.of());
        when(randomForestDecisionEngine.healthSnapshot()).thenReturn(Map.of());
        when(experimentService.report()).thenReturn(Map.of());
        when(opsDryRunProperties.isGlobalDryRun()).thenReturn(false);
        ReflectionTestUtils.setField(service, "aiApiKey", "");

        service.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> drain = (Map<String, Object>) service.snapshot().get("drain");

        assertEquals("unavailable", drain.get("status"));
        assertEquals(0, drain.get("templateCount"));
    }
}
