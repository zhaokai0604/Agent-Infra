package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库日志采集器
 * 支持从关系型数据库中采集日志数据
 */
@Slf4j
public class DatabaseLogCollector implements LogCollector {

    private final DataSource dataSource;
    private final String query;
    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final List<String> collectedLogs;
    private final Path checkpointPath;
    private long lastId = 0;

    public DatabaseLogCollector(DataSource dataSource, String query, String name) {
        this(dataSource, query, name, null);
    }

    public DatabaseLogCollector(DataSource dataSource, String query, String name, String checkpointFilePath) {
        this.dataSource = dataSource;
        this.query = query;
        this.name = name;
        this.executorService = Executors.newSingleThreadExecutor();
        this.collectedLogs = new ArrayList<>();
        if (checkpointFilePath == null || checkpointFilePath.isBlank()) {
            this.checkpointPath = Paths.get(System.getProperty("user.dir"), "storage", "collector-checkpoints", name + ".offset");
        } else {
            this.checkpointPath = Paths.get(checkpointFilePath);
        }
        this.lastId = loadLastId();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[数据库日志采集器] 开始采集，查询: {}", query);
            executorService.submit(this::collectLogsContinuously);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[数据库日志采集器] 停止采集");
            executorService.shutdown();
        }
    }

    @Override
    public List<String> collect() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            collectedLogs.clear();
            return logs;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void collectLogsContinuously() {
        while (running.get()) {
            try {
                collectFromDatabase();
                // 休眠一段时间，避免频繁数据库查询
                Thread.sleep(5000);
            } catch (Exception e) {
                log.error("[数据库日志采集器] 采集异常", e);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void collectFromDatabase() throws SQLException {
        String actualQuery = query;
        // 如果查询中包含{lastId}占位符，则替换为实际的最后ID
        if (query.contains("{lastId}")) {
            actualQuery = query.replace("{lastId}", String.valueOf(lastId));
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(actualQuery);
             ResultSet resultSet = statement.executeQuery()) {

            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (resultSet.next()) {
                StringBuilder logBuilder = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = resultSet.getObject(i);
                    logBuilder.append(columnName).append(": ").append(value);
                    if (i < columnCount) {
                        logBuilder.append(" | ");
                    }
                    // 记录最后一条记录的ID
                    if ("id".equals(columnName.toLowerCase())) {
                        lastId = resultSet.getLong(i);
                    }
                }
                synchronized (collectedLogs) {
                    collectedLogs.add(logBuilder.toString());
                }
            }
            persistLastId();

        } catch (SQLException e) {
            log.error("[数据库日志采集器] SQL执行异常", e);
            throw e;
        }
    }

    private long loadLastId() {
        try {
            if (Files.notExists(checkpointPath)) {
                return 0L;
            }
            String val = Files.readString(checkpointPath, StandardCharsets.UTF_8).trim();
            return val.isEmpty() ? 0L : Long.parseLong(val);
        } catch (Exception e) {
            log.warn("[数据库日志采集器] 读取断点失败，使用0作为起点: {}", e.getMessage());
            return 0L;
        }
    }

    private void persistLastId() {
        try {
            Path parent = checkpointPath.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(checkpointPath, String.valueOf(lastId), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[数据库日志采集器] 持久化断点失败: {}", e.getMessage());
        }
    }
}
