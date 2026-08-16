package com.award.log.agent;

import com.award.log.agent.awm.OpsWorkflow;
import com.award.log.agent.awm.OpsWorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillPlanTest {

    @Test
    void diskMessagePlansCleanupTools() {
        List<String> tools = AgentSkillPlan.forOrchestrate("磁盘快满了，帮我清理临时文件");
        assertTrue(tools.contains("DiskTool"));
        assertTrue(tools.contains("CleanTempTool") || tools.contains("LogCleanupTool"));
    }

    @Test
    void cpuMessagePlansProcessTools() {
        List<String> tools = AgentSkillPlan.forToolAgent("CPU 很高，看看哪个进程");
        assertTrue(tools.contains("SystemLoadTool") || tools.contains("ProcessTool"));
    }

    @Test
    void genericSystemStatusRequestStaysReadOnlyPatrol() {
        List<String> tools = AgentSkillPlan.forOrchestrate("检查系统状态，发现问题并给出处置计划");
        assertTrue(tools.contains("SystemLoadTool"));
        assertTrue(tools.contains("DiskTool"));
        assertFalse(tools.contains("CleanTempTool"));
        assertFalse(tools.contains("LogCleanupTool"));
        assertFalse(AgentSkillPlan.hasWriteTools(tools));
    }

    @Test
    void remediationItemsMarkPreviewWhenNotConfirmed() {
        List<String> items = AgentSkillPlan.remediationItems(List.of("CleanTempTool", "DiskTool"), false);
        assertFalse(items.isEmpty());
        assertTrue(items.stream().anyMatch(s -> s.contains("预览") || s.contains("确认")));
    }

    @Test
    void readOnlyPlanDoesNotInventConfirmationStep() {
        List<String> items = AgentSkillPlan.remediationItems(
                List.of("SystemLoadTool", "ProcessTool", "DiskTool"), false);
        assertFalse(items.stream().anyMatch(s -> s.contains("确认执行") || s.contains("写操作")));
    }

    @Test
    void remediationPlanMarkdownParsable() {
        String md = OpsReportFormat.remediationPlanMarkdown(List.of("预览清理临时目录", "采集磁盘占用"));
        assertTrue(md.contains("## 处置计划"));
        assertTrue(md.contains("确认执行"));
    }

    @Test
    void shortConfirmUsesConversationContext() {
        String seed = AgentSkillPlan.planningSeed(
                "确认执行",
                "磁盘快满了 帮我清理临时文件 ## 处置计划 CleanTempTool");
        assertTrue(seed.contains("磁盘") || seed.contains("清理"));
        List<String> tools = AgentSkillPlan.resolveTools(
                "确认执行",
                "磁盘快满了，帮我清理临时文件",
                null,
                false);
        assertTrue(tools.contains("CleanTempTool") || tools.contains("DiskTool"));
    }

    @Test
    void preferAwmPutsWorkflowToolsFirst() {
        OpsWorkflow awm = new OpsWorkflow(
                "wf-1",
                "disk",
                List.of("DISK_PRESSURE"),
                "历史清理",
                "复用清理套路",
                List.of(
                        OpsWorkflowStep.of("linux", "clean temp", "CleanTempTool"),
                        OpsWorkflowStep.of("linux", "check disk", "DiskTool")
                ),
                "INDUCED",
                "trace-1",
                3,
                true
        );
        List<String> merged = AgentSkillPlan.preferAwm(List.of("SystemLoadTool", "DiskTool"), awm);
        assertEquals("CleanTempTool", merged.get(0));
        assertTrue(merged.contains("SystemLoadTool"));
        assertTrue(merged.contains("DiskTool"));
    }

    @Test
    void hasWriteToolsAndPlanPhase() {
        assertTrue(AgentSkillPlan.hasWriteTools(List.of("DiskTool", "CleanTempTool")));
        assertFalse(AgentSkillPlan.hasWriteTools(List.of("DiskTool", "SystemLoadTool")));
        assertEquals("DIAGNOSE_THEN_CONFIRM",
                AgentSkillPlan.planPhase(false, List.of("CleanTempTool")));
        assertEquals("EXECUTE",
                AgentSkillPlan.planPhase(true, List.of("CleanTempTool")));
        assertEquals("DIAGNOSE",
                AgentSkillPlan.planPhase(false, List.of("DiskTool")));
    }

    @Test
    void phaseRulesMentionConfirmWhenPreview() {
        String rules = AssistantReplyPrompts.toolAgentPhaseRules(false, List.of("CleanTempTool"), false);
        assertTrue(rules.contains("未挂载") || rules.contains("确认执行"));
        String exec = AssistantReplyPrompts.toolAgentPhaseRules(true, List.of("CleanTempTool"), true);
        assertTrue(exec.contains("确认执行") || exec.contains("已挂载"));
    }
}
