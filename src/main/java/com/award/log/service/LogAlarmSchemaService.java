package com.award.log.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 启动时确保 log_alarm 具备真实告警生命周期所需字段，避免旧库结构导致写链路和升级链路失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAlarmSchemaService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ensureTable();
            migrateLegacyTable();
            bootstrapLegacyValues();
        } catch (Exception e) {
            log.warn("log_alarm 表结构检查失败（请确认数据库已启动且具备 DDL 权限）: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS log_alarm (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '告警ID',
                  alarm_id VARCHAR(64) NOT NULL COMMENT '告警唯一标识',
                  task_id VARCHAR(64) NOT NULL COMMENT '关联任务ID',
                  level VARCHAR(32) NOT NULL COMMENT '告警级别',
                  root_cause TEXT NULL COMMENT '根因分析',
                  solution TEXT NULL COMMENT '解决方案',
                  log_content TEXT NULL COMMENT '关联日志内容',
                  push_status VARCHAR(64) DEFAULT 'PENDING' COMMENT '推送状态',
                  lifecycle_status VARCHAR(32) DEFAULT 'NEW' COMMENT '生命周期状态',
                  ack_by VARCHAR(64) DEFAULT NULL COMMENT '确认人',
                  ack_time DATETIME DEFAULT NULL COMMENT '确认时间',
                  handled_by VARCHAR(64) DEFAULT NULL COMMENT '处理人',
                  handled_time DATETIME DEFAULT NULL COMMENT '处理时间',
                  closed_time DATETIME DEFAULT NULL COMMENT '关闭时间',
                  escalation_level INT DEFAULT 0 COMMENT '升级等级',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_alarm_id (alarm_id),
                  KEY idx_task_id (task_id),
                  KEY idx_level (level),
                  KEY idx_push_status (push_status),
                  KEY idx_lifecycle_status (lifecycle_status),
                  KEY idx_create_time (create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志告警表'
                """);
    }

    private void migrateLegacyTable() {
        tryAlter("ALTER TABLE log_alarm ADD COLUMN lifecycle_status VARCHAR(32) DEFAULT 'NEW' COMMENT '生命周期状态'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN ack_by VARCHAR(64) DEFAULT NULL COMMENT '确认人'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN ack_time DATETIME DEFAULT NULL COMMENT '确认时间'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN handled_by VARCHAR(64) DEFAULT NULL COMMENT '处理人'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN handled_time DATETIME DEFAULT NULL COMMENT '处理时间'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN closed_time DATETIME DEFAULT NULL COMMENT '关闭时间'");
        tryAlter("ALTER TABLE log_alarm ADD COLUMN escalation_level INT DEFAULT 0 COMMENT '升级等级'");
        tryAlter("ALTER TABLE log_alarm ADD INDEX idx_lifecycle_status (lifecycle_status)");
    }

    private void bootstrapLegacyValues() {
        tryUpdate("UPDATE log_alarm SET lifecycle_status = 'NEW' WHERE lifecycle_status IS NULL OR lifecycle_status = ''");
        tryUpdate("UPDATE log_alarm SET escalation_level = 0 WHERE escalation_level IS NULL");
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("log_alarm migrate skip: {}", e.getMessage());
        }
    }

    private void tryUpdate(String sql) {
        try {
            jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.debug("log_alarm bootstrap skip: {}", e.getMessage());
        }
    }
}
