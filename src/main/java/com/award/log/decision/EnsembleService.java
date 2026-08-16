package com.award.log.decision;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EnsembleService {

    public DecisionResult fuse(DecisionResult rule, DecisionResult rf, DecisionResult llm) {
        double weighted = rule.getConfidence() * 0.4 + rf.getConfidence() * 0.35 + llm.getConfidence() * 0.25;
        int alertCount = (rule.isShouldAlert() ? 1 : 0) + (rf.isShouldAlert() ? 1 : 0) + (llm.isShouldAlert() ? 1 : 0);
        boolean shouldAlert = alertCount >= 2;
        DecisionResult winner = rule;
        if (rf.getConfidence() > winner.getConfidence()) {
            winner = rf;
        }
        if (llm.getConfidence() > winner.getConfidence()) {
            winner = llm;
        }
        return DecisionResult.builder()
                .engineType(winner.getEngineType())
                .shouldAlert(shouldAlert)
                .confidence(Math.max(weighted, winner.getConfidence()))
                .featureVersion(winner.getFeatureVersion())
                .modelVersion(winner.getModelVersion())
                .reason("融合投票结果，详细=" + Map.of("rule", rule.isShouldAlert(), "rf", rf.isShouldAlert(), "llm", llm.isShouldAlert()))
                .recommendation(winner.getRecommendation())
                .build();
    }
}
