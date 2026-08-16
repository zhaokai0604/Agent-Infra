package com.award.log.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 启动时补齐 log_analysis_task 旧库缺字段（如 user_id），避免历史任务分页查询失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalysisTaskSchemaService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            migrateLegacyColumns();
        } catch (Exception e) {
            log.warn("log_analysis_task 表结构检查失败（请确认数据库已启动且具备 DDL 权限）: {}", e.getMessage());
        }
    }

    private void migrateLegacyColumns() {
        tryAlter("ALTER TABLE log_analysis_task ADD COLUMN user_id int(11) DEFAULT NULL COMMENT '创建用户ID' AFTER task_id");
        tryAlter("ALTER TABLE log_analysis_task ADD KEY idx_user_id (user_id)");
        tryAlter("""
                ALTER TABLE log_analysis_task
                ADD CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE SET NULL
                """);
    }

    private void tryAlter(String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.info("log_analysis_task migrate applied: {}", sql.lines().findFirst().orElse(sql));
        } catch (Exception e) {
            log.debug("log_analysis_task migrate skip: {}", e.getMessage());
        }
    }
}
