package com.award.log.service;

import lombok.Data;

/**
 * 存储统计信息
 * 用于返回存储策略的统计数据
 */
@Data
public class StorageStats {

    /**
     * 热数据存储大小（字节）
     */
    private long hotStorageSize;

    /**
     * 温数据存储大小（字节）
     */
    private long warmStorageSize;

    /**
     * 冷数据存储大小（字节）
     */
    private long coldStorageSize;

    /**
     * 热数据日志数量
     */
    private long hotLogCount;

    /**
     * 温数据日志数量
     */
    private long warmLogCount;

    /**
     * 冷数据日志数量
     */
    private long coldLogCount;

    /**
     * 总存储大小（字节）
     */
    private long totalStorageSize;

    /**
     * 总日志数量
     */
    private long totalLogCount;

    /**
     * 计算总存储大小
     */
    public void calculateTotals() {
        this.totalStorageSize = hotStorageSize + warmStorageSize + coldStorageSize;
        this.totalLogCount = hotLogCount + warmLogCount + coldLogCount;
    }
}
