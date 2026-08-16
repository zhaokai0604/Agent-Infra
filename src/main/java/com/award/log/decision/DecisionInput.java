package com.award.log.decision;

import com.award.log.collector.model.RawLogEvent;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DecisionInput {
    private RawLogEvent event;
    private String template;
    private double errorRate1m;
    private int total1m;
    private int error1m;
    /** 批量日志分析等场景跳过 LLM，避免每条异常日志触发一次大模型调用 */
    @Builder.Default
    private boolean skipLlm = false;
}
