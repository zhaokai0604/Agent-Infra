package com.award.log.service.impl;

import com.award.log.model.LogDocument;
import com.award.log.model.StorageLevel;
import com.award.log.service.ElasticsearchService;
import com.award.log.service.StorageStrategyService;
import com.award.log.service.StorageStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 存储策略服务实现类
 * 实现存储策略的核心功能
 */
@Slf4j
@Service
public class StorageStrategyServiceImpl implements StorageStrategyService {

    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    private static final String STORAGE_BASE_DIR = System.getProperty("user.dir") + File.separator + "storage";
    private static final String HOT_DIR = STORAGE_BASE_DIR + File.separator + "hot";
    private static final String WARM_DIR = STORAGE_BASE_DIR + File.separator + "warm";
    private static final String COLD_DIR = STORAGE_BASE_DIR + File.separator + "cold";

    public StorageStrategyServiceImpl() {
        // 初始化存储目录
        initStorageDirs();
    }

    private void initStorageDirs() {
        try {
            Files.createDirectories(Paths.get(HOT_DIR));
            Files.createDirectories(Paths.get(WARM_DIR));
            Files.createDirectories(Paths.get(COLD_DIR));
            log.info("[存储策略] 初始化存储目录成功");
        } catch (IOException e) {
            log.error("[存储策略] 初始化存储目录失败", e);
        }
    }

    @Override
    public StorageLevel determineStorageLevel(LocalDateTime timestamp) {
        LocalDateTime now = LocalDateTime.now();
        long daysDiff = java.time.Duration.between(timestamp, now).toDays();

        if (daysDiff < StorageLevel.HOT.getRetentionDays()) {
            return StorageLevel.HOT;
        } else if (daysDiff < StorageLevel.WARM.getRetentionDays()) {
            return StorageLevel.WARM;
        } else if (daysDiff < StorageLevel.COLD.getRetentionDays()) {
            return StorageLevel.COLD;
        } else {
            return null; // 过期
        }
    }

    @Override
    public boolean storeLog(LogDocument logDocument, StorageLevel storageLevel) {
        try {
            switch (storageLevel) {
                case HOT:
                    // 存储到Elasticsearch
                    if (elasticsearchService != null) {
                        elasticsearchService.indexLog(logDocument);
                    } else {
                        // 在开发环境中，将热数据也存储到压缩文件
                        storeToCompressedFile(logDocument, HOT_DIR);
                    }
                    break;
                case WARM:
                    // 存储到压缩文件
                    storeToCompressedFile(logDocument, WARM_DIR);
                    break;
                case COLD:
                    // 存储到归档文件
                    storeToArchiveFile(logDocument, COLD_DIR);
                    break;
            }
            return true;
        } catch (Exception e) {
            log.error("[存储策略] 存储日志失败", e);
            return false;
        }
    }

    @Override
    public int batchStoreLogs(List<LogDocument> logDocuments, StorageLevel storageLevel) {
        int successCount = 0;
        try {
            switch (storageLevel) {
                case HOT:
                    // 批量存储到Elasticsearch
                    if (elasticsearchService != null) {
                        successCount = elasticsearchService.bulkIndexLogs(logDocuments);
                    } else {
                        // 在开发环境中，将热数据也存储到压缩文件
                        successCount = batchStoreToCompressedFile(logDocuments, HOT_DIR);
                    }
                    break;
                case WARM:
                    // 批量存储到压缩文件
                    successCount = batchStoreToCompressedFile(logDocuments, WARM_DIR);
                    break;
                case COLD:
                    // 批量存储到归档文件
                    successCount = batchStoreToArchiveFile(logDocuments, COLD_DIR);
                    break;
            }
        } catch (Exception e) {
            log.error("[存储策略] 批量存储日志失败", e);
        }
        return successCount;
    }

    @Override
    public int archiveLogs(StorageLevel fromLevel, StorageLevel toLevel, LocalDateTime beforeTime) {
        Path fromDir = Paths.get(dirForLevel(fromLevel));
        Path toDir = Paths.get(dirForLevel(toLevel));
        if (!Files.isDirectory(fromDir)) {
            log.warn("[存储策略] 归档源目录不存在: {}", fromDir);
            return 0;
        }
        try {
            Files.createDirectories(toDir);
        } catch (IOException e) {
            log.error("[存储策略] 创建归档目标目录失败: {}", toDir, e);
            return 0;
        }
        int moved = 0;
        java.time.Instant cutoff = beforeTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fromDir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    java.nio.file.attribute.FileTime ft = Files.getLastModifiedTime(path);
                    if (ft.toInstant().isBefore(cutoff)) {
                        Path target = toDir.resolve(path.getFileName());
                        if (Files.exists(target)) {
                            target = toDir.resolve(System.currentTimeMillis() + "_" + path.getFileName());
                        }
                        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                        moved++;
                    }
                } catch (Exception e) {
                    log.warn("[存储策略] 归档移动失败: {} -> {}: {}", path, toDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[存储策略] 归档扫描失败 {} -> {}", fromLevel, toLevel, e);
        }
        log.info("[存储策略] 归档完成 {} -> {} before={}，搬迁 {} 个文件",
                fromLevel.getName(), toLevel.getName(), beforeTime, moved);
        return moved;
    }

