package com.award.log.decision;

import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.DecisionLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class DecisionTraceService {

    private final DecisionLogMapper decisionLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DecisionTraceService(DecisionLogMapper decisionLogMapper) {
        this.decisionLogMapper = decisionLogMapper;
    }

    public String record(DecisionInput input, DecisionResult result, long latencyMs) {
        try {
            DecisionLog log = new DecisionLog();
            String decisionId = UUID.randomUUID().toString().replace("-", "");
            log.setDecisionId(decisionId);
            log.setEngineType(result.getEngineType().name());
            log.setShouldAlert(result.isShouldAlert() ? 1 : 0);
            log.setConfidence(result.getConfidence());
            log.setLatencyMs(latencyMs);
            log.setInputJson(objectMapper.writeValueAsString(input));
            log.setOutputJson(objectMapper.writeValueAsString(result));
            log.setTraceJson(objectMapper.writeValueAsString(Map.of("reason", result.getReason())));
            decisionLogMapper.insert(log);
            return decisionId;
        } catch (Exception e) {
            return "";
        }
    }
}
