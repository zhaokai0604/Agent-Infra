package com.award.log.agent.awm;

import com.award.log.util.TestRuntimePlatform;
import com.award.log.util.TestTraceIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowInductionServiceTest {

    private final WorkflowMemoryService workflowMemoryService = mock(WorkflowMemoryService.class);
    private final TrajectoryEvaluator trajectoryEvaluator = mock(TrajectoryEvaluator.class);
    private final OpsExperienceLoader experienceLoader = mock(OpsExperienceLoader.class);

    private LlmWorkflowInductor newInductor() {
        return new LlmWorkflowInductor(new ObjectMapper(), new TestTraceIdGenerator("llm-trace-12345678"));
    }

    @Test
    void afterSuccessfulRunRecordsUtilityWhenToolSequenceAlreadyExists() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("online-trace-12345678"));

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(trajectoryEvaluator.shouldInduce(any())).thenReturn(true);
        when(workflowMemoryService.listByDomain("disk")).thenReturn(List.of(new OpsWorkflow(
                "wf-1",
                "disk",
                List.of("DISK_PRESSURE"),
                "disk",
                "disk",
                List.of(
                        OpsWorkflowStep.of("a", "a", "DiskTool", Map.of()),
                        OpsWorkflowStep.of("b", "b", "LogCleanupTool", Map.of())
                ),
                "seed",
                null,
                0,
                true
        )));

        service.afterSuccessfulRun(
                "trace-1",
                "cleanup disk",
                "EXECUTED",
                true,
                List.of(
                        Map.of("phase", "preview", "toolName", "DiskTool"),
                        Map.of("phase", "execute", "toolName", "LogCleanupTool")
                ),
                "disk",
                List.of("DISK_PRESSURE")
        );

        verify(workflowMemoryService).recordUtility("wf-1");
        verify(workflowMemoryService, never()).upsert(any(), anyBoolean());
    }

    @Test
    void induceFromRecentTracesCreatesWorkflowForStructuredSteps() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("online-trace-12345678"));

        OpsExperience exp = new OpsExperience(
                "trace-2",
                "restart nginx",
                "ASSISTANT",
                "AssistantOrchestrator",
                "EXECUTED",
                true,
                "done",
                List.of(
                        Map.of("phase", "preview", "toolName", "SystemLoadTool"),
                        Map.of("phase", "execute", "toolName", "ServiceRestartTool",
                                "parameters", Map.of("serviceName", "nginx", "dryRun", false))
                ),
                90L,
                null
        );

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(trajectoryEvaluator.shouldInduce(exp)).thenReturn(true);
        when(experienceLoader.loadRecentSuccessful(3)).thenReturn(List.of(exp));
        when(workflowMemoryService.existsWithToolSequence("service", List.of("SystemLoadTool", "ServiceRestartTool")))
                .thenReturn(false);
        when(workflowMemoryService.isDomainFull("service")).thenReturn(false);
        when(workflowMemoryService.upsert(any(), anyBoolean())).thenReturn(true);

        int created = service.induceFromRecentTraces(3).created();

        ArgumentCaptor<OpsWorkflow> workflowCaptor = ArgumentCaptor.forClass(OpsWorkflow.class);
        verify(workflowMemoryService).upsert(workflowCaptor.capture(), anyBoolean());
        assertEquals(1, created);
        assertEquals("service", workflowCaptor.getValue().domainTag());
        assertEquals("ServiceRestartTool", workflowCaptor.getValue().steps().get(1).toolName());
        assertEquals("{service-name}", workflowCaptor.getValue().steps().get(1).argsTemplate().get("serviceName"));
    }

    @Test
    void llmInductionResultIsPreferredWhenAvailable() {
        LlmWorkflowInductor inductor = new LlmWorkflowInductor(
                new ObjectMapper(), new TestTraceIdGenerator("llm-trace-12345678")) {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Optional<OpsWorkflow> induce(OpsExperience exp, String domainTag, List<String> findingKinds) {
                return Optional.of(new OpsWorkflow(
                        "llm-1",
                        domainTag,
                        findingKinds,
                        "llm",
                        "llm",
                        List.of(
                                OpsWorkflowStep.of("a", "a", "DiskTool", Map.of()),
                                OpsWorkflowStep.of("b", "b", "LogCleanupTool", Map.of("path", "{log-path}"))
                        ),
                        "llm",
                        exp.traceId(),
                        0,
                        true
                ));
            }
        };
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                inductor,
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("online-trace-12345678"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "llmInductionEnabled", true);

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(trajectoryEvaluator.shouldInduce(any())).thenReturn(true);
        when(workflowMemoryService.listByDomain("disk")).thenReturn(List.of());
        when(workflowMemoryService.isDomainFull("disk")).thenReturn(false);

        service.afterSuccessfulRun(
                "trace-3",
                "cleanup logs",
                "EXECUTED",
                true,
                List.of(
                        Map.of("phase", "preview", "toolName", "DiskTool"),
                        Map.of("phase", "execute", "toolName", "LogCleanupTool")
                ),
                "disk",
                List.of("DISK_PRESSURE")
        );

        ArgumentCaptor<OpsWorkflow> workflowCaptor = ArgumentCaptor.forClass(OpsWorkflow.class);
        verify(workflowMemoryService).upsert(workflowCaptor.capture(), anyBoolean());
        assertTrue(workflowCaptor.getValue().workflowId().startsWith("llm-"));
        assertEquals("llm", workflowCaptor.getValue().sourceType());
    }

    @Test
    void induceFromRecentTracesSkipsWhenSequenceAlreadyExistsOrTooShort() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("online-trace-12345678"));

        OpsExperience duplicate = new OpsExperience(
                "trace-dup",
                "restart nginx",
                "ASSISTANT",
                "AssistantOrchestrator",
                "EXECUTED",
                true,
                "ok",
                List.of(
                        Map.of("phase", "preview", "toolName", "SystemdTool"),
                        Map.of("phase", "execute", "toolName", "ServiceRestartTool")
                ),
                10L,
                null
        );
        OpsExperience tooShort = new OpsExperience(
                "trace-short",
                "disk status",
                "ASSISTANT",
                "AssistantOrchestrator",
                "EXECUTED",
                true,
                "ok",
                List.of(Map.of("phase", "preview", "toolName", "DiskTool")),
                5L,
                null
        );

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(experienceLoader.loadRecentSuccessful(5)).thenReturn(List.of(duplicate, tooShort));
        when(trajectoryEvaluator.shouldInduce(any())).thenReturn(true);
        when(workflowMemoryService.existsWithToolSequence("service", List.of("SystemdTool", "ServiceRestartTool")))
                .thenReturn(true);

        int created = service.induceFromRecentTraces(5).created();

        assertEquals(0, created);
        verify(workflowMemoryService, never()).upsert(any(), anyBoolean());
    }

    @Test
    void induceFromRecentTracesFallsBackToRuleBasedSequenceForWindowsService() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(true),
                new TestTraceIdGenerator("online-trace-abcdefgh"));

        OpsExperience exp = new OpsExperience(
                "trace-win",
                "service failed",
                "ASSISTANT",
                "AssistantOrchestrator",
                "EXECUTED",
                true,
                "ok",
                List.of(
                        Map.of("phase", "preview", "detail", "Systemd failed output"),
                        Map.of("phase", "execute", "detail", "ServiceRestart executed")
                ),
                42L,
                null
        );

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(experienceLoader.loadRecentSuccessful(2)).thenReturn(List.of(exp));
        when(trajectoryEvaluator.shouldInduce(exp)).thenReturn(true);
        when(workflowMemoryService.existsWithToolSequence("service", List.of("SystemdTool", "ServiceRestartTool")))
                .thenReturn(false);
        when(workflowMemoryService.isDomainFull("service")).thenReturn(false);
        when(workflowMemoryService.upsert(any(), anyBoolean())).thenReturn(true);

        int created = service.induceFromRecentTraces(2).created();

        ArgumentCaptor<OpsWorkflow> workflowCaptor = ArgumentCaptor.forClass(OpsWorkflow.class);
        verify(workflowMemoryService).upsert(workflowCaptor.capture(), anyBoolean());
        assertEquals(1, created);
        assertEquals("service", workflowCaptor.getValue().domainTag());
        assertEquals("SystemdTool", workflowCaptor.getValue().steps().get(0).toolName());
        assertTrue(workflowCaptor.getValue().steps().get(0).reason().contains("Windows"));
    }

    @Test
    void afterSuccessfulRunBuildsStructuredWorkflowWithAbstractArgs() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("online-trace-zxyw9876"));

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(trajectoryEvaluator.shouldInduce(any())).thenReturn(true);
        when(workflowMemoryService.listByDomain("disk")).thenReturn(List.of());
        when(workflowMemoryService.isDomainFull("disk")).thenReturn(false);

        service.afterSuccessfulRun(
                "trace-structured",
                "clean /tmp/app-cache",
                "EXECUTED",
                true,
                List.of(
                        Map.of(
                                "phase", "preview",
                                "toolName", "CleanTempTool",
                                "parameters", Map.of("path", "/tmp/app-cache", "removeDirectory", true, "days", 0)
                        ),
                        Map.of(
                                "phase", "execute",
                                "toolName", "ServiceRestartTool",
                                "parameters", Map.of("serviceName", "nginx", "dryRun", false)
                        )
                ),
                "disk",
                List.of("DISK_PRESSURE")
        );

        ArgumentCaptor<OpsWorkflow> workflowCaptor = ArgumentCaptor.forClass(OpsWorkflow.class);
        verify(workflowMemoryService).upsert(workflowCaptor.capture(), anyBoolean());
        OpsWorkflow workflow = workflowCaptor.getValue();
        assertEquals("CleanTempTool", workflow.steps().get(0).toolName());
        assertEquals("true", workflow.steps().get(0).argsTemplate().get("removeDirectory"));
        assertEquals("{service-name}", workflow.steps().get(1).argsTemplate().get("serviceName"));
    }

    @Test
    void extractToolSequenceFallsBackToDetailHeuristics() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("trace-seq-1"));

        List<String> seq = service.extractToolSequenceFromSteps(List.of(
                Map.of("phase", "perceive", "detail", "DiskAnalyze hotspot preview"),
                Map.of("phase", "execute", "detail", "LogCleanup deleted 5"),
                Map.of("phase", "verify", "detail", "DiskTool verification complete")
        ));

        assertEquals(List.of("DiskTool", "DiskAnalyzeTool", "LogCleanupTool", "DiskTool"), seq);
    }

    @Test
    void extractToolSequenceReturnsEmptyForNullSteps() {
        WorkflowInductionService service = new WorkflowInductionService(
                workflowMemoryService,
                trajectoryEvaluator,
                experienceLoader,
                newInductor(),
                new TestRuntimePlatform(false),
                new TestTraceIdGenerator("trace-seq-2"));

        assertTrue(service.extractToolSequenceFromSteps(null).isEmpty());
        assertFalse(service.extractToolSequenceFromSteps(List.of(Map.of("phase", "x", "detail", "none"))).size() > 0);
    }
}
