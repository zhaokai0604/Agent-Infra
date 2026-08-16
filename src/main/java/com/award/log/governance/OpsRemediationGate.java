package com.award.log.governance;

import com.award.log.security.AgenticRiskScoreEngine;
import com.award.log.security.OpsTrustPolicy;
import com.award.log.security.OpsTrustTier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写操作统一门禁：资产治理（{@link OpsGovernanceService}）优先于风险分档位（{@link OpsTrustPolicy}）。
 * <p>巡检自动修复、自主编排、Playbook 短路径共用同一口径。</p>
 */
@Component
public class OpsRemediationGate {

    public enum WriteDecision {
        FORBIDDEN,
        PREVIEW,
        EXECUTE
    }

    public record RemediationDecision(
            WriteDecision decision,
            GovernanceAdmissionVerdict governanceVerdict,
            OpsTrustTier trustTier,
            String reason
    ) {
        public boolean mayExecute() {
            return decision == WriteDecision.EXECUTE;
        }

        public boolean mayPreview() {
            return decision == WriteDecision.PREVIEW;
        }

        public boolean forbidden() {
            return decision == WriteDecision.FORBIDDEN;
        }
    }

    private final OpsGovernanceService governanceService;
    private final OpsTrustPolicy opsTrustPolicy;
    private final AgenticRiskScoreEngine riskScoreEngine;

    public OpsRemediationGate(
            OpsGovernanceService governanceService,
            OpsTrustPolicy opsTrustPolicy,
            AgenticRiskScoreEngine riskScoreEngine) {
        this.governanceService = governanceService;
        this.opsTrustPolicy = opsTrustPolicy;
        this.riskScoreEngine = riskScoreEngine;
    }

    public RemediationDecision decideTempCleanup(String path, int days, boolean forceConfirmed,
                                                 String instruction) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        params.put("days", days);
        params.put("dryRun", true);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "CLEAN_TEMP");
        step.put("path", path);
        step.put("days", days);
        return decide(step, forceConfirmed, "CleanTempTool", params, instruction);
    }

    public RemediationDecision decideLogCleanup(String path, int days, boolean forceConfirmed,
                                                String instruction) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        params.put("days", days);
        params.put("dryRun", true);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "CLEAN_LOG");
        step.put("path", path);
        step.put("days", days);
        return decide(step, forceConfirmed, "LogCleanupTool", params, instruction);
    }

    public RemediationDecision decideServiceRestart(String serviceName, boolean forceConfirmed,
                                                    String instruction) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceName", serviceName);
        params.put("dryRun", true);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", "RESTART_SERVICE");
        step.put("serviceName", serviceName);
        return decide(step, forceConfirmed, "ServiceRestartTool", params, instruction);
    }

    private RemediationDecision decide(
            Map<String, Object> step,
            boolean forceConfirmed,
            String toolName,
            Map<String, Object> params,
            String instruction) {

        OpsGovernanceService.GovernanceEvaluation gov = governanceService.evaluateStep(step);
        if (gov.verdict() == GovernanceAdmissionVerdict.FORBIDDEN) {
            return new RemediationDecision(
                    WriteDecision.FORBIDDEN,
                    gov.verdict(),
                    OpsTrustTier.BLOCK,
                    "治理策略：" + gov.reason());
        }

        OpsTrustTier trustTier = opsTrustPolicy.tierForTool(toolName, params, instruction);
        double score = riskScoreEngine.score(toolName, params, instruction).total();

        if (trustTier == OpsTrustTier.BLOCK) {
            return new RemediationDecision(
                    WriteDecision.FORBIDDEN,
                    gov.verdict(),
                    trustTier,
                    opsTrustPolicy.tierExplanation(trustTier, score));
        }

        if (gov.verdict() == GovernanceAdmissionVerdict.CONFIRM_ONLY) {
            if (forceConfirmed) {
                return new RemediationDecision(
                        WriteDecision.EXECUTE,
                        gov.verdict(),
                        trustTier,
                        "用户已确认；治理要求确认后执行：" + gov.reason());
            }
            return new RemediationDecision(
                    WriteDecision.PREVIEW,
                    gov.verdict(),
                    trustTier,
                    "治理策略：" + gov.reason());
        }

        if (forceConfirmed || trustTier == OpsTrustTier.AUTO || trustTier == OpsTrustTier.NOTIFY) {
            return new RemediationDecision(
                    WriteDecision.EXECUTE,
                    gov.verdict(),
                    trustTier,
                    opsTrustPolicy.tierExplanation(trustTier, score));
        }

        return new RemediationDecision(
                WriteDecision.PREVIEW,
                gov.verdict(),
                trustTier,
                opsTrustPolicy.tierExplanation(trustTier, score));
    }
}
