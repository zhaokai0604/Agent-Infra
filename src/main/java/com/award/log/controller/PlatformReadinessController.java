package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.KafkaMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/platform")
public class PlatformReadinessController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private KafkaMonitorService kafkaMonitorService;

    @Autowired
    private RequestUserResolver requestUserResolver;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    @GetMapping("/readiness")
    public Result<Map<String, Object>> readiness(HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "仅管理员可访问平台就绪检查");
        }
        Map<String, Object> checks = new HashMap<>();
        checks.put("database", checkDatabase());
        checks.put("redis", checkRedis());
        checks.put("kafka", checkKafka());
        checks.put("ai", checkAiConfig());

        boolean allUp = checks.values().stream()
                .allMatch(v -> v instanceof Map && okHealthStatus(((Map<?, ?>) v).get("status")));

        Map<String, Object> result = new HashMap<>();
        result.put("status", allUp ? "UP" : "DEGRADED");
        result.put("checks", checks);
        result.put("timestamp", System.currentTimeMillis());
        return Result.success(result);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> r = new HashMap<>();
        try {
            Integer v = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            r.put("status", (v != null && v == 1) ? "UP" : "DOWN");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private static boolean okHealthStatus(Object status) {
        if (!(status instanceof String s)) {
            return false;
        }
        return "UP".equals(s) || "SKIPPED".equals(s);
    }

    private Map<String, Object> checkRedis() {
        Map<String, Object> r = new HashMap<>();
        if (stringRedisTemplate == null) {
            r.put("status", "SKIPPED");
            r.put("message", "Redis 未启用（award.middleware.redis=false）");
            return r;
        }
        try {
            String pong = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            r.put("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkKafka() {
        Map<String, Object> r = new HashMap<>();
        if (kafkaMonitorService == null) {
            r.put("status", "SKIPPED");
            r.put("message", "Kafka 未启用（award.middleware.kafka=false）");
            return r;
        }
        try {
            Map<String, Object> snapshot = kafkaMonitorService.snapshot();
            boolean online = Boolean.TRUE.equals(snapshot.get("online"));
            r.put("status", online ? "UP" : "DOWN");
            r.put("brokers", snapshot.get("brokers"));
            if (!online) {
                r.put("error", snapshot.get("error"));
            }
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkAiConfig() {
        Map<String, Object> r = new HashMap<>();
        boolean configured = aiApiKey != null && !aiApiKey.isBlank();
        r.put("status", configured ? "UP" : "DOWN");
        r.put("configured", configured);
        return r;
    }
}
