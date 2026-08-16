package com.award.log.service.impl;

import com.award.log.mapper.AlarmRuleMapper;
import com.award.log.model.AlarmRuleEntity;
import com.award.log.model.RuleDefinition;
import com.award.log.rule.dsl.RuleExpressionEvaluator;
import com.award.log.service.RuleRegistryService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@org.springframework.context.annotation.DependsOn("alarmRuleSchemaService")
public class RuleRegistryServiceImpl implements RuleRegistryService {

    private final Map<String, RuleDefinition> cache = new ConcurrentHashMap<>();
    private final RuleExpressionEvaluator expressionEvaluator;
    private final AlarmRuleMapper alarmRuleMapper;

    public RuleRegistryServiceImpl(RuleExpressionEvaluator expressionEvaluator,
                                   AlarmRuleMapper alarmRuleMapper) {
        this.expressionEvaluator = expressionEvaluator;
        this.alarmRuleMapper = alarmRuleMapper;
    }

    @PostConstruct
    public void init() {
        loadDefaultsIfMissing();
        refreshCacheFromDatabase();
    }

    @Override
    public List<RuleDefinition> list() {
        log.debug("获取规则列表，当前规则数量: {}", cache.size());
        return new ArrayList<>(cache.values());
    }

    @Override
    public RuleDefinition save(RuleDefinition rule) {
        log.debug("保存规则: {}", rule.getName());
        validateRule(rule);
        AlarmRuleEntity entity = toEntity(rule);
        if (entity.getId() == null) {
            alarmRuleMapper.insert(entity);
        } else if (alarmRuleMapper.update(entity) == 0) {
            throw new IllegalArgumentException("规则不存在");
        }
        RuleDefinition saved = toRuleDefinition(entity);
        cache.put(saved.getId(), saved);
        log.info("规则保存成功: {} (ID: {})", saved.getName(), saved.getId());
        return saved;
    }

    @Override
    public boolean delete(String id) {
        log.debug("删除规则，ID: {}", id);
        Long dbId = parseId(id);
        if (dbId == null) {
            return false;
        }
        boolean removed = alarmRuleMapper.deleteById(dbId) > 0;
        if (removed) {
            cache.remove(id);
            log.info("规则删除成功，ID: {}", id);
        } else {
            log.warn("规则删除失败，ID不存在: {}", id);
        }
        return removed;
    }

