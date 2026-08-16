package com.award.log.service.impl;

import com.award.log.config.SystemConfigFileSupport;
import com.award.log.model.TaskAlarmConfig;
import com.award.log.service.AlarmConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class AlarmConfigServiceImpl implements AlarmConfigService {
    private static final String DEFAULT_KEY = "default";
    private static final String TASKS_KEY = "tasks";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Object> cachedRoot;
    private volatile long cachedRootMtime = -1L;

    @Override
    public TaskAlarmConfig getDefaultConfig() {
        Map<String, Object> root = loadRootCached();
        Map<String, Object> defaultMap = asMap(root.get(DEFAULT_KEY));
        return mergeWithDefault(null, defaultMap);
    }

    @Override
    public TaskAlarmConfig getTaskConfig(String taskId) {
        Map<String, Object> root = loadRootCached();
        Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
        Map<String, Object> taskMap = asMap(tasks.get(taskId));
        return mapToConfig(taskId, taskMap);
    }

    @Override
    public TaskAlarmConfig getEffectiveConfig(String taskId) {
        Map<String, Object> root = loadRootCached();
        Map<String, Object> defaultMap = asMap(root.get(DEFAULT_KEY));
        Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
        Map<String, Object> taskMap = asMap(tasks.get(taskId));
        return mergeWithDefault(taskId, mergeMaps(defaultMap, taskMap));
    }

    @Override
    public TaskAlarmConfig saveDefaultConfig(Map<String, Object> rawConfig) {
        synchronized (this) {
            Map<String, Object> root = loadRootUncached();
            root.put(DEFAULT_KEY, normalizeRaw(rawConfig));
            saveRoot(root);
            invalidateRootCache();
            return getDefaultConfig();
        }
    }

    @Override
    public TaskAlarmConfig saveTaskConfig(String taskId, Map<String, Object> rawConfig) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
        synchronized (this) {
            Map<String, Object> root = loadRootUncached();
            Map<String, Object> tasks = asMap(root.get(TASKS_KEY));
            if (tasks.isEmpty()) {
                tasks = new LinkedHashMap<>();
            }
            tasks.put(taskId, normalizeRaw(rawConfig));
            root.put(TASKS_KEY, tasks);
            saveRoot(root);
            invalidateRootCache();
            return getEffectiveConfig(taskId);
        }
    }

    private Map<String, Object> loadRootCached() {
        File file = configFile().toFile();
        long mtime = file.exists() ? file.lastModified() : 0L;
        Map<String, Object> hit = cachedRoot;
        if (hit != null && cachedRootMtime == mtime) {
            return hit;
        }
        synchronized (this) {
            if (cachedRoot != null && cachedRootMtime == mtime) {
                return cachedRoot;
            }
            Map<String, Object> root = loadRootUncached();
            cachedRoot = root;
            cachedRootMtime = mtime;
            return root;
        }
    }

    private void invalidateRootCache() {
        cachedRoot = null;
        cachedRootMtime = -1L;
    }

    private Map<String, Object> loadRootUncached() {
        File file = configFile().toFile();
        if (!file.exists()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put(DEFAULT_KEY, buildDefaultMap());
            root.put(TASKS_KEY, new LinkedHashMap<>());
            saveRoot(root);
            return root;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
            if (!root.containsKey(DEFAULT_KEY)) {
                root.put(DEFAULT_KEY, buildDefaultMap());
            }
            if (!root.containsKey(TASKS_KEY)) {
                root.put(TASKS_KEY, new LinkedHashMap<>());
            }
            return root;
        } catch (Exception e) {
            log.error("Failed to read alarm config, falling back to defaults", e);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put(DEFAULT_KEY, buildDefaultMap());
            root.put(TASKS_KEY, new LinkedHashMap<>());
            return root;
        }
    }

    private void saveRoot(Map<String, Object> root) {
        SystemConfigFileSupport.writeJsonFile(configFile(), root);
        invalidateRootCache();
    }

    private Path configFile() {
        return SystemConfigFileSupport.alarmConfigFile();
    }

    private Map<String, Object> buildDefaultMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alarmLevel", "ERROR");
        m.put("errorThreshold", 5);
        m.put("enabled", true);
        m.put("cooldownMs", 300000L);
        return m;
    }

    private Map<String, Object> normalizeRaw(Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("alarmLevel", normalizeLevel(asString(source.get("alarmLevel"), "ERROR")));
        n.put("errorThreshold", asInt(source.get("errorThreshold"), 5));
        n.put("enabled", asBoolean(source.get("enabled"), true));
        n.put("cooldownMs", asLong(source.get("cooldownMs"), 300000L));
        n.put("updatedAt", LocalDateTime.now().toString());
        return n;
    }

    private TaskAlarmConfig mergeWithDefault(String taskId, Map<String, Object> source) {
        Map<String, Object> merged = mergeMaps(buildDefaultMap(), source);
        TaskAlarmConfig config = mapToConfig(taskId, merged);
        if (config.getUpdatedAt() == null) {
            config.setUpdatedAt(LocalDateTime.now());
        }
        return config;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        if (override != null) {
            result.putAll(override);
        }
        return result;
    }

    private TaskAlarmConfig mapToConfig(String taskId, Map<String, Object> map) {
        TaskAlarmConfig config = new TaskAlarmConfig();
        config.setTaskId(taskId);
        config.setAlarmLevel(normalizeLevel(asString(map.get("alarmLevel"), "ERROR")));
        config.setErrorThreshold(asInt(map.get("errorThreshold"), 5));
        config.setEnabled(asBoolean(map.get("enabled"), true));
        config.setCooldownMs(asLong(map.get("cooldownMs"), 300000L));
        String updatedAt = asString(map.get("updatedAt"), null);
        if (updatedAt != null && !updatedAt.isEmpty()) {
            try {
                config.setUpdatedAt(LocalDateTime.parse(updatedAt));
            } catch (Exception ignored) {
            }
        }
        return config;
    }

    private String normalizeLevel(String level) {
        String l = level == null ? "ERROR" : level.trim().toUpperCase(Locale.ROOT);
        if (l.endsWith("_LEVEL")) {
            l = l.substring(0, l.length() - 6);
        }
        if ("WARN".equals(l)) {
            return "WARNING";
        }
        return l;
    }

    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            Map<String, Object> casted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                casted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return casted;
        }
        return new LinkedHashMap<>();
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Integer asInt(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Long asLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Boolean asBoolean(Object value, Boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
