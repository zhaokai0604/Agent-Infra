package com.award.log.analysis;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogSeverityLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 为 AI 诊断/告警构造更高信噪比的上下文：模板去重、保留时间序、补充正常基线对照。
 */
public final class AiDiagnosisContextBuilder {

    /** 焦点异常条数上限（略高于原 15，便于多样模板） */
    public static final int MAX_FOCUS_LINES = 18;
    /** 正常对照样本条数 */
    public static final int MAX_BASELINE_LINES = 5;
    /** 单条日志写入 prompt 的最大字符（避免超长堆栈撑爆上下文） */
    public static final int MAX_CHARS_PER_FOCUS_LINE = 2200;
    public static final int MAX_CHARS_PER_BASELINE_LINE = 1200;
    /** 告警正文里每条异常示例的最大字符 */
    public static final int ALARM_LOG_SNIPPET_CHARS = 1200;
    /** 补偿告警里每条样例的最大字符 */
    public static final int COMPENSATION_SNIPPET_CHARS = 1200;
    /** 运维对话用户输入长度上限 */
    public static final int CHAT_USER_MESSAGE_MAX_CHARS = 12000;

    private AiDiagnosisContextBuilder() {
    }

    /**
     * 选取用于批量诊断的异常焦点行：按异常分优先 + 模板去重 + 填满上限。
     */
    public static List<EnhancedLogParseResultEntity> selectFocusLines(List<EnhancedLogParseResultEntity> full) {
        if (full == null || full.isEmpty()) {
            return List.of();
        }
        List<EnhancedLogParseResultEntity> candidates = full.stream()
                .filter(l -> l != null && (l.isAnomaly()
                        || l.getSeverity() == LogSeverityLevel.ERROR_LEVEL
                        || l.getSeverity() == LogSeverityLevel.FATAL_LEVEL))
                .sorted(Comparator.comparingDouble(EnhancedLogParseResultEntity::getAnomalyScore).reversed())
                .collect(Collectors.toList());

        List<EnhancedLogParseResultEntity> out = new ArrayList<>(Math.min(MAX_FOCUS_LINES, candidates.size()));
        Set<String> seenTemplate = new LinkedHashSet<>();
        Set<String> takenKeys = new LinkedHashSet<>();
        for (EnhancedLogParseResultEntity e : candidates) {
            if (out.size() >= MAX_FOCUS_LINES) {
                break;
            }
            String tid = e.getTemplateId() != null ? e.getTemplateId() : "";
            if (seenTemplate.size() < 14 && seenTemplate.add(tid) && takenKeys.add(lineKey(e))) {
                out.add(e);
            }
        }
        for (EnhancedLogParseResultEntity e : candidates) {
            if (out.size() >= MAX_FOCUS_LINES) {
                break;
            }
            if (takenKeys.add(lineKey(e))) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingLong(AiDiagnosisContextBuilder::sortOrderKey));
        return out;
    }

    /**
     * 选取若干「未判异常且非 ERROR/FATAL」行作为对照基线，均匀抽样。
     */
    public static List<EnhancedLogParseResultEntity> selectBaselineLines(List<EnhancedLogParseResultEntity> full) {
        if (full == null || full.isEmpty()) {
            return List.of();
        }
        List<EnhancedLogParseResultEntity> pool = full.stream()
                .filter(Objects::nonNull)
                .filter(l -> !l.isAnomaly()
                        && l.getSeverity() != LogSeverityLevel.ERROR_LEVEL
                        && l.getSeverity() != LogSeverityLevel.FATAL_LEVEL)
                .filter(l -> l.getDesensitizedLog() != null && !l.getDesensitizedLog().isBlank())
                .collect(Collectors.toList());
        if (pool.isEmpty()) {
            return List.of();
        }
        if (pool.size() <= MAX_BASELINE_LINES) {
            return new ArrayList<>(pool);
        }
        List<EnhancedLogParseResultEntity> picks = new ArrayList<>(MAX_BASELINE_LINES);
        int n = pool.size();
        int[] idx = {0, n / 4, n / 2, (3 * n) / 4, n - 1};
        Set<Integer> used = new LinkedHashSet<>();
        for (int i : idx) {
            int j = Math.max(0, Math.min(n - 1, i));
            if (used.add(j)) {
                picks.add(pool.get(j));
            }
            if (picks.size() >= MAX_BASELINE_LINES) {
                break;
            }
        }
        for (int k = 0; k < n && picks.size() < MAX_BASELINE_LINES; k++) {
            if (used.add(k)) {
                picks.add(pool.get(k));
            }
        }
        picks.sort(Comparator.comparingLong(AiDiagnosisContextBuilder::sortOrderKey));
        return picks;
    }

    public static String truncateForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…(已截断，原文长度 " + text.length() + " 字符)";
    }

    private static String lineKey(EnhancedLogParseResultEntity e) {
        if (e.getSourceLineIndex() >= 0) {
            return "i:" + e.getSourceLineIndex();
        }
        long ts = e.getTimestamp();
        String log = e.getDesensitizedLog() != null ? e.getDesensitizedLog() : "";
        return "f:" + ts + ":" + log.hashCode();
    }

    private static long sortOrderKey(EnhancedLogParseResultEntity e) {
        if (e.getSourceLineIndex() >= 0) {
            return e.getSourceLineIndex();
        }
        return e.getTimestamp();
    }
}
