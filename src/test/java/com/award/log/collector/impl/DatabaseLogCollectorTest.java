package com.award.log.collector.impl;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseLogCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void collectFromDatabasePersistsCheckpointAndReturnsOnlyNewRows() throws Exception {
        JdbcDataSource dataSource = dataSource("collector_ok");
        createTable(dataSource);
        insertRow(dataSource, 1L, "INFO", "first");
        insertRow(dataSource, 2L, "ERROR", "second");

        Path checkpoint = tempDir.resolve("collector.offset");
        DatabaseLogCollector collector = new DatabaseLogCollector(
                dataSource,
                "SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC",
                "db-collector",
                checkpoint.toString());

        ReflectionTestUtils.invokeMethod(collector, "collectFromDatabase");
        List<String> firstBatch = collector.collect();

        assertEquals(2, firstBatch.size());
        assertTrue(firstBatch.get(0).toLowerCase().contains("message: first"));
        assertEquals("2", Files.readString(checkpoint, StandardCharsets.UTF_8).trim());

        insertRow(dataSource, 3L, "WARN", "third");
        ReflectionTestUtils.invokeMethod(collector, "collectFromDatabase");
        List<String> secondBatch = collector.collect();
        assertEquals(1, secondBatch.size());
        assertTrue(secondBatch.get(0).toLowerCase().contains("message: third"));

        insertRow(dataSource, 4L, "INFO", "fourth");
        DatabaseLogCollector resumed = new DatabaseLogCollector(
                dataSource,
                "SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC",
                "db-collector",
                checkpoint.toString());
        ReflectionTestUtils.invokeMethod(resumed, "collectFromDatabase");
        List<String> resumedBatch = resumed.collect();
        assertEquals(1, resumedBatch.size());
        assertTrue(resumedBatch.get(0).toLowerCase().contains("message: fourth"));
        assertEquals("db-collector", resumed.getName());
        assertFalse(resumed.isRunning());
    }

    @Test
    void constructorHandlesInvalidCheckpointAndDefaultPath() throws Exception {
        JdbcDataSource dataSource = dataSource("collector_bad_checkpoint");
        createTable(dataSource);
        insertRow(dataSource, 10L, "INFO", "ten");

        Path checkpoint = tempDir.resolve("broken.offset");
        Files.writeString(checkpoint, "not-a-number", StandardCharsets.UTF_8);
        DatabaseLogCollector collector = new DatabaseLogCollector(
                dataSource,
                "SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC",
                "broken-checkpoint",
                checkpoint.toString());

        ReflectionTestUtils.invokeMethod(collector, "collectFromDatabase");
        assertEquals(1, collector.collect().size());

        DatabaseLogCollector defaultPathCollector = new DatabaseLogCollector(
                dataSource,
                "SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC",
                "default-path");
        assertNotNull(ReflectionTestUtils.getField(defaultPathCollector, "checkpointPath"));
    }

    @Test
    void collectFromDatabaseRethrowsSqlErrorsAndStartStopToggleRunning() throws Exception {
        JdbcDataSource dataSource = dataSource("collector_fail");
        DatabaseLogCollector failingCollector = new DatabaseLogCollector(
                dataSource,
                "SELECT id FROM missing_table WHERE id > {lastId}",
                "failing",
                tempDir.resolve("fail.offset").toString());

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(failingCollector, "collectFromDatabase"));

        JdbcDataSource okDataSource = dataSource("collector_start_stop");
        createTable(okDataSource);
        DatabaseLogCollector collector = new DatabaseLogCollector(
                okDataSource,
                "SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC",
                "start-stop",
                tempDir.resolve("run.offset").toString());
        collector.start();
        Thread.sleep(50L);
        assertTrue(collector.isRunning());
        collector.stop();
        assertFalse(collector.isRunning());
    }

    private static JdbcDataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createTable(JdbcDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS system_log (" +
                    "id BIGINT PRIMARY KEY, level VARCHAR(32), message VARCHAR(255), created_at TIMESTAMP)");
        }
    }

    private static void insertRow(JdbcDataSource dataSource, long id, String level, String message) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("MERGE INTO system_log KEY(id) VALUES (" + id + ", '" + level + "', '" + message + "', CURRENT_TIMESTAMP())");
        }
    }
}
