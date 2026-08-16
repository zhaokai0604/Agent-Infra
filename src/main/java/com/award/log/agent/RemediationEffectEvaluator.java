package com.award.log.agent;

import com.award.log.service.StatisticsService;
import com.award.log.util.TimeSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自愈效果评估：对比修复前后关键指标，生成 0–100 评分供审计与迭代。
 */
@Component
@RequiredArgsConstructor
public class RemediationEffectEvaluator {

    private final StatisticsService statisticsService;
    private final TimeSource timeSource;

    public Map<String, Object> captureMetrics() {
        Map<String, Object> snap = new LinkedHashMap<>();
        try {
            Map<String, Object> perf = statisticsService.getSystemPerformance(null);
            if (perf != null) {
                snap.put("diskUsagePct", toDouble(perf.get("diskUsage")));
                snap.put("cpuUsagePct", toDouble(perf.get("cpuUsage")));
                snap.put("memoryUsagePct", toDouble(perf.get("memoryUsage")));
            }
        } catch (Exception ignored) {
            snap.put("error", "metrics_unavailable");
        }
        snap.put("capturedAtMs", timeSource.currentTimeMillis());
        return snap;
    }

    public Map<String, Object> evaluate(Map<String, Object> before, Map<String, Object> after, int remediationsExecuted) {
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("before", before != null ? before : Map.of());
        effect.put("after", after != null ? after : Map.of());
        effect.put("remediationsExecuted", remediationsExecuted);

        double diskBefore = toDouble(before != null ? before.get("diskUsagePct") : null);
        double diskAfter = toDouble(after != null ? after.get("diskUsagePct") : null);
        double cpuBefore = toDouble(before != null ? before.get("cpuUsagePct") : null);
        double cpuAfter = toDouble(after != null ? after.get("cpuUsagePct") : null);

        double diskDelta = diskBefore - diskAfter;
        double cpuDelta = cpuBefore - cpuAfter;
        effect.put("diskUsageDeltaPct", round2(diskDelta));
        effect.put("cpuUsageDeltaPct", round2(cpuDelta));

        int score = 50;
        if (remediationsExecuted > 0) {
            score += 10;
        }
        if (diskDelta > 0.5) {
            score += (int) Math.min(25, diskDelta * 2);
        }
        if (cpuDelta > 0.5) {
            score += (int) Math.min(15, cpuDelta);
        }
        if (diskAfter > diskBefore + 1) {
            score -= 15;
        }
        score = Math.max(0, Math.min(100, score));
        effect.put("healingScore", score);
        effect.put("verdict", score >= 70 ? "effective" : score >= 45 ? "partial" : "uncertain");
        return effect;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
