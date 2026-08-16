package com.award.log.service.impl;

import com.award.log.model.LogDocument;
import com.award.log.repository.LogDocumentRepository;
import com.award.log.service.ElasticsearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Elasticsearch服务实现类
 * 实现Elasticsearch的核心功能
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "spring.elasticsearch.enabled", havingValue = "true")
public class ElasticsearchServiceImpl implements ElasticsearchService {

    @Resource
    private LogDocumentRepository logDocumentRepository;

    @Resource
    private ElasticsearchOperations elasticsearchOperations;

    @Override
    public LogDocument indexLog(LogDocument logDocument) {
        try {
            if (logDocument.getId() == null) {
                logDocument.setId(UUID.randomUUID().toString());
            }
            if (logDocument.getTimestamp() == null) {
                logDocument.setTimestamp(LocalDateTime.now());
            }
            LogDocument saved = logDocumentRepository.save(logDocument);
            log.debug("[Elasticsearch] 索引日志成功: {}", saved.getId());
            return saved;
        } catch (Exception e) {
            log.error("[Elasticsearch] 索引日志失败", e);
            throw new RuntimeException("索引日志失败", e);
        }
    }

    @Override
    public int bulkIndexLogs(List<LogDocument> logDocuments) {
        try {
            logDocuments.forEach(doc -> {
                if (doc.getId() == null) {
                    doc.setId(UUID.randomUUID().toString());
                }
                if (doc.getTimestamp() == null) {
                    doc.setTimestamp(LocalDateTime.now());
                }
            });
            Iterable<LogDocument> saved = logDocumentRepository.saveAll(logDocuments);
            int count = 0;
            for (LogDocument doc : saved) {
                count++;
            }
            log.info("[Elasticsearch] 批量索引日志成功: {} 条", count);
            return count;
        } catch (Exception e) {
            log.error("[Elasticsearch] 批量索引日志失败", e);
            throw new RuntimeException("批量索引日志失败", e);
        }
    }

    @Override
    public LogDocument getLogById(String id) {
        try {
            return logDocumentRepository.findById(id).orElse(null);
        } catch (Exception e) {
            log.error("[Elasticsearch] 查询日志失败: {}", id, e);
            throw new RuntimeException("查询日志失败", e);
        }
    }

    @Override
    public Page<LogDocument> searchLogs(String query, Pageable pageable) {
        return searchLogs(query, null, null, null, null, pageable);
    }

    @Override
    public Page<LogDocument> searchLogs(String query, String severity, Boolean anomaly, LocalDateTime startTime, LocalDateTime endTime, Pageable pageable) {
        try {
            String searchQuery = buildSearchQuery(query, severity, anomaly, startTime, endTime);
            StringQuery stringQuery = new StringQuery(searchQuery);
            stringQuery.setPageable(pageable);
            SearchHits<LogDocument> searchHits = elasticsearchOperations.search(stringQuery, LogDocument.class);

            List<LogDocument> content = new java.util.ArrayList<>();
            for (SearchHit<LogDocument> hit : searchHits.getSearchHits()) {
                try {
                    LogDocument doc = hit.getContent();
                    if (doc != null) {
                        content.add(doc);
                    }
                } catch (Exception e) {
                    log.warn("跳过无法解析的日志记录: {}", e.getMessage());
                }
            }
            Page<LogDocument> result = new org.springframework.data.domain.PageImpl<>(content, pageable, searchHits.getTotalHits());
            log.debug("[Elasticsearch] 搜索日志成功，关键词: {}, 级别: {}, 异常: {}, 时间范围: {} - {}", query, severity, anomaly, startTime, endTime);
            return result;
        } catch (Exception e) {
            log.error("[Elasticsearch] 搜索日志失败: {}", query, e);
            throw new RuntimeException("搜索日志失败", e);
        }
    }

