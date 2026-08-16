package com.award.log.repository;

import com.award.log.model.LogDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Repository
public interface LogDocumentRepository extends ElasticsearchRepository<LogDocument, String> {

    List<LogDocument> findByTimestampBetween(java.time.LocalDateTime start, java.time.LocalDateTime end);
    Page<LogDocument> findByTimestampBetween(java.time.LocalDateTime start, java.time.LocalDateTime end, Pageable pageable);

    List<LogDocument> findByProtocol(String protocol);

    List<LogDocument> findBySeverity(String severity);
    Page<LogDocument> findBySeverity(String severity, Pageable pageable);

    List<LogDocument> findByAnomaly(Boolean anomaly);
    Page<LogDocument> findByAnomaly(Boolean anomaly, Pageable pageable);

    List<LogDocument> findByTaskId(String taskId);
    Page<LogDocument> findByTaskId(String taskId, Pageable pageable);
}