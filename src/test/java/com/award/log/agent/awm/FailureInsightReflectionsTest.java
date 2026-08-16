package com.award.log.agent.awm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureInsightReflectionsTest {

    @Test
    void reflect_mismatch_containsActionableHint() {
        String r = FailureInsightReflections.reflect("INTENT_TOOL_MISMATCH", "DiskTool", "删除整个系统盘");
        assertTrue(r.contains("只读"));
        assertTrue(r.contains("dryRun") || r.contains("CleanTemp"));
    }

    @Test
    void insightKey_stableForSameInput() {
        String k1 = FailureInsightReflections.insightKey("REJECT_INTENT_MISMATCH", "DiskTool", "删除日志");
        String k2 = FailureInsightReflections.insightKey("REJECT_INTENT_MISMATCH", "DiskTool", "删除日志");
        assertTrue(k1.equals(k2));
    }
}
