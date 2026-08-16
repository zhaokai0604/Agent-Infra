package com.award.log.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 启动时确保 alarm_rule 表可用于真实增删改，避免仅内存演示或旧库缺字段导致规则链路失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmRuleSchemaService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ensureTable();
            migrateLegacyColumns();
            bootstrapLegacyValues();
        } catch (Exception e) {
            log.warn("alarm_rule 表结构检查失败（请确认数据库已启动且具备 DDL 权限）: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS alarm_rule (
                  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
                  name VARCHAR(100) NOT NULL COMMENT '规则名称',
                  description VARCHAR(500) DEFAULT NULL COMMENT '规则描述',
                  rule_type VARCHAR(32) NOT NULL DEFAULT 'COMBINATION' COMMENT '规则类型',
                  rule_expression TEXT NOT NULL COMMENT '规则表达式',
                  severity VARCHAR(20) NOT NULL DEFAULT 'ERROR' COMMENT '告警级别',
                  push_channels VARCHAR(50) NOT NULL DEFAULT 'BOTH' COMMENT '推送渠道',
                  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                  create_by VARCHAR(50) DEFAULT NULL COMMENT '创建人',
                  update_by VARCHAR(50) DEFAULT NULL COMMENT '更新人',
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则表'
                """);
    }

    private void migrateLegacyColumns() {
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN description VARCHAR(500) DEFAULT NULL COMMENT '规则描述'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN rule_type VARCHAR(32) NOT NULL DEFAULT 'COMBINATION' COMMENT '规则类型'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN rule_expression TEXT NULL COMMENT '规则表达式'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'ERROR' COMMENT '告警级别'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN push_channels VARCHAR(50) NOT NULL DEFAULT 'BOTH' COMMENT '推送渠道'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN create_by VARCHAR(50) DEFAULT NULL COMMENT '创建人'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN update_by VARCHAR(50) DEFAULT NULL COMMENT '更新人'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'");
        tryAlter("ALTER TABLE alarm_rule ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'");
    }

    private void bootstrapLegacyValues() {
        tryUpdate("UPDATE alarm_rule SET description = name WHERE description IS NULL OR description = ''");
        tryUpdate("UPDATE alarm_rule SET rule_type = 'COMBINATION' WHERE rule_type IS NULL OR rule_type = ''");
        tryUpdate("UPDATE alarm_rule SET severity = 'ERROR' WHERE severity IS NULL OR severity = ''");
        tryUpdate("UPDATE alarm_rule SET push_channels = 'BOTH' WHERE push_channels IS NULL OR push_channels = ''");
        tryUpdate("UPDATE alarm_rule SET enabled = 1 WHERE enabled IS NULL");
        tryUpdate("UPDATE alarm_rule SET create_by = 'system' WHERE create_by IS NULL OR create_by = ''");
        tryUpdate("UPDATE alarm_rule SET update_by = 'system' WHERE update_by IS NULL OR update_by = ''");
        tryUpdate("UPDATE alarm_rule SET rule_expression = name WHERE (rule_expression IS NULL OR rule_expression = '') AND name IS NOT NULL");
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.debug("alarm_rule migrate skip: {}", e.getMessage());
        }
    }

    private void tryUpdate(String sql) {
        try {
            jdbcTemplate.update(sql);
        } catch (Exception e) {
            log.debug("alarm_rule bootstrap skip: {}", e.getMessage());
        }
    }
}
