package com.award.log.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 与 {@link AgenticRiskScoreEngine}、巡检自动修复共用口径的风险分 → 信任档位映射。
 */
@Component
public class OpsTrustPolicy {

    private final AgenticRiskScoreEngine agenticRiskScoreEngine;

    @Value("${agent.trust.notify-max:6.0}")
    private double notifyMax;

    public OpsTrustPolicy(AgenticRiskScoreEngine agenticRiskScoreEngine) {
        this.agenticRiskScoreEngine = agenticRiskScoreEngine;
    }

    public OpsTrustTier tierForTool(String toolName, Map<String, Object> parameters, String instruction) {
        AgenticRiskScoreEngine.ScoreResult score =
                agenticRiskScoreEngine.score(toolName, parameters, instruction);
        return tierForScore(score.total());
    }

    public OpsTrustTier tierForScore(double total) {
        double auto = agenticRiskScoreEngine.getAutoMax();
        double confirm = agenticRiskScoreEngine.getConfirmMax();
        if (total > confirm) {
            return OpsTrustTier.BLOCK;
        }
        if (total < auto) {
            return OpsTrustTier.AUTO;
        }
        if (total < notifyMax) {
            return OpsTrustTier.NOTIFY;
        }
        if (total <= confirm) {
            return OpsTrustTier.APPROVE;
        }
        return OpsTrustTier.BLOCK;
    }

    public String tierExplanation(OpsTrustTier tier, double score) {
        return switch (tier) {
            case AUTO -> String.format(Locale.ROOT,
                    "风险分 %.1f < %.0f（AUTO）：策略允许自动执行。", score, agenticRiskScoreEngine.getAutoMax());
            case NOTIFY -> String.format(Locale.ROOT,
                    "风险分 %.1f ∈ [%.0f, %.0f)（NOTIFY）：将执行并在结果中通报。", score,
                    agenticRiskScoreEngine.getAutoMax(), notifyMax);
            case APPROVE -> String.format(Locale.ROOT,
                    "风险分 %.1f：需您回复「执行清理」或「按预览执行」后再删除。", score);
            case BLOCK -> String.format(Locale.ROOT,
                    "风险分 %.1f > %.0f：已拒绝执行。", score, agenticRiskScoreEngine.getConfirmMax());
        };
    }
}