    private String buildSearchQuery(String query, String severity, Boolean anomaly, LocalDateTime startTime, LocalDateTime endTime) {
        // StringQuery 需要 query 内部 DSL（bool/...），不要包 {"query":...} 外壳
        StringBuilder builder = new StringBuilder();
        builder.append("{\"bool\":{\"must\":[");
        boolean hasCondition = false;

        if (query != null && !query.trim().isEmpty()) {
            builder.append("{\"match\":{\"content\":\"").append(escapeJson(query.trim())).append("\"}}");
            hasCondition = true;
        }

        if (severity != null && !severity.trim().isEmpty()) {
            if (hasCondition) {
                builder.append(",");
            }
            builder.append("{\"term\":{\"severity\":\"").append(escapeJson(severity.trim())).append("\"}}");
            hasCondition = true;
        }

        if (anomaly != null) {
            if (hasCondition) {
                builder.append(",");
            }
            builder.append("{\"term\":{\"anomaly\":").append(anomaly).append("}}");
            hasCondition = true;
        }

        if (startTime != null && endTime != null) {
            if (hasCondition) {
                builder.append(",");
            }
            builder.append("{\"range\":{\"@timestamp\":{\"gte\":\"")
                    .append(startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append("\",\"lte\":\"")
                    .append(endTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append("\"}}}");
            hasCondition = true;
        }

        if (!hasCondition) {
            builder.append("{\"match_all\":{}}");
        }

        builder.append("]}}");
        return builder.toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public SearchHits<LogDocument> advancedSearch(Query query) {
        try {
            SearchHits<LogDocument> result = elasticsearchOperations.search(query, LogDocument.class);
            log.debug("[Elasticsearch] 高级搜索成功");
            return result;
        } catch (Exception e) {
            log.error("[Elasticsearch] 高级搜索失败", e);
            throw new RuntimeException("高级搜索失败", e);
        }
    }

    @Override
    public Page<LogDocument> getLogsByTimeRange(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable) {
        try {
            return logDocumentRepository.findByTimestampBetween(startTime, endTime, pageable);
        } catch (Exception e) {
            log.error("[Elasticsearch] 按时间范围查询日志失败", e);
            throw new RuntimeException("按时间范围查询日志失败", e);
        }
    }

    @Override
    public Page<LogDocument> getLogsBySeverity(String severity, Pageable pageable) {
        try {
            return logDocumentRepository.findBySeverity(severity, pageable);
        } catch (Exception e) {
            log.error("[Elasticsearch] 按严重程度查询日志失败: {}", severity, e);
            throw new RuntimeException("按严重程度查询日志失败", e);
        }
    }

    @Override
    public Page<LogDocument> getLogsByTaskId(String taskId, Pageable pageable) {
        try {
            return logDocumentRepository.findByTaskId(taskId, pageable);
        } catch (Exception e) {
            log.error("[Elasticsearch] 按任务ID查询日志失败: {}", taskId, e);
            throw new RuntimeException("按任务ID查询日志失败", e);
        }
    }

    @Override
    public Page<LogDocument> getAnomalyLogs(Pageable pageable) {
        try {
            return logDocumentRepository.findByAnomaly(true, pageable);
        } catch (Exception e) {
            log.error("[Elasticsearch] 查询异常日志失败", e);
            throw new RuntimeException("查询异常日志失败", e);
        }
    }

    @Override
    public long deleteLogsBefore(LocalDateTime beforeTime) {
        try {
            // 这里需要实现按时间范围删除的逻辑
            // 由于Spring Data Elasticsearch没有直接的删除方法，需要先查询再删除
            List<LogDocument> logs = logDocumentRepository.findByTimestampBetween(LocalDateTime.MIN, beforeTime);
            logDocumentRepository.deleteAll(logs);
            log.info("[Elasticsearch] 删除时间范围之前的日志成功: {} 条", logs.size());
            return logs.size();
        } catch (Exception e) {
            log.error("[Elasticsearch] 删除日志失败", e);
            throw new RuntimeException("删除日志失败", e);
        }
    }

    @Override
    public long deleteLogsByTaskId(String taskId) {
        try {
            List<LogDocument> logs = logDocumentRepository.findByTaskId(taskId);
            logDocumentRepository.deleteAll(logs);
            log.info("[Elasticsearch] 删除任务日志成功: {} 条", logs.size());
            return logs.size();
        } catch (Exception e) {
            log.error("[Elasticsearch] 删除任务日志失败: {}", taskId, e);
            throw new RuntimeException("删除任务日志失败", e);
        }
    }

    @Override
    public Object getLogStatistics(String field) {
        try {
            // 这里需要实现统计逻辑
            // 可以使用Elasticsearch的聚合功能
            log.debug("[Elasticsearch] 获取日志统计信息: {}", field);
            return null;
        } catch (Exception e) {
            log.error("[Elasticsearch] 获取日志统计信息失败: {}", field, e);
            throw new RuntimeException("获取日志统计信息失败", e);
        }
    }
}
