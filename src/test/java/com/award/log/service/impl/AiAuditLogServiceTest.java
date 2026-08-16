package com.award.log.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AiAuditLogServiceTest {

    private InMemoryJdbc jdbc;
    private AiAuditLogService service;

    @BeforeEach
    void setUp() {
        jdbc = new InMemoryJdbc();
        service = new AiAuditLogService(jdbc);
        service.initTable();
    }

    @Test
    void saveAndListRecentShouldPersistAuditRows() {
        service.save("u1", "ADMIN", "127.0.0.1", "GET", "/api/test", 200, 12L, 64);

        List<Map<String, Object>> rows = service.listRecent(10);
        assertEquals(1, rows.size());
        assertEquals("u1", rows.get(0).get("user_id"));
        assertEquals(200, ((Number) rows.get(0).get("status")).intValue());
    }

    @Test
    void listRecentByUserIdShouldFilterAndPaginate() {
        service.save("u1", "USER", "1.1.1.1", "POST", "/a", 201, 1L, 1);
        service.save("u2", "USER", "2.2.2.2", "GET", "/b", 200, 2L, 2);
        service.save("u1", "USER", "1.1.1.1", "GET", "/c", 200, 3L, 3);

        assertEquals(2, service.listRecentByUserId("u1", 10).size());
        assertEquals(1, service.listRecentByUserId("u1", 1, 1).size());
        assertEquals(2L, service.countByUserId("u1"));
        assertTrue(service.listRecentByUserId(" ", 5).isEmpty());
    }

    @Test
    void listRecentShouldClampLimit() {
        for (int i = 0; i < 3; i++) {
            service.save("u" + i, "USER", "127.0.0.1", "GET", "/x", 200, 1L, 1);
        }
        assertEquals(3, service.listRecent(999).size());
    }

    private static final class InMemoryJdbc extends JdbcTemplate {
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public void execute(String sql) {
            // table creation no-op
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO ai_audit_log")) {
                Map<String, Object> row = new HashMap<>();
                row.put("id", seq.incrementAndGet());
                row.put("user_id", args[0]);
                row.put("user_role", args[1]);
                row.put("remote_ip", args[2]);
                row.put("method", args[3]);
                row.put("path", args[4]);
                row.put("status", args[5]);
                row.put("duration_ms", args[6]);
                row.put("request_bytes", args[7]);
                row.put("created_at", args[8]);
                rows.add(row);
                return 1;
            }
            return 0;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("WHERE user_id = ?") && sql.contains("OFFSET")) {
                String userId = String.valueOf(args[0]);
                int limit = ((Number) args[1]).intValue();
                int offset = ((Number) args[2]).intValue();
                return filterByUser(userId).stream().skip(offset).limit(limit)
                        .map(HashMap::new)
                        .<Map<String, Object>>map(m -> m)
                        .collect(Collectors.toList());
            }
            if (sql.contains("WHERE user_id = ?")) {
                String userId = String.valueOf(args[0]);
                int limit = ((Number) args[1]).intValue();
                return filterByUser(userId).stream().limit(limit)
                        .map(HashMap::new)
                        .<Map<String, Object>>map(m -> m)
                        .collect(Collectors.toList());
            }
            int limit = args.length > 0 ? ((Number) args[args.length - 1]).intValue() : rows.size();
            return rows.stream()
                    .sorted((a, b) -> Long.compare(((Number) b.get("id")).longValue(), ((Number) a.get("id")).longValue()))
                    .limit(limit)
                    .map(HashMap::new)
                    .<Map<String, Object>>map(m -> m)
                    .collect(Collectors.toList());
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("COUNT(*)") && args.length > 0) {
                String userId = String.valueOf(args[0]);
                long count = filterByUser(userId).size();
                return requiredType.cast(count);
            }
            return null;
        }

        private List<Map<String, Object>> filterByUser(String userId) {
            return rows.stream()
                    .filter(r -> userId.equals(String.valueOf(r.get("user_id"))))
                    .sorted((a, b) -> Long.compare(((Number) b.get("id")).longValue(), ((Number) a.get("id")).longValue()))
                    .toList();
        }
    }
}
