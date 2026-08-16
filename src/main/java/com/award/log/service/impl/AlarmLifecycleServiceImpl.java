package com.award.log.service.impl;

import com.award.log.mapper.LogAlarmMapper;
import com.award.log.model.LogAlarm;
import com.award.log.service.AlarmLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@org.springframework.context.annotation.DependsOn("logAlarmSchemaService")
public class AlarmLifecycleServiceImpl implements AlarmLifecycleService {

    private static final Path SILENCE_FILE = Paths.get("data", "alarm-silence.json");

    private final LogAlarmMapper logAlarmMapper;
    private final ObjectMapper objectMapper;
    private final AtomicReference<LocalDateTime> silenceStart = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> silenceEnd = new AtomicReference<>();

    public AlarmLifecycleServiceImpl(LogAlarmMapper logAlarmMapper, ObjectMapper objectMapper) {
        this.logAlarmMapper = logAlarmMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadPersistedSilence() {
        try {
            if (!Files.isRegularFile(SILENCE_FILE)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(SILENCE_FILE.toFile(), Map.class);
            if (map.get("startTime") != null && map.get("endTime") != null) {
                silenceStart.set(LocalDateTime.parse(map.get("startTime")));
                silenceEnd.set(LocalDateTime.parse(map.get("endTime")));
                log.info("[告警静默] 已从 {} 恢复窗口 {} ~ {}", SILENCE_FILE, map.get("startTime"), map.get("endTime"));
            }
        } catch (Exception e) {
            log.warn("[告警静默] 读取持久化失败: {}", e.getMessage());
        }
    }

    @Override
    public boolean acknowledge(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "ACKNOWLEDGED", operator) > 0;
    }

    @Override
    public boolean handle(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "HANDLED", operator) > 0;
    }

    @Override
    public boolean close(String alarmId, String operator) {
        return logAlarmMapper.updateLifecycle(alarmId, "CLOSED", operator) > 0;
    }

    @Override
    public Map<String, Object> silenceWindow(String startTime, String endTime) {
        LocalDateTime start = LocalDateTime.parse(startTime);
        LocalDateTime end = LocalDateTime.parse(endTime);
        silenceStart.set(start);
        silenceEnd.set(end);
        boolean persisted = persistSilence(startTime, endTime);
        Map<String, Object> out = new HashMap<>();
        out.put("startTime", startTime);
        out.put("endTime", endTime);
        out.put("enabled", true);
        out.put("persisted", persisted);
        out.put("persistPath", SILENCE_FILE.toAbsolutePath().toString());
        return out;
    }

    private boolean persistSilence(String startTime, String endTime) {
        try {
            Files.createDirectories(SILENCE_FILE.getParent());
            Map<String, String> payload = Map.of("startTime", startTime, "endTime", endTime);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(SILENCE_FILE.toFile(), payload);
            return true;
        } catch (Exception e) {
            log.warn("[告警静默] 持久化失败（仍生效于本进程）: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isSilencedNow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = silenceStart.get();
        LocalDateTime end = silenceEnd.get();
        return start != null && end != null && now.isAfter(start) && now.isBefore(end);
    }

    @Scheduled(fixedDelay = 60000)
    public void autoEscalate() {
        if (isSilencedNow()) {
            return;
        }
        List<LogAlarm> alarms = logAlarmMapper.selectNeedEscalation(15);
        for (LogAlarm alarm : alarms) {
            logAlarmMapper.increaseEscalation(alarm.getAlarmId());
            log.info("告警自动升级: alarmId={}, level={}", alarm.getAlarmId(), alarm.getEscalationLevel());
        }
    }
}
