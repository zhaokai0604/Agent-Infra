package com.award.log.decision;

import lombok.Builder;
import lombok.Getter;

/**
 * 随机森林训练/推理统一特征向量契约（固定6维）
 */
@Getter
@Builder
public class RfFeatureVector {

    public static final int FEATURE_SIZE = 6;

    /**
     * [0] 日志级别分值（FATAL=1.0, ERROR=0.8, WARN=0.5, INFO=0.2, OTHER=0.1）
     */
    private final float levelScore;
    /**
     * [1] 1分钟窗口错误率 [0,1]
     */
    private final float errorRate1m;
    /**
     * [2] 1分钟日志总量归一化 total/200，截断到[0,1]
     */
    private final float totalNorm1m;
    /**
     * [3] 1分钟错误日志量归一化 error/100，截断到[0,1]
     */
    private final float errorNorm1m;
    /**
     * [4] 模板包含exception关键字（1/0）
     */
    private final float templateHasException;
    /**
     * [5] 模板包含timeout关键字（1/0）
     */
    private final float templateHasTimeout;

    public float[] toArray() {
        return new float[]{
                levelScore,
                errorRate1m,
                totalNorm1m,
                errorNorm1m,
                templateHasException,
                templateHasTimeout
        };
    }

    public static RfFeatureVector fromDecisionInput(DecisionInput input) {
        String level = input.getEvent().getLevel();
        String template = input.getTemplate() == null ? "" : input.getTemplate().toLowerCase();
        return RfFeatureVector.builder()
                .levelScore(levelScore(level))
                .errorRate1m((float) input.getErrorRate1m())
                .totalNorm1m(Math.min(1.0f, input.getTotal1m() / 200.0f))
                .errorNorm1m(Math.min(1.0f, input.getError1m() / 100.0f))
                .templateHasException(template.contains("exception") ? 1.0f : 0.0f)
                .templateHasTimeout(template.contains("timeout") ? 1.0f : 0.0f)
                .build();
    }

    private static float levelScore(String level) {
        if ("FATAL".equals(level)) return 1.0f;
        if ("ERROR".equals(level)) return 0.8f;
        if ("WARN".equals(level)) return 0.5f;
        if ("INFO".equals(level)) return 0.2f;
        return 0.1f;
    }
}
