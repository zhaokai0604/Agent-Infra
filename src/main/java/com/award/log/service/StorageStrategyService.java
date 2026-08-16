package com.award.log.service;

import com.award.log.model.LogDocument;
import com.award.log.model.StorageLevel;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储策略服务接口
 * 用于管理日志的存储和归档
 */
public interface StorageStrategyService {

    /**
     * 根据时间确定存储级别
     * @param timestamp 日志时间戳
     * @return 存储级别
     */
    StorageLevel determineStorageLevel(LocalDateTime timestamp);

    /**
     * 存储日志
     * @param logDocument 日志文档
     * @param storageLevel 存储级别
     * @return 存储结果
     */
    boolean storeLog(LogDocument logDocument, StorageLevel storageLevel);

    /**
     * 批量存储日志
     * @param logDocuments 日志文档列表
     * @param storageLevel 存储级别
     * @return 存储成功的数量
     */
    int batchStoreLogs(List<LogDocument> logDocuments, StorageLevel storageLevel);

    /**
     * 执行日志归档
     * @param fromLevel 源存储级别
     * @param toLevel 目标存储级别
     * @param beforeTime 归档时间点之前的日志
     * @return 归档成功的数量
     */
    int archiveLogs(StorageLevel fromLevel, StorageLevel toLevel, LocalDateTime beforeTime);

    /**
     * 清理过期日志
     * @param storageLevel 存储级别
     * @param beforeTime 清理时间点之前的日志
     * @return 清理成功的数量
     */
    int cleanupExpiredLogs(StorageLevel storageLevel, LocalDateTime beforeTime);

    /**
     * 执行定时归档任务
     * 自动将热数据归档为温数据，温数据归档为冷数据
     */
    void executeScheduledArchive();

    /**
     * 获取存储统计信息
     * @return 存储统计信息
     */
    StorageStats getStorageStats();
}
