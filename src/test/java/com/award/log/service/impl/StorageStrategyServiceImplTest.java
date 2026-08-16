package com.award.log.service.impl;

import com.award.log.model.LogDocument;
import com.award.log.model.StorageLevel;
import com.award.log.service.ElasticsearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageStrategyServiceImplTest {

    @Mock
    private ElasticsearchService elasticsearchService;

    private StorageStrategyServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        service = new StorageStrategyServiceImpl();
        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        clearDir(directory("HOT_DIR"));
        clearDir(directory("WARM_DIR"));
        clearDir(directory("COLD_DIR"));
    }

    @Test
    void determineStorageLevelShouldClassifyByAge() {
        assertEquals(StorageLevel.HOT, service.determineStorageLevel(LocalDateTime.now().minusDays(1)));
        assertEquals(StorageLevel.WARM, service.determineStorageLevel(LocalDateTime.now().minusDays(10)));
        assertEquals(StorageLevel.COLD, service.determineStorageLevel(LocalDateTime.now().minusDays(40)));
        assertNull(service.determineStorageLevel(LocalDateTime.now().minusDays(120)));
    }

    @Test
    void storeLogHotShouldIndexViaElasticsearch() {
        LogDocument doc = new LogDocument();
        doc.setContent("hot log");
        when(elasticsearchService.indexLog(doc)).thenReturn(doc);
        assertTrue(service.storeLog(doc, StorageLevel.HOT));
        verify(elasticsearchService).indexLog(doc);
    }

    @Test
    void storeLogWarmShouldPersistWithoutElasticsearch() {
        LogDocument doc = new LogDocument();
        doc.setContent("warm log");
        assertTrue(service.storeLog(doc, StorageLevel.WARM));
    }

    @Test
    void getStorageStatsShouldReturnStructure() {
        var stats = service.getStorageStats();
        assertNotNull(stats);
        assertTrue(stats.getTotalLogCount() >= 0);
    }

    @Test
    void batchStoreLogsShouldHandleHotWarmAndColdBranches() throws IOException {
        LogDocument one = new LogDocument();
        one.setContent("one");
        LogDocument two = new LogDocument();
        two.setContent("two");
        List<LogDocument> docs = List.of(one, two);

        when(elasticsearchService.bulkIndexLogs(docs)).thenReturn(2);
        assertEquals(2, service.batchStoreLogs(docs, StorageLevel.HOT));

        ReflectionTestUtils.setField(service, "elasticsearchService", null);
        assertEquals(2, service.batchStoreLogs(docs, StorageLevel.WARM));
        assertEquals(2, service.batchStoreLogs(docs, StorageLevel.COLD));
        assertTrue(countFiles(directory("WARM_DIR")) > 0);
        assertTrue(countFiles(directory("COLD_DIR")) > 0);
    }

    @Test
    void storeLogHotFallbackAndCleanupExpiredLogsCoverFileAndElasticsearchModes() throws Exception {
        ReflectionTestUtils.setField(service, "elasticsearchService", null);

        LogDocument doc = new LogDocument();
        doc.setContent("fallback-hot");
        assertTrue(service.storeLog(doc, StorageLevel.HOT));
        assertTrue(countFiles(directory("HOT_DIR")) > 0);

        Path warmFile = directory("WARM_DIR").resolve("logs_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".gz");
        Path coldFile = directory("COLD_DIR").resolve("logs_" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".txt");
        Files.writeString(warmFile, "old");
        Files.writeString(coldFile, "old");

        int warmCleaned = service.cleanupExpiredLogs(StorageLevel.WARM, LocalDateTime.now());
        int coldCleaned = service.cleanupExpiredLogs(StorageLevel.COLD, LocalDateTime.now());
        assertTrue(warmCleaned >= 1);
        assertTrue(coldCleaned >= 1);

        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        when(elasticsearchService.deleteLogsBefore(any(LocalDateTime.class))).thenReturn(4L);
        assertEquals(4, service.cleanupExpiredLogs(StorageLevel.HOT, LocalDateTime.now()));
    }

    @Test
    void archiveAndScheduledArchiveShouldRemainNoOpSafe() {
        assertEquals(0, service.archiveLogs(StorageLevel.HOT, StorageLevel.WARM, LocalDateTime.now()));
        assertDoesNotThrow(service::executeScheduledArchive);
    }

    private static Path directory(String fieldName) {
        return Path.of(String.valueOf(ReflectionTestUtils.getField(StorageStrategyServiceImpl.class, fieldName)));
    }

    private static void clearDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static long countFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            long count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        }
    }
}
