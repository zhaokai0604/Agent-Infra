package com.award.log.decision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DecisionResult {
    private EngineType engineType;
    private boolean shouldAlert;
    private double confidence;
    private String featureVersion;
    private String modelVersion;
    private String reason;
    private String recommendation;
}
