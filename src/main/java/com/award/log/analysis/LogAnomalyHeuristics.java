package com.award.log.analysis;

import com.award.log.analyzer.DefaultCleanRules;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志异常检测启发式（关键词 / 频次突发 / 综合得分），供 MCP、实时分析与批量任务共用，避免三套逻辑漂移。
 */
public final class LogAnomalyHeuristics {

    /** 关键词权重达到该阈值则记为异常命中（通用日志场景略抬高，减少泛词误报） */
    public static final int KEYWORD_WEIGHT_THRESHOLD = 8;

    private static final Map<String, Integer> KEYWORD_CATEGORY_WEIGHTS = buildKeywordCategoryWeights();

    private LogAnomalyHeuristics() {
    }

    private static Map<String, Integer> buildKeywordCategoryWeights() {
        Map<String, Integer> m = new HashMap<>();
        m.put("致命错误", 10);
        m.put("内存异常", 9);
        m.put("认证失败", 8);
        m.put("网络异常", 7);
        m.put("数据库异常", 8);
        m.put("系统异常", 7);
        m.put("安全异常", 9);
        m.put("性能异常", 6);
        m.put("应用异常", 8);
        m.put("Linux系统与内核", 8);
        return m;
    }

    /**
     * 单行粗粒度等级推断（模板解析前即可使用），用于综合得分与罕见模板判定。
     */
    public static LogSeverityLevel inferSeverityFromLine(String line) {
        return GeneralLogSeverityResolver.resolve(line);
    }

    /**
     * 关键词异常：使用 {@link DefaultCleanRules#getEnhancedKeywords()} 与统一权重表。
     */
    public static void applyKeywordDetection(List<EnhancedLogParseResultEntity> resultList) {
        Map<String, List<String>> keywordsMap = DefaultCleanRules.getEnhancedKeywords();
        for (EnhancedLogParseResultEntity res : resultList) {
            String logContent = res.getDesensitizedLog().toLowerCase();
            int maxWeight = 0;
            String maxKeyword = "";
            String maxCategory = "";

            for (Map.Entry<String, List<String>> entry : keywordsMap.entrySet()) {
                String category = entry.getKey();
                Integer categoryWeight = KEYWORD_CATEGORY_WEIGHTS.get(category);
                if (categoryWeight == null) {
                    continue;
                }
                for (String keyword : entry.getValue()) {
                    if (logContent.contains(keyword.toLowerCase())) {
                        if (categoryWeight > maxWeight) {
                            maxWeight = categoryWeight;
                            maxKeyword = keyword;
                            maxCategory = category;
                        }
                    }
                }
            }

            if (maxWeight >= KEYWORD_WEIGHT_THRESHOLD) {
                res.getAnomalyReasons().add("异常关键词匹配：" + maxCategory + " - " + maxKeyword + " (权重: " + maxWeight + ")");
                res.setAnomaly(true);
                double currentScore = res.getAnomalyScore();
                double weightAdjustment = maxWeight / 10.0 * 0.3;
                res.setAnomalyScore(Math.min(1.0, currentScore + weightAdjustment));
            }
        }
    }

    /**
     * 频次异常：检测「连续日志片段内同模板突发」与「罕见但等级很高」的模板；
     * 不再把「全局高频模板」当作异常（否则会误伤大量重复 INFO）。
     */
    public static void applyFrequencyDetection(List<EnhancedLogParseResultEntity> resultList) {
        int n = resultList.size();
        if (n == 0) {
            return;
        }

        Map<String, Integer> globalFreqMap = new HashMap<>();
        for (EnhancedLogParseResultEntity res : resultList) {
            String tid = res.getTemplateId();
            globalFreqMap.put(tid, globalFreqMap.getOrDefault(tid, 0) + 1);
        }

        final int consecutiveWindow = 14;
        final int burstMin = Math.max(6, consecutiveWindow / 2);

        for (int i = 0; i < n; i++) {
            EnhancedLogParseResultEntity res = resultList.get(i);
            String tempId = res.getTemplateId();

            int start = Math.max(0, i - consecutiveWindow + 1);
            int sameInWindow = 0;
            for (int j = start; j <= i; j++) {
                if (tempId.equals(resultList.get(j).getTemplateId())) {
                    sameInWindow++;
                }
            }
            LogSeverityLevel sevBurst = res.getSeverity() != null ? res.getSeverity() : LogSeverityLevel.UNKNOWN_LEVEL;
            if (sameInWindow >= burstMin) {
                // 通用混合日志里 INFO/UNKNOWN 模板重复（访问日志、心跳）极为常见，不作为突发异常
                if (sevBurst != LogSeverityLevel.INFO_LEVEL
                        && sevBurst != LogSeverityLevel.DEBUG_LEVEL
                        && sevBurst != LogSeverityLevel.UNKNOWN_LEVEL) {
                    res.getAnomalyReasons().add(
                            "时间窗口突发（连续日志）：最近 " + consecutiveWindow + " 行内同模板出现 " + sameInWindow + " 次");
                    res.setAnomaly(true);
                }
            }

            int globalFreq = globalFreqMap.getOrDefault(tempId, 0);
            LogSeverityLevel sev = res.getSeverity() != null ? res.getSeverity() : LogSeverityLevel.UNKNOWN_LEVEL;
            if (globalFreq <= 2 && (sev == LogSeverityLevel.FATAL_LEVEL || sev == LogSeverityLevel.ERROR_LEVEL)) {
                res.getAnomalyReasons().add("罕见严重异常：模板出现频次低但严重程度高");
                res.setAnomaly(true);
            }
        }
    }

