package com.award.log.service.impl;

import com.award.log.agent.OpsReportFormat;
import com.award.log.model.LogAnalysisDetail;
import com.award.log.model.LogDocument;
import com.award.log.service.AiTool;
import com.award.log.service.LogQueryFallbackService;
import com.award.log.service.StatisticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SystemDiagnosticTool implements AiTool {

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private LogQueryFallbackService logQueryFallbackService;

    @Autowired
    private StatisticsService statisticsService;

    @Override
    public String getName() {
        return "diagnose_system";
    }

    @Override
    public String getDescription() {
        return "对系统进行综合诊断分析，包括CPU、内存、磁盘等性能指标，以及错误日志趋势、异常检测等，帮助定位系统问题根因。";
    }

    @Override
    public String getParameterDescription() {
        return "{\"timeRange\":\"诊断时间范围，如'1小时'、'30分钟'、'今天'等(可选，默认1小时)\", \"focusArea\":\"诊断重点，可选值:'performance'(性能)、'errors'(错误)、'anomaly'(异常)、'all'(全部，默认all)\"}";
    }

    @Override
    public Map<String, Object> getFunctionSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("name", getName());
        schema.put("description", getDescription());

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> timeRange = new HashMap<>();
        timeRange.put("type", "string");
        timeRange.put("description", "诊断时间范围，如'1小时'、'30分钟'、'今天'、'昨天'、'过去3天'等");
        properties.put("timeRange", timeRange);

        Map<String, Object> focusArea = new HashMap<>();
        focusArea.put("type", "string");
        focusArea.put("description", "诊断重点：performance(性能)、errors(错误)、anomaly(异常)、all(全部)");
        focusArea.put("default", "all");
        properties.put("focusArea", focusArea);

        schema.put("parameters", properties);
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            String timeRange = getStringParam(parameters, "timeRange", "1小时");
            String focusArea = getStringParam(parameters, "focusArea", "all");

            LocalDateTime[] timeRange2 = parseTimeRange(timeRange);
            LocalDateTime startTime = timeRange2[0];
            LocalDateTime endTime = timeRange2[1];
            int windowDays = estimateWindowDays(timeRange);

            StringBuilder diagnosis = new StringBuilder();
            diagnosis.append("## 系统诊断报告\n\n");
            diagnosis.append(OpsReportFormat.tableHeaderLine("项目", "值"));
            diagnosis.append("| 诊断起始 | `")
                    .append(startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append("` |\n");
            diagnosis.append("| 诊断结束 | `")
                    .append(endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    .append("` |\n\n");

            if ("all".equals(focusArea) || "performance".equals(focusArea)) {
                diagnosis.append("### 实时性能\n\n");
                diagnosis.append(getPerformanceDiagnosis());
                diagnosis.append("\n");
            }

            if ("all".equals(focusArea) || "errors".equals(focusArea)) {
                diagnosis.append("### 错误日志分析\n\n");
                diagnosis.append(getErrorDiagnosis(startTime, endTime, windowDays));
                diagnosis.append("\n");
            }

            if ("all".equals(focusArea) || "anomaly".equals(focusArea)) {
                diagnosis.append("### 异常检测分析\n\n");
                diagnosis.append(getAnomalyDiagnosis(startTime, endTime, windowDays));
                diagnosis.append("\n");
            }

            diagnosis.append("### 诊断结论\n\n");
            diagnosis.append(generateConclusion(startTime, endTime, windowDays));

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("timeRange", timeRange);
            resultData.put("startTime", startTime);
            resultData.put("endTime", endTime);
            resultData.put("focusArea", focusArea);

            return ToolResult.success(diagnosis.toString(), resultData);

        } catch (Exception e) {
            log.error("系统诊断工具执行失败", e);
            return ToolResult.error("诊断失败: " + e.getMessage());
        }
    }

    private String getPerformanceDiagnosis() {
        try {
            Map<String, Object> perf = statisticsService.getSystemPerformance(null);
            double cpu = toDouble(perf.get("cpuUsage"));
            double mem = toDouble(perf.get("memoryUsage"));
            double disk = toDouble(perf.get("diskUsage"));
            StringBuilder sb = new StringBuilder();
            sb.append(OpsReportFormat.tableHeaderLine("指标", "数值"));
            sb.append(String.format(Locale.ROOT, "| CPU | `%.1f%%` |\n", cpu));
            sb.append(String.format(Locale.ROOT, "| 内存 | `%.1f%%` |\n", mem));
            sb.append(String.format(Locale.ROOT, "| 磁盘 | `%.1f%%` |\n", disk));
            if (cpu >= 85 || mem >= 88 || disk >= 85) {
                sb.append("\n> **【WARN】** 存在资源压力，建议结合巡检与运维工具进一步定位.\n");
            } else {
                sb.append("\n> **【INFO】** 当前资源指标在正常范围内.\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "> 性能数据采集失败: `" + e.getMessage() + "`\n";
        }
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String getErrorDiagnosis(LocalDateTime startTime, LocalDateTime endTime, int windowDays) {
        StringBuilder result = new StringBuilder();

        try {
            List<LogDocument> allLogs = loadLogs(startTime, endTime, windowDays, null, false, 500);

            List<LogDocument> fatalLogs = allLogs.stream()
                    .filter(l -> "FATAL".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(10)
                    .collect(Collectors.toList());

            List<LogDocument> errorLogs = allLogs.stream()
                    .filter(l -> "ERROR".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(20)
                    .collect(Collectors.toList());

            if (fatalLogs.isEmpty() && errorLogs.isEmpty()) {
                result.append("> **【INFO】** 未检测到 `ERROR` 或 `FATAL` 级别日志, 系统运行正常.\n");
                return result.toString();
            }

            if (!fatalLogs.isEmpty()) {
                result.append(OpsReportFormat.formatFindingLine("HIGH", "FATAL 级别错误",
                        String.format(Locale.ROOT, "共 `%d` 条", fatalLogs.size())));
                for (LogDocument log : fatalLogs.stream().limit(3).collect(Collectors.toList())) {
                    String content = truncate(log.getContent(), 100);
                    result.append("- `")
                            .append(log.getTimestamp() != null
                                    ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                    : "N/A")
                            .append("`: ").append(content).append("\n");
                }
                if (fatalLogs.size() > 3) {
                    result.append("- 另有 `").append(fatalLogs.size() - 3).append("` 条 FATAL 日志\n");
                }
                result.append("\n");
            }

            if (!errorLogs.isEmpty()) {
                result.append(OpsReportFormat.formatFindingLine("WARN", "ERROR 级别错误",
                        String.format(Locale.ROOT, "共 `%d` 条", errorLogs.size())));

                Map<String, Long> errorDistribution = errorLogs.stream()
                        .filter(log -> log.getContent() != null)
                        .collect(Collectors.groupingBy(
                                log -> extractErrorType(log.getContent()),
                                Collectors.counting()
                        ));

                result.append(OpsReportFormat.tableHeaderLine("错误类型", "次数"));
                errorDistribution.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(entry -> result.append("| `").append(entry.getKey()).append("` | `")
                                .append(entry.getValue()).append("` |\n"));

                result.append("\n最近 5 条 ERROR 日志:\n");
                for (LogDocument log : errorLogs.stream().limit(5).collect(Collectors.toList())) {
                    String content = truncate(log.getContent(), 80);
                    result.append("- `")
                            .append(log.getTimestamp() != null
                                    ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                    : "N/A")
                            .append("`: ").append(content).append("\n");
                }
            }

        } catch (Exception e) {
            result.append("错误日志分析失败: ").append(e.getMessage());
        }

        return result.toString();
    }

    private String getAnomalyDiagnosis(LocalDateTime startTime, LocalDateTime endTime, int windowDays) {
        StringBuilder result = new StringBuilder();

        try {
            List<LogDocument> anomalyLogs = loadLogs(startTime, endTime, windowDays, null, true, 20);

            if (anomalyLogs.isEmpty()) {
                result.append("> **【INFO】** 未检测到异常行为日志.\n");
                return result.toString();
            }

            result.append(OpsReportFormat.formatFindingLine("WARN", "异常日志",
                    String.format(Locale.ROOT, "共 `%d` 条", anomalyLogs.size())));

            anomalyLogs.stream().limit(10).forEach(log -> {
                String content = truncate(log.getContent(), 100);
                result.append("- 分数 `")
                        .append(log.getAnomalyScore() != null
                                ? String.format(Locale.ROOT, "%.2f", log.getAnomalyScore())
                                : "N/A")
                        .append("` | `")
                        .append(log.getTimestamp() != null
                                ? log.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                : "N/A")
                        .append("`: ").append(content).append("\n");
            });

            if (anomalyLogs.size() > 10) {
                result.append("\n> 另有 `").append(anomalyLogs.size() - 10).append("` 条异常日志未列出.\n");
            }

        } catch (Exception e) {
            result.append("异常检测分析失败: ").append(e.getMessage());
        }

        return result.toString();
    }

    private String generateConclusion(LocalDateTime startTime, LocalDateTime endTime, int windowDays) {
        StringBuilder conclusion = new StringBuilder();

        try {
            List<LogDocument> allLogs = loadLogs(startTime, endTime, windowDays, null, false, 500);

            List<LogDocument> fatalLogs = allLogs.stream()
                    .filter(l -> "FATAL".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(1)
                    .collect(Collectors.toList());

            List<LogDocument> errorLogs = allLogs.stream()
                    .filter(l -> "ERROR".equalsIgnoreCase(extractSeverity(l.getSeverity())))
                    .limit(10)
                    .collect(Collectors.toList());

            List<LogDocument> anomalyLogs = allLogs.stream()
                    .filter(l -> l.getAnomaly() != null && l.getAnomaly())
                    .limit(1)
                    .collect(Collectors.toList());

            boolean hasIssues = !fatalLogs.isEmpty() || errorLogs.size() > 5 || !anomalyLogs.isEmpty();

            if (!hasIssues) {
                conclusion.append("> **【INFO】** 系统运行状态正常.\n\n");
                conclusion.append("在诊断时间范围内, 未检测到严重错误或异常行为. 建议:\n");
                conclusion.append("- 继续保持当前系统运行状态\n");
                conclusion.append("- 定期查看系统健康报告\n");
            } else {
                conclusion.append(OpsReportFormat.formatFindingLine("WARN", "系统存在潜在问题", "建议关注以下事项"));

                if (!fatalLogs.isEmpty()) {
                    conclusion.append("- **【HIGH】** 检测到 `").append(fatalLogs.size())
                            .append("` 条 FATAL 级别错误, 需要立即处理\n");
                }

                if (errorLogs.size() > 5) {
                    conclusion.append("- **【WARN】** ERROR 级别错误较多 (共 `").append(errorLogs.size())
                            .append("` 条), 建议详细分析\n");
                }

                if (!anomalyLogs.isEmpty()) {
                    conclusion.append("- **【WARN】** 检测到 `").append(anomalyLogs.size())
                            .append("` 条异常日志, 可能存在潜在风险\n");
                }

                conclusion.append("\n建议操作:\n");
                conclusion.append("1. 详细查看上述错误日志分析\n");
                conclusion.append("2. 使用「帮我分析这些错误的原因」获取详细诊断\n");
                conclusion.append("3. 检查相关服务的运行状态\n");
            }

        } catch (Exception e) {
            conclusion.append("无法生成诊断结论: ").append(e.getMessage());
        }

        return conclusion.toString();
    }

    private List<LogDocument> loadLogs(
            LocalDateTime startTime,
            LocalDateTime endTime,
            int windowDays,
            String severity,
            boolean anomalyOnly,
            int limit) {
        List<LogDocument> fromEs = anomalyOnly
                ? queryAnomalyLogsFromEs(startTime, endTime)
                : queryLogsFromEs(startTime, endTime);
        if (!fromEs.isEmpty()) {
            return fromEs;
        }
        return logQueryFallbackService.queryRecent(windowDays, severity, null, anomalyOnly, limit)
                .stream()
                .map(this::toLogDocument)
                .toList();
    }

    private LogDocument toLogDocument(LogAnalysisDetail row) {
        LogDocument doc = new LogDocument();
        doc.setContent(row.getDesensitizedLog());
        doc.setSeverity(row.getSeverity());
        doc.setProtocol(row.getProtocol());
        doc.setAnomaly(Boolean.TRUE.equals(row.getIsAnomaly()));
        doc.setAnomalyScore(row.getAnomalyScore());
        return doc;
    }

    private List<LogDocument> queryLogsFromEs(LocalDateTime startTime, LocalDateTime endTime) {
        if (elasticsearchOperations == null) {
            return List.of();
        }
        try {
            Criteria criteria = new Criteria("timestamp").between(startTime, endTime);
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(500);

            SearchHits<LogDocument> searchHits = elasticsearchOperations.search(query, LogDocument.class);

            List<LogDocument> logs = new ArrayList<>();
            for (SearchHit<LogDocument> hit : searchHits.getSearchHits()) {
                try {
                    logs.add(hit.getContent());
                } catch (Exception e) {
                    log.warn("跳过无法解析的日志记录: {}", e.getMessage());
                }
            }
            return logs;
        } catch (Exception e) {
            log.warn("ES 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<LogDocument> queryAnomalyLogsFromEs(LocalDateTime startTime, LocalDateTime endTime) {
        if (elasticsearchOperations == null) {
            return List.of();
        }
        try {
            Criteria criteria = new Criteria("timestamp").between(startTime, endTime)
                    .and(new Criteria("anomaly").is(true));
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setMaxResults(20);

            SearchHits<LogDocument> searchHits = elasticsearchOperations.search(query, LogDocument.class);

            List<LogDocument> logs = new ArrayList<>();
            for (SearchHit<LogDocument> hit : searchHits.getSearchHits()) {
                try {
                    logs.add(hit.getContent());
                } catch (Exception e) {
                    log.warn("跳过无法解析的异常日志记录: {}", e.getMessage());
                }
            }
            return logs;
        } catch (Exception e) {
            log.warn("ES 异常日志查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private int estimateWindowDays(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return 1;
        }
        if (timeRange.contains("月")) {
            return 30;
        }
        if (timeRange.contains("周")) {
            return 7;
        }
        if (timeRange.contains("天")) {
            return Math.max(1, extractNumber(timeRange));
        }
        return 1;
    }

    private String extractSeverity(String severity) {
        if (severity == null) return "UNKNOWN";
        if (severity.contains("FATAL")) return "FATAL";
        if (severity.contains("ERROR")) return "ERROR";
        if (severity.contains("WARN")) return "WARNING";
        if (severity.contains("INFO")) return "INFO";
        return "UNKNOWN";
    }

    private String extractErrorType(String content) {
        if (content == null) return "未知错误";

        String[] patterns = {
                "NullPointerException", "空指针异常",
                "Connection refused", "连接被拒绝",
                "Timeout", "超时",
                "OutOfMemory", "内存溢出",
                "FileNotFound", "文件未找到",
                "Permission denied", "权限拒绝",
                "SQLException", "数据库异常",
                "IOException", "IO异常"
        };

        for (int i = 0; i < patterns.length; i += 2) {
            if (content.contains(patterns[i])) {
                return patterns[i + 1];
            }
        }

        return content.length() > 30 ? content.substring(0, 30) + "..." : content;
    }

    private LocalDateTime[] parseTimeRange(String timeRange) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime;

        if (timeRange == null || timeRange.isEmpty() || timeRange.contains("1小时")) {
            startTime = now.minusHours(1);
        } else if (timeRange.contains("30分钟")) {
            startTime = now.minusMinutes(30);
        } else if (timeRange.contains("2小时")) {
            startTime = now.minusHours(2);
        } else if (timeRange.contains("今天")) {
            startTime = now.toLocalDate().atStartOfDay();
        } else if (timeRange.contains("昨天")) {
            startTime = now.minusDays(1).toLocalDate().atStartOfDay();
        } else if (timeRange.contains("天")) {
            int days = extractNumber(timeRange);
            startTime = now.minusDays(days);
        } else if (timeRange.contains("周")) {
            startTime = now.minusWeeks(1);
        } else if (timeRange.contains("月")) {
            startTime = now.minusMonths(1);
        } else {
            startTime = now.minusHours(1);
        }

        return new LocalDateTime[]{startTime, now};
    }

    private int extractNumber(String text) {
        return text.replaceAll("[^0-9]", "").isEmpty() ? 1 :
                Integer.parseInt(text.replaceAll("[^0-9]", ""));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}