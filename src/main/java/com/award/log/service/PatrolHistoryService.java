package com.award.log.service;

import com.alibaba.fastjson.JSON;
import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检历史持久化（ops_patrol_history）。
 */
@Slf4j
@Service
public class PatrolHistoryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${ops.patrol.history-retention-days:30}")
    private int historyRetentionDays;

    public PatrolHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureTable() {
        try {
            jdbcTemplate.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ops_patrol_history (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      patrol_time DATETIME NOT NULL,
                      finding_count INT NOT NULL DEFAULT 0,
                      correlation_json LONGTEXT,
                      findings_json LONGTEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_patrol_time (patrol_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维巡检历史'
                    """);
        } catch (Exception e) {
            log.warn("创建 ops_patrol_history 表失败（可能无库权限）: {}", e.getMessage());
        }
    }

    public void saveRun(PatrolCorrelationSnapshot correlation, List<PatrolFinding> findings) {
        try {
            String corrJson = JSON.toJSONString(correlation != null ? correlation.toMap() : Map.of());
            List<Map<String, Object>> findingMaps = findings == null
                    ? List.of()
                    : findings.stream().map(PatrolFinding::toMap).toList();
            String findingsJson = JSON.toJSONString(findingMaps);
            jdbcTemplate.update(
                    "INSERT INTO ops_patrol_history(patrol_time, finding_count, correlation_json, findings_json) VALUES (?,?,?,?)",
                    LocalDateTime.now(),
                    findingMaps.size(),
                    corrJson,
                    findingsJson);
        } catch (Exception e) {
            log.warn("持久化巡检历史失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> listHistory(int days, int limit) {
        int d = Math.min(30, Math.max(1, days));
        int n = Math.min(200, Math.max(1, limit));
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                    SELECT id, patrol_time, finding_count, correlation_json, findings_json, created_at
                    FROM ops_patrol_history
                    WHERE patrol_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    ORDER BY patrol_time DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("patrolTime", rs.getTimestamp("patrol_time").toLocalDateTime().toString());
                        row.put("findingCount", rs.getInt("finding_count"));
                        row.put("correlation", JSON.parseObject(rs.getString("correlation_json")));
                        row.put("findings", JSON.parseArray(rs.getString("findings_json")));
                        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
                        return row;
                    },
                    d,
                    n);
            return rows;
        } catch (Exception e) {
            log.warn("查询巡检历史失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> countByDay(int days) {
        int d = Math.min(30, Math.max(1, days));
        try {
            return jdbcTemplate.query(
                    """
                    SELECT DATE(patrol_time) AS day, SUM(finding_count) AS alert_count, COUNT(*) AS run_count
                    FROM ops_patrol_history
                    WHERE patrol_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    GROUP BY DATE(patrol_time)
                    ORDER BY day ASC
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("day", rs.getDate("day").toString());
                        row.put("alertCount", rs.getLong("alert_count"));
                        row.put("runCount", rs.getLong("run_count"));
                        return row;
                    },
                    d);
        } catch (Exception e) {
            log.warn("巡检趋势统计失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 返回巡检期间的资源时序，并计算相对上一采样点的变化量。 */
    public List<Map<String, Object>> metricsTrend(int days, int limit) {
        int d = Math.min(90, Math.max(1, days));
        int n = Math.min(2_000, Math.max(1, limit));
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                    SELECT patrol_time, correlation_json
                    FROM ops_patrol_history
                    WHERE patrol_time >= DATE_SUB(NOW(), INTERVAL ? DAY)
                    ORDER BY patrol_time ASC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> correlation = JSON.parseObject(rs.getString("correlation_json"));
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("timestamp", rs.getTimestamp("patrol_time").toLocalDateTime().toString());
                        copyMetric(correlation, row, "cpuUsagePct");
                        copyMetric(correlation, row, "memoryUsagePct");
                        copyMetric(correlation, row, "diskUsagePct");
                        copyMetric(correlation, row, "loadAvg1min");
                        copyMetric(correlation, row, "loadAvg5min");
                        copyMetric(correlation, row, "loadAvg15min");
                        copyMetric(correlation, row, "alarmTotal24h");
                        copyMetric(correlation, row, "zombieProcesses");
                        return row;
                    },
                    d,
                    n);
            Map<String, Object> previous = null;
            for (Map<String, Object> row : rows) {
                addDelta(row, previous, "cpuUsagePct");
                addDelta(row, previous, "memoryUsagePct");
                addDelta(row, previous, "diskUsagePct");
                previous = row;
            }
            return rows;
        } catch (Exception e) {
            log.warn("查询资源时序失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static void copyMetric(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.get(key) instanceof Number number) {
            target.put(key, number.doubleValue());
        }
    }

    private static void addDelta(Map<String, Object> row, Map<String, Object> previous, String key) {
        if (previous == null || !(row.get(key) instanceof Number current) || !(previous.get(key) instanceof Number before)) {
            return;
        }
        row.put("delta" + Character.toUpperCase(key.charAt(0)) + key.substring(1),
                current.doubleValue() - before.doubleValue());
    }

    /** 清理超出保留期的巡检历史，避免表无限增长 */
    @Scheduled(cron = "${ops.patrol.history-cleanup-cron:0 15 4 * * ?}")
    public void purgeExpiredHistory() {
        int days = Math.max(7, historyRetentionDays);
        try {
            int removed = jdbcTemplate.update(
                    "DELETE FROM ops_patrol_history WHERE patrol_time < DATE_SUB(NOW(), INTERVAL ? DAY)",
                    days);
            if (removed > 0) {
                log.info("[巡检历史] 已清理 {} 条超过 {} 天的记录", removed, days);
            }
        } catch (Exception e) {
            log.warn("[巡检历史] 清理失败: {}", e.getMessage());
        }
    }
}
