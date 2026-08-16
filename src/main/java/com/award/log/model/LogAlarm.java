package com.award.log.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 日志告警模型
 * 用于存储AI生成的告警信息和推送状态
 */
@Data
public class LogAlarm {
    private Integer id;             // 自增主键ID
    private String alarmId;         // 告警唯一标识
    private String taskId;          // 关联的日志分析任务ID
    private String logContent;      // 触发告警的日志内容
    private String level;           // 告警级别：FATAL/ERROR/WARNING/INFO
    private String rootCause;       // AI分析的根因
    private String solution;        // AI提供的解决方案
    private String pushChannels;    // 历史字段，外发渠道已下线
    private String pushStatus;      // RECORDED / SKIPPED:* / SUCCESS（历史）
    private String lifecycleStatus; // 生命周期状态：NEW/ACKNOWLEDGED/HANDLED/CLOSED
    private String ackBy;           // 确认人
    private LocalDateTime ackTime;  // 确认时间
    private String handledBy;       // 处理人
    private LocalDateTime handledTime; // 处理时间
    private LocalDateTime closedTime;  // 关闭时间
    private Integer escalationLevel;   // 告警升级级别
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间
}