    /**
     * 综合得分与按等级的异常标记策略（与实时分析引擎对齐）。
     */
    public static void calcComprehensiveAnomalyScore(List<EnhancedLogParseResultEntity> resultList) {
        Map<String, Double> templateAvgScoreMap = new HashMap<>();
        Map<String, Integer> templateCountMap = new HashMap<>();

        for (EnhancedLogParseResultEntity res : resultList) {
            String tempId = res.getTemplateId();
            double currentScore = res.getAnomalyScore();
            templateAvgScoreMap.put(tempId, templateAvgScoreMap.getOrDefault(tempId, 0.0) + currentScore);
            templateCountMap.put(tempId, templateCountMap.getOrDefault(tempId, 0) + 1);
        }

        for (Map.Entry<String, Double> entry : templateAvgScoreMap.entrySet()) {
            String tempId = entry.getKey();
            double sum = entry.getValue();
            int count = templateCountMap.get(tempId);
            templateAvgScoreMap.put(tempId, sum / count);
        }

        for (EnhancedLogParseResultEntity res : resultList) {
            double baseScore = res.getAnomalyScore();
            String tempId = res.getTemplateId();
            LogSeverityLevel severity = res.getSeverity() != null ? res.getSeverity() : LogSeverityLevel.UNKNOWN_LEVEL;

            double templateAvgScore = templateAvgScoreMap.getOrDefault(tempId, 0.0);
            double scoreDeviation = Math.abs(baseScore - templateAvgScore);

            int reasonCount = res.getAnomalyReasons().size();
            double reasonWeight = 1 + reasonCount * 0.15;
            double severityWeight = severity.getWeightFactor();

            double contextWeight = 1.0;
            for (String reason : res.getAnomalyReasons()) {
                if (reason.contains("时间窗口") || reason.contains("时序")) {
                    contextWeight += 0.2;
                }
            }

            double finalScore = baseScore * severityWeight * reasonWeight * contextWeight;
            if (scoreDeviation > 0.3) {
                finalScore *= (1 + scoreDeviation * 0.3);
            }

            res.setAnomalyScore(Math.min(1.0, finalScore));

            // 综合分只抬升异常标记，绝不抹掉关键词/频次等前期已认定的异常
            if (severity == LogSeverityLevel.FATAL_LEVEL || severity == LogSeverityLevel.ERROR_LEVEL) {
                if (finalScore > 0.65) {
                    res.setAnomaly(true);
                }
            } else if (severity == LogSeverityLevel.WARNING_LEVEL) {
                if (finalScore > 0.85 && reasonCount >= 2) {
                    res.setAnomaly(true);
                }
            } else {
                boolean hasSeriousReason = res.getAnomalyReasons().stream()
                        .anyMatch(reason -> reason.contains("致命") || reason.contains("内存溢出") || reason.contains("认证失败"));

                if (finalScore > 0.95 && hasSeriousReason) {
                    res.setAnomaly(true);
                } else if (severity == LogSeverityLevel.DEBUG_LEVEL && !res.isAnomaly()) {
                    res.getAnomalyReasons().clear();
                }
            }
        }
    }
}
