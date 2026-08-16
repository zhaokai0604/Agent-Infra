package com.award.log.service;

import com.award.log.model.LogDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch服务接口
 * 用于处理日志的索引、查询和管理操作
 */
public interface ElasticsearchService {

    /**
     * 索引单条日志
     * @param logDocument 日志文档
     * @return 索引后的日志文档
     */
    LogDocument indexLog(LogDocument logDocument);

    /**
     * 批量索引日志
     * @param logDocuments 日志文档列表
     * @return 索引成功的数量
     */
    int bulkIndexLogs(List<LogDocument> logDocuments);

    /**
     * 根据ID查询日志
     * @param id 日志ID
     * @return 日志文档
     */
    LogDocument getLogById(String id);

    /**
     * 全文检索日志
     * @param query 搜索关键词
     * @param pageable 分页参数
     * @return 搜索结果
     */
    Page<LogDocument> searchLogs(String query, Pageable pageable);

    /**
     * 多条件检索日志
     * @param query 搜索关键词（可选）
     * @param severity 日志级别（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @param pageable 分页参数
     * @return 搜索结果
     */
    Page<LogDocument> searchLogs(String query, String severity, Boolean anomaly, LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * 高级搜索日志
     * @param query Elasticsearch查询对象
     * @return 搜索结果
     */
    SearchHits<LogDocument> advancedSearch(Query query);

    /**
     * 根据时间范围查询日志
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param pageable 分页参数
     * @return 日志列表
     */
    Page<LogDocument> getLogsByTimeRange(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * 根据严重程度查询日志
     * @param severity 严重程度
     * @param pageable 分页参数
     * @return 日志列表
     */
    Page<LogDocument> getLogsBySeverity(String severity, Pageable pageable);

    /**
     * 根据任务ID查询日志
     * @param taskId 任务ID
     * @param pageable 分页参数
     * @return 日志列表
     */
    Page<LogDocument> getLogsByTaskId(String taskId, Pageable pageable);

    /**
     * 查询异常日志
     * @param pageable 分页参数
     * @return 异常日志列表
     */
    Page<LogDocument> getAnomalyLogs(Pageable pageable);

    /**
     * 删除指定时间范围之前的日志
     * @param beforeTime 时间点
     * @return 删除的数量
     */
    long deleteLogsBefore(LocalDateTime beforeTime);

    /**
     * 删除指定任务的日志
     * @param taskId 任务ID
     * @return 删除的数量
     */
    long deleteLogsByTaskId(String taskId);

    /**
     * 获取日志统计信息
     * @param field 统计字段
     * @return 统计结果
     */
    Object getLogStatistics(String field);
}
