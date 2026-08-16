package com.award.log.service;

import com.award.log.model.RuleDefinition;

import java.util.List;
import java.util.Map;

public interface RuleRegistryService {
    List<RuleDefinition> list();

    RuleDefinition save(RuleDefinition rule);

    boolean delete(String id);

    boolean testRule(String id, Map<String, Object> payload);
}
