package com.award.log.mcp.tools;

import com.award.log.agent.DrainTemplateNoveltyTracker;
import com.award.log.analysis.GeneralLogSeverityResolver;
import com.award.log.analysis.LinuxLogParseSupport;
import com.award.log.analysis.LogAnomalyHeuristics;
import com.award.log.analysis.LogFieldPlaceholder;
import com.award.log.analysis.LogLineParseSupport;
import com.award.log.analyzer.DefaultCleanRules;
import com.award.log.analyzer.DrainPlusParser;
import com.award.log.analyzer.LogCleaner;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.model.LogProtocolType;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.McpToolResponses;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.mcp.McpToolsConfig;
import com.award.log.security.OpsPathPolicy;
import com.award.log.util.LogTailReader;
import com.award.log.util.OsRuntime;
import com.award.log.service.AiDiagnosisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;

@Slf4j
@Component
public class LogAnalysisTool extends AbstractCommandExecutor {

    private final ObjectMapper objectMapper;
    private final DrainPlusParser drainPlusParser;
    private final AiDiagnosisService aiDiagnosisService;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsPathPolicy opsPathPolicy;
    private final LogCleaner logCleaner;
    private final DrainTemplateNoveltyTracker drainTemplateNoveltyTracker;

    @Autowired
    public LogAnalysisTool(
            @Qualifier("mcpToolExecutorService") ExecutorService mcpToolExecutor,
            ObjectMapper objectMapper,
            DrainPlusParser drainPlusParser,
            AiDiagnosisService aiDiagnosisService,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsPathPolicy opsPathPolicy,
            LogCleaner logCleaner,
            DrainTemplateNoveltyTracker drainTemplateNoveltyTracker) {
        super(mcpToolExecutor);
        this.objectMapper = objectMapper;
        this.drainPlusParser = drainPlusParser;
        this.aiDiagnosisService = aiDiagnosisService;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsPathPolicy = opsPathPolicy;
        this.logCleaner = logCleaner;
        this.drainTemplateNoveltyTracker = drainTemplateNoveltyTracker;
    }

