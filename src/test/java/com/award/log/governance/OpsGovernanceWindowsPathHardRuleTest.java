package com.award.log.governance;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsGovernanceWindowsPathHardRuleTest {

    @Test
    void windowsProtectedPathsForceConfirmOnly() {
        OpsGovernanceService svc = new OpsGovernanceService(new OpsGovernanceProperties());
        var eval = svc.evaluateStep(Map.of(
                "kind", "CLEAN_TEMP",
                "path", "C:\\Windows\\Temp",
                "days", 7));
        assertEquals(GovernanceAdmissionVerdict.CONFIRM_ONLY, eval.verdict());
        assertTrue(eval.reason().contains("硬规则"));
    }

    @Test
    void userTempStillEligibleForMatrix() {
        OpsGovernanceService svc = new OpsGovernanceService(new OpsGovernanceProperties());
        var eval = svc.evaluateStep(Map.of(
                "kind", "CLEAN_TEMP",
                "path", "C:/Users/Administrator/AppData/Local/Temp",
                "days", 7));
        // 非系统硬规则路径：由动作矩阵决定（temp-cleanup 默认须确认）
        assertEquals(GovernanceAdmissionVerdict.CONFIRM_ONLY, eval.verdict());
    }

    @Test
    void isWindowsProtectedPathDetectsProgramFiles() {
        assertTrue(OpsGovernanceService.isWindowsProtectedPath("C:/Program Files/Foo"));
        assertTrue(OpsGovernanceService.isWindowsProtectedPath("C:\\Windows\\System32"));
    }
}
