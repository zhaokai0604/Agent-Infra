package com.award.log.service;

import java.util.Map;

public interface RuleStatService {
    void record(String ruleId, String ruleName, boolean hit);
    Map<String, Object> summary();
}