    @Tool(name = "analyzeLogs", description = "分析系统日志：可选时间窗与关键词过滤；Drain 模板统计含「本轮新见模板」计数，供 Agent 主动汇报异常模式演化")
    @Cacheable(
            value = McpToolsConfig.CACHE_LOG_ANALYSIS,
            key = "'analyzeLogs|' + T(java.util.Objects).toString(#logPath) + '|' + T(java.util.Objects).toString(#lines) "
                    + "+ '|' + T(java.util.Objects).toString(#sinceHours) + '|' + T(java.util.Objects).toString(#keywordContains)",
            cacheManager = "mcpCacheManager")
    public String analyzeLogs(
            @ToolParam(description = "日志文件路径（默认 /var/log/syslog 或 /var/log/messages）", required = false) String logPath,
            @ToolParam(description = "读取并参与分析的最大行数（默认 100，有过滤条件时自动放大读取窗口）", required = false) Integer lines,
            @ToolParam(description = "仅保留约最近 N 小时内能解析到时间的行（可选，与关键词可同时使用）", required = false) Integer sinceHours,
            @ToolParam(description = "仅保留包含任一关键词子串的行（忽略大小写；多个关键词用竖线 | 分隔表示 OR）", required = false) String keywordContains
    ) throws JsonProcessingException {
        long startTime = System.currentTimeMillis();
        String targetPath = logPath != null && !logPath.isBlank() ? logPath.trim()
                : (OsRuntime.isWindows() ? "C:\\Windows\\setupact.log" : "/var/log/syslog");
        int maxLines = lines != null && lines > 0 ? Math.min(lines, 1000) : 100;
        boolean filtered = (sinceHours != null && sinceHours > 0)
                || (keywordContains != null && !keywordContains.isBlank());
        int readCap = filtered ? Math.min(3000, Math.max(maxLines * 25, maxLines)) : maxLines;

        log.info("开始分析日志文件，路径: {}, 行数上限: {}, 读窗口: {}, sinceHours: {}, keyword: {}",
                targetPath, maxLines, readCap, sinceHours, keywordContains);

        try {
            if (!opsPathPolicy.isAllowedLogReadPath(targetPath)) {
                return McpToolResponses.error(objectMapper, opsPathPolicy.rejectReason("日志读取路径"), startTime);
            }

            List<String> logEntries = readLogFile(targetPath, readCap);
            logEntries = applyLineFilters(logEntries, sinceHours, keywordContains, maxLines);

            if (logEntries.isEmpty()) {
                Map<String, Object> emptyPayload = new LinkedHashMap<>();
                emptyPayload.put("logPath", targetPath);
                emptyPayload.put("status", "EMPTY");
                emptyPayload.put("linesAnalyzed", 0);
                emptyPayload.put("summary", filtered
                        ? "过滤后无匹配行（请放宽关键词或时间窗，或增大 lines）"
                        : "日志文件为空或不存在");
                emptyPayload.put("recommendation", "请检查日志文件路径是否正确");
                String data = objectMapper.writeValueAsString(emptyPayload);
                long duration = System.currentTimeMillis() - startTime;
                return McpToolResponses.warn(objectMapper, data, duration);
            }

            // 使用Drain-Plus解析日志
            List<EnhancedLogParseResultEntity> parsedLogs = parseLogsWithDrainPlus(logEntries);
            
            // 执行异常检测
            LogAnomalyHeuristics.applyKeywordDetection(parsedLogs);
            LogAnomalyHeuristics.applyFrequencyDetection(parsedLogs);
            LogAnomalyHeuristics.calcComprehensiveAnomalyScore(parsedLogs);
            
            List<EnhancedLogParseResultEntity> anomalies = filterAnomalies(parsedLogs);
            
            // 生成AI诊断
            String diagnosis = generateAiDiagnosis(parsedLogs, anomalies);
            String recommendation = generateFixRecommendation(anomalies);
            
            // 提取模板信息
            List<Map<String, Object>> templateSummary = buildTemplateSummary(parsedLogs, 48);
            List<Map<String, Object>> anomalySamples = buildAnomalySamples(anomalies, 16);
            Map<String, Object> lineTimeStats = buildLineTimeStats(parsedLogs);
            Map<String, Long> severityHistogram = buildSeverityHistogram(parsedLogs);

            Set<String> templateIds = new LinkedHashSet<>();
            for (EnhancedLogParseResultEntity e : parsedLogs) {
                if (e.getTemplateId() != null && !e.getTemplateId().isBlank()) {
                    templateIds.add(e.getTemplateId());
                }
            }
            int novelThisRun = drainTemplateNoveltyTracker.registerFromTemplateIds(templateIds);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("logPath", targetPath);
            payload.put("filters", Map.of(
                    "sinceHours", sinceHours != null ? sinceHours : "none",
                    "keywordContains", keywordContains != null && !keywordContains.isBlank() ? keywordContains : "none"));
            payload.put("status", anomalies.isEmpty() ? "HEALTHY" : "ISSUES_DETECTED");
            payload.put("linesAnalyzed", logEntries.size());
            payload.put("errorCount", anomalies.size());
            payload.put("novelTemplateCountThisRun", novelThisRun);
            payload.put("drainTemplateSignal", drainTemplateNoveltyTracker.snapshotForContext());
            payload.put("distinctTemplateIds", templateIds.size());
            payload.put("templateSummary", templateSummary);
            payload.put("anomalySamples", anomalySamples);
            payload.put("lineTimeStats", lineTimeStats);
            payload.put("severityHistogram", severityHistogram);
            payload.put("rootCause", diagnosis);
            payload.put("recommendation", recommendation);
            payload.put("integrationStatus", "INTEGRATED");
            String data = objectMapper.writeValueAsString(payload);

            long duration = System.currentTimeMillis() - startTime;
            log.info("日志分析完成，异常行: {} 个, 模板桶: {}, 状态: {}, 耗时: {}ms",
                anomalies.size(), templateSummary.size(), anomalies.isEmpty() ? "HEALTHY" : "ISSUES_DETECTED", duration);

            return McpToolResponses.success(objectMapper, data, duration);

        } catch (Exception e) {
            log.error("分析日志时发生异常", e);
            return McpToolResponses.error(objectMapper, "分析日志时发生异常: " + e.getMessage(), startTime);
        }
    }

