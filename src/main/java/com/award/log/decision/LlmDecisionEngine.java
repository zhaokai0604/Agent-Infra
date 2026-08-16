package com.award.log.decision;

import com.award.log.service.AiAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LlmDecisionEngine {

    @Autowired(required = false)
    private AiAnalysisService aiAnalysisService;

    @org.springframework.beans.factory.annotation.Value("${log.pipeline.decision.feature-version:rf-v1}")
    private String featureVersion;

    public DecisionResult evaluate(DecisionInput input) {
        String analysis = aiAnalysisService == null
                ? "LLM 服务未接入"
                : aiAnalysisService.analyzeLog(input.getEvent().getContent());
        if (analysis != null && analysis.startsWith("AI 分析未就绪")) {
            analysis = "当前环境未配置 AI，已跳过 LLM 兜底建议。";
        }
        boolean shouldAlert = input.getErrorRate1m() >= 0.5 || "FATAL".equals(input.getEvent().getLevel());
        return DecisionResult.builder()
                .engineType(EngineType.LLM)
                .shouldAlert(shouldAlert)
                .confidence(0.65)
                .featureVersion(featureVersion)
                .modelVersion("llm-v1")
                .reason("低置信或未知场景由大模型兜底")
                .recommendation(analysis)
                .build();
    }
}
