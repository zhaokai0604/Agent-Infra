package com.award.log.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * 日志分析任务在磁盘上的产物：上传临时文件 + {@code target/output/{taskId}/} 报告目录。
 */
@Slf4j
@Component
public class LogAnalysisArtifactCleaner {

    public Path outputDirForTask(String taskId) {
        return Path.of(System.getProperty("user.dir"), "target", "output", taskId);
    }

    /** 删除任务输出目录与上传临时文件（不影响数据库）。 */
    public void purgeTaskArtifacts(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        deleteDirectoryQuietly(outputDirForTask(taskId));
        deleteUploadTemps(taskId);
    }

    public void deleteUploadTemps(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Files.isDirectory(tmpDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, taskId + "_*")) {
            for (Path path : stream) {
                try {
                    Files.deleteIfExists(path);
                    log.debug("已删除上传临时文件: {}", path);
                } catch (IOException e) {
                    log.warn("删除上传临时文件失败: {} - {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描上传临时文件失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    public void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除路径失败: {} - {}", path, e.getMessage());
                        }
                    });
            log.info("已清理任务产物目录: {}", dir);
        } catch (IOException e) {
            log.warn("清理任务产物目录失败: {} - {}", dir, e.getMessage());
        }
    }
}
