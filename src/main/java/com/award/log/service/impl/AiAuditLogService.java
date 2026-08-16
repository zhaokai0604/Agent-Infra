package com.award.log.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public AiAuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS ai_audit_log (" +
                            "id BIGINT PRIMARY KEY AUTO_INCREMENT," +
                            "user_id VARCHAR(64)," +
                            "user_role VARCHAR(32)," +
                            "remote_ip VARCHAR(64)," +
                            "method VARCHAR(16)," +
                            "path VARCHAR(255)," +
                            "status INT," +
                            "duration_ms BIGINT," +
                            "request_bytes INT," +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );
        } catch (Exception e) {
            log.warn("ai_audit_log 表初始化失败（请确认数据库已启动且已执行 schema.sql）: {}", e.getMessage());
        }
    }

    public void save(Object userId, Object role, String remoteIp, String method, String path, int status, long durationMs, int requestBytes) {
        jdbcTemplate.update(
                "INSERT INTO ai_audit_log(user_id,user_role,remote_ip,method,path,status,duration_ms,request_bytes,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId == null ? null : String.valueOf(userId),
                role == null ? null : String.valueOf(role),
                remoteIp,
                method,
                path,
                status,
                durationMs,
                requestBytes,
                new Timestamp(System.currentTimeMillis())
        );
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.queryForList("SELECT * FROM ai_audit_log ORDER BY id DESC LIMIT ?", size);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM ai_audit_log WHERE id = ? LIMIT 1", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 个人中心「登录 / 请求足迹」：按用户过滤 HTTP 审计记录 */
    public List<Map<String, Object>> listRecentByUserId(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT id, method, path, status, duration_ms, remote_ip, created_at FROM ai_audit_log "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                userId,
                size);
    }

    public List<Map<String, Object>> listRecentByUserId(String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safePageSize;
        return jdbcTemplate.queryForList(
                "SELECT id, method, path, status, duration_ms, remote_ip, created_at FROM ai_audit_log "
                        + "WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                userId,
                safePageSize,
                offset);
    }

    public long countByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_log WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }
}
