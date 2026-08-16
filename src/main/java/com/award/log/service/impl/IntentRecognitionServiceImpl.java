package com.award.log.service.impl;

import com.award.log.service.IntentRecognitionService;
import com.award.log.service.IntentRecognitionService.Intent;
import com.award.log.service.IntentRecognitionService.RecognitionResult;
import com.award.log.service.IntentRecognitionService.TimeRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class IntentRecognitionServiceImpl implements IntentRecognitionService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(最近|过去|上方)?(\\d+)(分钟|小时|天|周|月)|" +
            "(\\d{4}[-/]\\d{2}[-/]\\d{2}[\\s\\d:：]*至[\\s\\d:：]*\\d{4}[-/]\\d{2}[-/]\\d{2})|" +
            "(今天|昨天|本周|上周|本月的|上个月)|" +
            "(\\d{4}[-/]\\d{2}[-/]\\d{2}[\\s\\d:：]*)"
    );

    private static final Pattern ERROR_KEYWORDS = Pattern.compile(
            "错误|异常|失败|故障|危急|严重|fatal|error|exception|failed|critical|warn|警告"
    );

    private static final Pattern DIAGNOSE_KEYWORDS = Pattern.compile(
            "诊断|分析|原因|根因|为什么|怎么回事|问题|故障定位|排查"
    );

    private static final Pattern REPORT_KEYWORDS = Pattern.compile(
            "报告|总结|概览|报表|生成报告"
    );

    private static final Pattern STATISTICS_KEYWORDS = Pattern.compile(
            "统计|数量|多少|分布|趋势|占比|百分比"
    );

    private static final Pattern SERVICE_KEYWORDS = Pattern.compile(
            "mysql|redis|nginx|kafka|es|elasticsearch|docker|kubernetes|k8s|mongodb|postgresql|api|gateway|auth|login|search"
    );

    @Override
    public RecognitionResult recognize(String userQuery) {
        log.info("开始意图识别: [{}]", userQuery);

        Intent intent = determineIntent(userQuery);
        TimeRange timeRange = extractTimeRange(userQuery);
        List<String> keywords = extractKeywords(userQuery);
        String targetService = extractTargetService(userQuery);

        String refinedQuery = refineQuery(userQuery, intent, timeRange, keywords);

        RecognitionResult result = new RecognitionResult(intent, userQuery);
        result.setTimeRange(timeRange);
        result.setKeywords(keywords);
        result.setTargetService(targetService);
        result.setRefinedQuery(refinedQuery);

        log.info("意图识别完成: intent={}, timeRange={}, keywords={}, targetService={}",
                intent, timeRange, keywords, targetService);

        return result;
    }

    private Intent determineIntent(String query) {
        String lowerQuery = query.toLowerCase();

        if (DIAGNOSE_KEYWORDS.matcher(lowerQuery).find()) {
            return Intent.DIAGNOSE_ISSUE;
        }

        if (REPORT_KEYWORDS.matcher(lowerQuery).find()) {
            return Intent.GENERATE_REPORT;
        }

        if (STATISTICS_KEYWORDS.matcher(lowerQuery).find()) {
            return Intent.STATISTICS;
        }

        if (ERROR_KEYWORDS.matcher(lowerQuery).find()) {
            if (lowerQuery.contains("最近") || lowerQuery.contains("小时") ||
                lowerQuery.contains("分钟") || lowerQuery.contains("今天")) {
                return Intent.QUERY_ANOMALIES;
            }
            return Intent.QUERY_ERRORS;
        }

        if (lowerQuery.contains("最近") || lowerQuery.contains("出现了") ||
            lowerQuery.contains("查看") || lowerQuery.contains("查询")) {
            return Intent.QUERY_ANOMALIES;
        }

        if (lowerQuery.contains("帮我") || lowerQuery.contains("请") ||
            lowerQuery.contains("如何") || lowerQuery.contains("怎么")) {
            return Intent.HELP;
        }

        return Intent.UNKNOWN;
    }

    private TimeRange extractTimeRange(String query) {
        Matcher matcher = TIME_PATTERN.matcher(query);

        LocalDateTime now = LocalDateTime.now();
        String startTime = null;
        String endTime = null;
        String duration = null;

        while (matcher.find()) {
            String match = matcher.group();

            if (match.contains("最近") || match.contains("过去")) {
                int num = extractNumber(match);
                String unit = extractUnit(match);
                duration = num + unit;
                startTime = calculateStartTime(now, num, unit);
                endTime = now.format(FORMATTER);
                break;
            }

            if (match.contains("分钟")) {
                int minutes = extractNumber(match);
                duration = minutes + "分钟";
                startTime = now.minusMinutes(minutes).format(FORMATTER);
                endTime = now.format(FORMATTER);
                break;
            }

            if (match.contains("小时")) {
                int hours = extractNumber(match);
                duration = hours + "小时";
                startTime = now.minusHours(hours).format(FORMATTER);
                endTime = now.format(FORMATTER);
                break;
            }

            if (match.contains("天")) {
                int days = extractNumber(match);
                duration = days + "天";
                startTime = now.minusDays(days).format(FORMATTER);
                endTime = now.format(FORMATTER);
                break;
            }

            if (match.contains("今天")) {
                startTime = now.toLocalDate().atStartOfDay().format(FORMATTER);
                endTime = now.format(FORMATTER);
                duration = "今天";
                break;
            }

            if (match.contains("昨天")) {
                startTime = now.minusDays(1).toLocalDate().atStartOfDay().format(FORMATTER);
                endTime = now.minusDays(1).toLocalDate().atTime(23, 59, 59).format(FORMATTER);
                duration = "昨天";
                break;
            }
        }

        if (startTime == null) {
            startTime = now.minusHours(1).format(FORMATTER);
            endTime = now.format(FORMATTER);
            duration = "最近1小时";
        }

        return new TimeRange(startTime, endTime, duration);
    }

    private int extractNumber(String text) {
        Pattern p = Pattern.compile("\\d+");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group());
        }
        return 1;
    }

    private String extractUnit(String text) {
        if (text.contains("分钟")) return "分钟";
        if (text.contains("小时")) return "小时";
        if (text.contains("天")) return "天";
        if (text.contains("周")) return "周";
        if (text.contains("月")) return "月";
        return "";
    }

    private String calculateStartTime(LocalDateTime now, int num, String unit) {
        LocalDateTime start;
        switch (unit) {
            case "分钟":
                start = now.minusMinutes(num);
                break;
            case "小时":
                start = now.minusHours(num);
                break;
            case "天":
                start = now.minusDays(num);
                break;
            case "周":
                start = now.minusWeeks(num);
                break;
            case "月":
                start = now.minusMonths(num);
                break;
            default:
                start = now.minusHours(1);
        }
        return start.format(FORMATTER);
    }

    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();

        if (ERROR_KEYWORDS.matcher(query).find()) {
            if (query.toLowerCase().contains("fatal") || query.contains("致命")) {
                keywords.add("FATAL");
            }
            if (query.toLowerCase().contains("error") || query.contains("错误")) {
                keywords.add("ERROR");
            }
            if (query.toLowerCase().contains("exception") || query.contains("异常")) {
                keywords.add("EXCEPTION");
            }
            if (query.toLowerCase().contains("warn") || query.contains("警告")) {
                keywords.add("WARNING");
            }
        }

        Matcher serviceMatcher = SERVICE_KEYWORDS.matcher(query.toLowerCase());
        while (serviceMatcher.find()) {
            keywords.add(serviceMatcher.group().toUpperCase());
        }

        return keywords;
    }

    private String extractTargetService(String query) {
        Matcher matcher = SERVICE_KEYWORDS.matcher(query.toLowerCase());
        if (matcher.find()) {
            return matcher.group().toLowerCase();
        }
        return null;
    }

    private String refineQuery(String query, Intent intent, TimeRange timeRange, List<String> keywords) {
        StringBuilder refined = new StringBuilder();

        switch (intent) {
            case QUERY_ANOMALIES:
                refined.append("查询异常日志");
                if (timeRange != null && timeRange.getDuration() != null) {
                    refined.append(", 时间范围: ").append(timeRange.getDuration());
                }
                if (!keywords.isEmpty()) {
                    refined.append(", 关键词: ").append(String.join(", ", keywords));
                }
                break;

            case QUERY_ERRORS:
                refined.append("查询错误日志");
                if (timeRange != null) {
                    refined.append(", 从 ").append(timeRange.getStartTime())
                           .append(" 到 ").append(timeRange.getEndTime());
                }
                break;

            case DIAGNOSE_ISSUE:
                refined.append("诊断分析: ").append(query);
                break;

            case GENERATE_REPORT:
                refined.append("生成报告");
                if (timeRange != null) {
                    refined.append(", 报告周期: ").append(timeRange.getDuration());
                }
                break;

            case STATISTICS:
                refined.append("统计分析");
                if (!keywords.isEmpty()) {
                    refined.append(", 关注点: ").append(String.join(", ", keywords));
                }
                break;

            default:
                refined.append(query);
        }

        return refined.toString();
    }
}
