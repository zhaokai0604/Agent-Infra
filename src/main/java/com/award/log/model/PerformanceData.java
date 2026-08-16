package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 性能数据实体类
 * 用于存储性能分析数据
 */
@Data
public class PerformanceData {

    /**
     * 数据ID
     */
    private Long id;

    /**
     * 数据类型
     * CPU: CPU使用率
     * MEMORY: 内存使用率
     * DISK: 磁盘使用率
     * NETWORK: 网络使用率
     * LOAD: 系统负载
     * RESPONSE: 响应时间
     */
    private String dataType;

    /**
     * 数据值
     */
    private Double value;

    /**
     * 数据单位
     */
    private String unit;

    /**
     * 采集时间
     */
    private LocalDateTime collectTime;

    /**
     * 主机名称
     */
    private String hostname;

    /**
     * 实例名称
     */
    private String instanceName;

    /**
     * 备注信息
     */
    private String remark;
}
