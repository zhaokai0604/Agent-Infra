package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowMemoryServiceTest {

    @Test
    void fromRowParsesWorkflowStepsAndKinds() {
        WorkflowMemoryService service = new WorkflowMemoryService(null);
        Map<String, Object> row = Map.of(
                "workflow_id", "wf-1",
                "domain_tag", "disk",
                "finding_kinds", "DISK_PRESSURE,LOG_ANOMALY",
                "title", "disk flow",
                "description", "desc",
                "steps_json", """
                        [
                          {"envDesc":"disk high","reason":"collect","toolName":"DiskTool","argsTemplate":{}},
                          {"envDesc":"cleanup","reason":"preview","toolName":"LogCleanupTool","argsTemplate":{"path":"{log-path}"}}
                        ]
                        """,
                "source_type", "online",
                "source_trace_id", "trace-1",
                "utility_count", 3,
                "enabled", 1
        );

        OpsWorkflow workflow = service.fromRow(row);

        assertNotNull(workflow);
        assertEquals("wf-1", workflow.workflowId());
        assertEquals(List.of("DISK_PRESSURE", "LOG_ANOMALY"), workflow.findingKinds());
        assertEquals(2, workflow.steps().size());
        assertEquals("LogCleanupTool", workflow.steps().get(1).toolName());
    }

    @Test
    void existsWithToolSequenceChecksCache() {
        WorkflowMemoryService service = new WorkflowMemoryService(null);
        ReflectionTestUtils.setField(service, "enabled", true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.CopyOnWriteArrayList<OpsWorkflow> cache =
                (java.util.concurrent.CopyOnWriteArrayList<OpsWorkflow>) ReflectionTestUtils.getField(service, "cache");
        cache.add(new OpsWorkflow(
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
        ));

        assertTrue(service.existsWithToolSequence("disk", List.of("DiskTool", "LogCleanupTool")));
        assertFalse(service.existsWithToolSequence("cpu", List.of("DiskTool", "LogCleanupTool")));
    }

    @Test
    void isDomainFullUsesConfiguredCap() {
        WorkflowMemoryService service = new WorkflowMemoryService(null);
        ReflectionTestUtils.setField(service, "maxWorkflowsPerDomain", 1);
        @SuppressWarnings("unchecked")
        java.util.concurrent.CopyOnWriteArrayList<OpsWorkflow> cache =
                (java.util.concurrent.CopyOnWriteArrayList<OpsWorkflow>) ReflectionTestUtils.getField(service, "cache");
        cache.add(new OpsWorkflow(
                "wf-1",
                "service",
                List.of("FAILED_SERVICE"),
                "service",
                "service",
                List.of(),
                "seed",
                null,
                0,
                true
        ));

        assertTrue(service.isDomainFull("service"));
        assertEquals(1, service.countByDomain("service"));
    }
}
