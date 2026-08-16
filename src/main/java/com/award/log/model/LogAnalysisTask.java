package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LogAnalysisTask {
    private String taskId;
    private Integer userId;
    private String fileName;
    private String status;
    private Integer progress;
    private String currentStep;
    private String errorMsg;
    private String aiDiagnosis;
    private Integer totalLogs;
    private Integer anomalyCount;
    private Double anomalyRate;
    private Long costTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
