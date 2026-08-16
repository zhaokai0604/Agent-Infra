package com.award.log.dto;

import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 日志解析结果实体（全字段覆盖，结构化存储核心数据）
 * 创新点：新增风险加权得分、特征向量哈希，支持后续大数据扩展
 */
import lombok.Getter;
import lombok.Setter;
import com.award.log.model.LogProtocolType;
import com.award.log.model.LogSeverityLevel;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 日志解析结果实体（全字段覆盖，结构化存储核心数据）
 */
@Getter
@Setter
public class EnhancedLogParseResultEntity implements Serializable {
    private static final long serialVersionUID = 1002L;
    
    // 基础脱敏信息
    private String desensitizedLog;     // 分级脱敏后日志内容
    private String logTime;             // 标准化时间
    private long timestamp;             // 毫秒级时间戳
    /** 全文件行序（从 0 递增）；分片合并后写入，供 AI 上下文按时间线排序 */
    private int sourceLineIndex = -1;
    
    // 分类特征信息
    private LogSeverityLevel severity;  // 日志等级
    private LogProtocolType protocol;   // 日志协议类型
    
    // 提取维度信息
    private String pid;                 // 进程ID/事件ID
    private String uid;                 // 用户ID
    private String traceId;             // 调用链ID
    private String stackTrace;          // 异常调用栈
    private String templateId;          // 匹配模板ID
    
    // 异常检测结果
    private boolean isAnomaly;          // 是否异常标记
    private double anomalyScore;        // 最终异常得分
    private Set<String> anomalyReasons; // 多维度异常原因集合
    private Map<String, Object> featureMap; // 四维特征Map
    

    
    @Setter(lombok.AccessLevel.NONE)
    private double riskWeightScore;     // 风险加权得分（由等级联动计算，不开放直接设置）

    public EnhancedLogParseResultEntity(String desensitizedLog) {
        this.desensitizedLog = desensitizedLog;
        this.anomalyReasons = new LinkedHashSet<>();
        this.featureMap = new HashMap<>(8);
        this.timestamp = System.currentTimeMillis();
        this.severity = LogSeverityLevel.UNKNOWN_LEVEL;
        this.protocol = LogProtocolType.UNKNOWN_LOG_TYPE;
        this.pid = "未提取";
        this.uid = "未提取";
        this.traceId = "未提取";
        this.stackTrace = "无异常栈";
        this.templateId = "未匹配模板";
        this.isAnomaly = false;
        this.anomalyScore = 0.0;
        this.riskWeightScore = 1.0;
    }

    /**
     * 自定义 Setter，确保设置等级时自动更新风险得分
     */
    public void setSeverity(LogSeverityLevel severity) {
        this.severity = severity;
        if (severity != null) {
            this.riskWeightScore = severity.getSeverityScore() * severity.getWeightFactor();
        }
    }
}
