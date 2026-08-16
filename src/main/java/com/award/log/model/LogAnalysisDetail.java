package com.award.log.model;

import lombok.Data;

@Data
public class LogAnalysisDetail {
    private Long id;                // 明细记录唯一ID
    private String taskId;          // 所属分析任务ID
    private String logTime;         // 日志记录的发生时间
    private String severity;        // 日志等级 (如 INFO, ERROR)
    private String protocol;        // 识别出的协议类型
    private String pid;             // 进程ID或Windows事件ID
    private Boolean isAnomaly;      // 是否判定为异常
    private Double anomalyScore;    // 异常得分 (0-1)
    private String anomalyReasons;  // 异常判定原因描述
    private String desensitizedLog; // 脱敏后的日志正文
    private String templateId;      // 聚合后的日志模板ID
}