    private String dirForLevel(StorageLevel level) {
        return switch (level) {
            case HOT -> HOT_DIR;
            case WARM -> WARM_DIR;
            case COLD -> COLD_DIR;
        };
    }

    @Override
    public int cleanupExpiredLogs(StorageLevel storageLevel, LocalDateTime beforeTime) {
        log.info("[存储策略] 清理过期日志: {}, 清理时间点: {}", storageLevel.getName(), beforeTime);
        int cleanedCount = 0;

        try {
            switch (storageLevel) {
                case HOT:
                    // 清理Elasticsearch中的过期日志
                    if (elasticsearchService != null) {
                        cleanedCount = (int) elasticsearchService.deleteLogsBefore(beforeTime);
                    } else {
                        // 在开发环境中，清理热数据目录中的过期文件
                        cleanedCount = cleanupDirectory(HOT_DIR, beforeTime);
                    }
                    break;
                case WARM:
                    // 清理温数据目录中的过期文件
                    cleanedCount = cleanupDirectory(WARM_DIR, beforeTime);
                    break;
                case COLD:
                    // 清理冷数据目录中的过期文件
                    cleanedCount = cleanupDirectory(COLD_DIR, beforeTime);
                    break;
            }
            log.info("[存储策略] 清理完成，成功清理 {} 条日志", cleanedCount);
        } catch (Exception e) {
            log.error("[存储策略] 清理过期日志失败", e);
        }

        return cleanedCount;
    }

    @Override
    public void executeScheduledArchive() {
        log.info("[存储策略] 执行定时归档任务");

        LocalDateTime now = LocalDateTime.now();

        LocalDateTime hotToWarmTime = now.minusDays(StorageLevel.HOT.getRetentionDays());
        int hotMoved = archiveLogs(StorageLevel.HOT, StorageLevel.WARM, hotToWarmTime);

        LocalDateTime warmToColdTime = now.minusDays(StorageLevel.WARM.getRetentionDays());
        int warmMoved = archiveLogs(StorageLevel.WARM, StorageLevel.COLD, warmToColdTime);

        LocalDateTime coldCleanupTime = now.minusDays(StorageLevel.COLD.getRetentionDays());
        int cleaned = cleanupExpiredLogs(StorageLevel.COLD, coldCleanupTime);

        log.info("[存储策略] 定时归档结束：热→温 {}，温→冷 {}，冷清理 {}", hotMoved, warmMoved, cleaned);
    }

    @Override
    public StorageStats getStorageStats() {
        StorageStats stats = new StorageStats();

        try {
            // 这里需要实现存储统计的逻辑
            // 由于实现复杂度较高，这里只提供框架
            stats.calculateTotals();
        } catch (Exception e) {
            log.error("[存储策略] 获取存储统计信息失败", e);
        }

        return stats;
    }

    private void storeToCompressedFile(LogDocument logDocument, String directory) throws IOException {
        String fileName = getCompressedFileName();
        Path filePath = Paths.get(directory, fileName);

        try (GZIPOutputStream gzipOut = new GZIPOutputStream(
                new FileOutputStream(filePath.toFile(), true));
             OutputStreamWriter writer = new OutputStreamWriter(gzipOut, "UTF-8")) {

            writer.write(logDocument.getContent());
            writer.write("\n");
        }
    }

    private void storeToArchiveFile(LogDocument logDocument, String directory) throws IOException {
        String fileName = getArchiveFileName();
        Path filePath = Paths.get(directory, fileName);

        try (FileWriter writer = new FileWriter(filePath.toFile(), true)) {
            writer.write(logDocument.getContent());
            writer.write("\n");
        }
    }

    private int batchStoreToCompressedFile(List<LogDocument> logDocuments, String directory) throws IOException {
        String fileName = getCompressedFileName();
        Path filePath = Paths.get(directory, fileName);

        try (GZIPOutputStream gzipOut = new GZIPOutputStream(
                new FileOutputStream(filePath.toFile(), true));
             OutputStreamWriter writer = new OutputStreamWriter(gzipOut, "UTF-8")) {

            for (LogDocument logDocument : logDocuments) {
                writer.write(logDocument.getContent());
                writer.write("\n");
            }
            return logDocuments.size();
        }
    }

    private int batchStoreToArchiveFile(List<LogDocument> logDocuments, String directory) throws IOException {
        String fileName = getArchiveFileName();
        Path filePath = Paths.get(directory, fileName);

        try (FileWriter writer = new FileWriter(filePath.toFile(), true)) {
            for (LogDocument logDocument : logDocuments) {
                writer.write(logDocument.getContent());
                writer.write("\n");
            }
            return logDocuments.size();
        }
    }

    private int cleanupDirectory(String directory, LocalDateTime beforeTime) throws IOException {
        int cleanedCount = 0;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String beforeDate = beforeTime.format(formatter);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(directory))) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    String fileName = path.getFileName().toString();
                    // 假设文件名包含日期信息
                    if (fileName.contains(beforeDate)) {
                        Files.delete(path);
                        cleanedCount++;
                    }
                }
            }
        }

        return cleanedCount;
    }

    private String getCompressedFileName() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return "logs_" + LocalDateTime.now().format(formatter) + ".gz";
    }

    private String getArchiveFileName() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        return "logs_" + LocalDateTime.now().format(formatter) + ".txt";
    }
}
