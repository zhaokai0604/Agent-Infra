package com.award.log.service.impl;

import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.award.log.model.LogSeverityLevel;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AiLogAlarmService;
import com.award.log.service.AlarmConfigService;
import com.award.log.service.AlarmLifecycleService;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class AiLogAlarmServiceImpl implements AiLogAlarmService {

    @Autowired
    private ChatClient chatClient;

    @Qualifier("logAnalysisExecutor")
    @Autowired
    private ThreadPoolTaskExecutor logAnalysisExecutor;

    @Autowired
    private LogAlarmMapper logAlarmMapper;

    @Autowired
    private AlarmConfigService alarmConfigService;

    @Autowired(required = false)
    private AlarmLifecycleService alarmLifecycleService;

    @Autowired
    private AnalysisTaskManager analysisTaskManager;

    private final ConcurrentHashMap<String, Long> fingerprintCooldownMap = new ConcurrentHashMap<>();

    @Value("${alarm.aggregation.enabled:true}")
    private boolean aggregationEnabled;

    @Value("${alarm.aggregation.window-minutes:5}")
    private int aggregationWindowMinutes;

    @Value("${alarm.aggregation.dimension:task_level_rootCause}")
    private String aggregationDimension;

    private static final String AI_PROMPT_TEMPLATE = """
            你是告警分诊引擎，只能根据下面的日志样本生成结构化告警 JSON。
            输出要求：
            - 只输出单行合法 JSON
            - schema: {"level":"ERROR","rootCause":"...","solution":"..."}
            日志样本：
            %s
            """;

    @Override
    public LogAlarm analyzeLogAndGenerateAlarm(String taskId, String logContent) {
        log.info("Analyze alarm for task {}", taskId);
        if (alarmLifecycleService != null && alarmLifecycleService.isSilencedNow()) {
            log.info("Silence window active, skip AI alarm generation for task {}", taskId);
            LogAlarm skipped = new LogAlarm();
            skipped.setAlarmId(UUID.randomUUID().toString());
            skipped.setTaskId(taskId);
            skipped.setLogContent(logContent);
            skipped.setCreateTime(LocalDateTime.now());
            skipped.setUpdateTime(LocalDateTime.now());
            skipped.setPushStatus("SKIPPED:SILENCED");
            skipped.setLifecycleStatus("SUPPRESSED");
            skipped.setEscalationLevel(0);
            skipped.setLevel("INFO");
            skipped.setRootCause("Silence window");
            skipped.setSolution("Wait until silence ends");
            return skipped;
        }
        String aiPrompt = String.format(AI_PROMPT_TEMPLATE, logContent);
        String aiResponse;
        try {
            aiResponse = chatClient.prompt().user(aiPrompt).call().content();
        } catch (Exception e) {
            log.error("AI alarm analysis failed for task {}", taskId, e);
            LogAlarm defaultAlarm = new LogAlarm();
            defaultAlarm.setAlarmId(UUID.randomUUID().toString());
            defaultAlarm.setTaskId(taskId);
            defaultAlarm.setLogContent(logContent);
            defaultAlarm.setCreateTime(LocalDateTime.now());
            defaultAlarm.setUpdateTime(LocalDateTime.now());
            defaultAlarm.setPushStatus("FAILED");
            defaultAlarm.setLifecycleStatus("NEW");
            defaultAlarm.setEscalationLevel(0);
            defaultAlarm.setLevel("ERROR");
            defaultAlarm.setRootCause("AI analysis unavailable");
            defaultAlarm.setSolution("Check AI service configuration");
            addAlarmToHistory(defaultAlarm);
            return defaultAlarm;
        }

        LogAlarm alarm = parseAIResponse(taskId, logContent, aiResponse);
        if (!shouldAggregate(alarm)) {
            addAlarmToHistory(alarm);
        } else {
            alarm.setPushStatus("AGGREGATED");
            return alarm;
        }

        logAnalysisExecutor.execute(() -> {
            boolean result = pushAlarm(alarm);
            log.info("Alarm push finished for {} result={}", alarm.getAlarmId(), result);
        });

        return alarm;
    }

    private AlarmDecision evaluatePushPolicy(LogAlarm alarm, TaskAlarmConfig config) {
        if (config == null) {
            return AlarmDecision.skip("CONFIG_NOT_FOUND");
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            return AlarmDecision.skip("ALARM_DISABLED");
        }

        String configAlarmLevel = config.getAlarmLevel() == null ? "ERROR" : config.getAlarmLevel();
        if (!isSeverityMatch(alarm.getLevel(), configAlarmLevel)) {
            return AlarmDecision.skip("LEVEL_BELOW_THRESHOLD");
        }

        String taskId = alarm.getTaskId();
        if (taskId == null) {
            return AlarmDecision.skip("TASK_ID_MISSING");
        }

        if (config.getErrorThreshold() != null && config.getErrorThreshold() > 1 && alarm.getLogContent() != null) {
            int eventCount = countErrorEntries(alarm.getLogContent());
            if (eventCount < config.getErrorThreshold()) {
                return AlarmDecision.skip("ERROR_THRESHOLD_NOT_REACHED");
            }
        }

        String fingerprint = generateAlarmFingerprint(alarm);
        long now = System.currentTimeMillis();
        long cooldownMs = config.getCooldownMs() == null ? 300000L : config.getCooldownMs();
        Long lastPush = fingerprintCooldownMap.get(fingerprint);
        if (lastPush != null && now - lastPush < cooldownMs) {
            return AlarmDecision.skip("COOLDOWN_ACTIVE");
        }

        return AlarmDecision.pass(fingerprint);
    }

    private boolean isSeverityMatch(String level, String configAlarmLevel) {
        Map<String, Integer> severityPriority = new HashMap<>();
        severityPriority.put("INFO", 0);
        severityPriority.put("WARNING", 1);
        severityPriority.put("ERROR", 2);
        severityPriority.put("FATAL", 3);

        String normalizedConfigLevel = configAlarmLevel;
        if (normalizedConfigLevel != null && normalizedConfigLevel.endsWith("_LEVEL")) {
            normalizedConfigLevel = normalizedConfigLevel.substring(0, normalizedConfigLevel.length() - 6);
        }

        Integer currentPriority = severityPriority.getOrDefault(level, -1);
        Integer minPriority = severityPriority.getOrDefault(normalizedConfigLevel, -1);
        return currentPriority >= minPriority;
    }

    @Override
    public boolean pushAlarm(LogAlarm alarm) {
        if (alarmLifecycleService != null && alarmLifecycleService.isSilencedNow()) {
            alarm.setPushStatus("SKIPPED:SILENCED");
            alarm.setUpdateTime(LocalDateTime.now());
            updateAlarm(alarm);
            return false;
        }
        TaskAlarmConfig taskConfig = alarmConfigService.getEffectiveConfig(alarm.getTaskId());
        AlarmDecision decision = evaluatePushPolicy(alarm, taskConfig);
        if (!decision.allowed()) {
            alarm.setPushStatus("SKIPPED:" + decision.reason());
            alarm.setUpdateTime(LocalDateTime.now());
            updateAlarm(alarm);
            return false;
        }

        alarm.setPushStatus("RECORDED");
        alarm.setUpdateTime(LocalDateTime.now());
        fingerprintCooldownMap.put(decision.fingerprint(), System.currentTimeMillis());
        updateAlarm(alarm);
        return true;
    }

    @Override
    public boolean retryPushAlarm(Integer alarmId, int retryCount) {
        LogAlarm alarm = logAlarmMapper.selectById(alarmId != null ? Long.valueOf(alarmId) : null);
        if (alarm == null) {
            return false;
        }
        alarm.setPushStatus("PENDING");
        alarm.setLifecycleStatus("NEW");
        alarm.setEscalationLevel(0);
        alarm.setUpdateTime(LocalDateTime.now());
        return pushAlarm(alarm);
    }

    @Override
    public void processAlarmsByTaskId(String taskId) {
        processAlarmsByTaskIdForUser(taskId, null, true);
    }

    @Override
    public void processAlarmsByTaskIdForUser(String taskId, Integer userId, boolean admin) {
        if (!analysisTaskManager.canAccessTask(taskId, userId, admin)) {
            throw new IllegalArgumentException("Task not found or access denied");
        }

        List<LogAlarm> alarms = logAlarmMapper.selectByTaskId(taskId);
        if (alarms == null || alarms.isEmpty()) {
            TaskInfo task = admin ? analysisTaskManager.getTask(taskId) : analysisTaskManager.getTaskForUser(taskId, userId, false);
            if (task == null || task.getResult() == null || task.getResult().isEmpty()) {
                return;
            }

            List<String> samples = new ArrayList<>();
            Set<String> reasons = new LinkedHashSet<>();
            for (EnhancedLogParseResultEntity item : task.getResult()) {
                boolean highSeverity = item.getSeverity() == LogSeverityLevel.ERROR_LEVEL
                        || item.getSeverity() == LogSeverityLevel.FATAL_LEVEL;
                if (!item.isAnomaly() && !highSeverity) {
                    continue;
                }
                if (item.getAnomalyReasons() != null) {
                    reasons.addAll(item.getAnomalyReasons());
                }
                String content = item.getDesensitizedLog() == null ? "" : item.getDesensitizedLog();
                samples.add(AiDiagnosisContextBuilder.truncateForPrompt(
                        content, AiDiagnosisContextBuilder.COMPENSATION_SNIPPET_CHARS));
            }

            if (samples.isEmpty()) {
                return;
            }

            StringBuilder alarmContent = new StringBuilder();
            alarmContent.append("TaskId: ").append(taskId).append("\n")
                    .append("AnomalyCount: ").append(samples.size()).append("\n")
                    .append("Reasons: ")
                    .append(reasons.isEmpty() ? "unknown" : String.join(" | ", reasons))
                    .append("\n")
                    .append("Samples:\n");
            samples.stream().limit(10).forEach(s -> alarmContent.append("- ").append(s).append("\n"));

            analyzeLogAndGenerateAlarm(taskId, alarmContent.toString());
            return;
        }

        for (LogAlarm alarm : alarms) {
            String status = alarm.getPushStatus();
            if (!"SUCCESS".equals(status) && !"RECORDED".equals(status)) {
                pushAlarm(alarm);
            }
        }
    }

    @Override
    public Map<String, Object> getAlarmHistory(int pageNum, int pageSize, String level, String taskId) {
        return getAlarmHistoryForUser(pageNum, pageSize, level, taskId, null, true);
    }

    @Override
    public Map<String, Object> getAlarmHistoryForUser(int pageNum, int pageSize, String level, String taskId,
                                                      Integer userId, boolean admin) {
        String safeTaskId = accessibleTaskId(taskId, userId, admin);
        int safePage = Math.max(pageNum, 1);
        int safePageSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safePageSize;

        List<LogAlarm> alarms = admin
                ? logAlarmMapper.selectPage(safePage, safePageSize, offset, emptyToNull(level), safeTaskId)
                : logAlarmMapper.selectPageByUserId(safePageSize, offset, userId, emptyToNull(level), safeTaskId);
        long total = admin
                ? logAlarmMapper.count(emptyToNull(level), safeTaskId)
                : logAlarmMapper.countByUserId(userId, emptyToNull(level), safeTaskId);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("pageNum", safePage);
        result.put("pageSize", safePageSize);
        result.put("list", alarms);
        return result;
    }

    @Override
    public Map<String, Object> getAlarmStatistics(int days, String level, String taskId) {
        return getAlarmStatisticsForUser(days, level, taskId, null, true);
    }

    @Override
    public Map<String, Object> getAlarmStatisticsForUser(int days, String level, String taskId,
                                                         Integer userId, boolean admin) {
        String safeTaskId = accessibleTaskId(taskId, userId, admin);
        Map<String, Object> statistics = new HashMap<>();
        Map<String, Object> dbStatistics = admin
                ? logAlarmMapper.selectAlarmStatistics(days, emptyToNull(level), safeTaskId)
                : logAlarmMapper.selectAlarmStatisticsByUserId(days, userId, emptyToNull(level), safeTaskId);

        long totalAlarms = toLong(dbStatistics == null ? null : dbStatistics.get("total_alarms"));
        long successAlarms = toLong(dbStatistics == null ? null : dbStatistics.get("success_alarms"));
        long failedAlarms = toLong(dbStatistics == null ? null : dbStatistics.get("failed_alarms"));
        double successRate = totalAlarms > 0 ? (double) successAlarms / totalAlarms * 100 : 0;

        statistics.put("totalAlarms", totalAlarms);
        statistics.put("successAlarms", successAlarms);
        statistics.put("failedAlarms", failedAlarms);
        statistics.put("successRate", successRate);
        statistics.put("alarmTrend", getAlarmTrendForUser(days, level, taskId, userId, admin));
        statistics.put("levelDistribution", getAlarmLevelDistributionForUser(days, level, taskId, userId, admin));
        statistics.put("rootCauseStatistics", getAlarmRootCauseStatisticsForUser(days, level, taskId, userId, admin));
        return statistics;
    }

    @Override
    public List<Map<String, Object>> getAlarmTrend(int days, String level, String taskId) {
        return getAlarmTrendForUser(days, level, taskId, null, true);
    }

    @Override
    public List<Map<String, Object>> getAlarmTrendForUser(int days, String level, String taskId,
                                                          Integer userId, boolean admin) {
        String safeTaskId = accessibleTaskId(taskId, userId, admin);
        return admin
                ? logAlarmMapper.selectAlarmTrend(days, emptyToNull(level), safeTaskId)
                : logAlarmMapper.selectAlarmTrendByUserId(days, userId, emptyToNull(level), safeTaskId);
    }

    @Override
    public List<Map<String, Object>> getAlarmLevelDistribution(int days, String level, String taskId) {
        return getAlarmLevelDistributionForUser(days, level, taskId, null, true);
    }

    @Override
    public List<Map<String, Object>> getAlarmLevelDistributionForUser(int days, String level, String taskId,
                                                                      Integer userId, boolean admin) {
        String safeTaskId = accessibleTaskId(taskId, userId, admin);
        return admin
                ? logAlarmMapper.selectAlarmLevelDistribution(days, emptyToNull(level), safeTaskId)
                : logAlarmMapper.selectAlarmLevelDistributionByUserId(days, userId, emptyToNull(level), safeTaskId);
    }

    @Override
    public List<Map<String, Object>> getAlarmRootCauseStatistics(int days, String level, String taskId) {
        return getAlarmRootCauseStatisticsForUser(days, level, taskId, null, true);
    }

    @Override
    public List<Map<String, Object>> getAlarmRootCauseStatisticsForUser(int days, String level, String taskId,
                                                                        Integer userId, boolean admin) {
        String safeTaskId = accessibleTaskId(taskId, userId, admin);
        return admin
                ? logAlarmMapper.selectAlarmRootCauseStatistics(days, emptyToNull(level), safeTaskId)
                : logAlarmMapper.selectAlarmRootCauseStatisticsByUserId(days, userId, emptyToNull(level), safeTaskId);
    }

    private String accessibleTaskId(String taskId, Integer userId, boolean admin) {
        String safeTaskId = emptyToNull(taskId);
        if (safeTaskId == null) {
            return null;
        }
        if (!analysisTaskManager.canAccessTask(safeTaskId, userId, admin)) {
            throw new IllegalArgumentException("Task not found or access denied");
        }
        return safeTaskId;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void addAlarmToHistory(LogAlarm alarm) {
        if (alarm == null) {
            return;
        }
        if (alarm.getCreateTime() == null) {
            alarm.setCreateTime(LocalDateTime.now());
        }
        if (alarm.getUpdateTime() == null) {
            alarm.setUpdateTime(LocalDateTime.now());
        }
        try {
            logAlarmMapper.insert(alarm);
        } catch (Exception e) {
            log.error("Failed to persist alarm history", e);
        }
    }

    private boolean shouldAggregate(LogAlarm alarm) {
        if (!aggregationEnabled) {
            return false;
        }
        if (!"task_level_rootCause".equalsIgnoreCase(aggregationDimension)) {
            return false;
        }
        String rootCause = alarm.getRootCause() == null ? "" : alarm.getRootCause();
        String prefix = rootCause.substring(0, Math.min(20, rootCause.length()));
        LogAlarm latest = logAlarmMapper.selectLatestByFingerprint(
                alarm.getTaskId(), alarm.getLevel(), prefix, aggregationWindowMinutes);
        return latest != null;
    }

    private String generateAlarmFingerprint(LogAlarm alarm) {
        String rootCause = alarm.getRootCause() == null ? "" : alarm.getRootCause().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        String source = (alarm.getTaskId() == null ? "unknownTask" : alarm.getTaskId())
                + "|"
                + (alarm.getLevel() == null ? "UNKNOWN" : alarm.getLevel())
                + "|"
                + rootCause;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(source.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return source;
        }
    }

    private int countErrorEntries(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return 0;
        }
        int count = 0;
        String[] lines = logContent.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("error") || lower.contains("fatal") || lower.contains("exception")) {
                count++;
            }
        }
        return count;
    }

    private void updateAlarm(LogAlarm alarm) {
        try {
            logAlarmMapper.update(alarm);
        } catch (Exception e) {
            log.error("Failed to update alarm {}", alarm.getAlarmId(), e);
        }
    }

    private record AlarmDecision(boolean allowed, String reason, String fingerprint) {
        static AlarmDecision skip(String reason) {
            return new AlarmDecision(false, reason, "");
        }

        static AlarmDecision pass(String fingerprint) {
            return new AlarmDecision(true, "PASS", fingerprint);
        }
    }

    private LogAlarm parseAIResponse(String taskId, String logContent, String aiResponse) {
        LogAlarm alarm = new LogAlarm();
        alarm.setAlarmId(UUID.randomUUID().toString());
        alarm.setTaskId(taskId);
        alarm.setLogContent(logContent);
        alarm.setCreateTime(LocalDateTime.now());
        alarm.setUpdateTime(LocalDateTime.now());
        alarm.setPushStatus("PENDING");

        try {
            String jsonOnlyResponse = extractJsonFromResponse(aiResponse);
            com.alibaba.fastjson.JSONObject aiResult = com.alibaba.fastjson.JSON.parseObject(jsonOnlyResponse);
            String level = aiResult.getString("level");
            if (level == null || level.isEmpty()) {
                level = "ERROR";
            }
            alarm.setLevel(level);
            String rootCause = aiResult.getString("rootCause");
            if (rootCause == null || rootCause.isEmpty()) {
                rootCause = "AI parsed root cause unavailable";
            }
            alarm.setRootCause(rootCause);
            String solution = aiResult.getString("solution");
            if (solution == null || solution.isEmpty()) {
                solution = "Review the original logs for remediation";
            }
            alarm.setSolution(solution);
        } catch (Exception e) {
            setDefaultAlarmValues(alarm, "AI analysis failed");
        }

        return alarm;
    }

    private String extractJsonFromResponse(String aiResponse) {
        int jsonStart = aiResponse.indexOf("```json");
        int jsonEnd = aiResponse.indexOf("```", jsonStart + 1);
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return aiResponse.substring(jsonStart + 7, jsonEnd).trim();
        }
        return aiResponse.trim();
    }

    private void setDefaultAlarmValues(LogAlarm alarm, String rootCause) {
        alarm.setLevel("ERROR");
        alarm.setRootCause(rootCause);
        alarm.setSolution("Review the original logs for remediation");
    }
}
