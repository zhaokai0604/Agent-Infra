package com.award.log.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据用户意图风险 + utterance 文本特征，决定 MCP 工具面（全量 / 只读）。
 * <p>
 * 默认策略：HTTP 层已拦截 {@link RiskLevel#HIGH} 的聊天请求后，{@code MEDIUM} 意图不再强制只读，
 * 以便智能体在路径白名单与工具层风控下调用清理、日志裁剪、服务重启等写类 MCP；
 * 生产极保守模式可将 {@code agent.security.medium-intent-shrink-to-readonly} 设为 {@code true}。
 */
@Component
public class ToolSurfaceResolver {

    private final AgenticRiskScoreEngine agenticRiskScoreEngine;

    /**
     * utterance 特征分 ≥ 该阈值时会话收缩为只读（与 MEDIUM 意图叠加策略：见 {@link #resolve}）。
     */
    @Value("${agent.security.read-only-surface-utterance-min:4.5}")
    private double readOnlyUtteranceMin;

    /**
     * 为 true 时 {@link RiskLevel#MEDIUM} 仍映射只读工具面（与旧版一致）。
     */
    @Value("${agent.security.medium-intent-shrink-to-readonly:false}")
    private boolean mediumIntentShrinkToReadonly;

    /**
     * 为 true 时仅对 {@link RiskLevel#LOW} 做 utterance 高分收缩；为 false 时任意非 HIGH 意图均可能因高分 utterance 只读。
     */
    @Value("${agent.security.read-only-utterance-applies-to-low-intent-only:true}")
    private boolean readOnlyUtteranceAppliesToLowIntentOnly;

    public ToolSurfaceResolver(AgenticRiskScoreEngine agenticRiskScoreEngine) {
        this.agenticRiskScoreEngine = agenticRiskScoreEngine;
    }

    public McpToolSurface resolve(String userMessage, RiskLevel intentRisk) {
        if (intentRisk == RiskLevel.HIGH) {
            return McpToolSurface.READ_ONLY;
        }
        if (intentRisk == RiskLevel.MEDIUM) {
            return mediumIntentShrinkToReadonly ? McpToolSurface.READ_ONLY : McpToolSurface.FULL;
        }
        boolean allowUtteranceShrink = !readOnlyUtteranceAppliesToLowIntentOnly || intentRisk == RiskLevel.LOW;
        if (!allowUtteranceShrink) {
            return McpToolSurface.FULL;
        }
        String norm = AgenticRiskScoreEngine.normalizeUtterance(userMessage);
        if (norm.isEmpty()) {
            return McpToolSurface.FULL;
        }
        AgenticRiskScoreEngine.ScoreResult utter = agenticRiskScoreEngine.scoreNaturalLanguageUtterance(norm);
        if (utter.total() >= readOnlyUtteranceMin) {
            return McpToolSurface.READ_ONLY;
        }
        return McpToolSurface.FULL;
    }
}
