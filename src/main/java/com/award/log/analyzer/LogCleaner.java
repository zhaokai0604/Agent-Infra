package com.award.log.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志清洗器
 * 用于根据规则清洗日志内容
 */
@Slf4j
@Component
public class LogCleaner {

    /**
     * 清洗日志内容
     * @param logContent 原始日志内容
     * @param rules 清洗规则列表
     * @return 清洗后的日志内容
     */
    public String cleanLog(String logContent, List<CleanRule> rules) {
        if (logContent == null || logContent.isEmpty()) {
            return logContent;
        }

        String result = logContent;

        // 应用所有启用的规则
        for (CleanRule rule : rules) {
            if (rule.isEnabled()) {
                result = applyRule(result, rule);
            }
        }

        return result;
    }

    /**
     * 应用单个清洗规则
     * @param content 日志内容
     * @param rule 清洗规则
     * @return 应用规则后的内容
     */
    private String applyRule(String content, CleanRule rule) {
        try {
            switch (rule.getType()) {
                case "regex":
                    return applyRegexRule(content, rule.getPattern(), rule.getReplacement());
                case "string":
                    return applyStringRule(content, rule.getPattern(), rule.getReplacement());
                case "whitespace":
                    return applyWhitespaceRule(content);
                case "time":
                    return applyTimeRule(content, rule.getTimeFormat());
                default:
                    log.warn("Unknown rule type: {}", rule.getType());
                    return content;
            }
        } catch (Exception e) {
            log.error("Error applying rule: {}", e.getMessage(), e);
            return content;
        }
    }

    /**
     * 应用正则替换规则
     */
    private String applyRegexRule(String content, String pattern, String replacement) {
        try {
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(content);
            return matcher.replaceAll(replacement);
        } catch (Exception e) {
            log.error("Invalid regex pattern: {}", pattern, e);
            return content;
        }
    }

    /**
     * 应用字符串替换规则
     */
    private String applyStringRule(String content, String pattern, String replacement) {
        return content.replace(pattern, replacement);
    }

    /**
     * 应用空白字符清理规则
     */
    private String applyWhitespaceRule(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    /**
     * 应用时间格式化规则
     */
    private String applyTimeRule(String content, String timeFormat) {
        // 简单的时间格式化逻辑
        // 实际项目中可能需要更复杂的时间处理
        return content;
    }

    /**
     * 清洗规则类
     */
    public static class CleanRule {
        private String id;
        private String name;
        private String type;
        private String pattern;
        private String replacement;
        private String timeFormat;
        private boolean enabled;

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getReplacement() {
            return replacement;
        }

        public void setReplacement(String replacement) {
            this.replacement = replacement;
        }

        public String getTimeFormat() {
            return timeFormat;
        }

        public void setTimeFormat(String timeFormat) {
            this.timeFormat = timeFormat;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
