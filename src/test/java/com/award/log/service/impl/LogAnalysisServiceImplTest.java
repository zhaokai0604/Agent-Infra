package com.award.log.service.impl;

import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.util.LogAnalysisArtifactCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogAnalysisServiceImplTest {

    @Mock
    private AnalysisTaskManager taskManager;
    @Mock
    private LogAnalysisArtifactCleaner artifactCleaner;

    private LogAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LogAnalysisServiceImpl();
        ReflectionTestUtils.setField(service, "taskManager", taskManager);
        ReflectionTestUtils.setField(service, "artifactCleaner", artifactCleaner);
    }

    @Test
    void pauseAnalysisShouldUpdateTaskManager() {
        service.pauseAnalysis("task-1");
        verify(taskManager).pauseTask("task-1");
    }

    @Test
    void resumeAnalysisShouldRejectNonPausedTask() {
        assertThrows(IllegalStateException.class, () -> service.resumeAnalysis("task-1"));
    }

    @Test
    void resumeAnalysisShouldResumePausedTask() {
        service.pauseAnalysis("task-1");
        service.resumeAnalysis("task-1");
        verify(taskManager).resumeTask("task-1");
    }

    @Test
    void cancelAnalysisShouldCancelTaskAndPurgeArtifacts() {
        service.cancelAnalysis("task-2");
        verify(taskManager).cancelTask("task-2", "任务被用户取消");
        verify(artifactCleaner).purgeTaskArtifacts("task-2");
    }

    @Test
    void ensureReportArtifactsShouldSkipBlankInput() throws IOException {
        assertDoesNotThrow(() -> service.ensureReportArtifacts(null, List.of()));
        assertDoesNotThrow(() -> service.ensureReportArtifacts("t1", List.of()));
    }

    @Test
    void ensureReportArtifactsShouldWriteCsvAndHtml() throws IOException {
        EnhancedLogParseResultEntity row = new EnhancedLogParseResultEntity("error line");
        row.setSeverity(com.award.log.model.LogSeverityLevel.ERROR_LEVEL);
        row.setLogTime("2024-01-01 10:00:00");

        service.ensureReportArtifacts("export-task", List.of(row));

        Path outputDir = Path.of(System.getProperty("user.dir"), "target", "output", "export-task");
        assertTrue(Files.exists(outputDir));
        try (var paths = Files.list(outputDir)) {
            assertTrue(paths.findAny().isPresent());
        }
    }

}
