package com.award.log.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsReportFormatTest {

    @Test
    void tableSeparatorLineMatchesColumnCount() {
        assertEquals("|------|------|\n", OpsReportFormat.tableSeparatorLine(2));
        assertEquals("|------|------|------|\n", OpsReportFormat.tableSeparatorLine(3));
    }

    @Test
    void tableHeaderLineUsesDashSeparator() {
        String header = OpsReportFormat.tableHeaderLine("项目", "状态");
        assertTrue(header.contains("|------|------|"));
        assertFalse(header.contains("| --- |"));
    }

    @Test
    void formatFindingLineUsesSeverityBadge() {
        String line = OpsReportFormat.formatFindingLine("HIGH", "磁盘压力", "最高分区约 `90%`");
        assertTrue(line.contains("**[HIGH]**"));
        assertTrue(line.contains("`90%`"));
    }

    @Test
    void formatNameSampleCapsList() {
        String sample = OpsReportFormat.formatNameSample(List.of("a", "b", "c", "d", "e", "f"), 3);
        assertTrue(sample.contains("6 total"));
        assertTrue(sample.contains("`a`"));
    }

    @Test
    void markdownOutputSpecForPromptContainsCoreRules() {
        String spec = OpsReportFormat.markdownOutputSpecForPrompt();
        assertTrue(spec.contains("|------|------|"));
        assertTrue(spec.contains("**[HIGH]**"));
        assertTrue(spec.contains("输出格式"));
    }
}
