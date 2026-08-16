package com.award.log.security;

import com.award.log.rule.dsl.RuleExpressionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HighRiskCommandDetector {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\/\\s*\\*|chmod\\s+-R?\\s*777\\s+/|dd\\s+if=/dev/(zero|random)|\\bmkfs\\.?\\b|fork\\s+bomb|sudo\\s+(rm|mkfs|dd|shutdown|reboot)|"
                    + ":\\(\\)\\{\\s*:\\|:&\\s*\\};:|\\bcurl\\b[^\\n]{0,400}\\|\\s*(ba)?sh|\\bwget\\b[^\\n]{0,400}\\|\\s*(ba)?sh|"
                    + "\\bcurl\\b[^\\n]{0,300}-o\\s+\\S+[^\\n]{0,160}(&&|;|\\n)[^\\n]{0,120}(\\b(ba)?sh\\b|chmod\\s+\\+x|/tmp/\\S+\\.(sh|bash|py))|"
                    + "\\bwget\\b[^\\n]{0,300}(-O\\s+\\S+)?[^\\n]{0,160}(&&|;|\\n)[^\\n]{0,120}(\\b(ba)?sh\\b|chmod\\s+\\+x|/tmp/\\S+\\.(sh|bash|py))|"
                    + "chmod\\s+\\+x\\s+/tmp/\\S+|\\b(ba)?sh\\s+/tmp/\\S+|"
                    + "powershell(\\.exe)?[^\\n]{0,200}-enc\\s|certutil\\s+-decode|regsvr32[^\\n]{0,200}https?://|"
                    + "((?:ba)?sh|source)\\s*<\\s*\\(\\s*(?:curl|wget)\\b)",
            Pattern.CASE_INSENSITIVE);

    private final RuleExpressionEvaluator ruleExpressionEvaluator;
    private final Map<String, HighRiskCommandRule> ruleCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public HighRiskCommandDetector(RuleExpressionEvaluator ruleExpressionEvaluator) {
        this.ruleExpressionEvaluator = ruleExpressionEvaluator;
        // 初始化默认规则
        ruleCache.put(HighRiskCommandRule.createDefaultRule().getId(), HighRiskCommandRule.createDefaultRule());
    }

    public boolean isHighRiskCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }

        if (evaluateWithRegex(command)) {
            log.info("内置正则命中高危命令特征");
            return true;
        }

        if (ruleExpressionEvaluator != null && evaluateWithRuleEngine(command)) {
            log.info("扩展高危命令规则引擎命中");
            return true;
        }
        return false;
    }

    private boolean evaluateWithRuleEngine(String command) {
        try {
            Map<String, Object> context = Map.of(
                "COMMAND", command.toUpperCase()
            );

            for (HighRiskCommandRule rule : ruleCache.values()) {
                if (!rule.isEnabled()) {
                    continue;
                }
                if (ruleExpressionEvaluator.evaluate(rule.getRuleExpression(), context)) {
                    log.info("规则 [{}] 匹配成功", rule.getName());
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("高危命令规则引擎评估异常，已忽略该规则: {}", e.getMessage());
            return false;
        }
    }

    private boolean evaluateWithRegex(String command) {
        return HIGH_RISK_PATTERN.matcher(command).find();
    }

    public void registerRule(HighRiskCommandRule rule) {
        if (rule != null && rule.isEnabled()) {
            ruleCache.put(rule.getId(), rule);
            log.info("注册高危命令规则: {}", rule.getName());
        }
    }

    public void unregisterRule(String ruleId) {
        ruleCache.remove(ruleId);
        log.info("移除高危命令规则: {}", ruleId);
    }

    public void clearRules() {
        ruleCache.clear();
        // 保留默认规则
        ruleCache.put(HighRiskCommandRule.createDefaultRule().getId(), HighRiskCommandRule.createDefaultRule());
        log.info("清空并重置默认规则");
    }

    public int getRuleCount() {
        return ruleCache.size();
    }

    public void loadRulesFromConfig(String ruleExpression) {
        if (ruleExpression != null && !ruleExpression.trim().isEmpty()) {
            HighRiskCommandRule configRule = HighRiskCommandRule.builder()
                    .id("CONFIG_RULE")
                    .name("配置文件规则")
                    .description("从配置文件加载的规则")
                    .ruleExpression(ruleExpression)
                    .enabled(true)
                    .build();
            registerRule(configRule);
        }
    }
}