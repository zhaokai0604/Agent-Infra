package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;
@Data
public class TaskAlarmConfig {
    private String taskId;
    private String alarmLevel;
    private Integer errorThreshold;
    private Boolean enabled;
    private Long cooldownMs;
    private LocalDateTime updatedAt;
}
