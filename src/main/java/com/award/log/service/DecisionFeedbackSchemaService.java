package com.award.log.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 启动时补齐 decision_feedback 训练样本相关字段，避免旧库结构导致持续学习任务报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionFeedbackSchemaService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ensureTable();
            migrateLegacyColumns();
        } catch (Exception e) {
            log.warn("decision_feedback 表结构检查失败（请确认数据库已启动且具备 DDL 权限）: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS decision_feedback (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  decision_id VARCHAR(64) NOT NULL,
                  actual_alert TINYINT(1) NOT NULL DEFAULT 0,
                  reviewer VARCHAR(64) DEFAULT NULL,
                  remark VARCHAR(500) DEFAULT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  log_content TEXT NULL,
                  log_level VARCHAR(32) DEFAULT NULL,
                  log_template VARCHAR(255) DEFAULT NULL,
                  model_confidence DOUBLE DEFAULT NULL,
                  is_trained TINYINT(1) DEFAULT 0,
                  error_rate_1m DOUBLE DEFAULT NULL,
                  error_1m DOUBLE DEFAULT NULL,
                  total_1m DOUBLE DEFAULT NULL,
                  interval_ms DOUBLE DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_decision_feedback (decision_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策人工反馈表'
                """);
    }

    private void migrateLegacyColumns() {
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN log_content TEXT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN log_level VARCHAR(32) DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN log_template VARCHAR(255) DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN model_confidence DOUBLE DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN is_trained TINYINT(1) DEFAULT 0");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN error_rate_1m DOUBLE DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN error_1m DOUBLE DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN total_1m DOUBLE DEFAULT NULL");
        tryAlter("ALTER TABLE decision_feedback ADD COLUMN interval_ms DOUBLE DEFAULT NULL");
        tryUpdate("UPDATE decision_feedback SET is_trained = 0 WHERE is_trained IS NULL");
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.info("decision_feedback migrate applied: {}", sql);
        } catch (Exception e) {
            log.debug("decision_feedback migrate skip: {}", e.getMessage());
        }
    }

    private void tryUpdate(String sql) {
        try {
            jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.debug("decision_feedback bootstrap skip: {}", e.getMessage());
        }
    }
}
