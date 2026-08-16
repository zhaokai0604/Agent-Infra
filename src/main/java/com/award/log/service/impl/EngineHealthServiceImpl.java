package com.award.log.service.impl;

import com.award.log.analyzer.DrainParserFactory;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.model.RuleDefinition;
import com.award.log.service.EngineHealthService;
import com.award.log.service.ExperimentService;
import com.award.log.service.RuleRegistryService;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class EngineHealthServiceImpl implements EngineHealthService {

    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final RuleRegistryService ruleRegistryService;
    private final ExperimentService experimentService;
    private final OpsDryRunProperties opsDryRunProperties;

    @Autowired(required = false)
    private DrainParserFactory drainParserFactory;

    @Autowired(required = false)
    private ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    private volatile Map<String, Object> latest = new HashMap<>();

    public EngineHealthServiceImpl(RandomForestDecisionEngine randomForestDecisionEngine,
                                   RuleRegistryService ruleRegistryService,
                                   ExperimentService experimentService,
                                   OpsDryRunProperties opsDryRunProperties) {
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.ruleRegistryService = ruleRegistryService;
        this.experimentService = experimentService;
        this.opsDryRunProperties = opsDryRunProperties;
    }

    @PostConstruct
    void warmSnapshotOnStartup() {
        collect();
    }

    @Scheduled(fixedDelay = 30000)
    public void collect() {
        long enabledRules = ruleRegistryService.list().stream().filter(RuleDefinition::isEnabled).count();
        Map<String, Object> m = new HashMap<>();
        m.put("ruleEngine", Map.of("ruleCount", ruleRegistryService.list().size(), "enabledRules", enabledRules));
        m.put("randomForest", randomForestDecisionEngine.healthSnapshot());
        m.put("llm", llmSnapshot());
        m.put("drain", drainSnapshot());
        m.put("opsDryRunGlobal", opsDryRunProperties.isGlobalDryRun());
        m.put("abExperiment", experimentService.report());
        latest = m;
    }

    private Map<String, Object> llmSnapshot() {
        boolean configured = chatClient != null && aiApiKey != null && !aiApiKey.isBlank();
        Map<String, Object> llm = new HashMap<>();
        llm.put("configured", configured);
        llm.put("availability", configured ? 1.0 : 0.0);
        return llm;
    }

    private Map<String, Object> drainSnapshot() {
        Map<String, Object> drain = new HashMap<>();
        if (drainParserFactory == null) {
            drain.put("templateCount", 0);
            drain.put("parseSuccessRate", 0.0);
            drain.put("status", "unavailable");
            return drain;
        }
        Map<String, Object> all = drainParserFactory.getAllStats();
        Object plus = all.get("plus");
        int templates = 0;
        if (plus instanceof Map<?, ?> pm) {
            Object t = pm.get("totalTemplates");
            if (t instanceof Number n) {
                templates = n.intValue();
            }
        }
        drain.put("templateCount", templates);
        drain.put("parseSuccessRate", templates > 0 ? 1.0 : 0.0);
        drain.put("activeParser", all.get("active"));
        drain.put("stats", plus);
        return drain;
    }

    @Override
    public Map<String, Object> snapshot() {
        return latest;
    }
}