    @Override
    public boolean testRule(String id, Map<String, Object> payload) {
        log.debug("测试规则，ID: {}", id);
        try {
            RuleDefinition rule = cache.get(id);
            if (rule == null) {
                log.warn("测试规则失败，规则不存在，ID: {}", id);
                return false;
            }
            if (!rule.isEnabled()) {
                log.debug("测试规则失败，规则已禁用，ID: {}", id);
                return false;
            }
            boolean result = expressionEvaluator.evaluate(rule.getExpression(), payload);
            log.debug("规则测试结果，ID: {}, 结果: {}", id, result);
            return result;
        } catch (Exception e) {
            log.error("测试规则失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private void validateRule(RuleDefinition rule) {
        if (rule == null) {
            throw new IllegalArgumentException("规则不能为空");
        }
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (rule.getExpression() == null || rule.getExpression().isBlank()) {
            throw new IllegalArgumentException("规则表达式不能为空");
        }
        if (!validateRuleExpression(rule.getExpression())) {
            throw new IllegalArgumentException("规则表达式无效");
        }
        if (rule.getSeverity() == null || rule.getSeverity().isBlank()) {
            rule.setSeverity("ERROR");
        }
    }

    private boolean validateRuleExpression(String expression) {
        try {
            expressionEvaluator.evaluate(expression, Map.of(
                    "LEVEL", "ERROR",
                    "RATE", 1.0D,
                    "COUNT", 1,
                    "SUPPRESSED", false));
            return true;
        } catch (Exception e) {
            log.error("验证规则表达式失败: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadDefaultsIfMissing() {
        try {
            List<AlarmRuleEntity> existing = alarmRuleMapper.selectAll();
            if (existing != null && !existing.isEmpty()) {
                return;
            }
        } catch (Exception e) {
            log.warn("检查默认规则失败: {}", e.getMessage());
            return;
        }

        log.info("加载默认规则");
        try (InputStream in = getClass().getResourceAsStream("/rules/default-rules.yml")) {
            if (in == null) {
                log.warn("默认规则文件不存在");
                return;
            }
            Object loadedObject = new Yaml().load(in);
            if (!(loadedObject instanceof Map<?, ?> root)) {
                log.warn("默认规则文件格式错误，根节点不是 Map");
                return;
            }
            Object rulesObject = root.get("rules");
            if (!(rulesObject instanceof List<?> rulesList)) {
                log.warn("默认规则文件格式错误，rules 节点不是 List");
                return;
            }
            for (Object item : rulesList) {
                if (!(item instanceof Map<?, ?> ruleMap)) {
                    continue;
                }
                RuleDefinition rule = new RuleDefinition();
                Object nameObj = ruleMap.get("name");
                Object expressionObj = ruleMap.get("expression");
                if (nameObj == null || expressionObj == null) {
                    continue;
                }
                rule.setName(String.valueOf(nameObj));
                Object descriptionObj = ruleMap.containsKey("description") ? ruleMap.get("description") : "系统默认规则";
                Object ruleTypeObj = ruleMap.containsKey("ruleType") ? ruleMap.get("ruleType") : "COMBINATION";
                rule.setExpression(String.valueOf(expressionObj));
                Object severityObj = ruleMap.containsKey("severity") ? ruleMap.get("severity") : "ERROR";
                Object pushChannelsObj = ruleMap.containsKey("pushChannels") ? ruleMap.get("pushChannels") : "BOTH";
                Object enabledObj = ruleMap.containsKey("enabled") ? ruleMap.get("enabled") : Boolean.TRUE;
                rule.setDescription(String.valueOf(descriptionObj));
                rule.setRuleType(String.valueOf(ruleTypeObj));
                rule.setSeverity(String.valueOf(severityObj));
                rule.setPushChannels(String.valueOf(pushChannelsObj));
                rule.setEnabled(Boolean.parseBoolean(String.valueOf(enabledObj)));
                if (!validateRuleExpression(rule.getExpression())) {
                    continue;
                }
                AlarmRuleEntity entity = toEntity(rule);
                entity.setCreateBy("system");
                entity.setUpdateBy("system");
                try {
                    alarmRuleMapper.insert(entity);
                } catch (Exception e) {
                    log.debug("默认规则落库跳过: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("加载默认规则失败: {}", e.getMessage(), e);
        }
    }

    private void refreshCacheFromDatabase() {
        cache.clear();
        try {
            for (AlarmRuleEntity entity : alarmRuleMapper.selectAll()) {
                RuleDefinition rule = toRuleDefinition(entity);
                if (rule.getId() != null) {
                    cache.put(rule.getId(), rule);
                }
            }
            log.info("规则缓存已从数据库刷新，当前规则数量: {}", cache.size());
        } catch (Exception e) {
            log.warn("从数据库加载规则失败: {}", e.getMessage());
        }
    }

    private AlarmRuleEntity toEntity(RuleDefinition rule) {
        AlarmRuleEntity entity = new AlarmRuleEntity();
        entity.setId(parseId(rule.getId()));
        entity.setName(rule.getName());
        entity.setDescription(rule.getDescription() == null || rule.getDescription().isBlank()
                ? rule.getName() : rule.getDescription());
        entity.setRuleType(rule.getRuleType() == null || rule.getRuleType().isBlank()
                ? "COMBINATION" : rule.getRuleType());
        entity.setRuleExpression(rule.getExpression());
        entity.setSeverity(rule.getSeverity());
        entity.setPushChannels(rule.getPushChannels() == null || rule.getPushChannels().isBlank()
                ? "BOTH" : rule.getPushChannels());
        entity.setEnabled(rule.isEnabled());
        entity.setCreateBy("system");
        entity.setUpdateBy("system");
        return entity;
    }

    private RuleDefinition toRuleDefinition(AlarmRuleEntity entity) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(entity.getId() == null ? null : String.valueOf(entity.getId()));
        rule.setName(entity.getName());
        rule.setDescription(entity.getDescription());
        rule.setRuleType(entity.getRuleType());
        rule.setExpression(entity.getRuleExpression());
        rule.setSeverity(entity.getSeverity());
        rule.setPushChannels(entity.getPushChannels());
        rule.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        return rule;
    }

    private Long parseId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
