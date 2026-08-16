package com.award.log.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsGovernanceServiceTest {

    private OpsGovernanceService service;

    @BeforeEach
    void setUp() {
        OpsGovernanceProperties props = new OpsGovernanceProperties();
        props.setEnabled(true);
        service = new OpsGovernanceService(props);
    }

    @Test
    void tempCleanupOnTmpRequiresConfirm() {
        Map<String, Object> step = Map.of("kind", "CLEAN_TEMP", "path", "/tmp", "days", 7);
        OpsGovernanceService.GovernanceEvaluation eval = service.evaluateStep(step);
        assertEquals(GovernanceAdmissionVerdict.CONFIRM_ONLY, eval.verdict());
        assertEquals(AssetTier.NON_CORE, eval.assetTier());
    }

    @Test
    void tempCleanupOnFilesystemRootIsForbidden() {
        Map<String, Object> step = Map.of("kind", "CLEAN_TEMP", "path", "/", "days", 7);
        OpsGovernanceService.GovernanceEvaluation eval = service.evaluateStep(step);
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, eval.verdict());
        assertEquals(AssetTier.FORBIDDEN_AUTO, eval.assetTier());
        assertTrue(OpsGovernanceService.isFilesystemRoot("/"));
        assertTrue(OpsGovernanceService.isFilesystemRoot("C:/"));
        assertFalse(OpsGovernanceService.isFilesystemRoot("/tmp"));
    }

    @Test
    void serviceRestartRequiresConfirm() {
        Map<String, Object> step = Map.of("kind", "RESTART_SERVICE", "serviceName", "nginx");
        OpsGovernanceService.GovernanceEvaluation eval = service.evaluateStep(step);
        assertEquals(GovernanceAdmissionVerdict.CONFIRM_ONLY, eval.verdict());
    }

    @Test
    void forbiddenServiceIsRejected() {
        Map<String, Object> step = Map.of("kind", "RESTART_SERVICE", "serviceName", "sshd");
        OpsGovernanceService.GovernanceEvaluation eval = service.evaluateStep(step);
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, eval.verdict());
    }

    @Test
    void filterPlanStepsRemovesForbidden() {
        List<Map<String, Object>> in = List.of(
                Map.of("kind", "RESTART_SERVICE", "serviceName", "sshd"),
                Map.of("kind", "CLEAN_TEMP", "path", "/tmp", "days", 7)
        );
        List<Map<String, Object>> out = service.filterPlanSteps(in);
        assertEquals(1, out.size());
        assertEquals("CLEAN_TEMP", out.get(0).get("kind"));
        assertTrue(out.get(0).containsKey("governanceVerdict"));
    }

    @Test
    void coreStatefulServiceRestartForbidden() {
        Map<String, Object> step = Map.of("kind", "RESTART_SERVICE", "serviceName", "mysqld");
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, service.evaluateStep(step).verdict());
    }

    @Test
    void forbiddenPathBlocksCleanup() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "CLEAN_LOG");
        step.put("path", "/etc/nginx/logs");
        step.put("days", 30);
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, service.evaluateStep(step).verdict());
    }

    @Test
    void summaryAndTierResolutionHelpers() {
        Map<String, Object> summary = service.summaryForPlatform();
        assertTrue((Boolean) summary.get("enabled"));
        assertNotNull(summary.get("actionMatrix"));

        assertEquals(AssetTier.NON_CORE, service.resolvePathTier("/var/log/nginx"));
        assertEquals(AssetTier.FORBIDDEN_AUTO, service.resolvePathTier("/etc/passwd"));
        assertEquals(AssetTier.NON_CORE, service.resolveServiceTier("nginx.service"));
        assertEquals(AssetTier.CORE_STATEFUL, service.resolveServiceTier("mysqld"));
    }

    @Test
    void disabledGovernanceAllowsAllKnownSteps() {
        OpsGovernanceProperties props = new OpsGovernanceProperties();
        props.setEnabled(false);
        OpsGovernanceService disabled = new OpsGovernanceService(props);

        OpsGovernanceService.GovernanceEvaluation eval = disabled.evaluateStep(
                Map.of("kind", "RESTART_SERVICE", "serviceName", "sshd"));
        assertEquals(GovernanceAdmissionVerdict.ALLOW_AUTO, eval.verdict());
        assertFalse(disabled.isEnabled());
    }

    @Test
    void unknownStepKindIsForbidden() {
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN,
                service.evaluateStep(Map.of("kind", "UNKNOWN")).verdict());
    }

    @Test
    void cleanTempReadsTargetAliasAndForbidsEtc() {
        var eval = service.evaluateToolCall("CleanTempTool",
                Map.of("target", "/etc", "dryRun", false));
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, eval.verdict());
    }

    @Test
    void dockerLibPathIsForbidden() {
        var eval = service.evaluateToolCall("CleanTempTool",
                Map.of("path", "/var/lib/docker", "dryRun", false));
        assertEquals(GovernanceAdmissionVerdict.FORBIDDEN, eval.verdict());
    }
}
