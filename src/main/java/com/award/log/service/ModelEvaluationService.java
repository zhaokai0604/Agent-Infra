package com.award.log.service;

import java.util.List;
import java.util.Map;

public interface ModelEvaluationService {
    Map<String, Object> evaluate(String modelVersion, List<Map<String, Object>> dataset);
}
