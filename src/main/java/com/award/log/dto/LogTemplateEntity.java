package com.award.log.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志模板实体类（Drain算法聚类结果载体，支持序列化持久化）
 * 创新点：新增模板风险权重、协议关联度，用于频率异常检测的加权计算
 */
import lombok.Getter;
import lombok.Setter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志模板实体类（Drain算法聚类结果载体）
 */
@Getter
@Setter
public class LogTemplateEntity implements Serializable {
    private static final long serialVersionUID = 1001L;
    
    private String templateId;          // 模板唯一标识
    private String templateContent;     // 归一化后的模板内容
    private List<String> paramList;     // 模板提取的变量列表
    
    @Setter(lombok.AccessLevel.NONE)
    private AtomicLong occurCount;      // 出现频次（使用原子类保证并发安全）
    
    private double anomalyScore;        // 模板整体异常得分
    private Set<String> protocolSet;    // 关联的协议类型集合
    private double riskWeight;          // 模板风险权重

    public LogTemplateEntity(String templateId, String templateContent) {
        this.templateId = templateId;
        this.templateContent = templateContent;
        this.paramList = new ArrayList<>();
        this.occurCount = new AtomicLong(1);
        this.anomalyScore = 0.0;
        this.protocolSet = new HashSet<>();
        this.riskWeight = 1.0;
    }
}
