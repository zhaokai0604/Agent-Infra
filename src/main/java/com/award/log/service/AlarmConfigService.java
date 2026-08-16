package com.award.log.service;

import com.award.log.model.TaskAlarmConfig;

import java.util.Map;

public interface AlarmConfigService {
    TaskAlarmConfig getDefaultConfig();

    TaskAlarmConfig getTaskConfig(String taskId);

    TaskAlarmConfig getEffectiveConfig(String taskId);

    TaskAlarmConfig saveDefaultConfig(Map<String, Object> rawConfig);

    TaskAlarmConfig saveTaskConfig(String taskId, Map<String, Object> rawConfig);
}
