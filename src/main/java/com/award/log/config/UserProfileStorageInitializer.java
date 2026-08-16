package com.award.log.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserProfileStorageInitializer {

    private final JdbcTemplate jdbcTemplate;

    public UserProfileStorageInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS user_profile_preference (" +
                        "user_id INT NOT NULL PRIMARY KEY," +
                        "email_enabled TINYINT(1) DEFAULT 0," +
                        "sms_enabled TINYINT(1) DEFAULT 0," +
                        "task_alerts TINYINT(1) DEFAULT 1," +
                        "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                        "CONSTRAINT fk_user_profile_preference_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE" +
                        ")"
        );
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS user_api_key (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                        "user_id INT NOT NULL," +
                        "key_name VARCHAR(64) NOT NULL," +
                        "key_prefix VARCHAR(32) NOT NULL," +
                        "key_hash VARCHAR(255) NOT NULL," +
                        "scope_bundle VARCHAR(64) NOT NULL," +
                        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                        "last_used_at DATETIME DEFAULT NULL," +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                        "revoked_at DATETIME DEFAULT NULL," +
                        "KEY idx_user_api_key_user (user_id)," +
                        "KEY idx_user_api_key_prefix (key_prefix)," +
                        "CONSTRAINT fk_user_api_key_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id) ON DELETE CASCADE" +
                        ")"
        );
    }
}
