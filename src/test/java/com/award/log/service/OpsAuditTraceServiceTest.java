package com.award.log.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OpsAuditTraceServiceTest {

    private InMemoryJdbc jdbc;
    private OpsAuditTraceService service;

    @BeforeEach
    void setUp() {
        jdbc = new InMemoryJdbc();
        service = new OpsAuditTraceService(jdbc);
        service.initTable();
    }

    @Test
    void saveAndFindByTraceIdShouldRoundTrip() {
        List<Map<String, Object>> steps = List.of(Map.of("phase", "receive", "detail", "ok"));
        service.save("trace-1", "CHAT", "check disk", "LOW", "PASS", "DiskTool",
                true, "done", steps, 50L, "admin", "v1");

        Map<String, Object> found = service.findByTraceId("trace-1");
        assertEquals("trace-1", found.get("traceId"));
        assertEquals("CHAT", found.get("channel"));
        assertTrue((Boolean) found.get("executionOk"));
        assertFalse(((List<?>) found.get("steps")).isEmpty());
    }

    @Test
    void listRecentShouldReturnSummariesWithoutStepsJson() {
        service.save("t2", "RUNBOOK", "cleanup", "MEDIUM", "PASS", "CleanTool",
                true, "summary", List.of(), 10L, "u1", "v2");

        List<Map<String, Object>> rows = service.listRecent(5);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("steps"));
    }

    @Test
    void listRecentWithStepsShouldIncludeParsedSteps() {
        service.save("t3", "AGENT", "restart nginx", "HIGH", "BLOCK", "RestartTool",
                false, "blocked", List.of(Map.of("phase", "execute")), 5L, "u2", "v3");

        List<Map<String, Object>> rows = service.listRecentWithSteps(5);
        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).get("steps"));
    }

    @Test
    void saveWithEffectShouldAppendEffectStep() {
        service.saveWithEffect("t4", "AGENT", "fix", "LOW", "PASS", "Tool",
                true, "ok", new ArrayList<>(List.of(Map.of("phase", "execute"))),
                1L, "op", "v4", Map.of("improved", true));

        Map<String, Object> found = service.findByTraceId("t4");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) found.get("steps");
        assertTrue(steps.stream().anyMatch(s -> "effect".equals(s.get("phase"))));
    }

    @Test
    void findByTraceIdReturnsEmptyForBlankId() {
        assertTrue(service.findByTraceId(" ").isEmpty());
    }

    @Test
    void deleteOlderThanDaysShouldRemoveRows() {
        jdbc.seedOldRow("old-trace");
        assertEquals(1, service.deleteOlderThanDays(30));
        assertTrue(service.listRecent(10).isEmpty());
    }

    private static final class InMemoryJdbc extends JdbcTemplate {
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();

        void seedOldRow(String traceId) {
            Map<String, Object> row = baseRow(traceId);
            row.put("created_at", new Timestamp(System.currentTimeMillis() - 86400000L * 40));
            rows.add(row);
        }

        @Override
        public void execute(String sql) {
            // DDL no-op
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO ops_audit_trace")) {
                Map<String, Object> row = baseRow(String.valueOf(args[0]));
                row.put("channel", args[1]);
                row.put("audit_kind", args[2]);
                row.put("request_channel", args[3]);
                row.put("stage", args[4]);
                row.put("decision", args[5]);
                row.put("user_input", args[6]);
                row.put("risk_level", args[7]);
                row.put("security_outcome", args[8]);
                row.put("tool_name", args[9]);
                row.put("target_type", args[10]);
                row.put("target_name", args[11]);
                row.put("parent_trace_id", args[12]);
                row.put("confirmation_id", args[13]);
                row.put("effect_summary", args[14]);
                row.put("execution_ok", args[15]);
                row.put("result_summary", args[16]);
                row.put("steps_json", args[17]);
                row.put("duration_ms", args[18]);
                row.put("operator_user_id", args[19]);
                row.put("policy_version", args[20]);
                row.put("target_host_id", args[21]);
                row.put("target_host_label", args[22]);
                row.put("created_at", args[23]);
                rows.add(row);
                return 1;
            }
            if (sql.startsWith("DELETE FROM ops_audit_trace")) {
                int before = rows.size();
                rows.clear();
                return before;
            }
            return 0;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<Map<String, Object>> source = new ArrayList<>(rows);
            if (sql.contains("WHERE trace_id = ?")) {
                String traceId = String.valueOf(args[0]);
                source = source.stream()
                        .filter(r -> traceId.equals(String.valueOf(r.get("trace_id"))))
                        .toList();
            }
            int limit = args.length > 0 && sql.contains("LIMIT ?")
                    ? ((Number) args[args.length - 1]).intValue()
                    : source.size();
            return source.stream()
                    .sorted((a, b) -> Long.compare(((Number) b.get("id")).longValue(), ((Number) a.get("id")).longValue()))
                    .limit(limit)
                    .map(row -> {
                        try {
                            return rowMapper.mapRow(new MapResultSet(row), 0);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(row -> (T) row)
                    .toList();
        }

        private Map<String, Object> baseRow(String traceId) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", seq.incrementAndGet());
            row.put("trace_id", traceId);
            row.put("execution_ok", 1);
            row.put("duration_ms", 1L);
            row.put("created_at", new Timestamp(System.currentTimeMillis()));
            return row;
        }
    }

    private static final class MapResultSet implements ResultSet {
        private final Map<String, Object> row;

        private MapResultSet(Map<String, Object> row) {
            this.row = row;
        }

        @Override
        public long getLong(String columnLabel) {
            Object v = row.get(columnLabel);
            return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
        }

        @Override
        public int getInt(String columnLabel) {
            Object v = row.get(columnLabel);
            return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
        }

        @Override
        public String getString(String columnLabel) {
            Object v = row.get(columnLabel);
            return v == null ? null : String.valueOf(v);
        }

        @Override
        public Timestamp getTimestamp(String columnLabel) {
            Object v = row.get(columnLabel);
            return v instanceof Timestamp ts ? ts : null;
        }

        @Override public boolean next() { return false; }
        @Override public void close() {}
        @Override public boolean wasNull() { return false; }
        @Override public String getString(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public boolean getBoolean(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public byte getByte(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public short getShort(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public int getInt(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public long getLong(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public float getFloat(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public double getDouble(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.math.BigDecimal getBigDecimal(int columnIndex, int scale) { throw new UnsupportedOperationException(); }
        @Override public byte[] getBytes(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Date getDate(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Time getTime(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public Timestamp getTimestamp(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getAsciiStream(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getUnicodeStream(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getBinaryStream(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public boolean getBoolean(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public byte getByte(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public short getShort(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public float getFloat(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public double getDouble(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.math.BigDecimal getBigDecimal(String columnLabel, int scale) { throw new UnsupportedOperationException(); }
        @Override public byte[] getBytes(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Date getDate(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Time getTime(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getAsciiStream(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getUnicodeStream(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getBinaryStream(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public String getCursorName() { throw new UnsupportedOperationException(); }
        @Override public java.sql.ResultSetMetaData getMetaData() { throw new UnsupportedOperationException(); }
        @Override public Object getObject(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public Object getObject(String columnLabel) { return row.get(columnLabel); }
        @Override public int findColumn(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.io.Reader getCharacterStream(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.io.Reader getCharacterStream(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.math.BigDecimal getBigDecimal(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.math.BigDecimal getBigDecimal(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return false; }
        @Override public boolean isFirst() { return true; }
        @Override public boolean isLast() { return true; }
        @Override public void beforeFirst() {}
        @Override public void afterLast() {}
        @Override public boolean first() { return true; }
        @Override public boolean last() { return true; }
        @Override public int getRow() { return 1; }
        @Override public boolean absolute(int row) { throw new UnsupportedOperationException(); }
        @Override public boolean relative(int rows) { throw new UnsupportedOperationException(); }
        @Override public boolean previous() { return false; }
        @Override public void setFetchDirection(int direction) {}
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) {}
        @Override public int getFetchSize() { return 0; }
        @Override public int getType() { return 0; }
        @Override public int getConcurrency() { return 0; }
        @Override public boolean rowUpdated() { return false; }
        @Override public boolean rowInserted() { return false; }
        @Override public boolean rowDeleted() { return false; }
        @Override public void updateNull(int columnIndex) {}
        @Override public void updateBoolean(int columnIndex, boolean x) {}
        @Override public void updateByte(int columnIndex, byte x) {}
        @Override public void updateShort(int columnIndex, short x) {}
        @Override public void updateInt(int columnIndex, int x) {}
        @Override public void updateLong(int columnIndex, long x) {}
        @Override public void updateFloat(int columnIndex, float x) {}
        @Override public void updateDouble(int columnIndex, double x) {}
        @Override public void updateBigDecimal(int columnIndex, java.math.BigDecimal x) {}
        @Override public void updateString(int columnIndex, String x) {}
        @Override public void updateBytes(int columnIndex, byte[] x) {}
        @Override public void updateDate(int columnIndex, java.sql.Date x) {}
        @Override public void updateTime(int columnIndex, java.sql.Time x) {}
        @Override public void updateTimestamp(int columnIndex, Timestamp x) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x, int length) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x, int length) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x, int length) {}
        @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) {}
        @Override public void updateObject(int columnIndex, Object x) {}
        @Override public void updateNull(String columnLabel) {}
        @Override public void updateBoolean(String columnLabel, boolean x) {}
        @Override public void updateByte(String columnLabel, byte x) {}
        @Override public void updateShort(String columnLabel, short x) {}
        @Override public void updateInt(String columnLabel, int x) {}
        @Override public void updateLong(String columnLabel, long x) {}
        @Override public void updateFloat(String columnLabel, float x) {}
        @Override public void updateDouble(String columnLabel, double x) {}
        @Override public void updateBigDecimal(String columnLabel, java.math.BigDecimal x) {}
        @Override public void updateString(String columnLabel, String x) {}
        @Override public void updateBytes(String columnLabel, byte[] x) {}
        @Override public void updateDate(String columnLabel, java.sql.Date x) {}
        @Override public void updateTime(String columnLabel, java.sql.Time x) {}
        @Override public void updateTimestamp(String columnLabel, Timestamp x) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, int length) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, int length) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader, int length) {}
        @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) {}
        @Override public void updateObject(String columnLabel, Object x) {}
        @Override public void insertRow() {}
        @Override public void updateRow() {}
        @Override public void deleteRow() {}
        @Override public void refreshRow() {}
        @Override public void cancelRowUpdates() {}
        @Override public void moveToInsertRow() {}
        @Override public void moveToCurrentRow() {}
        @Override public java.sql.Statement getStatement() { return null; }
        @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Ref getRef(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Blob getBlob(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Clob getClob(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Array getArray(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Ref getRef(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Blob getBlob(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Clob getClob(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Array getArray(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Date getDate(int columnIndex, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Date getDate(String columnLabel, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Time getTime(int columnIndex, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Time getTime(String columnLabel, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public Timestamp getTimestamp(int columnIndex, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public Timestamp getTimestamp(String columnLabel, java.util.Calendar cal) { throw new UnsupportedOperationException(); }
        @Override public java.net.URL getURL(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.net.URL getURL(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public void updateRef(int columnIndex, java.sql.Ref x) {}
        @Override public void updateRef(String columnLabel, java.sql.Ref x) {}
        @Override public void updateBlob(int columnIndex, java.sql.Blob x) {}
        @Override public void updateBlob(String columnLabel, java.sql.Blob x) {}
        @Override public void updateClob(int columnIndex, java.sql.Clob x) {}
        @Override public void updateClob(String columnLabel, java.sql.Clob x) {}
        @Override public void updateArray(int columnIndex, java.sql.Array x) {}
        @Override public void updateArray(String columnLabel, java.sql.Array x) {}
        @Override public java.sql.RowId getRowId(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.RowId getRowId(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public void updateRowId(int columnIndex, java.sql.RowId x) {}
        @Override public void updateRowId(String columnLabel, java.sql.RowId x) {}
        @Override public int getHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void updateNString(int columnIndex, String nString) {}
        @Override public void updateNString(String columnLabel, String nString) {}
        @Override public void updateNClob(int columnIndex, java.sql.NClob nClob) {}
        @Override public void updateNClob(String columnLabel, java.sql.NClob nClob) {}
        @Override public java.sql.NClob getNClob(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.NClob getNClob(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.sql.SQLXML getSQLXML(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.sql.SQLXML getSQLXML(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public void updateSQLXML(int columnIndex, java.sql.SQLXML xmlObject) {}
        @Override public void updateSQLXML(String columnLabel, java.sql.SQLXML xmlObject) {}
        @Override public String getNString(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public String getNString(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public java.io.Reader getNCharacterStream(int columnIndex) { throw new UnsupportedOperationException(); }
        @Override public java.io.Reader getNCharacterStream(String columnLabel) { throw new UnsupportedOperationException(); }
        @Override public void updateNCharacterStream(int columnIndex, java.io.Reader x, long length) {}
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x, long length) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x, long length) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x, long length) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, long length) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, long length) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateBlob(int columnIndex, java.io.InputStream inputStream, long length) {}
        @Override public void updateBlob(String columnLabel, java.io.InputStream inputStream, long length) {}
        @Override public void updateClob(int columnIndex, java.io.Reader reader, long length) {}
        @Override public void updateClob(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateNClob(int columnIndex, java.io.Reader reader, long length) {}
        @Override public void updateNClob(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateNCharacterStream(int columnIndex, java.io.Reader x) {}
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader reader) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader) {}
        @Override public void updateBlob(int columnIndex, java.io.InputStream inputStream) {}
        @Override public void updateBlob(String columnLabel, java.io.InputStream inputStream) {}
        @Override public void updateClob(int columnIndex, java.io.Reader reader) {}
        @Override public void updateClob(String columnLabel, java.io.Reader reader) {}
        @Override public void updateNClob(int columnIndex, java.io.Reader reader) {}
        @Override public void updateNClob(String columnLabel, java.io.Reader reader) {}
        @Override public <T> T getObject(int columnIndex, Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public <T> T getObject(String columnLabel, Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
