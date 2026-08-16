package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.mapper.AlarmRuleMapper;
import com.award.log.model.AlarmRuleEntity;
import com.award.log.model.RuleDefinition;
import com.award.log.service.RuleRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 与前端 /api/alarm-rule/** 对齐的兼容接口，底层改为真实数据库持久化。
 */
@Tag(name = "Alarm Rule (compat)", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/alarm-rule")
public class AlarmRuleCompatController {

    private final AlarmRuleMapper alarmRuleMapper;
    private final RuleRegistryService ruleRegistryService;

    public AlarmRuleCompatController(AlarmRuleMapper alarmRuleMapper,
                                     RuleRegistryService ruleRegistryService) {
        this.alarmRuleMapper = alarmRuleMapper;
        this.ruleRegistryService = ruleRegistryService;
    }

    @Operation(summary = "新增告警规则")
    @PostMapping("/add")
    public Result<Map<String, Object>> add(@RequestBody Map<String, Object> rule) {
        RuleDefinition saved = ruleRegistryService.save(toRuleDefinition(rule, null));
        AlarmRuleEntity entity = alarmRuleMapper.selectById(Long.parseLong(saved.getId()));
        return Result.success(toCompatMap(entity));
    }

    @Operation(summary = "更新告警规则")
    @PostMapping("/update")
    public Result<Map<String, Object>> update(@RequestBody Map<String, Object> rule) {
        Object idObj = rule.get("id");
        if (idObj == null) {
            return Result.error("id 不能为空");
        }
        Long id = parseId(idObj);
        if (id == null || alarmRuleMapper.selectById(id) == null) {
            return Result.error("规则不存在");
        }
        RuleDefinition saved = ruleRegistryService.save(toRuleDefinition(rule, String.valueOf(id)));
        AlarmRuleEntity entity = alarmRuleMapper.selectById(Long.parseLong(saved.getId()));
        return Result.success(toCompatMap(entity));
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> delete(@PathVariable long id) {
        return Result.success(ruleRegistryService.delete(String.valueOf(id)));
    }

    @Operation(summary = "按 id 查询")
    @GetMapping("/get/{id}")
    public Result<Map<String, Object>> get(@PathVariable long id) {
        AlarmRuleEntity entity = alarmRuleMapper.selectById(id);
        if (entity == null) {
            return Result.error("规则不存在");
        }
        return Result.success(toCompatMap(entity));
    }

    @Operation(summary = "全部规则")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.success(alarmRuleMapper.selectAll().stream()
                .map(this::toCompatMap)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "已启用规则")
    @GetMapping("/enabled")
    public Result<List<Map<String, Object>>> enabled() {
        return Result.success(alarmRuleMapper.selectEnabled().stream()
                .map(this::toCompatMap)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "启用/禁用")
    @PostMapping("/enable")
    public Result<Map<String, Object>> enable(@RequestParam long id, @RequestParam boolean enabled) {
        if (alarmRuleMapper.updateEnabled(id, enabled) == 0) {
            return Result.error("规则不存在");
        }
        AlarmRuleEntity entity = alarmRuleMapper.selectById(id);
        if (entity != null) {
            RuleDefinition refreshed = new RuleDefinition();
            refreshed.setId(String.valueOf(entity.getId()));
            refreshed.setName(entity.getName());
            refreshed.setDescription(entity.getDescription());
            refreshed.setRuleType(entity.getRuleType());
            refreshed.setExpression(entity.getRuleExpression());
            refreshed.setSeverity(entity.getSeverity());
            refreshed.setPushChannels(entity.getPushChannels());
            refreshed.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
            ruleRegistryService.save(refreshed);
        }
        return Result.success(toCompatMap(alarmRuleMapper.selectById(id)));
    }

    @Operation(summary = "规则试匹配")
    @PostMapping("/test")
    public Result<Map<String, Object>> test(
            @RequestParam long ruleId,
            @RequestParam(required = false, defaultValue = "") String testContent) {
        AlarmRuleEntity entity = alarmRuleMapper.selectById(ruleId);
        if (entity == null) {
            return Result.error("规则不存在");
        }
        Map<String, Object> payload = buildPayload(entity, testContent);
        boolean matched = ruleRegistryService.testRule(String.valueOf(ruleId), payload);
        return Result.success(Map.of(
                "ruleId", ruleId,
                "matched", matched,
                "sampleLength", testContent.length()
        ));
    }

    private RuleDefinition toRuleDefinition(Map<String, Object> raw, String forcedId) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(forcedId != null ? forcedId : stringValue(raw.get("id")));
        rule.setName(stringValue(raw.get("name")));
        rule.setDescription(stringValue(raw.get("description")));
        rule.setRuleType(resolveRuleType(raw));
        rule.setExpression(resolveExpression(raw));
        rule.setSeverity(normalizeSeverity(stringValue(raw.get("severity"))));
        rule.setPushChannels(resolvePushChannels(raw));
        rule.setEnabled(booleanValue(raw.get("enabled"), true));
        return rule;
    }

    private String resolveExpression(Map<String, Object> raw) {
        String camel = stringValue(raw.get("ruleExpression"));
        if (!camel.isBlank()) {
            return camel;
        }
        String direct = stringValue(raw.get("expression"));
        if (!direct.isBlank()) {
            return direct;
        }
        String ruleExpression = stringValue(raw.get("rule_expression"));
        if (!ruleExpression.isBlank()) {
            return ruleExpression;
        }
        String pattern = stringValue(raw.get("pattern"));
        if (!pattern.isBlank()) {
            return patternToExpression(pattern);
        }
        return "";
    }

    private String patternToExpression(String pattern) {
        String upper = pattern.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FAIL")) {
            return "LEVEL IN (ERROR,FATAL)";
        }
        if (upper.contains("WARN") || upper.contains("ALERT")) {
            return "LEVEL = WARNING";
        }
        if (upper.contains("INFO")) {
            return "LEVEL = INFO";
        }
        return "COUNT > 0";
    }

    private String resolveRuleType(Map<String, Object> raw) {
        String ruleType = stringValue(raw.get("ruleType"));
        if (ruleType.isBlank()) {
            ruleType = stringValue(raw.get("rule_type"));
        }
        return ruleType.isBlank() ? "COMBINATION" : ruleType.toUpperCase();
    }

    private String resolvePushChannels(Map<String, Object> raw) {
        String pushChannels = stringValue(raw.get("pushChannels"));
        if (pushChannels.isBlank()) {
            pushChannels = stringValue(raw.get("push_channels"));
        }
        return pushChannels.isBlank() ? "BOTH" : pushChannels.toUpperCase();
    }

    private Map<String, Object> buildPayload(AlarmRuleEntity entity, String testContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String content = testContent == null ? "" : testContent.toUpperCase();
        String severity = normalizeSeverity(entity.getSeverity());
        if (content.contains("FATAL")) {
            severity = "FATAL";
        } else if (content.contains("ERROR") || content.contains("EXCEPTION") || content.contains("FAIL")) {
            severity = "ERROR";
        } else if (content.contains("WARN") || content.contains("WARNING") || content.contains("ALERT")) {
            severity = "WARNING";
        } else if (content.contains("INFO")) {
            severity = "INFO";
        }
        payload.put("LEVEL", severity);
        payload.put("COUNT", testContent == null || testContent.isBlank() ? 0 : 1);
        payload.put("RATE", testContent == null || testContent.isBlank() ? 0D : 1D);
        payload.put("SUPPRESSED", false);
        return payload;
    }

    private Map<String, Object> toCompatMap(AlarmRuleEntity entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entity.getId());
        row.put("name", entity.getName());
        row.put("description", entity.getDescription());
        row.put("ruleType", entity.getRuleType());
        row.put("rule_type", entity.getRuleType());
        row.put("expression", entity.getRuleExpression());
        row.put("rule_expression", entity.getRuleExpression());
        row.put("pattern", entity.getRuleExpression());
        row.put("severity", entity.getSeverity());
        row.put("pushChannels", entity.getPushChannels());
        row.put("push_channels", entity.getPushChannels());
        row.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
        row.put("createTime", entity.getCreateTime());
        row.put("updateTime", entity.getUpdateTime());
        return row;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeSeverity(String severity) {
        String value = severity == null ? "" : severity.trim().toUpperCase();
        return switch (value) {
            case "FATAL", "ERROR", "WARNING", "INFO" -> value;
            case "WARN" -> "WARNING";
            default -> "ERROR";
        };
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
