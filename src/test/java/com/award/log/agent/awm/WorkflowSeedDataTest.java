package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowSeedDataTest {

    @Test
    void diskWorkflows_hasTwoSeedsWithToolSteps() {
        List<OpsWorkflow> seeds = WorkflowSeedData.diskWorkflows();
        assertEquals(2, seeds.size());
        assertEquals("disk-pressure-diagnose", seeds.get(0).workflowId());
        assertEquals("disk-pressure-remediation", seeds.get(1).workflowId());
        assertFalse(seeds.get(1).toolSequence().isEmpty());
        assertTrue(seeds.get(1).toolSequence().contains("LogCleanupTool"));
    }

    @Test
    void cpuWorkflows_hasDiagnoseAndRestart() {
        List<OpsWorkflow> cpu = WorkflowSeedData.cpuWorkflows();
        assertEquals(2, cpu.size());
        assertTrue(cpu.get(0).toolSequence().contains("ProcessTool"));
        assertTrue(cpu.get(1).toolSequence().contains("ServiceRestartTool"));
    }

    @Test
    void allSeeds_hasSixEntries() {
        assertEquals(6, WorkflowSeedData.allSeeds().size());
    }

    @Test
    void serviceWorkflows_hasSystemdAndRestart() {
        List<OpsWorkflow> svc = WorkflowSeedData.serviceWorkflows();
        assertEquals(2, svc.size());
        assertTrue(svc.get(0).toolSequence().contains("SystemdTool"));
        assertTrue(svc.get(1).toolSequence().contains("ServiceRestartTool"));
    }
}
