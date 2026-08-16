package com.award.log.governance;

import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.OpsTrustPolicy;
import com.award.log.security.OpsTrustTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsRemediationGateTest {

    @Mock
    private OpsGovernanceService governanceService;

    @Mock
    private OpsTrustPolicy opsTrustPolicy;

    @Mock
    private AgenticRiskScoreEngine riskScoreEngine;

    private OpsRemediationGate gate;

    @BeforeEach
    void setUp() {
        gate = new OpsRemediationGate(governanceService, opsTrustPolicy, riskScoreEngine);
    }

    @Test
    void serviceRestartConfirmOnlyWithoutForce() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        AssetTier.NON_CORE,
                        "nginx",
                        "须确认"));
        when(opsTrustPolicy.tierForTool(anyString(), anyMap(), anyString())).thenReturn(OpsTrustTier.AUTO);
        when(riskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(2.0, Map.of(), "low"));

        OpsRemediationGate.RemediationDecision d =
                gate.decideServiceRestart("nginx", false, "restart nginx");

        assertEquals(OpsRemediationGate.WriteDecision.PREVIEW, d.decision());
        assertTrue(d.mayPreview());
        assertFalse(d.mayExecute());
    }

    @Test
    void serviceRestartConfirmOnlyWithForce() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.CONFIRM_ONLY,
                        AssetTier.NON_CORE,
                        "nginx",
                        "须确认"));
        when(opsTrustPolicy.tierForTool(anyString(), anyMap(), anyString())).thenReturn(OpsTrustTier.AUTO);
        when(riskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(2.0, Map.of(), "low"));

        OpsRemediationGate.RemediationDecision d =
                gate.decideServiceRestart("nginx", true, "restart nginx");

        assertEquals(OpsRemediationGate.WriteDecision.EXECUTE, d.decision());
        assertTrue(d.mayExecute());
    }

    @Test
    void forbiddenGovernanceBlocksEvenWithForce() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.FORBIDDEN,
                        AssetTier.FORBIDDEN_AUTO,
                        "sshd",
                        "禁止"));

        OpsRemediationGate.RemediationDecision d =
                gate.decideServiceRestart("sshd", true, "restart sshd");

        assertEquals(OpsRemediationGate.WriteDecision.FORBIDDEN, d.decision());
        assertTrue(d.forbidden());
    }

    @Test
    void tempCleanupAutoLaneExecutesWhenTrustAllows() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        AssetTier.NON_CORE,
                        "/tmp",
                        "ok"));
        when(opsTrustPolicy.tierForTool(anyString(), anyMap(), anyString())).thenReturn(OpsTrustTier.AUTO);
        when(riskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(1.0, Map.of(), "low"));
        when(opsTrustPolicy.tierExplanation(any(), anyDouble())).thenReturn("auto lane");

        OpsRemediationGate.RemediationDecision d =
                gate.decideTempCleanup("/tmp", 7, false, "clean temp");
        assertEquals(OpsRemediationGate.WriteDecision.EXECUTE, d.decision());
    }

    @Test
    void trustBlockOverridesAllowAuto() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        AssetTier.NON_CORE,
                        "/tmp",
                        "ok"));
        when(opsTrustPolicy.tierForTool(anyString(), anyMap(), anyString())).thenReturn(OpsTrustTier.BLOCK);
        when(riskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(9.0, Map.of(), "high"));
        when(opsTrustPolicy.tierExplanation(any(), anyDouble())).thenReturn("blocked");

        OpsRemediationGate.RemediationDecision d =
                gate.decideLogCleanup("/var/log", 14, false, "clean logs");
        assertEquals(OpsRemediationGate.WriteDecision.FORBIDDEN, d.decision());
    }

    @Test
    void confirmTierWithoutForcePreviewsLogCleanup() {
        when(governanceService.evaluateStep(anyMap())).thenReturn(
                new OpsGovernanceService.GovernanceEvaluation(
                        GovernanceAdmissionVerdict.ALLOW_AUTO,
                        AssetTier.NON_CORE,
                        "/var/log",
                        "ok"));
        when(opsTrustPolicy.tierForTool(anyString(), anyMap(), anyString())).thenReturn(OpsTrustTier.APPROVE);
        when(riskScoreEngine.score(anyString(), anyMap(), anyString()))
                .thenReturn(new AgenticRiskScoreEngine.ScoreResult(5.0, Map.of(), "medium"));
        when(opsTrustPolicy.tierExplanation(any(), anyDouble())).thenReturn("need confirm");

        OpsRemediationGate.RemediationDecision d =
                gate.decideLogCleanup("/var/log", 14, false, "clean logs");
        assertEquals(OpsRemediationGate.WriteDecision.PREVIEW, d.decision());
    }
}