    /**
     * 兼容旧调用点：仅路径 + 行数（等价于无时间窗、无关键词过滤）。
     */
    public String analyzeLogs(String logPath, Integer lines) throws JsonProcessingException {
        return analyzeLogs(logPath, lines, null, null);
    }

    private List<String> applyLineFilters(
            List<String> lines,
            Integer sinceHours,
            String keywordContains,
            int maxOutLines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Long cutoffMs = null;
        if (sinceHours != null && sinceHours > 0) {
            cutoffMs = System.currentTimeMillis() - sinceHours * 3_600_000L;
        }
        String kw = keywordContains != null && !keywordContains.isBlank() ? keywordContains.trim() : null;

        List<String> buf = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (kw != null && !matchesKeywordLine(line, kw)) {
                continue;
            }
            if (cutoffMs != null) {
                String ts = LogLineParseSupport.extractNormalizedTimeString(line);
                if (ts != null && !ts.isBlank()) {
                    long epoch = LogLineParseSupport.toEpochMillis(ts);
                    if (epoch > 0 && epoch < cutoffMs) {
                        continue;
                    }
                }
            }
            buf.add(line);
        }
        if (buf.size() > maxOutLines) {
            return new ArrayList<>(buf.subList(buf.size() - maxOutLines, buf.size()));
        }
        return buf;
    }

    private static boolean matchesKeywordLine(String line, String keywordRaw) {
        if (keywordRaw == null || keywordRaw.isBlank()) {
            return true;
        }
        String lower = line.toLowerCase();
        if (!keywordRaw.contains("|")) {
            return lower.contains(keywordRaw.toLowerCase());
        }
        for (String p : keywordRaw.split("\\|")) {
            String k = p.trim().toLowerCase();
            if (!k.isEmpty() && lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readLogFile(String path, int maxLines) {
        List<String> lines = new ArrayList<>();
        try {
            if (!opsPathPolicy.isAllowedLogReadPath(path)) {
                return lines;
            }
            Path p = Paths.get(path);
            if (Files.isRegularFile(p) && Files.isReadable(p)) {
                long sz = Files.size(p);
                int budget = (int) Math.min(2 * 1024 * 1024, Math.max(64 * 1024, (long) maxLines * 4096));
                if (sz > 512 * 1024) {
                    lines = new ArrayList<>(LogTailReader.readTailLines(p, maxLines, budget));
                } else {
                    try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            lines.add(line);
                        }
                    }
                    if (lines.size() > maxLines) {
                        lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
                    }
                }
            } else {
                AbstractCommandExecutor.CommandResult result = minPrivilegeExecutor.executeSafely(
                        List.of("tail", "-n", String.valueOf(maxLines), path)
                );
                if (result.success() && result.output() != null) {
                    for (String line : result.output().split("\n")) {
                        if (!line.isBlank() && lines.size() < maxLines) {
                            lines.add(line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取日志文件失败(IO): {}", e.getMessage());
        } catch (Exception e) {
            log.warn("读取日志文件失败: {}", e.getMessage());
        }
        return lines;
    }

    private List<EnhancedLogParseResultEntity> parseLogsWithDrainPlus(List<String> logEntries) {
        List<EnhancedLogParseResultEntity> parsedLogs = new ArrayList<>();
        for (int i = 0; i < logEntries.size(); i++) {
            String entry = logEntries.get(i);
            try {
                String processed = logCleaner.cleanLog(entry, DefaultCleanRules.getDefaultRules());
                String templateId = drainPlusParser.parse(processed);
                EnhancedLogParseResultEntity logEntity = new EnhancedLogParseResultEntity(processed);
                logEntity.setSourceLineIndex(i);
                logEntity.setTemplateId(templateId);
                LogProtocolType protocol = LogLineParseSupport.detectProtocol(processed);
                logEntity.setProtocol(protocol);
                logEntity.setSeverity(GeneralLogSeverityResolver.resolve(processed));
                String timeStr = LogLineParseSupport.extractNormalizedTimeString(processed);
                if (timeStr == null || timeStr.isBlank()) {
                    timeStr = LogFieldPlaceholder.TIME_UNPARSED;
                }
                logEntity.setLogTime(timeStr);
                logEntity.setTimestamp(LogFieldPlaceholder.TIME_UNPARSED.equals(timeStr)
                        ? 0L
                        : LogLineParseSupport.toEpochMillis(timeStr));
                LinuxLogParseSupport.enrichContainerHints(logEntity.getFeatureMap(), processed);
                if (protocol == LogProtocolType.LINUX_SYSTEM_LOG) {
                    LinuxLogParseSupport.enrichFeatureMap(logEntity.getFeatureMap(), processed, protocol);
                    String tp = LinuxLogParseSupport.extractTagPid(processed);
                    if (tp != null) {
                        logEntity.setPid(tp);
                    }
                }
                logEntity.setAnomalyScore(0.0);
                parsedLogs.add(logEntity);
            } catch (Exception e) {
                log.warn("解析日志条目失败: {}", e.getMessage());
            }
        }
        return parsedLogs;
    }

    private List<EnhancedLogParseResultEntity> filterAnomalies(List<EnhancedLogParseResultEntity> parsedLogs) {
        List<EnhancedLogParseResultEntity> anomalies = new ArrayList<>();
        for (EnhancedLogParseResultEntity log : parsedLogs) {
            if (log.isAnomaly()) {
                anomalies.add(log);
            }
        }
        return anomalies;
    }

    private String generateAiDiagnosis(List<EnhancedLogParseResultEntity> parsedLogs,
                                       List<EnhancedLogParseResultEntity> anomalies) {
        if (anomalies.isEmpty()) {
            return "未检测到明显异常，系统运行正常";
        }

        try {
            return aiDiagnosisService.generateDiagnosisFromFullResult(parsedLogs);
        } catch (Exception e) {
            log.warn("AI诊断失败，使用默认诊断: {}", e.getMessage());
            return "检测到系统异常，需要进一步排查";
        }
    }

    private String generateFixRecommendation(List<EnhancedLogParseResultEntity> anomalies) {
        if (anomalies.isEmpty()) {
            return "系统运行正常，无需特殊处理";
        }
        
        // 基于异常类型生成修复建议
        StringBuilder recommendation = new StringBuilder();
        recommendation.append("建议：");
        
        boolean hasMemoryIssue = false;
        boolean hasConnectionIssue = false;
        boolean hasDiskIssue = false;
        boolean hasPermissionIssue = false;
        
        for (EnhancedLogParseResultEntity anomaly : anomalies) {
            String logContent = anomaly.getDesensitizedLog().toLowerCase();
            if (logContent.contains("memory") || logContent.contains("oom")) {
                hasMemoryIssue = true;
            } else if (logContent.contains("connection") || logContent.contains("refused")) {
                hasConnectionIssue = true;
            } else if (logContent.contains("disk") || logContent.contains("space")) {
                hasDiskIssue = true;
            } else if (logContent.contains("permission") || logContent.contains("denied")) {
                hasPermissionIssue = true;
            }
        }
        
        int count = 1;
        if (hasMemoryIssue) {
            recommendation.append(count++).append(") 检查进程内存使用: ps aux --sort=-%mem | head -5\n");
        }
        if (hasConnectionIssue) {
            recommendation.append(count++).append(") 检查网络连通性: ping -c 4 localhost\n");
        }
        if (hasDiskIssue) {
            recommendation.append(count++).append(") 检查磁盘空间: df -h\n");
        }
        if (hasPermissionIssue) {
            recommendation.append(count++).append(") 检查文件权限: ls -la\n");
        }
        
        if (count == 1) {
            if (OsRuntime.isWindows()) {
                recommendation.append("1) 查看完整错误日志: Get-Content -Tail 100 <log-path>\n");
                recommendation.append("2) 检查相关服务状态: Get-Service 或 sc query\n");
            } else {
                recommendation.append("1) 查看完整错误日志: tail -n 100 /var/log/syslog\n");
                recommendation.append("2) 检查相关服务状态: systemctl status\n");
            }
        }
        
        return recommendation.toString();
    }

    private List<Map<String, Object>> buildTemplateSummary(List<EnhancedLogParseResultEntity> parsedLogs, int topK) {
        Map<String, List<EnhancedLogParseResultEntity>> byId = new LinkedHashMap<>();
        for (EnhancedLogParseResultEntity e : parsedLogs) {
            String id = e.getTemplateId() == null || e.getTemplateId().isBlank() ? "_unknown_" : e.getTemplateId();
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(e);
        }
        return byId.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(topK)
                .map(en -> summarizeTemplateBucket(en.getKey(), en.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, Object> summarizeTemplateBucket(String templateId, List<EnhancedLogParseResultEntity> rows) {
        int n = rows.size();
        int anomalies = (int) rows.stream().filter(EnhancedLogParseResultEntity::isAnomaly).count();
        double maxScore = rows.stream().mapToDouble(EnhancedLogParseResultEntity::getAnomalyScore).max().orElse(0);
        EnhancedLogParseResultEntity sample = rows.get(rows.size() - 1);
        String line = sample.getDesensitizedLog();
        if (line.length() > 140) {
            line = line.substring(0, 140) + "…";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", templateId);
        m.put("lineCount", n);
        m.put("anomalyLineCount", anomalies);
        m.put("maxAnomalyScore", round2(maxScore));
        m.put("sampleLine", line);
        if (sample.getSeverity() != null) {
            m.put("dominantSeverity", sample.getSeverity().name());
        }
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private List<Map<String, Object>> buildAnomalySamples(List<EnhancedLogParseResultEntity> anomalies, int max) {
        return anomalies.stream()
                .sorted(Comparator.comparingDouble(EnhancedLogParseResultEntity::getAnomalyScore).reversed())
                .limit(max)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("templateId", a.getTemplateId());
                    m.put("anomalyScore", round2(a.getAnomalyScore()));
                    m.put("reasons", a.getAnomalyReasons());
                    String s = a.getDesensitizedLog();
                    m.put("line", s.length() > 220 ? s.substring(0, 220) + "…" : s);
                    if (a.getSeverity() != null) {
                        m.put("severity", a.getSeverity().name());
                    }
                    m.put("logTime", a.getLogTime());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildLineTimeStats(List<EnhancedLogParseResultEntity> parsedLogs) {
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;
        int parsed = 0;
        for (EnhancedLogParseResultEntity e : parsedLogs) {
            long t = e.getTimestamp();
            if (t > 0) {
                parsed++;
                minTs = Math.min(minTs, t);
                maxTs = Math.max(maxTs, t);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linesWithParsedTime", parsed);
        m.put("linesTotal", parsedLogs.size());
        m.put("unparsedTimeRatio", parsedLogs.isEmpty() ? 0.0
                : round2(1.0 - (double) parsed / parsedLogs.size()));
        if (parsed > 0) {
            m.put("minEpochMs", minTs);
            m.put("maxEpochMs", maxTs);
        }
        return m;
    }

    private Map<String, Long> buildSeverityHistogram(List<EnhancedLogParseResultEntity> parsedLogs) {
        Map<String, Long> hist = new LinkedHashMap<>();
        for (EnhancedLogParseResultEntity e : parsedLogs) {
            String k = e.getSeverity() != null ? e.getSeverity().name() : "UNKNOWN";
            hist.merge(k, 1L, Long::sum);
        }
        return hist;
    }
}