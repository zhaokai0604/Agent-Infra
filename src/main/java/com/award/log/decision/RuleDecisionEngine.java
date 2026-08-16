package com.award.log.decision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RuleDecisionEngine {

    @Value("${log.pipeline.decision.rule.high-confidence:0.9}")
    private double highConfidence;

    @Value("${log.pipeline.decision.feature-version:rf-v1}")
    private String featureVersion;

    public DecisionResult evaluate(DecisionInput input) {
        boolean errorLevel = "ERROR".equals(input.getEvent().getLevel()) || "FATAL".equals(input.getEvent().getLevel());
        boolean highRate = input.getErrorRate1m() >= 0.30 && input.getTotal1m() >= 20;
        boolean shouldAlert = errorLevel && highRate;
        return DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(shouldAlert)
                .confidence(shouldAlert ? highConfidence : 0.40)
                .featureVersion(featureVersion)
                .modelVersion("rule-v1")
                .reason("规则判断: 错误级别 + 1min窗口错误率")
                .recommendation("检查最近5分钟同模板错误与实例健康状态")
                .build();
    }
}
