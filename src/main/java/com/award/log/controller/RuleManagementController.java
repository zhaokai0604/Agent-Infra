package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.RuleDefinition;
import com.award.log.service.RuleRegistryService;
import com.award.log.service.RuleStatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Rule Management", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/rules")
public class RuleManagementController {

    private final RuleRegistryService ruleRegistryService;
    private final RuleStatService ruleStatService;

    public RuleManagementController(RuleRegistryService ruleRegistryService,
                                    RuleStatService ruleStatService) {
        this.ruleRegistryService = ruleRegistryService;
        this.ruleStatService = ruleStatService;
    }

    @Operation(summary = "查询规则")
    @GetMapping
    public Result<List<RuleDefinition>> list() {
        return Result.success(ruleRegistryService.list());
    }

    @Operation(summary = "创建规则")
    @PostMapping
    public Result<RuleDefinition> create(@RequestBody RuleDefinition rule) {
        return Result.success(ruleRegistryService.save(rule));
    }

    @Operation(summary = "更新规则")
    @PutMapping("/{id}")
    public Result<RuleDefinition> update(@PathVariable String id, @RequestBody RuleDefinition rule) {
        rule.setId(id);
        return Result.success(ruleRegistryService.save(rule));
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        return Result.success(ruleRegistryService.delete(id));
    }

    @Operation(summary = "测试单条规则")
    @PostMapping("/{id}/test")
    public Result<Boolean> test(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return Result.success(ruleRegistryService.testRule(id, payload));
    }

    @Operation(summary = "批量评估规则命中率")
    @GetMapping("/evaluate")
    public Result<Map<String, Object>> evaluate() {
        int total = ruleRegistryService.list().size();
        int enabled = (int) ruleRegistryService.list().stream().filter(RuleDefinition::isEnabled).count();
        Map<String, Object> stat = ruleStatService.summary();
        return Result.success(Map.of(
                "totalRules", total,
                "enabledRules", enabled,
                "hitRate", stat.get("hitRate"),
                "totalHit", stat.get("totalHit"),
                "totalMiss", stat.get("totalMiss")
        ));
    }
}
