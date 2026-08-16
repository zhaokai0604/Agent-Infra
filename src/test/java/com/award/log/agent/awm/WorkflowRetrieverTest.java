package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRetrieverTest {

    @Mock
    private WorkflowMemoryService workflowMemoryService;

    @Test
    void bestMatchPrefersDomainAndFindingOverlap() {
        WorkflowRetriever retriever = new WorkflowRetriever(workflowMemoryService);
        OpsWorkflow disk = new OpsWorkflow(
                "disk-pressure-remediation",
                "disk",
                List.of("DISK_PRESSURE"),
                "disk",
                "disk",
                List.of(OpsWorkflowStep.of("x", "x", "DiskTool", Map.of())),
                "seed",
                null,
                1,
                true
        );
        OpsWorkflow cpu = new OpsWorkflow(
                "cpu-pressure-diagnose",
                "cpu",
                List.of("CPU_HIGH"),
                "cpu",
                "cpu",
                List.of(OpsWorkflowStep.of("x", "x", "SystemLoadTool", Map.of())),
                "seed",
                null,
                5,
                true
        );

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(workflowMemoryService.listByDomain("disk")).thenReturn(List.of(disk));

        OpsWorkflow best = retriever.bestMatch("disk", List.of("DISK_PRESSURE"), "磁盘空间满了，帮我 clean");

        assertEquals("disk-pressure-remediation", best.workflowId());
    }

    @Test
    void retrieveReturnsAtMostLimitSortedByScore() {
        WorkflowRetriever retriever = new WorkflowRetriever(workflowMemoryService);
        OpsWorkflow lowUtility = new OpsWorkflow(
                "service-a",
                "service",
                List.of("FAILED_SERVICE"),
                "a",
                "a",
                List.of(OpsWorkflowStep.of("x", "x", "SystemdTool", Map.of())),
                "seed",
                null,
                0,
                true
        );
        OpsWorkflow highUtility = new OpsWorkflow(
                "service-b",
                "service",
                List.of("FAILED_SERVICE"),
                "b",
                "b",
                List.of(OpsWorkflowStep.of("x", "x", "SystemdTool", Map.of())),
                "seed",
                null,
                5,
                true
        );

        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(workflowMemoryService.listByDomain("service")).thenReturn(List.of(lowUtility, highUtility));

        List<OpsWorkflow> result = retriever.retrieve("service", List.of("FAILED_SERVICE"), "systemd failed", 1);

        assertEquals(1, result.size());
        assertEquals("service-b", result.get(0).workflowId());
    }

    @Test
    void bestMatchBoostsTempAndLagSynonyms() {
        WorkflowRetriever retriever = new WorkflowRetriever(workflowMemoryService);
        OpsWorkflow disk = new OpsWorkflow(
                "disk-pressure-remediation",
                "disk",
                List.of("DISK_PRESSURE"),
                "disk",
                "清理临时目录",
                List.of(OpsWorkflowStep.of("x", "x", "DiskTool", Map.of())),
                "seed",
                null,
                1,
                true
        );
        when(workflowMemoryService.isEnabled()).thenReturn(true);
        when(workflowMemoryService.listByDomain("disk")).thenReturn(List.of(disk));

        OpsWorkflow best = retriever.bestMatch("disk", List.of("DISK_PRESSURE"), "临时目录占用过高");

        assertEquals("disk-pressure-remediation", best.workflowId());
        verify(workflowMemoryService).listByDomain("disk");
    }

    @Test
    void recordHitDelegatesToMemoryService() {
        WorkflowRetriever retriever = new WorkflowRetriever(workflowMemoryService);
        OpsWorkflow workflow = new OpsWorkflow(
                "disk-pressure-remediation",
                "disk",
                List.of("DISK_PRESSURE"),
                "disk",
                "disk",
                List.of(),
                "seed",
                null,
                0,
                true
        );

        retriever.recordHit(workflow);

        verify(workflowMemoryService).recordUtility("disk-pressure-remediation");
    }

    @Test
    void retrieveReturnsEmptyWhenDisabled() {
        WorkflowRetriever retriever = new WorkflowRetriever(workflowMemoryService);
        when(workflowMemoryService.isEnabled()).thenReturn(false);
        assertTrue(retriever.retrieve("disk", List.of("DISK_PRESSURE"), "磁盘", 2).isEmpty());
    }
}
