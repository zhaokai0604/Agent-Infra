package com.award.log.service.impl;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionInput;
import com.award.log.decision.DecisionResult;
import com.award.log.service.AiLogAlarmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 轻量流处理：解析、窗口聚合、特征写入Redis、规则告警
 */
@Slf4j
@Component
public class MvpStreamProcessor {

    private final Deque<Long> total1m = new ArrayDeque<>();
    private final Deque<Long> err1m = new ArrayDeque<>();
    private final Deque<Long> total5m = new ArrayDeque<>();
    private final Deque<Long> err5m = new ArrayDeque<>();
    private final Map<String, Long> alertCooldown = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private AiLogAlarmService aiLogAlarmService;

    @Autowired
    private CollaborativeDecisionService collaborativeDecisionService;

    @Value("${log.pipeline.stream.error-rate-threshold:0.3}")
    private double errorRateThreshold;

    @Value("${log.pipeline.stream.min-sample-size:20}")
    private int minSampleSize;

    @Value("${log.pipeline.stream.cooldown-ms:300000}")
    private long cooldownMs;

    public void process(RawLogEvent event) {
        long ts = event.getEventTime() == null ? System.currentTimeMillis() : event.getEventTime();
        String level = normalizeLevel(event.getLevel(), event.getContent());
        String template = toTemplate(event.getContent());
        event.setLevel(level);

        updateWindows(ts, level);
        writeRealtimeFeature(event, template);
        evaluateDecision(event, template);
    }

    private synchronized void updateWindows(long ts, String level) {
        total1m.addLast(ts);
        total5m.addLast(ts);
        if (isError(level)) {
            err1m.addLast(ts);
            err5m.addLast(ts);
        }
        prune(total1m, ts - 60_000);
        prune(err1m, ts - 60_000);
        prune(total5m, ts - 300_000);
        prune(err5m, ts - 300_000);
    }

    private void prune(Deque<Long> deque, long minTs) {
        while (!deque.isEmpty() && deque.peekFirst() < minTs) {
            deque.pollFirst();
        }
    }

    private void evaluateDecision(RawLogEvent event, String template) {
        int totalCount = total1m.size();
        int errCount = err1m.size();
        if (totalCount < minSampleSize) {
            return;
        }
        double errRate = totalCount == 0 ? 0D : (double) errCount / totalCount;
        String host = event.getHost() == null ? "unknown-host" : event.getHost();
        String alertKey = host + ":" + template + ":" + event.getLevel();
        long now = System.currentTimeMillis();
        long last = alertCooldown.getOrDefault(alertKey, 0L);
        if (now - last < cooldownMs) {
            return;
        }
        DecisionInput input = DecisionInput.builder()
                .event(event)
                .template(template)
                .errorRate1m(errRate)
                .total1m(totalCount)
                .error1m(errCount)
                .build();
        DecisionResult decisionResult = collaborativeDecisionService.decide(input);
        if (!decisionResult.isShouldAlert()) {
            return;
        }
        if (errRate < errorRateThreshold && "RULE".equals(decisionResult.getEngineType().name())) {
            return;
        }
        alertCooldown.put(alertKey, now);
        String msg = String.format("引擎=%s, confidence=%.2f, featureVersion=%s, modelVersion=%s, host=%s, rate=%.2f, total=%d, error=%d, template=%s, reason=%s",
                decisionResult.getEngineType().name(), decisionResult.getConfidence(),
                decisionResult.getFeatureVersion(), decisionResult.getModelVersion(),
                host, errRate, totalCount, errCount, template, decisionResult.getReason());
        log.warn("[协同决策告警] {}", msg);
        if (aiLogAlarmService != null) {
            aiLogAlarmService.analyzeLogAndGenerateAlarm("mvp-stream-" + decisionResult.getEngineType().name().toLowerCase(), msg);
        }
    }

    private void writeRealtimeFeature(RawLogEvent event, String template) {
        if (redisTemplate == null) {
            return;
        }
        String key = "log:feature:" + (event.getHost() == null ? "unknown-host" : event.getHost());
        redisTemplate.opsForHash().put(key, "lastLevel", event.getLevel());
        redisTemplate.opsForHash().put(key, "lastTemplate", template);
        redisTemplate.opsForHash().put(key, "count1m", String.valueOf(total1m.size()));
        redisTemplate.opsForHash().put(key, "error1m", String.valueOf(err1m.size()));
        redisTemplate.opsForHash().put(key, "count5m", String.valueOf(total5m.size()));
        redisTemplate.opsForHash().put(key, "error5m", String.valueOf(err5m.size()));
        redisTemplate.expire(key, 1, TimeUnit.DAYS);
    }

    private String normalizeLevel(String currentLevel, String content) {
        if (currentLevel != null && !currentLevel.isBlank()) {
            return currentLevel.toUpperCase();
        }
        String lower = content == null ? "" : content.toLowerCase();
        if (lower.contains("fatal")) return "FATAL";
        if (lower.contains("error") || lower.contains("exception")) return "ERROR";
        if (lower.contains("warn")) return "WARN";
        if (lower.contains("debug")) return "DEBUG";
        return "INFO";
    }

    private boolean isError(String level) {
        return "ERROR".equals(level) || "FATAL".equals(level);
    }

    private String toTemplate(String content) {
        if (content == null || content.isBlank()) {
            return "EMPTY_LOG";
        }
        String template = content
                .replaceAll("\\d+", "{N}")
                .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "{IP}");
        return template.length() > 128 ? template.substring(0, 128) : template;
    }
}
