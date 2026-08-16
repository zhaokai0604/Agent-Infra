package com.award.log.decision;

import com.award.log.model.RuleDefinition;
import com.award.log.service.AlarmLifecycleService;
import com.award.log.service.RuleRegistryService;
import com.award.log.service.RuleStatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

@Component
public class RuleEngineV2 {

    private final RuleRegistryService ruleRegistryService;
    private final RuleStatService ruleStatService;

    @Autowired(required = false)
    private AlarmLifecycleService alarmLifecycleService;

    @Value("${log.pipeline.decision.rule.high-confidence:0.9}")
    private double highConfidence;

    @Value("${log.pipeline.decision.feature-version:rf-v2}")
    private String featureVersion;

    public RuleEngineV2(RuleRegistryService ruleRegistryService,
                        RuleStatService ruleStatService) {
        this.ruleRegistryService = ruleRegistryService;
        this.ruleStatService = ruleStatService;
    }

    public DecisionResult evaluate(DecisionInput input) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("LEVEL", input.getEvent().getLevel());
        ctx.put("RATE", input.getErrorRate1m());
        ctx.put("COUNT", input.getError1m());
        boolean silenced = alarmLifecycleService != null && alarmLifecycleService.isSilencedNow();
        ctx.put("SUPPRESSED", silenced);
        ctx.put("CPU_USAGE", currentCpuUsagePercent());

        if (silenced) {
            return DecisionResult.builder()
                    .engineType(EngineType.RULE)
                    .shouldAlert(false)
                    .confidence(0.9)
                    .featureVersion(featureVersion)
                    .modelVersion("rule-v2")
                    .reason("静默窗口内抑制规则告警")
                    .recommendation("等待静默结束后再评估")
                    .build();
        }

        boolean hit = false;
        String ruleName = "none";
        for (RuleDefinition rule : ruleRegistryService.list()) {
            boolean matched = ruleRegistryService.testRule(rule.getId(), ctx);
            ruleStatService.record(rule.getId(), rule.getName(), matched);
            if (matched) {
                hit = true;
                ruleName = rule.getName();
                break;
            }
        }
        return DecisionResult.builder()
                .engineType(EngineType.RULE)
                .shouldAlert(hit)
                .confidence(hit ? highConfidence : 0.4)
                .featureVersion(featureVersion)
                .modelVersion("rule-v2")
                .reason("规则引擎V2命中规则: " + ruleName)
                .recommendation("优先执行Runbook并标记告警生命周期")
                .build();
    }

    private static double currentCpuUsagePercent() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                double load = sunOs.getCpuLoad();
                if (load >= 0) {
                    return load * 100.0;
                }
            }
            double sysLoad = bean.getSystemLoadAverage();
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            if (sysLoad >= 0) {
                return Math.min(100.0, (sysLoad / cores) * 100.0);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return 0.0;
    }
}
